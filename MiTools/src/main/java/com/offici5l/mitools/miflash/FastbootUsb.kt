package com.offici5l.mitools.miflash

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.delay

object FastbootUsb {

    class Endpoints(val iface: UsbInterface, val epIn: UsbEndpoint, val epOut: UsbEndpoint)

    fun findEndpoints(device: UsbDevice): Endpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != 0xFF ||
                iface.interfaceSubclass != 0x42 ||
                iface.interfaceProtocol != 0x03
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
}
