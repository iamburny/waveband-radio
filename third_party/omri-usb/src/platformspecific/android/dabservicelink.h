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

#ifndef DABSERVICELINK_H
#define DABSERVICELINK_H

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "../../dabservice.h"
#include "../../dabservicecomponentmscstreamaudio.h"
#include "../../datatypes.h"

/* Receives decoded service data. Called from the library's decoder threads. */
class DabServiceDataListener {

public:
    virtual ~DabServiceDataListener() = default;

    /* One encoded audio frame: an MPEG-1/2 Layer II frame (ascty 0) or a DAB+ AAC access unit (ascty 63). */
    virtual void audioData(const std::vector<uint8_t>& data, int ascty, int channels, int sampleRate, bool sbrUsed, bool psUsed) = 0;
    virtual void dynamicLabel(const std::string& utf8Text) = 0;
    /*
     * DL Plus tags of the current label: content types (ETSI TS 102 980 table 2) with their UTF-8
     * text, and whether the item is running and its toggle bit.
     */
    virtual void dynamicLabelPlus(const std::vector<std::pair<int, std::string>>& utf8Tags, bool itemRunning, bool itemToggle) = 0;
    virtual void slideshow(const std::vector<uint8_t>& image, uint16_t contentSubType) = 0;
};

/*
 * The service a client asked to play: identifies it by ensemble and service ID,
 * and once the tuner has found it in the ensemble, routes its audio and PAD
 * (dynamic label, slideshow) to a DabServiceDataListener while decoding is on.
 */
class DabServiceLink {

public:
    DabServiceLink(DabServiceDataListener* listener, uint32_t ensembleFrequency, uint16_t ensembleId, uint8_t ensembleEcc, uint32_t serviceId);
    ~DabServiceLink();

    void setLinkDabService(std::shared_ptr<DabService> linkedDabSrv);
    std::shared_ptr<DabService> getLinkDabService() const;

    uint32_t getEnsembleFrequency() const;
    uint16_t getEnsembleId() const;
    uint8_t getEnsembleEcc() const;
    uint32_t getServiceId() const;

    void decodeAudio(bool decode);
    bool isDecodingAudio() const;

private:
    /*
     * State shared with the decoder callbacks. The callbacks hold it by shared_ptr, so a
     * callback already running on a decoder thread stays valid while the link is destroyed.
     */
    struct Sink {
        explicit Sink(DabServiceDataListener* l) : listener(l) {}

        DabServiceDataListener* const listener;
        std::atomic<bool> decoding{false};
        std::mutex padMutex;
        std::string lastLabel;
        std::vector<std::pair<int, std::string>> lastTags;
        bool lastItemRunning{false};
        bool lastItemToggle{false};
        std::vector<uint8_t> lastSlide;
        uint16_t lastSlideSubType{0};

        void audioInput(const std::vector<uint8_t>& audioData, int ascty, int channels, int sampleRate, bool sbrUsed, bool psUsed);
        void dynamicLabelInput(const std::shared_ptr<void>& label);
        void slideshowInput(const std::shared_ptr<void>& slideShow);
        void replayPad();
    };

    const std::shared_ptr<Sink> m_sink;
    std::shared_ptr<DabService> m_linkedDabService{nullptr};

    const uint32_t m_serviceId;
    const uint32_t m_ensembleFrequency;
    const uint16_t m_ensembleId;
    const uint8_t m_ensembleEcc;

    std::shared_ptr<DabServiceComponentMscStreamAudio::AUDIO_DATA_CALLBACK> m_audioDataCb{nullptr};
    std::shared_ptr<DabUserapplicationDecoder::UserapplicationDataCallback> m_dlsCallback{nullptr};
    std::shared_ptr<DabUserapplicationDecoder::UserapplicationDataCallback> m_slsCallback{nullptr};
};

/* Converts a DAB label or dynamic label in the given DAB character set to UTF-8. */
std::string dabTextToUtf8(const std::string& text, uint8_t charset);

#endif //DABSERVICELINK_H
