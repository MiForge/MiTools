package com.offici5l.mitools.miflash

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.IOException
import java.util.Locale

open class FastbootException(message: String) : IOException(message)
class FastbootDisconnected : FastbootException("The device was disconnected.")

class FastbootConnection private constructor(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val endpoints: FastbootUsb.Endpoints,
) : AutoCloseable {

    companion object {
        const val WRITE_TIMEOUT_MS = 20_000
        const val SHORT_TIMEOUT_MS = 15_000L
        const val LONG_TIMEOUT_MS = 10 * 60_000L

        fun open(usbManager: UsbManager, device: UsbDevice): FastbootConnection {
            val eps = FastbootUsb.findEndpoints(device)
                ?: throw FastbootException("The device is not in fastboot mode.")
            val conn = usbManager.openDevice(device)
                ?: throw FastbootException("Could not open the USB device (permission denied?).")
            if (!conn.claimInterface(eps.iface, true)) {
                conn.close()
                throw FastbootException("Could not claim the fastboot USB interface.")
            }
            return FastbootConnection(usbManager, device, conn, eps)
        }
    }

    private val rbuf = ByteArray(256)

    override fun close() {
        try {
            connection.releaseInterface(endpoints.iface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }

    private fun isConnected(): Boolean =
        usbManager.deviceList.values.any { it.deviceName == device.deviceName }

    private fun write(data: ByteArray, off: Int, len: Int) {
        var sent = 0
        var errors = 0
        while (sent < len) {
            val r = connection.bulkTransfer(endpoints.epOut, data, off + sent, len - sent, WRITE_TIMEOUT_MS)
            if (r > 0) {
                sent += r
                errors = 0
                continue
            }
            if (!isConnected()) throw FastbootDisconnected()
            errors++
            if (errors >= 4) throw FastbootException("USB write failed (result=$r).")
            SystemClock.sleep(100)
        }
    }

    private fun readResponse(timeoutMs: Long, onInfo: (String) -> Unit): Pair<String, String> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var quickErrors = 0
        while (true) {
            val t0 = SystemClock.elapsedRealtime()
            val r = connection.bulkTransfer(endpoints.epIn, rbuf, 0, rbuf.size, 2000)
            if (r > 0) {
                quickErrors = 0
                val prefix = String(rbuf, 0, minOf(4, r), Charsets.ISO_8859_1)
                val body = if (r > 4) String(rbuf, 4, r - 4, Charsets.UTF_8).trimEnd('\u0000', '\n', '\r', ' ') else ""
                if (prefix == "INFO" || prefix == "TEXT") {
                    onInfo(body)
                    continue
                }
                return Pair(prefix, body)
            }
            if (!isConnected()) throw FastbootDisconnected()
            val now = SystemClock.elapsedRealtime()
            if (now > deadline) throw FastbootException("Timed out waiting for the device.")
            if (r < 0 && now - t0 < 500) {

                quickErrors++
                if (quickErrors >= 100) throw FastbootException("USB read keeps failing.")
                SystemClock.sleep(50)
            }
        }
    }

    fun command(cmd: String, timeoutMs: Long = SHORT_TIMEOUT_MS, onInfo: (String) -> Unit = {}): String {
        val bytes = cmd.toByteArray(Charsets.UTF_8)
        if (bytes.size > 64) throw FastbootException("Command too long: $cmd")
        write(bytes, 0, bytes.size)
        val (prefix, body) = readResponse(timeoutMs, onInfo)
        return when (prefix) {
            "OKAY" -> body
            "FAIL" -> throw FastbootException(body.ifEmpty { "Command failed: $cmd" })
            else -> throw FastbootException("Unexpected response to \"$cmd\": $prefix$body")
        }
    }

    fun getvar(name: String): String? = try {
        command("getvar:$name").trim().ifEmpty { null }
    } catch (e: FastbootDisconnected) {
        throw e
    } catch (e: FastbootException) {
        null
    }

    fun maxDownloadSize(): Long {
        val v = getvar("max-download-size")?.trim()?.lowercase(Locale.US)
        val parsed = when {
            v == null -> null
            v.startsWith("0x") -> v.substring(2).toLongOrNull(16)
            else -> v.toLongOrNull()
        }
        return (parsed ?: 0x10000000L).coerceIn(1L shl 20, 1L shl 30)
    }

    fun beginDownload(size: Long) {
        val bytes = String.format(Locale.US, "download:%08x", size).toByteArray(Charsets.UTF_8)
        write(bytes, 0, bytes.size)
        val (prefix, body) = readResponse(SHORT_TIMEOUT_MS) {}
        when (prefix) {
            "DATA" -> {
                val granted = body.trim().toLongOrNull(16) ?: -1L
                if (granted != size) throw FastbootException("The device did not accept the download size ($size).")
            }
            "FAIL" -> throw FastbootException(body.ifEmpty { "Download refused by the device." })
            else -> throw FastbootException("Unexpected response to download: $prefix$body")
        }
    }

    fun send(data: ByteArray, off: Int, len: Int) = write(data, off, len)

    fun finishDownload() {
        val (prefix, body) = readResponse(LONG_TIMEOUT_MS) {}
        if (prefix == "FAIL") throw FastbootException(body.ifEmpty { "The device rejected the data." })
        if (prefix != "OKAY") throw FastbootException("Unexpected response after download: $prefix$body")
    }
}
