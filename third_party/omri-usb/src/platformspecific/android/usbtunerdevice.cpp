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

#include "usbtunerdevice.h"

#include <cerrno>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

UsbTunerDevice::UsbTunerDevice(int fileDescriptor, std::string deviceName)
        : m_fd(fileDescriptor), m_deviceName(std::move(deviceName)) {
}

std::string UsbTunerDevice::getDeviceName() const {
    return m_deviceName;
}

int UsbTunerDevice::bulk(uint8_t endPointAddress, void* data, size_t length, int timeOutMs) {
    if(m_fd < 0) {
        errno = EBADF;
        return -1;
    }

    usbdevfs_bulktransfer transfer{};
    transfer.ep = endPointAddress;
    transfer.len = static_cast<unsigned int>(length);
    transfer.timeout = static_cast<unsigned int>(timeOutMs);
    transfer.data = data;

    int ret;
    do {
        ret = ioctl(m_fd, USBDEVFS_BULK, &transfer);
    } while(ret < 0 && errno == EINTR);

    return ret;
}

int UsbTunerDevice::writeBulkTransferData(uint8_t endPointAddress, const std::vector<uint8_t>& buffer, int timeOutMs) {
    // USBDEVFS_BULK takes a non-const pointer even for OUT transfers.
    std::vector<uint8_t> copy(buffer);
    return bulk(endPointAddress, copy.data(), copy.size(), timeOutMs);
}

int UsbTunerDevice::readBulkTransferData(uint8_t endPointAddress, std::vector<uint8_t>& buffer, int timeOutMs) {
    if(buffer.empty()) {
        return 0;
    }
    return bulk(endPointAddress, buffer.data(), buffer.size(), timeOutMs);
}
