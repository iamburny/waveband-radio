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

#include "omri_api.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <iostream>
#include <memory>
#include <streambuf>
#include <string>

#include "dabservicelink.h"
#include "raontunerinput.h"
#include "usbtunerdevice.h"

#ifndef OMRI_UPSTREAM_COMMIT
#define OMRI_UPSTREAM_COMMIT "unknown"
#endif

namespace {

/*
 * std::cout sink for logcat. The library logs from many threads at once, so each thread
 * assembles its own line and only complete lines are written.
 */
class LogcatStreamBuf : public std::streambuf {
protected:
    int overflow(int c) override {
        if(c != traits_type::eof()) {
            char ch = traits_type::to_char_type(c);
            append(&ch, 1);
        }
        return traits_type::not_eof(c);
    }

    std::streamsize xsputn(const char* s, std::streamsize n) override {
        append(s, static_cast<size_t>(n));
        return n;
    }

    int sync() override {
        flushLine();
        return 0;
    }

private:
    static std::string& line() {
        thread_local std::string current;
        return current;
    }

    static void append(const char* s, size_t n) {
        std::string& current = line();
        for(size_t i = 0; i < n; i++) {
            if(s[i] == '\n') {
                flushLine();
            } else {
                current.push_back(s[i]);
                if(current.size() >= 1000) {
                    flushLine();
                }
            }
        }
    }

    static void flushLine() {
        std::string& current = line();
        if(!current.empty()) {
            __android_log_write(ANDROID_LOG_DEBUG, "omri", current.c_str());
            current.clear();
        }
    }
};

LogcatStreamBuf g_logcatBuf;

void copyInfo(const std::string& text, char* info, size_t infoLength) {
    if(info == nullptr || infoLength == 0) {
        return;
    }
    size_t n = std::min(text.size(), infoLength - 1);
    std::memcpy(info, text.data(), n);
    info[n] = '\0';
}

class CallbackTunerDevice final : public UsbTunerDevice, public DabServiceDataListener {
public:
    CallbackTunerDevice(int fd, std::string name, const omri_callbacks& callbacks)
            : UsbTunerDevice(fd, std::move(name)), m_cb(callbacks) {}

    void callCallback(TUNER_CALLBACK_TYPE type) override {
        if(m_cb.state == nullptr) {
            return;
        }
        switch(type) {
            case TUNER_CALLBACK_READY:
                m_cb.state(m_cb.user, OMRI_STATE_READY);
                break;
            case TUNER_CALLBACK_FAILED:
                m_cb.state(m_cb.user, OMRI_STATE_FAILED);
                break;
            case TUNER_SCAN_IN_PROGRESS:
                m_cb.state(m_cb.user, OMRI_STATE_SCANNING);
                break;
            default:
                break;
        }
    }

    void scanProgress(int channelIndex, int channelCount) override {
        if(m_cb.scan_progress != nullptr) {
            m_cb.scan_progress(m_cb.user, channelIndex, channelCount);
        }
    }

    void ensembleReady(const DabEnsemble& ensemble) override {
        if(m_cb.service_found == nullptr) {
            return;
        }
        const std::string ensembleLabel = dabTextToUtf8(ensemble.getEnsembleLabel(), ensemble.getEnsembleLabelCharset());
        for(const auto& srv : ensemble.getDabServices()) {
            const std::string label = dabTextToUtf8(srv->getServiceLabel(), srv->getLabelCharset());
            const std::string shortLabel = dabTextToUtf8(srv->getServiceShortLabel(), srv->getLabelCharset());

            int codec = OMRI_CODEC_NONE;
            int bitrate = 0;
            int subChId = -1;
            for(const auto& component : srv->getServiceComponents()) {
                if(component->isPrimary() && component->getServiceComponentType() == DabServiceComponent::SERVICECOMPONENTTYPE::MSC_STREAM_AUDIO) {
                    codec = std::static_pointer_cast<DabServiceComponentMscStreamAudio>(component)->getAudioServiceComponentType();
                    bitrate = component->getSubchannelBitrate();
                    if(component->getSubChannelId() <= 0x3F) {
                        subChId = component->getSubChannelId();
                    }
                    break;
                }
            }

            uint16_t asuFlags = 0;
            std::vector<uint8_t> clusterIds;
            ensemble.getAnnouncementSupport(srv->getServiceId(), asuFlags, clusterIds);

            omri_service_info info{};
            info.service_id = srv->getServiceId();
            info.ensemble_id = ensemble.getEnsembleId();
            info.ensemble_ecc = ensemble.getEnsembleEcc();
            // The tuner keeps frequencies in Hz.
            info.frequency_khz = srv->getEnsembleFrequency() / 1000;
            info.label = label.c_str();
            info.short_label = shortLabel.c_str();
            info.ensemble_label = ensembleLabel.c_str();
            info.programme_type = srv->getProgrammeTypeCode();
            info.is_programme_service = srv->isProgrammeService() ? 1 : 0;
            info.audio_codec = codec;
            info.bitrate_kbps = bitrate;
            info.subchannel_id = subChId;
            info.announcement_flags = asuFlags;
            info.cluster_ids = clusterIds.data();
            info.cluster_count = clusterIds.size();
            m_cb.service_found(m_cb.user, &info);
        }
    }

    void serviceStarted(uint32_t serviceId) override {
        if(m_cb.service_started != nullptr) {
            m_cb.service_started(m_cb.user, serviceId);
        }
    }

    void serviceStopped(uint32_t serviceId) override {
        if(m_cb.service_stopped != nullptr) {
            m_cb.service_stopped(m_cb.user, serviceId);
        }
    }

    void receptionStatistics(bool rfLock, int antennaLevel, int errorRateE4) override {
        if(m_cb.reception != nullptr) {
            m_cb.reception(m_cb.user, rfLock ? 1 : 0, antennaLevel, errorRateE4);
        }
    }

    void audioData(const std::vector<uint8_t>& data, int ascty, int channels, int sampleRate, bool sbrUsed, bool psUsed) override {
        if(m_cb.audio != nullptr && !data.empty()) {
            m_cb.audio(m_cb.user, data.data(), data.size(), ascty, channels, sampleRate, sbrUsed ? 1 : 0, psUsed ? 1 : 0);
        }
    }

    void announcementSupport(uint16_t ensembleId, uint32_t serviceId, uint16_t asuFlags, const std::vector<uint8_t>& clusterIds) override {
        if(m_cb.announcement_support != nullptr) {
            m_cb.announcement_support(m_cb.user, ensembleId, serviceId, asuFlags, clusterIds.data(), clusterIds.size());
        }
    }

    void announcementSwitched(uint8_t clusterId, uint16_t aswFlags, uint8_t subChId, bool active) override {
        if(m_cb.announcement != nullptr) {
            m_cb.announcement(m_cb.user, clusterId, aswFlags, subChId, active ? 1 : 0);
        }
    }

    void dynamicLabel(const std::string& utf8Text) override {
        if(m_cb.dynamic_label != nullptr) {
            m_cb.dynamic_label(m_cb.user, utf8Text.c_str());
        }
    }

    void dynamicLabelPlus(const std::vector<std::pair<int, std::string>>& utf8Tags, bool itemRunning, bool itemToggle) override {
        if(m_cb.dl_plus == nullptr) {
            return;
        }
        std::vector<omri_dl_plus_tag> tags;
        tags.reserve(utf8Tags.size());
        for(const auto& tag : utf8Tags) {
            tags.push_back(omri_dl_plus_tag{tag.first, tag.second.c_str()});
        }
        m_cb.dl_plus(m_cb.user, tags.data(), tags.size(), itemRunning ? 1 : 0, itemToggle ? 1 : 0);
    }

    void slideshow(const std::vector<uint8_t>& image, uint16_t contentSubType) override {
        if(m_cb.slideshow != nullptr && !image.empty()) {
            m_cb.slideshow(m_cb.user, image.data(), image.size(), contentSubType);
        }
    }

private:
    const omri_callbacks m_cb;
};

}

struct omri_tuner {
    std::shared_ptr<CallbackTunerDevice> device;
    std::unique_ptr<RaonTunerInput> input;
};

extern "C" {

const char* omri_version(void) {
    return "omri-usb " OMRI_UPSTREAM_COMMIT " (modified)";
}

void omri_set_logging(int enabled) {
    // A null buffer puts cout in a failed state, so disabled logging costs almost nothing.
    std::cout.rdbuf(enabled ? &g_logcatBuf : nullptr);
}

omri_tuner* omri_tuner_open(int fd, const char* device_name, const omri_callbacks* callbacks, char* info, size_t info_length) {
    if(fd < 0 || callbacks == nullptr) {
        copyInfo("invalid arguments", info, info_length);
        return nullptr;
    }

    auto tuner = std::unique_ptr<omri_tuner>(new omri_tuner);
    tuner->device = std::make_shared<CallbackTunerDevice>(fd, device_name != nullptr ? device_name : "", *callbacks);
    tuner->input = std::unique_ptr<RaonTunerInput>(new RaonTunerInput(tuner->device));

    std::string probeInfo;
    if(!tuner->input->probe(probeInfo)) {
        copyInfo(probeInfo, info, info_length);
        return nullptr;
    }
    copyInfo(probeInfo, info, info_length);

    tuner->input->initialize();
    return tuner.release();
}

void omri_tuner_close(omri_tuner* tuner) {
    if(tuner == nullptr) {
        return;
    }
    // Joins every worker thread before the device (and with it the callbacks) goes away.
    tuner->input.reset();
    tuner->device.reset();
    delete tuner;
}

void omri_tuner_start_scan(omri_tuner* tuner) {
    if(tuner != nullptr) {
        tuner->input->startServiceScan();
    }
}

void omri_tuner_stop_scan(omri_tuner* tuner) {
    if(tuner != nullptr) {
        tuner->input->stopServiceScan();
    }
}

void omri_tuner_start_service(omri_tuner* tuner, uint32_t frequency_khz, uint16_t ensemble_id, uint8_t ensemble_ecc, uint32_t service_id) {
    if(tuner == nullptr) {
        return;
    }
    auto link = std::make_shared<DabServiceLink>(tuner->device.get(), frequency_khz * 1000, ensemble_id, ensemble_ecc, service_id);
    tuner->input->startService(link);
}

void omri_tuner_stop_service(omri_tuner* tuner) {
    if(tuner != nullptr) {
        tuner->input->stopAllRunningServices();
    }
}

}
