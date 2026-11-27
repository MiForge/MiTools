package com.offici5l.mitools.miassistant

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.FileInputStream
import java.security.MessageDigest

class RomItem(val fileName: String, val url: String)

interface MiAssistantUi {

    fun status(message: String)

    fun step(step: Int, total: Int, label: String)

    fun progress(percent: Int)

    fun showInfo(rows: List<Pair<String, String>>)
    fun showRoms(roms: List<RomItem>, title: String?)
    fun showFiles(path: String, entries: List<RemoteEntry>)

    fun showMessage(message: String, success: Boolean?)
    suspend fun confirm(message: String): Boolean
    suspend fun confirmTyped(message: String, word: String): Boolean
    suspend fun askText(title: String, message: String, hint: String, default: String = ""): String?
}

class MiAssistantEngine(private val adb: MiAssistantAdb, private val ui: MiAssistantUi) {

    private var info: Map<String, String>? = null

    private class LocalZip(
        val pfd: ParcelFileDescriptor,
        val size: Long,
        val md5: String,
        val sha1: String,
    )

    private fun getInfo(): Map<String, String> {
        info?.let { return it }
        val i = adb.readInfo()
        info = i
        return i
    }

    fun readInfo() {
        ui.status("Reading device info ...")
        val i = getInfo()
        ui.showInfo(
            listOf(
                "Device" to (i["device"] ?: ""),
                "Version" to (i["version"] ?: ""),
                "Region" to (i["region"] ?: ""),
                "Zone" to (i["romzone"] ?: ""),
                "Branch" to (i["branch"] ?: ""),
                "Codebase" to (i["codebase"] ?: ""),
                "Recovery" to (i["recoveryversion"] ?: ""),
                "Serial" to (i["sn"] ?: ""),
            )
        )
    }

    private suspend fun openZip(resolver: ContentResolver, uri: Uri): LocalZip? {
        var name = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: ""
        }
        if (!name.lowercase().endsWith(".zip")) {
            ui.showMessage("Please choose a .zip file.", false)
            return null
        }
        val pfd = resolver.openFileDescriptor(uri, "r")
        if (pfd == null) {
            ui.showMessage("Could not open the selected file.", false)
            return null
        }
        val size = pfd.statSize
        if (size <= 0) {
            pfd.close()
            ui.showMessage("Could not read the file size. Copy the ROM to the phone storage and try again.", false)
            return null
        }

        try {
            FileInputStream(pfd.fileDescriptor).channel.position(0)
        } catch (e: Exception) {
            pfd.close()
            ui.showMessage("This file cannot be read randomly (cloud/virtual file). Copy it to the phone storage first.", false)
            return null
        }

        ui.status("Preparing the file ...")
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        resolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(1024 * 1024)
            var done = 0L
            var lastPct = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n)
                sha1.update(buf, 0, n)
                done += n
                val pct = (done * 100 / size).toInt().coerceAtMost(100)
                if (pct != lastPct) {
                    lastPct = pct
                    ui.progress(pct)
                }
            }
        }
        return LocalZip(pfd, size, hex(md5.digest()), hex(sha1.digest()))
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    suspend fun flashRom(resolver: ContentResolver, uri: Uri) {
        val zip = openZip(resolver, uri) ?: return
        try {
            val info = getInfo()
            ui.status("Checking the ROM ...")
            val pkg = MiAssistantApi.pkgRom(MiAssistantApi.validateRequest(info, zip.md5))

            val validate: String
            val erase: Any?

            if (pkg.has("Validate")) {
                val signed = MiAssistantApi.signedSha1(pkg.optString("Validate"))
                if (signed != zip.sha1) {
                    ui.showMessage("This file does not match the Xiaomi server record (SHA1 mismatch).", false)
                    return
                }
                validate = pkg.optString("Validate")
                erase = pkg.opt("Erase")
            } else {
                val checkv = MiAssistantApi.extractValidate(MiAssistantApi.validateRequest(info, ""))
                var found: Pair<String, Any?>? = null
                for (v in checkv.values) {
                    if (MiAssistantApi.signedSha1(v.validate) != zip.sha1) continue
                    var vValidate = v.validate
                    var vErase = v.erase
                    if (v.md5 != zip.md5) {
                        val p2 = MiAssistantApi.pkgRom(MiAssistantApi.validateRequest(info, v.md5))
                        if (!p2.has("Validate")) {
                            ui.showMessage("The server did not return a flashable package for this file.", false)
                            return
                        }
                        if (MiAssistantApi.signedSha1(p2.optString("Validate")) != zip.sha1) {
                            ui.showMessage("This file does not match the Xiaomi server record (SHA1 mismatch).", false)
                            return
                        }
                        vValidate = p2.optString("Validate")
                        vErase = p2.opt("Erase")
                    }
                    found = Pair(vValidate, vErase)
                    break
                }
                if (found == null) {
                    checkRom(checkv, "This file cannot be flashed on this phone. Compatible ROMs:")
                    return
                }
                validate = found.first
                erase = found.second
            }

            val e = MiAssistantApi.eraseText(erase)
            if (e != "0") {
                val note = if (e == "1") "Data will be erased during flashing."
                else "Could not determine whether this package erases data; assume it will."
                if (!ui.confirm("$note\n\nThis cannot be undone.")) {
                    ui.showMessage("Cancelled.", false)
                    return
                }
            }

            var currentStep = 0
            fun onStep(step: Int, pct: Int) {
                if (step != currentStep) {
                    currentStep = step
                    ui.step(step, 2, if (step == 1) "Transferring" else "Installing")
                }
                ui.progress(pct)
            }
            onStep(1, 0)

            val input = FileInputStream(zip.pfd.fileDescriptor)
            val finalMessage = adb.sideload(
                input = input,
                fileSize = zip.size,
                validate = validate,
                onProgress = { step, pct -> onStep(step, pct) },
            )

            val text = finalMessage.trim().replace('_', ' ').ifEmpty { "Finished." }
            ui.showMessage(text, text.lowercase().contains("complete"))
        } finally {
            zip.pfd.close()
        }
    }

    suspend fun checkRom(checkv: Map<String, ValidateEntry>? = null, title: String? = null) {
        val info = getInfo()
        ui.status("Looking for compatible ROMs ...")
        val list = checkv ?: MiAssistantApi.extractValidate(MiAssistantApi.validateRequest(info, ""))
        val roms = ArrayList<RomItem>()
        for (v in list.values) {
            currentCoroutineContext().ensureActive()
            val pkg = MiAssistantApi.pkgRom(MiAssistantApi.validateRequest(info, v.md5))
            if (pkg.has("Validate")) {
                val file = pkg.optString("filename")
                roms.add(RomItem(file, "https://bigota.d.miui.com/${pkg.optString("version")}/$file"))
            }
        }
        val unique = roms.distinctBy { it.url }
        if (unique.isEmpty()) ui.showMessage("No ROM available for this device.", false)
        else ui.showRoms(unique, title)
    }

    private fun outcome(text: String): Boolean? {
        val low = text.lowercase()
        return when {
            "fail" in low || "error" in low -> false
            "success" in low || "complete" in low || "done" in low || "ok" == low -> true
            else -> null
        }
    }

    fun runCommand(command: String) {
        ui.status("Sending command ...")
        val connectionDrops = command.startsWith("reboot") || command.startsWith("shutdown")
        try {
            val out = adb.command(command).trim()
            if (connectionDrops) ui.showMessage(out.ifEmpty { "Command sent." }, true)
            else ui.showMessage(out.ifEmpty { "Done." }, outcome(out) ?: if (out.isEmpty()) true else null)
        } catch (e: DeviceException) {

            if (connectionDrops) ui.showMessage("Command sent.", true)
            else ui.showMessage("${command.substringBefore(':')} failed: ${e.message}", false)
        }
    }

    suspend fun formatFrp() {
        ui.status("Reading MiToken ...")
        val token = adb.command("getmitoken:").trim()
        val signed = ui.askText(
            "Format FRP",
            "MiToken:\n$token\n\nSign it with an authorized Xiaomi account, then paste the signedMiToken below.",
            "signedMiToken",
        )
        if (signed.isNullOrBlank()) {
            ui.showMessage("Cancelled.", null)
            return
        }
        ui.status("Formatting FRP ...")
        val out = adb.command("format-frp:${signed.trim()}").trim()
        ui.showMessage(out.ifEmpty { "Done." }, outcome(out))
    }

    suspend fun listFiles(path: String) {
        ui.status("Listing files ...")
        val raw = path.toByteArray(Charsets.UTF_8)
        if (raw.size > MiAssistantAdb.SYNC_MAX_PATH) {
            ui.showMessage("The path is too long.", false)
            return
        }
        val stream = adb.SyncStream()
        stream.write("LIS2".toByteArray() + u32le(raw.size) + raw)

        val entries = ArrayList<RemoteEntry>()
        val buf = ByteQueue()
        var status: String? = null
        val deadline = System.currentTimeMillis() + MiAssistantAdb.SYNC_IDLE_LIMIT_MS

        while (status == null && System.currentTimeMillis() < deadline && !(stream.closed && stream.buf.size == 0)) {
            currentCoroutineContext().ensureActive()
            buf.append(stream.buf.takeAll())
            while (status == null) {
                val tag = if (buf.size >= 4) String(buf.bytes(0, 4), Charsets.ISO_8859_1) else ""
                if (tag == "DONE") {
                    status = "ok"
                } else if (tag == "FAIL") {
                    status = "bad"
                } else if (buf.size < 76) {
                    break
                } else if (tag != "DNT2") {
                    status = "bad"
                } else {
                    val nameLen = buf.u32(72)
                    if (nameLen > MiAssistantAdb.SYNC_MAX_PATH) {
                        status = "bad"
                    } else if (buf.size < 76 + nameLen) {
                        break
                    } else {
                        val n = nameLen.toInt()
                        entries.add(
                            RemoteEntry(
                                name = String(buf.bytes(76, 76 + n), Charsets.UTF_8),
                                mode = buf.u32(24),
                                size = buf.u64(40),
                                mtime = buf.u64(56),
                            )
                        )
                        buf.drop(76 + n)
                    }
                }
            }
            if (status == null && !stream.pump()) delay(10)
        }

        if (status != "ok") {
            ui.showMessage("Could not list this directory (it may not exist or is not accessible).", false)
            return
        }
        val list = entries
            .filter { it.name != "." && it.name != ".." }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        ui.showFiles(path, list)
    }

    suspend fun pullFile(resolver: ContentResolver, remote: String, outUri: Uri) {
        ui.status("Pulling file ...")
        val raw = remote.toByteArray(Charsets.UTF_8)
        if (raw.size > MiAssistantAdb.SYNC_MAX_PATH) {
            ui.showMessage("The path is too long.", false)
            return
        }
        val out = resolver.openOutputStream(outUri, "w")
        if (out == null) {
            ui.showMessage("Could not open the output file.", false)
            return
        }

        var ok = false
        var total = 0L
        try {
            val stream = adb.SyncStream()
            stream.write("RCV2".toByteArray() + u32le(raw.size) + raw)
            stream.write(u32le(0) + u32le(0))

            val buf = ByteQueue()
            var lastActivity = System.currentTimeMillis()
            var lastUi = 0L

            while (true) {
                currentCoroutineContext().ensureActive()
                val tag = if (buf.size >= 4) String(buf.bytes(0, 4), Charsets.ISO_8859_1) else ""
                var need = 4
                if (tag == "DATA" || tag == "FAIL") {
                    need = 8
                    if (buf.size >= 8) {
                        val n = buf.u32(4)
                        if (tag == "DATA" && n > MiAssistantAdb.ADB_MAX_DATA) {
                            throw DeviceIOError("invalid RCV2 data block size ($n)")
                        }
                        need = 8 + n.toInt()
                    }
                } else if (tag == "DONE") {
                    break
                } else if (buf.size >= 4) {
                    throw DeviceIOError("unexpected RCV2 reply: $tag")
                }

                if (buf.size < need) {
                    if (stream.buf.size > 0) {
                        buf.append(stream.buf.takeAll())
                        lastActivity = System.currentTimeMillis()
                    } else if (stream.closed && buf.size < 4) {
                        break
                    } else if (!stream.pump()) {
                        if (System.currentTimeMillis() - lastActivity > MiAssistantAdb.SYNC_IDLE_LIMIT_MS) {
                            throw DeviceTimeout("timed out waiting for RCV2 data")
                        }
                        delay(10)
                    }
                    continue
                }

                if (tag == "FAIL") {
                    throw DeviceIOError("RCV2 failed: " + String(buf.bytes(8, need), Charsets.UTF_8))
                }
                out.write(buf.bytes(8, need))
                buf.drop(need)
                total += need - 8
                lastActivity = System.currentTimeMillis()

                val now = System.currentTimeMillis()
                if (now - lastUi > 250) {
                    lastUi = now
                    ui.status("Receiving ...  ${humanSize(total)}")
                }
            }
            ok = true
        } finally {
            try {
                out.close()
            } catch (_: Exception) {
            }
            if (!ok) {
                try {
                    DocumentsContract.deleteDocument(resolver, outUri)
                } catch (_: Exception) {
                }
            }
        }
        ui.showMessage("File saved (${humanSize(total)}).", true)
    }
}
