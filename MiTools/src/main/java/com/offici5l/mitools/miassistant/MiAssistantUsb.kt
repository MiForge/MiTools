package com.offici5l.mitools.miassistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object MiAssistantUsb {

    private const val ACTION_USB_PERMISSION = "com.offici5l.mitools.MIASSISTANT_USB_PERMISSION"

    const val B_INTERFACE_CLASS = 0xFF
    const val B_INTERFACE_SUBCLASS = 0x42
    const val B_INTERFACE_PROTOCOL = 0x01

    class Endpoints(val iface: UsbInterface, val epIn: UsbEndpoint, val epOut: UsbEndpoint)

    fun findEndpoints(device: UsbDevice): Endpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != B_INTERFACE_CLASS ||
                iface.interfaceSubclass != B_INTERFACE_SUBCLASS ||
                iface.interfaceProtocol != B_INTERFACE_PROTOCOL
            ) continue

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn != null && epOut != null) return Endpoints(iface, epIn, epOut)
        }
        return null
    }

    fun findDevice(usbManager: UsbManager): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { findEndpoints(it) != null }

    suspend fun waitForDevice(usbManager: UsbManager, pollMs: Long = 700L): UsbDevice {
        while (true) {
            findDevice(usbManager)?.let { return it }
            delay(pollMs)
        }
    }

    suspend fun ensurePermission(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    deferred.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)
            val granted = withTimeoutOrNull(120_000L) { deferred.await() } ?: false
            return granted && usbManager.hasPermission(device)
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun open(usbManager: UsbManager, device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)
}
