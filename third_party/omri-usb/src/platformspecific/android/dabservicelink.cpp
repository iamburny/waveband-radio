/*
 * This file is a part of IRT DAB library (modified for DABtuner).
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 */

#include "dabservicelink.h"

#include <iostream>

namespace {

void appendUtf8(std::string& out, uint32_t codePoint) {
    if(codePoint < 0x80) {
        out.push_back(static_cast<char>(codePoint));
    } else if(codePoint < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    }
}

const std::string LOG_TAG{"[DabServiceLink] "};

}

std::string dabTextToUtf8(const std::string& text, uint8_t charset) {
    switch(charset) {
        case 0x0F:
            // Already UTF-8; the JNI side decodes leniently, so malformed input cannot crash it.
            return text;
        case 0x06: {
            // UCS-2 big endian.
            std::string out;
            for(size_t i = 0; i + 1 < text.size(); i += 2) {
                uint32_t cp = (static_cast<uint8_t>(text[i]) << 8u) | static_cast<uint8_t>(text[i + 1]);
                if(cp != 0) {
                    appendUtf8(out, cp);
                }
            }
            return out;
        }
        case 0x04: {
            // ISO 8859-1.
            std::string out;
            for(char c : text) {
                if(c != 0) {
                    appendUtf8(out, static_cast<uint8_t>(c));
                }
            }
            return out;
        }
        default:
            // EBU Latin (charset 0) and the legacy EBU-based sets.
            return convertEbuToUtf(text);
    }
}

DabServiceLink::DabServiceLink(DabServiceDataListener* listener, uint32_t ensembleFrequency, uint16_t ensembleId, uint8_t ensembleEcc, uint32_t serviceId)
        : m_sink(std::make_shared<Sink>(listener)),
          m_serviceId(serviceId),
          m_ensembleFrequency(ensembleFrequency),
          m_ensembleId(ensembleId),
          m_ensembleEcc(ensembleEcc) {
}

DabServiceLink::~DabServiceLink() {
    m_sink->decoding = false;
}

void DabServiceLink::setLinkDabService(std::shared_ptr<DabService> linkedDabSrv) {
    m_linkedDabService = linkedDabSrv;
    m_audioDataCb = nullptr;
    m_dlsCallback = nullptr;
    m_slsCallback = nullptr;

    if(!linkedDabSrv->isProgrammeService()) {
        return;
    }

    std::shared_ptr<Sink> sink = m_sink;
    for(const auto& srvComp : linkedDabSrv->getServiceComponents()) {
        if(srvComp->getServiceComponentType() != DabServiceComponent::SERVICECOMPONENTTYPE::MSC_STREAM_AUDIO) {
            continue;
        }

        auto audioComponent = std::static_pointer_cast<DabServiceComponentMscStreamAudio>(srvComp);
        m_audioDataCb = audioComponent->registerAudioDataCallback(
                [sink](const std::vector<uint8_t>& data, int ascty, int channels, int sampleRate, bool sbr, bool ps) {
                    sink->audioInput(data, ascty, channels, sampleRate, sbr, ps);
                });

        for(const auto& uApp : srvComp->getUserApplications()) {
            switch(uApp.getUserApplicationType()) {
                case registeredtables::USERAPPLICATIONTYPE::DYNAMIC_LABEL:
                    m_dlsCallback = uApp.getUserApplicationDecoder()->registerUserapplicationDataCallback(
                            [sink](std::shared_ptr<void> label) { sink->dynamicLabelInput(label); });
                    break;
                case registeredtables::USERAPPLICATIONTYPE::MOT_SLIDESHOW:
                    m_slsCallback = uApp.getUserApplicationDecoder()->registerUserapplicationDataCallback(
                            [sink](std::shared_ptr<void> slide) { sink->slideshowInput(slide); });
                    break;
                default:
                    break;
            }
        }
        break;
    }
}

std::shared_ptr<DabService> DabServiceLink::getLinkDabService() const {
    return m_linkedDabService;
}

uint32_t DabServiceLink::getEnsembleFrequency() const {
    return m_ensembleFrequency;
}

uint16_t DabServiceLink::getEnsembleId() const {
    return m_ensembleId;
}

uint8_t DabServiceLink::getEnsembleEcc() const {
    return m_ensembleEcc;
}

uint32_t DabServiceLink::getServiceId() const {
    return m_serviceId;
}

void DabServiceLink::decodeAudio(bool decode) {
    bool was = m_sink->decoding.exchange(decode);
    if(decode && !was) {
        m_sink->replayPad();
    }
}

bool DabServiceLink::isDecodingAudio() const {
    return m_sink->decoding;
}

void DabServiceLink::Sink::audioInput(const std::vector<uint8_t>& audioData, int ascty, int channels, int sampleRate, bool sbrUsed, bool psUsed) {
    if(decoding && listener != nullptr) {
        listener->audioData(audioData, ascty, channels, sampleRate, sbrUsed, psUsed);
    }
}

void DabServiceLink::Sink::dynamicLabelInput(const std::shared_ptr<void>& label) {
    auto dls = std::static_pointer_cast<DabDynamicLabel>(label);
    if(dls == nullptr) {
        return;
    }

    std::string text = dabTextToUtf8(dls->dynamicLabel, dls->charset);
    {
        std::lock_guard<std::mutex> lock(padMutex);
        lastLabel = text;
    }
    if(decoding && listener != nullptr) {
        listener->dynamicLabel(text);
    }
}

void DabServiceLink::Sink::slideshowInput(const std::shared_ptr<void>& slideShow) {
    auto slide = std::static_pointer_cast<DabSlideshow>(slideShow);
    if(slide == nullptr || slide->slideshowData.empty()) {
        std::cout << LOG_TAG << "Ignoring empty slide" << std::endl;
        return;
    }

    {
        std::lock_guard<std::mutex> lock(padMutex);
        lastSlide = slide->slideshowData;
        lastSlideSubType = slide->contentSubType;
    }
    if(decoding && listener != nullptr) {
        listener->slideshow(slide->slideshowData, slide->contentSubType);
    }
}

void DabServiceLink::Sink::replayPad() {
    std::string label;
    std::vector<uint8_t> slide;
    uint16_t subType;
    {
        std::lock_guard<std::mutex> lock(padMutex);
        label = lastLabel;
        slide = lastSlide;
        subType = lastSlideSubType;
    }
    if(listener == nullptr) {
        return;
    }
    if(!label.empty()) {
        listener->dynamicLabel(label);
    }
    if(!slide.empty()) {
        listener->slideshow(slide, subType);
    }
}
