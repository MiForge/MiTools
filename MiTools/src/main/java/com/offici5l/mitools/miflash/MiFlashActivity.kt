package com.offici5l.mitools.miflash

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import android.content.res.ColorStateList
import com.offici5l.mitools.R
import com.offici5l.mitools.miassistant.MiAssistantUsb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MiFlashActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var busy = false
    private var prepared: PreparedRom? = null

    private lateinit var menuContainer: View
    private lateinit var menuList: LinearLayout
    private lateinit var workContainer: View
    private lateinit var resultContainer: View
    private lateinit var workTitle: TextView
    private lateinit var detailText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var backButton: Button

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepare(uri)
    }

    private fun launchPicker() = pickRom.launch(arrayOf("*/*"))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun show(panel: View) {
        listOf(menuContainer, workContainer, resultContainer).forEach {
            it.visibility = if (it === panel) View.VISIBLE else View.GONE
        }
    }

    private val ui = object : MiFlashUi {

        override fun status(message: String) {
            runOnUiThread {
                show(workContainer)
                workTitle.text = message
                detailText.visibility = View.GONE
                progressBar.isIndeterminate = true
                percentText.visibility = View.GONE
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

        override fun detail(text: String) {
            runOnUiThread {
                detailText.text = text
                detailText.visibility = View.VISIBLE
            }
        }

        override fun showMessage(message: String, success: Boolean?) {
            runOnUiThread {
                resultContent.removeAllViews()
                resultContent.addView(TextView(this@MiFlashActivity).apply {
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
    }

    private suspend fun confirm(message: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val dialog = AlertDialog.Builder(this@MiFlashActivity)
                .setTitle("Warning")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Continue") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                .show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private suspend fun confirmTyped(message: String, word: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val box = LinearLayout(this@MiFlashActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            box.addView(TextView(this@MiFlashActivity).apply {
                text = message
                textSize = 14f
                setTextColor(Color.BLACK)
            })
            val edit = EditText(this@MiFlashActivity).apply {
                hint = "Type $word to continue"
                isSingleLine = true
            }
            box.addView(edit)
            val dialog = AlertDialog.Builder(this@MiFlashActivity)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_miflash)

        menuContainer = findViewById(R.id.menuContainer)
        menuList = findViewById(R.id.menuList)
        workContainer = findViewById(R.id.workContainer)
        resultContainer = findViewById(R.id.resultContainer)
        workTitle = findViewById(R.id.workTitle)
        detailText = findViewById(R.id.detailText)
        hintText = findViewById(R.id.hintText)
        progressBar = findViewById(R.id.progressBar)
        percentText = findViewById(R.id.percentText)
        resultContent = findViewById(R.id.resultContent)
        backButton = findViewById(R.id.backButton)

        backButton.setOnClickListener { show(menuContainer) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    busy -> AlertDialog.Builder(this@MiFlashActivity)
                        .setTitle("Stop?")
                        .setMessage("The current operation will be stopped.\n\nStopping in the middle of flashing can leave the phone unbootable.")
                        .setPositiveButton("Stop") { _, _ -> job?.cancel() }
                        .setNegativeButton("Continue", null)
                        .show()
                    resultContainer.visibility == View.VISIBLE -> show(menuContainer)
                    else -> finish()
                }
            }
        })

        showRomMenu()
        show(menuContainer)

        if (savedInstanceState == null) launchPicker()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun addMenuButton(text: CharSequence, topMargin: Int, gray: Boolean = false, onClick: () -> Unit) {
        val button = layoutInflater.inflate(R.layout.item_menu_button, menuList, false) as Button
        button.text = text
        if (gray) button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#888888"))
        (button.layoutParams as LinearLayout.LayoutParams).topMargin = topMargin
        button.setOnClickListener { onClick() }
        menuList.addView(button)
    }

    private fun showRomMenu() {
        prepared = null
        menuList.removeAllViews()
        addMenuButton("Select ROM", 0) { launchPicker() }
    }

    private fun showOptions(rom: PreparedRom) {
        menuList.removeAllViews()
        rom.options.forEachIndexed { i, option ->
            val text = SpannableStringBuilder(option.label).append("\n")
            val start = text.length
            text.append(option.file.name)
            text.setSpan(RelativeSizeSpan(0.75f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            addMenuButton(text, if (i == 0) 0 else dp(12)) { onOption(rom, option) }
        }
        addMenuButton("Select another ROM", dp(28), gray = true) { launchPicker() }
    }

    private fun prepare(uri: Uri) {
        if (job?.isActive == true) return

        showRomMenu()
        workTitle.text = "Reading the ROM ..."
        detailText.visibility = View.GONE
        hintText.visibility = View.GONE
        progressBar.isIndeterminate = true
        percentText.visibility = View.GONE
        show(workContainer)

        job = scope.launch {
            setBusy(true)
            try {
                val rom = withContext(Dispatchers.IO) { MiFlashEngine(applicationContext, ui).prepare(uri) }
                if (rom != null) {
                    prepared = rom
                    showOptions(rom)
                    show(menuContainer)
                }
            } catch (e: CancellationException) {
                show(menuContainer)
                throw e
            } catch (e: Exception) {
                ui.showMessage(e.message ?: e.javaClass.simpleName, false)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun onOption(rom: PreparedRom, option: ScriptOption) {
        if (job?.isActive == true) return

        if (option.parsed.unsupported.isNotEmpty()) {
            ui.showMessage(
                "This script uses commands that are not supported yet:\n\n" +
                    option.parsed.unsupported.take(3).joinToString("\n"),
                false
            )
            return
        }

        val text = StringBuilder(option.label).append("\n\n")
        text.append(
            if (option.erasesData) "All data on the phone will be erased.\n\n"
            else "This script does not erase user data.\n\n"
        )
        if (option.locksBootloader) text.append("The bootloader will be LOCKED at the end.\n\n")
        text.append("If the ROM is not the official one for this exact phone, the phone may become unusable.")

        scope.launch {
            val ok = if (option.locksBootloader) confirmTyped(text.toString(), "YES") else confirm(text.toString())
            if (ok) startFlash(rom, option)
        }
    }

    private fun startFlash(rom: PreparedRom, option: ScriptOption) {
        if (job?.isActive == true) return

        workTitle.text = "Waiting for the phone ..."
        detailText.visibility = View.GONE
        hintText.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        percentText.visibility = View.GONE
        show(workContainer)

        job = scope.launch {
            setBusy(true)
            try {
                val usbManager = getSystemService(USB_SERVICE) as UsbManager

                val connector: suspend () -> FastbootConnection = {
                    val device = FastbootUsb.waitForDevice(usbManager)
                    if (!MiAssistantUsb.ensurePermission(this@MiFlashActivity, usbManager, device)) {
                        throw FastbootException("USB permission was not granted.")
                    }
                    withContext(Dispatchers.IO) { FastbootConnection.open(usbManager, device) }
                }

                val connection = connector()
                hintText.visibility = View.GONE
                val finished = withContext(Dispatchers.IO) {
                    MiFlashEngine(applicationContext, ui).flash(rom, option, connection, connector)
                }

                if (finished) showRomMenu()
            } catch (e: CancellationException) {
                show(menuContainer)
                throw e
            } catch (e: Exception) {
                ui.showMessage(e.message ?: e.javaClass.simpleName, false)
            } finally {
                setBusy(false)
            }
        }
    }
}
