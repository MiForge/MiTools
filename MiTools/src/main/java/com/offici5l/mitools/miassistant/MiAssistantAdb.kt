package com.offici5l.mitools.miassistant

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class DeviceException(message: String) : IOException(message)
class DeviceTimeout(message: String = "timeout") : DeviceException(message)
class DeviceDisconnected(message: String = "The device was disconnected.") : DeviceException(message)
class DeviceIOError(message: String) : DeviceException(message)
class DeviceWriteError(message: String) : DeviceException(message)

/**
 * Minimal ADB wire protocol over Android's USB Host API.
 * (Port of the transport / sideload logic of the `miasst` CLI. No root needed.)
 */
class MiAssistantAdb(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AutoCloseable {

    companion object {
        private fun tag(s: String): Int =
            ByteBuffer.wrap(s.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int

        val ADB_CNXN = tag("CNXN")
        val ADB_OPEN = tag("OPEN")
        val ADB_OKAY = tag("OKAY")
        val ADB_WRTE = tag("WRTE")
        val ADB_CLSE = tag("CLSE")

        const val ADB_VERSION = 0x01000001
        const val ADB_MAX_DATA = 1024 * 1024
        /** Fallback only: the real block size is the max-data the phone announces in its banner. */
        const val SIDELOAD_CHUNK_SIZE = 1024 * 64
        const val USB_TIMEOUT_MS = 10_000
        const val WRITE_TIMEOUT_MS = 30_000
        const val RX_CHUNK = 16 * 1024
        const val MAX_CONSECUTIVE_IO_ERRORS = 10
        const val SIDELOAD_IDLE_LIMIT_S = 900
        const val MAX_CONSECUTIVE_WRITE_ERRORS = 20
        const val SYNC_IDLE_LIMIT_MS = 30_000L
        const val SYNC_MAX_PATH = 1024

        /** Opens the device and claims its ADB interface. Throws DeviceException on failure. */
        fun open(usbManager: UsbManager, device: UsbDevice): MiAssistantAdb {
            val eps = MiAssistantUsb.findEndpoints(device)
                ?: throw DeviceException("Device is not in Mi Assistant mode.")
            val conn = usbManager.openDevice(device)
                ?: throw DeviceException("Could not open the USB device (permission denied?).")
            if (!conn.claimInterface(eps.iface, true)) {
                conn.close()
                throw DeviceException("Could not claim the USB interface.")
            }
            return MiAssistantAdb(usbManager, device, conn, eps.iface, eps.epIn, eps.epOut)
        }
    }

    private var nextId = 1
    val banner = LinkedHashMap<String, String>()

    /** Sideload block size = max-data announced by the phone in its "sideload::" banner packet. */
    var sideloadChunkSize: Int = SIDELOAD_CHUNK_SIZE
        private set

    override fun close() {
        try {
            connection.releaseInterface(iface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }

    private fun stillConnected(): Boolean = usbManager.deviceList.values.any { it.deviceName == device.deviceName }

    // ---- raw USB -------------------------------------------------------------------------

    // Receive buffer: we always ask the USB stack for a big chunk (never "exactly 24 bytes"), so
    // a device that coalesces header+payload, or a short packet, can't cause overflow/lost data.
    private val rx = ByteArray(ADB_MAX_DATA + 24 + 2 * RX_CHUNK)
    private var rxStart = 0
    private var rxEnd = 0
    private var readErrors = 0

    private fun clearHalt(ep: UsbEndpoint) {
        try {
            // CLEAR_FEATURE(ENDPOINT_HALT) on the endpoint
            connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000)
        } catch (_: Exception) {
        }
    }

    private fun usbWrite(data: ByteArray) {
        var sent = 0
        var errors = 0
        while (sent < data.size) {
            val t0 = System.currentTimeMillis()
            val r = connection.bulkTransfer(epOut, data, sent, data.size - sent, WRITE_TIMEOUT_MS)
            if (r > 0) {
                sent += r
                errors = 0
                continue
            }
            if (!stillConnected()) throw DeviceDisconnected()
            errors++
            if (errors == 2) clearHalt(epOut)
            if (errors >= 4) {
                throw DeviceWriteError("USB write failed (result=$r after ${System.currentTimeMillis() - t0} ms)")
            }
            SystemClock.sleep(100)
        }
    }

    /** One bulk-IN transfer into the receive buffer. Returns false on a plain timeout. */
    private fun fill(timeoutMs: Int): Boolean {
        if (rxStart == rxEnd) {
            rxStart = 0
            rxEnd = 0
        } else if (rx.size - rxEnd < RX_CHUNK) {
            System.arraycopy(rx, rxStart, rx, 0, rxEnd - rxStart)
            rxEnd -= rxStart
            rxStart = 0
        }
        val t0 = System.currentTimeMillis()
        val r = connection.bulkTransfer(epIn, rx, rxEnd, RX_CHUNK, timeoutMs)
        if (r > 0) {
            rxEnd += r
            readErrors = 0
            return true
        }
        if (r == 0) return false
        if (!stillConnected()) throw DeviceDisconnected()
        if (System.currentTimeMillis() - t0 >= timeoutMs - 500) return false // ordinary timeout

        // Failed immediately: the endpoint is in an error state. Try to recover, then give up loudly.
        readErrors++
        if (readErrors == 3) clearHalt(epIn)
        if (readErrors >= 15) throw DeviceIOError("USB read keeps failing (result=$r)")
        SystemClock.sleep(100)
        return false
    }

    /** Makes sure at least [n] bytes are buffered. Returns false on timeout (nothing is consumed). */
    private fun ensure(n: Int, timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (rxEnd - rxStart < n) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) return false
            fill(left)
        }
        return true
    }

    // ---- ADB packets ---------------------------------------------------------------------

    class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(0).putInt(cmd.inv())
            .array()
        usbWrite(header)
        if (data.isNotEmpty()) usbWrite(data)
    }

    private fun readPacket(): Packet {
        if (!ensure(24, USB_TIMEOUT_MS)) throw DeviceTimeout()
        val bb = ByteBuffer.wrap(rx, rxStart, 24).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val length = bb.int
        bb.int // checksum
        val magic = bb.int
        if (magic != cmd.inv() || length < 0 || length > ADB_MAX_DATA) {
            rxStart = rxEnd // drop the garbage so the stream can resynchronise
            throw DeviceIOError("Invalid ADB packet (cmd=${Integer.toHexString(cmd)}, len=$length)")
        }
        if (!ensure(24 + length, USB_TIMEOUT_MS)) throw DeviceTimeout()
        val data = rx.copyOfRange(rxStart + 24, rxStart + 24 + length)
        rxStart += 24 + length
        return Packet(cmd, arg0, arg1, data)
    }

    // ---- session -------------------------------------------------------------------------

    /** Handshake. Reads the "sideload::" banner the phone sends in Mi Assistant mode. */
    fun connectHost() {
        sendPacket(ADB_CNXN, ADB_VERSION, ADB_MAX_DATA, "host::\u0000".toByteArray())
        repeat(10) {
            val p = try {
                readPacket()
            } catch (_: DeviceTimeout) {
                return@repeat
            }
            val text = String(p.data, Charsets.ISO_8859_1)
            if (text.startsWith("sideload::")) {
                if (p.arg1 > 0) sideloadChunkSize = p.arg1
                val props = text.trimEnd('\u0000').substringAfter("::")
                props.split(";").forEach { item ->
                    val i = item.indexOf('=')
                    if (i > 0) banner[item.substring(0, i)] = item.substring(i + 1)
                }
                return
            }
        }
        throw DeviceException("The device did not send a valid sideload banner. Make sure it is in Mi Assistant mode.")
    }

    /** Runs a one-shot service command like "getsn:" and returns its text answer. */
    fun command(command: String): String {
        val localId = nextId++
        sendPacket(ADB_OPEN, localId, 0, command.toByteArray() + 0.toByte())

        var response: ByteArray? = null
        var closed = false

        for (i in 0 until 12) {
            val p = try {
                readPacket()
            } catch (_: DeviceTimeout) {
                continue
            }
            if (p.arg1 != localId) continue

            if (p.cmd == ADB_CLSE) {
                closed = true
                sendPacket(ADB_CLSE, localId, p.arg0)
                break
            }
            if (p.cmd == ADB_OKAY) {
                sendPacket(ADB_OKAY, p.arg1, p.arg0)
                continue
            }
            if (p.cmd == ADB_WRTE && p.data.isNotEmpty()) {
                response = p.data
                sendPacket(ADB_OKAY, p.arg1, p.arg0)
                continue
            }
            if (p.data.isNotEmpty() && response == null) response = p.data
        }

        if (response == null) {
            if (closed) throw DeviceIOError("Channel closed before receiving a response for: $command")
            throw DeviceTimeout("No response received for command: $command")
        }
        val text = String(response, Charsets.UTF_8).trimEnd('\u0000')
        return if (text.endsWith("\n")) text.dropLast(1) else text
    }

    fun readInfo(): Map<String, String> {
        val fields = listOf(
            "sn", "region", "device", "branch", "version", "romzone",
            "language", "codebase", "carrier", "mitoken", "recoveryversion"
        )
        val out = LinkedHashMap<String, String>()
        for (k in fields) out[k] = command("get$k:")
        return out
    }

    /**
     * Streams the ROM zip to the phone using the "sideload-host" service; the phone requests
     * 64 KB blocks by index. [validate] is the signed token returned by the Xiaomi server.
     *
     * The phone reads the whole zip TWICE: pass 1 = verification, pass 2 = installation.
     * [onProgress] receives (step, percent): step 1 = transferring/verifying, step 2 = installing,
     * each going 0..100 on its own. Returns the final message printed by the phone's recovery.
     */
    suspend fun sideload(
        input: FileInputStream,
        fileSize: Long,
        validate: String,
        onProgress: (step: Int, percent: Int) -> Unit,
    ): String {
        val channel = input.channel
        val chunkSize = sideloadChunkSize
        val service = "sideload-host:$fileSize:$chunkSize:$validate:0".toByteArray() + 0.toByte()

        val localId = nextId++
        sendPacket(ADB_OPEN, localId, 0, service)

        val totalBlocks = maxOf(1L, (fileSize + chunkSize - 1) / chunkSize)
        val pass1 = HashSet<Long>()
        val pass2 = HashSet<Long>()
        var phase = 1
        var lastPct = -1
        var nearEnd = false
        var finalMessage = ""
        var lastActivity = System.currentTimeMillis()
        var ioErrors = 0

        while (true) {
            currentCoroutineContext().ensureActive()

            val p = try {
                readPacket()
            } catch (e: DeviceDisconnected) {
                throw DeviceDisconnected("The device was disconnected during the transfer.")
            } catch (e: DeviceIOError) {
                ioErrors++
                if (ioErrors >= MAX_CONSECUTIVE_IO_ERRORS) {
                    throw DeviceIOError(
                        if (nearEnd)
                            "The device stopped responding near the end of the installation (${e.message}). " +
                                "It probably left sideload mode; check the phone screen and the installed version."
                        else "Repeated USB read errors (${e.message}). Aborting."
                    )
                }
                delay(100)
                continue
            } catch (e: DeviceTimeout) {
                val idle = (System.currentTimeMillis() - lastActivity) / 1000
                if (idle > SIDELOAD_IDLE_LIMIT_S) throw DeviceTimeout("No response from the device for $idle seconds. Aborting.")
                continue
            }
            ioErrors = 0
            lastActivity = System.currentTimeMillis()

            if (p.arg1 != localId) continue

            if (p.cmd == ADB_CLSE) {
                if (finalMessage.isEmpty() && p.data.isNotEmpty()) finalMessage = String(p.data, Charsets.UTF_8)
                break
            }

            if (p.data.size > 8) {
                finalMessage = String(p.data, Charsets.UTF_8)
                break
            }

            if (p.cmd == ADB_OKAY) {
                sendPacket(ADB_OKAY, p.arg1, p.arg0)
                continue
            }
            if (p.cmd != ADB_WRTE) continue

            val digits = Regex("^\\d+").find(String(p.data, Charsets.ISO_8859_1))?.value
            val block = digits?.toLongOrNull() ?: 0L
            val offset = block * chunkSize
            if (offset > fileSize) break
            val toRead = minOf(chunkSize.toLong(), fileSize - offset).toInt()
            val chunk = ByteBuffer.allocate(toRead)
            var pos = offset
            while (chunk.hasRemaining()) {
                val n = channel.read(chunk, pos)
                if (n < 0) break
                pos += n
            }
            val bytes = chunk.array().copyOf(chunk.position())

            // Never drop the answer to a block request: the phone asks only once.
            var attempt = 0
            while (true) {
                try {
                    sendPacket(ADB_WRTE, p.arg1, p.arg0, bytes)
                    sendPacket(ADB_OKAY, p.arg1, p.arg0)
                    break
                } catch (e: DeviceDisconnected) {
                    throw DeviceDisconnected("The device was disconnected during the transfer.")
                } catch (e: DeviceWriteError) {
                    attempt++
                    if (attempt >= 3) throw DeviceWriteError("Could not send block $block to the phone (${e.message})")
                    delay(300)
                }
            }

            if (!pass1.add(block)) pass2.add(block)

            if (phase == 1) {
                val pct = (pass1.size * 100L / totalBlocks).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(1, pct)
                }
                if (pass1.size >= totalBlocks) {
                    phase = 2
                    lastPct = 0
                    onProgress(2, 0)
                }
            } else {
                val pct = minOf(99L, pass2.size * 100L / totalBlocks).toInt()
                if (pct >= 99) nearEnd = true
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(2, pct)
                }
            }
        }
        if (finalMessage.lowercase().contains("complete")) onProgress(phase, 100)
        return finalMessage
    }

    /** The ADB "sync:" service (List Files / Pull File). Port of _SyncStream in core.py. */
    inner class SyncStream {
        private val localId = nextId++
        private var remoteId: Int? = null
        var closed = false
            private set
        val buf = ByteQueue()

        init {
            sendPacket(ADB_OPEN, localId, 0, "sync:".toByteArray() + 0.toByte())
            var last = System.currentTimeMillis()
            while (remoteId == null) {
                if (closed) throw DeviceIOError("the device refused the sync: service")
                if (pump()) last = System.currentTimeMillis()
                else if (System.currentTimeMillis() - last > SYNC_IDLE_LIMIT_MS) {
                    throw DeviceTimeout("timed out while opening the sync: service")
                }
            }
        }

        /** Reads one packet. Returns true if it belonged to this stream. */
        fun pump(): Boolean {
            val p = try {
                readPacket()
            } catch (e: DeviceDisconnected) {
                throw e
            } catch (e: DeviceTimeout) {
                return false
            } catch (e: DeviceIOError) {
                return false
            }
            if (p.arg1 != localId) return false
            when (p.cmd) {
                ADB_OKAY -> {
                    if (remoteId == null) remoteId = p.arg0
                    sendPacket(ADB_OKAY, localId, remoteId ?: p.arg0)
                }
                ADB_WRTE -> {
                    buf.append(p.data)
                    sendPacket(ADB_OKAY, localId, p.arg0)
                }
                ADB_CLSE -> closed = true
            }
            return true
        }

        fun write(data: ByteArray) {
            sendPacket(ADB_WRTE, localId, remoteId ?: 0, data)
        }
    }
}
