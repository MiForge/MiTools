package com.offici5l.mitools.miassistant

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.offici5l.mitools.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MiAssistantActivity : AppCompatActivity() {

    private enum class Action(val label: String, val command: String? = null, val warning: String? = null) {
        INFO("Read Info"),
        FLASH("Flash Official Recovery ROM"),
        ROMS("ROMs That Can Be Flashed"),

        FORMAT_DATA("Format Data", "format-data:", "This will erase all user data on the device."),
        FORMAT_CACHE("Format Cache", "format-cache:"),
        FORMAT_STORAGE("Format Storage", "format-storage:", "This will erase the internal storage (photos, files)."),
        FORMAT_DATA_STORAGE("Format Data + Storage", "format-data-storage:", "This will erase user data AND internal storage."),
        FORMAT_FRP("Format FRP"),
        WIPE_DATA_STORAGE("Wipe Data Storage", "wipe-data-storage:", "This will wipe user data and internal storage."),
        WIPE_EFS("Wipe EFS", "wipe-efs:"),

        REBOOT("Reboot", "reboot:"),
        REBOOT_RECOVERY("Reboot to recovery", "reboot:recovery"),
        REBOOT_FASTBOOT("Reboot to fastboot", "reboot:fastboot"),
        REBOOT_BOOTLOADER("Reboot to bootloader", "reboot:bootloader"),
        SHUTDOWN("Shutdown", "shutdown:"),

        MINIDUMP("Get Minidump", "getminidump:"),
        LIST("List Files"),
        PULL("Pull File"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var busy = false
    private var pendingRemote: String? = null
    private var inSubmenu = false

    private class MenuItem(
        val label: String,
        val action: Action? = null,
        val children: List<MenuItem>? = null,
        val gapBefore: Boolean = false,
    )

    private fun item(a: Action, gap: Boolean = false) = MenuItem(a.label, a, null, gap)

    private val mainMenu: List<MenuItem> by lazy {
        listOf(
            item(Action.INFO),
            item(Action.FLASH),
            item(Action.ROMS),
            MenuItem(
                "Format",
                children = listOf(
                    item(Action.FORMAT_DATA),
                    item(Action.FORMAT_CACHE),
                    item(Action.FORMAT_STORAGE),
                    item(Action.FORMAT_DATA_STORAGE),
                    item(Action.FORMAT_FRP),
                    item(Action.WIPE_DATA_STORAGE, gap = true),
                    item(Action.WIPE_EFS),
                ),
                gapBefore = true,
            ),
            MenuItem(
                "Reboot",
                children = listOf(
                    item(Action.REBOOT),
                    item(Action.REBOOT_RECOVERY),
                    item(Action.REBOOT_FASTBOOT),
                    item(Action.REBOOT_BOOTLOADER),
                    item(Action.SHUTDOWN, gap = true),
                ),
            ),
            item(Action.MINIDUMP, gap = true),
            MenuItem("Files", children = listOf(item(Action.LIST), item(Action.PULL))),
        )
    }

    private lateinit var menuContainer: View
    private lateinit var menuList: LinearLayout
    private lateinit var workContainer: View
    private lateinit var resultContainer: View
    private lateinit var workTitle: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var backButton: Button

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(Action.FLASH, zip = uri)
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val remote = pendingRemote
        pendingRemote = null
        if (uri != null && remote != null) start(Action.PULL, path = remote, outUri = uri)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun show(panel: View) {
        listOf(menuContainer, workContainer, resultContainer).forEach {
            it.visibility = if (it === panel) View.VISIBLE else View.GONE
        }
    }

    private val ui = object : MiAssistantUi {

        override fun status(message: String) {
            runOnUiThread {
                show(workContainer)
                workTitle.text = message
                progressBar.isIndeterminate = true
                percentText.visibility = View.GONE
            }
        }

        override fun step(step: Int, total: Int, label: String) {
            runOnUiThread {
                show(workContainer)
                workTitle.text = "Step $step/$total  -  $label"
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                percentText.text = "0%"
                percentText.visibility = View.VISIBLE
            }
        }

        override fun progress(percent: Int) {
            runOnUiThread {
                if (percent < 0) {
                    progressBar.isIndeterminate = true
                    percentText.visibility = View.GONE
                } else {
                    progressBar.isIndeterminate = false
                    progressBar.progress = percent
                    percentText.text = "$percent%"
                    percentText.visibility = View.VISIBLE
                }
            }
        }

        override fun showInfo(rows: List<Pair<String, String>>) {
            runOnUiThread {
                resultContent.removeAllViews()
                resultContent.gravity = Gravity.CENTER_VERTICAL
                val card = newCard()
                for ((label, value) in rows) {
                    val row = LinearLayout(this@MiAssistantActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(7), 0, dp(7))
                    }
                    row.addView(TextView(this@MiAssistantActivity).apply {
                        text = label
                        textSize = 14f
                        setTextColor(Color.parseColor("#777777"))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this@MiAssistantActivity).apply {
                        text = value.ifEmpty { "-" }
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.END
                        setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                    })
                    card.addView(row)
                }
                resultContent.addView(card)
                show(resultContainer)
            }
        }

        override fun showRoms(roms: List<RomItem>, title: String?) {
            runOnUiThread {
                resultContent.removeAllViews()
                resultContent.gravity = Gravity.TOP
                if (!title.isNullOrEmpty()) {
                    resultContent.addView(TextView(this@MiAssistantActivity).apply {
                        text = title
                        textSize = 14f
                        setTextColor(Color.parseColor("#777777"))
                        setPadding(dp(4), 0, dp(4), dp(12))
                    })
                }
                for (rom in roms) {
                    val card = newCard()
                    card.addView(TextView(this@MiAssistantActivity).apply {
                        text = rom.fileName
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextIsSelectable(true)
                    })
                    card.addView(TextView(this@MiAssistantActivity).apply {
                        text = rom.url
                        textSize = 13f
                        setTextColor(Color.parseColor("#1565C0"))
                        setPadding(0, dp(6), 0, 0)
                        setOnClickListener { openLink(rom.url) }
                        setOnLongClickListener {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ROM link", rom.url))
                            Toast.makeText(this@MiAssistantActivity, "Link copied", Toast.LENGTH_SHORT).show()
                            true
                        }
                    })
                    resultContent.addView(card)
                }
                show(resultContainer)
            }
        }

        override fun showFiles(path: String, entries: List<RemoteEntry>) {
            runOnUiThread {
                resultContent.removeAllViews()
                resultContent.gravity = Gravity.TOP
                resultContent.addView(TextView(this@MiAssistantActivity).apply {
                    text = path
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.BLACK)
                    setTextIsSelectable(true)
                    setPadding(dp(4), 0, dp(4), dp(12))
                })
                if (path != "/") {
                    val up = newCard()
                    up.addView(TextView(this@MiAssistantActivity).apply {
                        text = ".."
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#E65100"))
                    })
                    up.setOnClickListener { start(Action.LIST, path = parentOf(path)) }
                    resultContent.addView(up)
                }
                if (entries.isEmpty()) {
                    resultContent.addView(TextView(this@MiAssistantActivity).apply {
                        text = "Empty directory."
                        textSize = 14f
                        setTextColor(Color.parseColor("#777777"))
                        setPadding(dp(4), dp(8), dp(4), 0)
                    })
                }
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                for (entry in entries) {
                    val openAsFolder = entry.isDir || entry.isLink
                    val card = newCard()
                    card.addView(TextView(this@MiAssistantActivity).apply {
                        text = if (openAsFolder) entry.name + "/" else entry.name
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (openAsFolder) Color.parseColor("#E65100") else Color.BLACK)
                    })
                    val when_ = if (entry.mtime > 0) dateFormat.format(Date(entry.mtime * 1000)) else "-"
                    card.addView(TextView(this@MiAssistantActivity).apply {
                        text = "${fileModeString(entry.mode)}   ${if (entry.isDir) "-" else humanSize(entry.size)}   $when_"
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setTextColor(Color.parseColor("#777777"))
                        setPadding(0, dp(4), 0, 0)
                    })
                    card.setOnClickListener {
                        if (openAsFolder) {
                            start(Action.LIST, path = path + entry.name + "/")
                        } else {
                            pendingRemote = path + entry.name
                            createDoc.launch(entry.name)
                        }
                    }
                    resultContent.addView(card)
                }
                show(resultContainer)
            }
        }

        override fun showMessage(message: String, success: Boolean?) {
            runOnUiThread {
                resultContent.removeAllViews()
                resultContent.gravity = Gravity.CENTER
                resultContent.addView(TextView(this@MiAssistantActivity).apply {
                    text = message
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(
                        Color.parseColor(
                            when (success) {
                                true -> "#2E7D32"
                                false -> "#C62828"
                                null -> "#000000"
                            }
                        )
                    )
                })
                show(resultContainer)
            }
        }

        override suspend fun confirm(message: String): Boolean = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val dialog = AlertDialog.Builder(this@MiAssistantActivity)
                    .setTitle("Warning")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Continue") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }

        override suspend fun confirmTyped(message: String, word: String): Boolean = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val box = LinearLayout(this@MiAssistantActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(8), dp(24), 0)
                }
                box.addView(TextView(this@MiAssistantActivity).apply {
                    text = message
                    textSize = 14f
                    setTextColor(Color.BLACK)
                })
                val edit = EditText(this@MiAssistantActivity).apply {
                    hint = "Type $word to continue"
                    isSingleLine = true
                }
                box.addView(edit)
                val dialog = AlertDialog.Builder(this@MiAssistantActivity)
                    .setTitle("Warning")
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton("Continue") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .create()
                dialog.setOnShowListener { dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false }
                edit.doAfterTextChanged {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = it?.toString()?.trim() == word
                }
                dialog.show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }

        override suspend fun askText(title: String, message: String, hint: String, default: String): String? =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val box = LinearLayout(this@MiAssistantActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(24), dp(8), dp(24), 0)
                    }
                    if (message.isNotEmpty()) {
                        box.addView(TextView(this@MiAssistantActivity).apply {
                            text = message
                            textSize = 14f
                            setTextColor(Color.BLACK)
                            setTextIsSelectable(true)
                        })
                    }
                    val hintValue = hint
                    val edit = EditText(this@MiAssistantActivity).apply {
                        setHint(hintValue)
                        setText(default)
                        isSingleLine = true
                    }
                    box.addView(edit)
                    val dialog = AlertDialog.Builder(this@MiAssistantActivity)
                        .setTitle(title)
                        .setView(box)
                        .setCancelable(false)
                        .setPositiveButton("OK") { _, _ -> if (cont.isActive) cont.resume(edit.text.toString().trim()) }
                        .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
                        .show()
                    cont.invokeOnCancellation { dialog.dismiss() }
                }
            }
    }

    private fun newCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeDir(p: String): String {
        var s = p.trim().ifEmpty { "/cache/" }
        if (!s.startsWith("/")) s = "/$s"
        if (!s.endsWith("/")) s += "/"
        return s
    }

    private fun parentOf(path: String): String {
        val t = path.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i + 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_miassistant)

        menuContainer = findViewById(R.id.menuContainer)
        menuList = findViewById(R.id.menuList)
        workContainer = findViewById(R.id.workContainer)
        resultContainer = findViewById(R.id.resultContainer)
        workTitle = findViewById(R.id.workTitle)
        hintText = findViewById(R.id.hintText)
        progressBar = findViewById(R.id.progressBar)
        percentText = findViewById(R.id.percentText)
        resultContent = findViewById(R.id.resultContent)
        backButton = findViewById(R.id.backButton)

        showMenu(mainMenu)
        backButton.setOnClickListener { show(menuContainer) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    busy -> AlertDialog.Builder(this@MiAssistantActivity)
                        .setTitle("Stop?")
                        .setMessage("The current operation will be stopped.")
                        .setPositiveButton("Stop") { _, _ -> job?.cancel() }
                        .setNegativeButton("Continue", null)
                        .show()
                    resultContainer.visibility == View.VISIBLE -> show(menuContainer)
                    inSubmenu -> showMenu(mainMenu)
                    else -> finish()
                }
            }
        })

        show(menuContainer)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun addMenuButton(text: String, topMargin: Int, gray: Boolean = false, onClick: () -> Unit) {
        val button = layoutInflater.inflate(R.layout.item_menu_button, menuList, false) as Button
        button.text = text
        if (gray) button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#888888"))
        (button.layoutParams as LinearLayout.LayoutParams).topMargin = topMargin
        button.setOnClickListener { onClick() }
        menuList.addView(button)
    }

    private fun showMenu(items: List<MenuItem>, sub: Boolean = false) {
        inSubmenu = sub
        menuList.removeAllViews()
        items.forEachIndexed { i, entry ->
            val gap = when {
                i == 0 -> 0
                entry.gapBefore -> dp(28)
                else -> dp(12)
            }
            val children = entry.children
            if (children != null) {
                addMenuButton(entry.label + "  \u203A", gap) { showMenu(children, sub = true) }
            } else {
                addMenuButton(entry.label, gap) { onAction(entry.action!!) }
            }
        }
        if (sub) addMenuButton("Back", dp(28), gray = true) { showMenu(mainMenu) }
        (menuContainer as? ScrollView)?.scrollTo(0, 0)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onAction(action: Action) {
        if (job?.isActive == true) return
        when (action) {
            Action.FLASH ->
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))

            Action.LIST -> scope.launch {
                val p = ui.askText("List Files", "", "Remote directory", "/cache/") ?: return@launch
                start(Action.LIST, path = normalizeDir(p))
            }

            Action.PULL -> scope.launch {
                val p = ui.askText("Pull File", "", "Remote file path", "/cache/recovery/last_log") ?: return@launch
                if (p.isBlank()) return@launch
                pendingRemote = p
                createDoc.launch(p.trimEnd('/').substringAfterLast('/').ifEmpty { "pulled_file" })
            }

            Action.WIPE_EFS -> scope.launch {
                val ok = ui.confirmTyped(
                    "If you are a normal user and don't know what this means, DO NOT use this command!\n\n" +
                        "Wiping EFS will permanently break your phone's network and delete your IMEI.",
                    "YES"
                )
                if (ok) start(Action.WIPE_EFS)
            }

            else -> {
                val warning = action.warning
                if (warning == null) start(action)
                else scope.launch {
                    if (ui.confirm("$warning\n\nThis cannot be undone.")) start(action)
                }
            }
        }
    }

    private fun start(action: Action, zip: Uri? = null, path: String? = null, outUri: Uri? = null) {
        if (job?.isActive == true) return

        resultContent.removeAllViews()
        workTitle.text = "Waiting for the phone ..."
        hintText.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        percentText.visibility = View.GONE
        show(workContainer)

        job = scope.launch {
            setBusy(true)
            var adb: MiAssistantAdb? = null
            try {
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                val device = MiAssistantUsb.waitForDevice(usbManager)

                hintText.visibility = View.GONE
                ui.status("Connecting ...")
                if (!MiAssistantUsb.ensurePermission(this@MiAssistantActivity, usbManager, device)) {
                    ui.showMessage("USB permission was not granted.", false)
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val session = MiAssistantAdb.open(usbManager, device)
                    adb = session
                    session.connectHost()
                    val engine = MiAssistantEngine(session, ui)
                    when (action) {
                        Action.INFO -> engine.readInfo()
                        Action.FLASH -> engine.flashRom(contentResolver, zip!!)
                        Action.ROMS -> engine.checkRom()
                        Action.FORMAT_FRP -> engine.formatFrp()
                        Action.LIST -> engine.listFiles(path!!)
                        Action.PULL -> engine.pullFile(contentResolver, path!!, outUri!!)
                        else -> engine.runCommand(action.command!!)
                    }
                }
            } catch (e: CancellationException) {
                show(menuContainer)
                throw e
            } catch (e: Exception) {
                ui.showMessage(e.message ?: e.javaClass.simpleName, false)
            } finally {
                adb?.close()
                setBusy(false)
            }
        }
    }
}
