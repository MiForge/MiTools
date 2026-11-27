package com.offici5l.mitools.miflash

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class ScriptOption(
    val file: File,
    val label: String,
    val parsed: ParsedScript,

    val erasesData: Boolean,

    val locksBootloader: Boolean,
)

class PreparedRom(val romDir: File, val options: List<ScriptOption>)

interface MiFlashUi {
    fun status(message: String)
    fun progress(percent: Int)
    fun detail(text: String)

    fun showMessage(message: String, success: Boolean?)
}

fun scriptLabel(fileName: String): String {
    val n = fileName.lowercase().removeSuffix(".sh")
    return when {
        n == "flash_all" -> "Flash all without locking bootloader"
        n == "flash_all_lock" -> "Flash all and lock bootloader"
        n == "flash_all_except_storage" -> "Flash all except storage"
        n == "flash_all_except_data_storage" -> "Flash all except data and storage"
        n.startsWith("flash_all_except_") -> "Flash all except " + n.removePrefix("flash_all_except_").replace('_', ' ')
        n == "flash_modemfirmware" -> "Flash modem firmware"
        n.startsWith("flash_") -> "Flash " + n.removePrefix("flash_").replace('_', ' ')
        else -> n.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

class MiFlashEngine(private val context: Context, private val ui: MiFlashUi) {

    private companion object {
        const val ERASE_WEIGHT = 8L * 1024 * 1024
        const val FLASH_TIMEOUT_MS = 10 * 60_000L
        val DATA_PARTITIONS = setOf("userdata", "metadata", "data")
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        var name = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: ""
        }
        return name
    }

    private fun fileSize(resolver: ContentResolver, uri: Uri): Long =
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

    private fun anyStep(steps: List<Step>, pred: (Step) -> Boolean): Boolean =
        steps.any { s -> pred(s) || (s is Step.If && (anyStep(s.then, pred) || anyStep(s.otherwise, pred))) }

    private fun scriptOrder(name: String): Int {
        val n = name.lowercase()
        return when {
            n == "flash_all.sh" -> 0
            n == "flash_all_lock.sh" -> 1
            n.startsWith("flash_all_except") -> 2
            n.startsWith("flash_all") -> 3
            else -> 4
        }
    }

    suspend fun prepare(uri: Uri): PreparedRom? {
        val resolver = context.contentResolver
        val job = currentCoroutineContext()[Job]
        val check: () -> Unit = { job?.ensureActive() }

        val name = displayName(resolver, uri)
        val lower = name.lowercase()
        if (!(lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".tar"))) {
            ui.showMessage("Please choose a fastboot ROM (.tgz).", false)
            return null
        }
        val size = fileSize(resolver, uri)
        if (size <= 0) {
            ui.showMessage("Could not read the selected file.", false)
            return null
        }

        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "miflash")
        val romDir = File(base, "rom_" + java.lang.Long.toHexString(size) + "_" + Integer.toHexString(name.hashCode()))
        val done = File(romDir, ".done")

        ui.status("Extracting the ROM ...")
        if (done.exists()) {
            ui.progress(100)
        } else {
            base.mkdirs()
            base.listFiles()?.forEach { it.deleteRecursively() }
            romDir.mkdirs()

            val free = StatFs(base.path).availableBytes
            if (free < size / 10 * 12) {
                ui.showMessage("Not enough free storage to extract this ROM.", false)
                return null
            }
            ui.progress(0)
            try {
                val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the selected file.")
                input.use { TarGz.extract(it, size, romDir, check) { pct -> ui.progress(pct) } }
                done.writeText("ok")
            } catch (e: IOException) {
                romDir.deleteRecursively()
                val msg = e.message ?: ""
                if (msg.contains("ENOSPC") || msg.contains("No space left", ignoreCase = true)) {
                    ui.showMessage("Not enough free storage to extract this ROM.", false)
                    return null
                }
                throw e
            }
            ui.progress(100)
        }

        val scripts = romDir.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".sh") }.orEmpty()
        if (scripts.isEmpty()) {
            ui.showMessage("No flashing scripts (.sh) were found in this ROM.", false)
            return null
        }
        val options = scripts
            .sortedWith(compareBy({ scriptOrder(it.name) }, { it.name.lowercase() }))
            .map { f ->
                val parsed = FlashScript.parse(f.readText())
                ScriptOption(
                    file = f,
                    label = scriptLabel(f.name),
                    parsed = parsed,
                    erasesData = anyStep(parsed.steps) {
                        (it is Step.Erase && it.partition in DATA_PARTITIONS) ||
                            (it is Step.Flash && it.partition in DATA_PARTITIONS)
                    },
                    locksBootloader = anyStep(parsed.steps) {
                        it is Step.Raw && (it.command.startsWith("oem lock") || it.command.startsWith("flashing lock"))
                    },
                )
            }
        return PreparedRom(romDir, options)
    }

    private fun securityPatchValue(text: String): Int? {
        val m = Regex("(\\d{4})-?(\\d{1,2})?-?(\\d{1,2})?").find(text) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toIntOrNull() ?: 1
        val d = m.groupValues[3].toIntOrNull() ?: 1
        return y * 10000 + mo * 100 + d
    }

    private fun conditionHolds(conn: FastbootConnection, cond: Cond, phoneAnti: Int, packageAnti: Int): Boolean =
        when (cond) {
            is Cond.Getvar -> {
                val v = conn.getvar(cond.variable)
                val text = if (v == null) "" else "${cond.variable}: $v"
                cond.pattern.containsMatchIn(text)
            }
            is Cond.CommandOutput -> {

                val lines = ArrayList<String>()
                val last = try {
                    conn.command(cond.command, FastbootConnection.SHORT_TIMEOUT_MS) { lines.add(it) }
                } catch (e: FastbootDisconnected) {
                    throw e
                } catch (e: FastbootException) {
                    ""
                }
                lines.add(last)
                cond.pattern.containsMatchIn(lines.joinToString("\n"))
            }
            is Cond.Anti -> when (cond.op) {
                "eq" -> phoneAnti == packageAnti
                "ne" -> phoneAnti != packageAnti
                "gt" -> phoneAnti > packageAnti
                "lt" -> phoneAnti < packageAnti
                "ge" -> phoneAnti >= packageAnti
                "le" -> phoneAnti <= packageAnti
                else -> false
            }
        }

    private fun resolve(
        steps: List<Step>,
        conn: FastbootConnection,
        phoneAnti: Int,
        packageAnti: Int,
        out: MutableList<Step>,
    ) {
        for (s in steps) {
            when (s) {
                is Step.Require -> {
                    if (!conditionHolds(conn, s.cond, phoneAnti, packageAnti)) {
                        val c = s.cond
                        val extra = if (c is Cond.Getvar) "\n\n" + c.variable + ": " + (conn.getvar(c.variable) ?: "unknown") else ""
                        throw FastbootException(s.message + extra)
                    }
                }
                is Step.If -> {
                    val holds = conditionHolds(conn, s.cond, phoneAnti, packageAnti)
                    resolve(if (holds == s.matchExpected) s.then else s.otherwise, conn, phoneAnti, packageAnti, out)
                }
                else -> out.add(s)
            }
        }
    }

    private fun rebootCommand(conn: FastbootConnection, cmd: String) {
        try {
            conn.command(cmd, 20_000L)
        } catch (e: FastbootDisconnected) {

        } catch (e: FastbootException) {
            if (!(e.message ?: "").startsWith("Timed out")) throw e
        }
    }

    suspend fun flash(
        rom: PreparedRom,
        option: ScriptOption,
        firstConnection: FastbootConnection,
        reconnect: suspend () -> FastbootConnection,
    ): Boolean {
        var conn = firstConnection
        try {
            val romDir = rom.romDir
            val script = option.parsed
            val job = currentCoroutineContext()[Job]
            val check: () -> Unit = { job?.ensureActive() }

            if (script.unsupported.isNotEmpty()) {
                ui.showMessage(
                    "This script uses commands that are not supported yet:\n\n" +
                        script.unsupported.take(3).joinToString("\n"),
                    false
                )
                return false
            }

            ui.status("Reading device info ...")
            if (conn.getvar("unlocked")?.trim().equals("no", ignoreCase = true)) {
                ui.showMessage("The bootloader is locked. Unlock it first (Mi Unlock).", false)
                return false
            }
            var maxDownload = conn.maxDownloadSize()

            val antiFile = File(romDir, "images/anti_version.txt")
            val packageAnti: Int? =
                if (antiFile.exists()) antiFile.readText().trim().toIntOrNull() else (script.antiVersion ?: 0)
            val phoneAnti = conn.getvar("anti")?.trim()?.toIntOrNull() ?: 0
            if (script.hasAntiCheck) {
                if (packageAnti == null) {
                    ui.showMessage("Could not read the ROM's anti-rollback version.", false)
                    return false
                }
                if (phoneAnti > packageAnti) {
                    ui.showMessage(
                        "Anti-rollback: the phone's version ($phoneAnti) is newer than this ROM ($packageAnti).\n\n" +
                            "Flashing it could brick the phone.",
                        false
                    )
                    return false
                }
            }

            if (script.hasSecurityPatchCheck) {
                val spFile = File(romDir, "images/platform_security_patch.txt")
                val packagePatch = if (spFile.exists()) securityPatchValue(spFile.readText()) else null
                val phonePatchText = conn.getvar("security-patch-level")
                val phonePatch = phonePatchText?.let { securityPatchValue(it) }
                if (packagePatch != null && phonePatch != null && phonePatch > packagePatch) {
                    ui.showMessage(
                        "The phone's security patch ($phonePatchText) is newer than this ROM " +
                            "(${spFile.readText().trim()}).\n\nFlashing an older ROM while keeping user data can cause a bootloop.",
                        false
                    )
                    return false
                }
            }

            ui.status("Checking the phone ...")
            val steps = ArrayList<Step>()
            resolve(script.steps, conn, phoneAnti, packageAnti ?: 0, steps)
            if (steps.isEmpty()) {
                ui.showMessage("The script has nothing to flash.", false)
                return false
            }

            val images = HashMap<String, File>()
            File(romDir, "images").walkTopDown().filter { it.isFile }.forEach { images[it.name.lowercase()] = it }
            for (s in steps) {
                if (s is Step.Flash && images[s.file.lowercase()] == null) {
                    ui.showMessage("The ROM is missing the image \"${s.file}\".", false)
                    return false
                }
            }

            ui.status("Flashing ...")
            ui.progress(0)

            var total = 0L
            var flashCount = 0
            for (s in steps) {
                when (s) {
                    is Step.Flash -> {
                        total += images[s.file.lowercase()]!!.length()
                        flashCount++
                    }
                    is Step.Erase -> total += ERASE_WEIGHT
                    else -> {}
                }
            }
            if (total <= 0) total = 1
            var sent = 0L
            var lastPct = -1
            fun report(bytes: Long) {
                sent += bytes
                val pct = minOf(99L, sent * 100 / total).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    ui.progress(pct)
                }
            }

            var flashIndex = 0
            var finishedWithReboot = false
            var lockSent = false
            for (s in steps) {
                check()
                when (s) {
                    is Step.Flash -> {
                        flashIndex++
                        val file = images[s.file.lowercase()]!!
                        val targets =
                            if (s.slotAll && conn.getvar("has-slot:${s.partition}").equals("yes", ignoreCase = true)) {
                                listOf(s.partition + "_a", s.partition + "_b")
                            } else {
                                listOf(s.partition)
                            }
                        for (p in targets) {
                            ui.detail("$p   ($flashIndex/$flashCount)")
                            flashImage(conn, p, file, maxDownload, ::report)
                        }
                    }
                    is Step.Erase -> {
                        ui.detail("Erasing ${s.partition}")
                        conn.command("erase:${s.partition}", FLASH_TIMEOUT_MS)
                        report(ERASE_WEIGHT)
                    }
                    is Step.SetActive -> conn.command("set_active:${s.slot}", FLASH_TIMEOUT_MS)
                    is Step.Raw -> {
                        if (s.command.startsWith("oem lock") || s.command.startsWith("flashing lock")) {

                            rebootCommand(conn, s.command)
                            lockSent = true
                        } else {
                            conn.command(s.command, FLASH_TIMEOUT_MS)
                        }
                    }
                    is Step.Require, is Step.If -> {}
                    is Step.Reboot -> {
                        when (val target = s.target) {
                            null -> {
                                rebootCommand(conn, "reboot")
                                finishedWithReboot = true
                            }
                            "bootloader", "fastboot" -> {
                                rebootCommand(conn, "reboot-$target")
                                conn.close()
                                ui.status("Waiting for the phone to restart in $target mode ...")
                                conn = reconnect()
                                maxDownload = conn.maxDownloadSize()
                                ui.status("Flashing ...")
                                ui.progress(maxOf(0, lastPct))
                            }
                            else -> {
                                rebootCommand(conn, "reboot-$target")
                                finishedWithReboot = true
                            }
                        }
                    }
                }
            }

            ui.progress(100)
            ui.showMessage(
                when {
                    lockSent -> "Flashing finished.\nThe bootloader lock command was sent."
                    finishedWithReboot -> "Flashing finished.\nThe phone is restarting."
                    else -> "Flashing finished."
                },
                true
            )
            romDir.deleteRecursively()
            return true
        } finally {
            conn.close()
        }
    }

    private suspend fun flashImage(
        conn: FastbootConnection,
        partition: String,
        file: File,
        maxDownload: Long,
        report: (Long) -> Unit,
    ) {
        if (file.length() == 0L) return
        val pieces = ImagePlanner.plan(file, maxDownload)
        val buf = ByteArray(1 shl 20)
        RandomAccessFile(file, "r").use { raf ->
            for (piece in pieces) {
                currentCoroutineContext().ensureActive()
                conn.beginDownload(piece.size)
                for (seg in piece.segments) {
                    when (seg) {
                        is BytesSegment -> {
                            conn.send(seg.bytes, 0, seg.bytes.size)
                            report(seg.bytes.size.toLong())
                        }
                        is FileSegment -> {
                            raf.seek(seg.offset)
                            var left = seg.length
                            while (left > 0) {
                                currentCoroutineContext().ensureActive()
                                val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n <= 0) throw IOException("Unexpected end of file: ${file.name}")
                                conn.send(buf, 0, n)
                                report(n.toLong())
                                left -= n
                            }
                        }
                    }
                }
                conn.finishDownload()
                conn.command("flash:$partition", FLASH_TIMEOUT_MS)
            }
        }
    }
}
