package com.iamburny.waveband.usb

import android.hardware.usb.UsbDevice

/**
 * USB IDs of DAB receivers the app reacts to. Keep in sync with res/xml/usb_device_filter.xml.
 *
 * 16C0:05DC is a shared VID/PID (VOTI "for use with libusb"), so a match does not prove the device
 * is a tuner; the backend must probe it before use.
 */
object SupportedTuners {

    data class Id(val vendorId: Int, val productId: Int, val description: String)

    val ids: List<Id> = listOf(
        Id(0x16C0, 0x05DC, "USB DAB+ receiver (Raontech-based, shared VOTI ID)"),
    )

    fun isSupported(device: UsbDevice): Boolean =
        ids.any { it.vendorId == device.vendorId && it.productId == device.productId }
}
