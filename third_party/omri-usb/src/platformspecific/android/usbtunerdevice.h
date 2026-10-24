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

#ifndef USBTUNERDEVICE_H
#define USBTUNERDEVICE_H

#include <cstdint>
#include <string>
#include <vector>

#include "../../dabensemble.h"

/*
 * A USB tuner reached through a usbfs file descriptor (from Android's
 * UsbDeviceConnection.getFileDescriptor()). Bulk transfers go straight to the
 * kernel with USBDEVFS_BULK. The caller owns the descriptor and must have claimed
 * the interface before handing it over.
 *
 * The notification methods are implemented by the embedding layer and may be
 * called from any of the tuner's worker threads.
 */
class UsbTunerDevice {

public:
    enum TUNER_CALLBACK_TYPE {
        TUNER_CALLBACK_READY = 0,
        TUNER_CALLBACK_FAILED,
        TUNER_CALLBACK_FREQUENCY_LOCKED,
        TUNER_CALLBACK_FREQUENCY_NOT_LOCKED,
        TUNER_SCAN_IN_PROGRESS
    };

    UsbTunerDevice(int fileDescriptor, std::string deviceName);
    virtual ~UsbTunerDevice() = default;

    UsbTunerDevice(const UsbTunerDevice&) = delete;
    UsbTunerDevice& operator=(const UsbTunerDevice&) = delete;

    std::string getDeviceName() const;

    /* Returns the number of bytes transferred, or -1 on error (errno is preserved). */
    int writeBulkTransferData(uint8_t endPointAddress, const std::vector<uint8_t>& buffer, int timeOutMs = 5000);
    /* Reads at most buffer.size() bytes; returns the number read, or -1 on error or timeout. */
    int readBulkTransferData(uint8_t endPointAddress, std::vector<uint8_t>& buffer, int timeOutMs = 5000);

    virtual void callCallback(TUNER_CALLBACK_TYPE callbackType) = 0;
    virtual void scanProgress(int channelIndex, int channelCount) = 0;
    virtual void ensembleReady(const DabEnsemble& ensemble) = 0;
    virtual void serviceStarted(uint32_t serviceId) = 0;
    virtual void serviceStopped(uint32_t serviceId) = 0;
    /* antennaLevel is 0 (worst) .. 6 (best). */
    virtual void receptionStatistics(bool rfLock, int antennaLevel) = 0;
    /* FIG 0/18 support of a service in the tuned ensemble; see DabEnsemble::registerAnnouncementSupportCallback. */
    virtual void announcementSupport(uint16_t ensembleId, uint32_t serviceId, uint16_t asuFlags, const std::vector<uint8_t>& clusterIds) = 0;
    /* FIG 0/19 switching in the tuned ensemble; see DabEnsemble::registerAnnouncementSwitchingCallback. */
    virtual void announcementSwitched(uint8_t clusterId, uint16_t aswFlags, uint8_t subChId, bool active) = 0;

private:
    int bulk(uint8_t endPointAddress, void* data, size_t length, int timeOutMs);

    const int m_fd;
    const std::string m_deviceName;
};

#endif //USBTUNERDEVICE_H
