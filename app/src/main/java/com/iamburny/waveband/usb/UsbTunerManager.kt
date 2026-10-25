package com.iamburny.waveband.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Finds supported tuners, runs the USB permission dialog, and reports detach events.
 * Only one permission request is shown at a time; further requests are queued.
 */
class UsbTunerManager(context: Context) {

    interface Callback {
        fun onPermissionResult(device: UsbDevice, granted: Boolean)
        fun onDetached(device: UsbDevice)
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"
    private val pendingRequests = ArrayDeque<UsbDevice>()
    private var requestInFlight: UsbDevice? = null
    private var callback: Callback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                ?: return
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    requestInFlight = null
                    callback?.onPermissionResult(device, granted)
                    pendingRequests.removeFirstOrNull()?.let { requestPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> callback?.onDetached(device)
            }
        }
    }

    fun start(callback: Callback) {
        this.callback = callback
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        appContext.unregisterReceiver(receiver)
        callback = null
        pendingRequests.clear()
        requestInFlight = null
    }

    fun findTuners(): List<UsbDevice> =
        usbManager.deviceList.values.filter { SupportedTuners.isSupported(it) }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            callback?.onPermissionResult(device, true)
            return
        }
        if (requestInFlight != null) {
            if (requestInFlight != device && device !in pendingRequests) pendingRequests.addLast(device)
            return
        }
        requestInFlight = device
        // The system adds EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED, so the PendingIntent must be mutable;
        // the explicit package keeps that allowed on API 34+.
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    /** Opens the device; the caller owns the returned connection and must close it. */
    fun open(device: UsbDevice): UsbDeviceConnection? =
        try {
            usbManager.openDevice(device)
        } catch (e: SecurityException) {
            null
        }
}
