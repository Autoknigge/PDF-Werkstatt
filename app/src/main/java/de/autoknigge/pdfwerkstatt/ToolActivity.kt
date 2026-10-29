package de.autoknigge.pdfwerkstatt

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

/** Werkzeuge für eine einzelne PDF: Aufteilen, Verkleinern, zu Bildern, Passwort & Metadaten. */
class ToolActivity : AppCompatActivity() {

    enum class Mode(val title: String, val action: String, val actionSub: String) {
        SPLIT("PDF aufteilen", "Aufteilen", "Teile in „Downloads/PDF-Profi Werkstatt“"),
        COMPRESS("PDF verkleinern", "Verkleinern", "Wird in „Downloads/PDF-Profi Werkstatt“ gespeichert"),
        IMAGES("PDF zu Bildern", "Bilder speichern", "In „Bilder/PDF-Profi Werkstatt“ (Galerie)"),
        PROTECT("Passwort & Metadaten", "Speichern", "Wird in „Downloads/PDF-Profi Werkstatt“ gespeichert")
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }

    private lateinit var mode: Mode
    private var source: PdfSource? = null
    private var busy = false
    private var loading = false

    private lateinit var adManager: AdManager
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var tvFile: TextView
    private lateinit var btnPick: View
    private lateinit var btnRun: View
    private lateinit var progress: ProgressBar
    private lateinit var tvResult: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: Mode.COMPRESS
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_tool)
        Ui.insets(findViewById(R.id.root))

        tvFile = findViewById(R.id.tvFile)
        btnPick = findViewById(R.id.btnPick)
        btnRun = findViewById(R.id.btnRun)
        progress = findViewById(R.id.progress)
        tvResult = findViewById(R.id.tvResult)

        findViewById<TextView>(R.id.tvTitle).text = mode.title
        findViewById<TextView>(R.id.tvAction).text = mode.action
        findViewById<TextView>(R.id.tvActionSub).text = mode.actionSub
        mapOf(
            Mode.SPLIT to R.id.secSplit,
            Mode.COMPRESS to R.id.secCompress,
            Mode.IMAGES to R.id.secImages,
            Mode.PROTECT to R.id.secProtect
        ).forEach { (m, id) -> findViewById<View>(id).visibility = if (m == mode) View.VISIBLE else View.GONE }

        val etRanges = findViewById<EditText>(R.id.etRanges)
        findViewById<RadioGroup>(R.id.rgSplit).setOnCheckedChangeListener { _, id ->
            etRanges.visibility = if (id == R.id.rbRanges) View.VISIBLE else View.GONE
        }
        val pwBox = findViewById<View>(R.id.boxPassword)
        findViewById<CheckBox>(R.id.cbPassword).setOnCheckedChangeListener { _, on ->
            pwBox.visibility = if (on) View.VISIBLE else View.GONE
        }

        btnPick.setOnClickListener { if (!busy && !loading) picker.launch(arrayOf("application/pdf")) }
        btnRun.setOnClickListener { run() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        adManager = AdManager(this, findViewById<FrameLayout>(R.id.adContainer), null)
        Intro.run(this) { adManager.start() }

        updateUi()
        if (savedInstanceState == null) Flow.incomingUris(intent).firstOrNull()?.let { open(it) }
    }

    override fun onPause() {
        adManager.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adManager.resume()
    }

    override fun onDestroy() {
        adManager.destroy()
        worker.shutdown()
        super.onDestroy()
    }

    private fun open(uri: android.net.Uri) {
        loading = true
        tvResult.visibility = View.GONE
        updateUi()
        Flow.load(this, worker, uri) { src ->
            loading = false
            if (src != null) source = src
            updateUi()
        }
    }

    private fun updateUi() {
        val src = source
        tvFile.text = when {
            loading -> "Wird geöffnet …"
            src == null -> "Tippen, um eine PDF zu wählen"
            else -> "${src.name}\n${Docs.pagesText(src.pages)} · ${Docs.formatSize(src.size)}" +
                if (src.wasEncrypted) " · Passwort entfernt" else ""
        }
        findViewById<TextView>(R.id.tvWasEncrypted).visibility =
            if (mode == Mode.PROTECT && src?.wasEncrypted == true) View.VISIBLE else View.GONE
        btnRun.isEnabled = src != null && !busy && !loading
        btnRun.alpha = if (btnRun.isEnabled) 1f else 0.5f
        btnPick.isEnabled = !busy
    }

    private fun run() {
        val src = source ?: return
        if (busy) return
        when (mode) {
            Mode.SPLIT -> runSplit(src)
            Mode.COMPRESS -> runCompress(src)
            Mode.IMAGES -> runImages(src)
            Mode.PROTECT -> runProtect(src)
        }
    }

    private fun rangeError(src: PdfSource) =
        Flow.toast(this, "Ungültige Seitenangabe. Beispiel: 1-3, 5, 8-10 (die PDF hat ${Docs.pagesText(src.pages)}).")

    private fun runSplit(src: PdfSource) {
        val ranges = if (findViewById<RadioGroup>(R.id.rgSplit).checkedRadioButtonId == R.id.rbRanges) {
            Docs.parseRanges(findViewById<EditText>(R.id.etRanges).text.toString(), src.pages)
                ?: return rangeError(src)
        } else {
            (1..src.pages).map { it..it }
        }
        if (ranges.size > 500) return Flow.toast(this, "Höchstens 500 Teile auf einmal.")
        work(ranges.size, "Aufteilen fehlgeschlagen", { p -> PdfOps.split(this, src, ranges, p) }) {
            Flow.showDone(this, "Aufgeteilt", it, "application/pdf", "Downloads/${Docs.FOLDER}")
        }
    }

    private fun runCompress(src: PdfSource) {
        val level = when (findViewById<RadioGroup>(R.id.rgLevel).checkedRadioButtonId) {
            R.id.rbLight -> PdfOps.Level.LIGHT
            R.id.rbStrong -> PdfOps.Level.STRONG
            else -> PdfOps.Level.MEDIUM
        }
        val raster = findViewById<CheckBox>(R.id.cbRaster).isChecked
        work(src.pages, "Verkleinern fehlgeschlagen", { p -> PdfOps.compress(this, src, level, raster, p) }) { r ->
            val before = Docs.formatSize(r.before)
            val result = r.result
            if (result == null) {
                tvResult.text = "Diese PDF lässt sich mit dieser Einstellung nicht weiter verkleinern ($before).\n" +
                    if (raster) "Versuche die Stufe „Stark“." else "Versuche „Stark“ oder „Seiten komplett als Bild“."
                tvResult.visibility = View.VISIBLE
            } else {
                val pct = (100 - r.after * 100 / r.before).coerceAtLeast(1L)
                tvResult.text = "Vorher $before · nachher ${Docs.formatSize(r.after)} (−$pct %)"
                tvResult.visibility = View.VISIBLE
                Flow.showDone(this, "Verkleinert um $pct %", result, "application/pdf", "Downloads/${Docs.FOLDER}")
            }
        }
    }

    private fun runImages(src: PdfSource) {
        val text = findViewById<EditText>(R.id.etPages).text.toString()
        val pages = if (text.isBlank()) {
            (1..src.pages).toList()
        } else {
            Docs.parseRanges(text, src.pages)?.flatMap { it.toList() }?.distinct() ?: return rangeError(src)
        }
        val png = findViewById<RadioGroup>(R.id.rgFormat).checkedRadioButtonId == R.id.rbPng
        val dpi = when (findViewById<RadioGroup>(R.id.rgRes).checkedRadioButtonId) {
            R.id.rbResLow -> 100
            R.id.rbResHigh -> 300
            else -> 150
        }
        work(pages.size, "Bilder konnten nicht gespeichert werden", { p -> PdfOps.toImages(this, src, pages, dpi, png, p) }) {
            Flow.showDone(this, "Bilder gespeichert", it, if (png) "image/png" else "image/jpeg", "Bilder/${Docs.FOLDER}")
        }
    }

    private fun runProtect(src: PdfSource) {
        val strip = findViewById<CheckBox>(R.id.cbMeta).isChecked
        var password: String? = null
        if (findViewById<CheckBox>(R.id.cbPassword).isChecked) {
            val p1 = findViewById<EditText>(R.id.etPw1).text.toString()
            val p2 = findViewById<EditText>(R.id.etPw2).text.toString()
            if (p1.length < 4) return Flow.toast(this, "Das Passwort muss mindestens 4 Zeichen haben.")
            if (p1 != p2) return Flow.toast(this, "Die beiden Passwörter stimmen nicht überein.")
            password = p1
        }
        if (!strip && password == null && !src.wasEncrypted) {
            return Flow.toast(this, "Bitte wähle aus, was geändert werden soll.")
        }
        val title = when {
            password != null -> "PDF geschützt"
            src.wasEncrypted && !strip -> "Passwort entfernt"
            else -> "PDF bereinigt"
        }
        work(0, "PDF konnte nicht gespeichert werden", { PdfOps.protect(this, src, strip, password) }) {
            val note = if (password != null) "Ohne das Passwort lässt sich die PDF nicht öffnen. Gut aufbewahren!" else ""
            Flow.showDone(this, title, SaveResult(it.uris, it.names, note), "application/pdf", "Downloads/${Docs.FOLDER}")
        }
    }

    /** Führt [task] im Hintergrund aus, mit Fortschrittsbalken ([steps] = 0: ohne Zähler). */
    private fun <T> work(steps: Int, failText: String, task: ((Int) -> Unit) -> T, onSuccess: (T) -> Unit) {
        busy = true
        tvResult.visibility = View.GONE
        progress.isIndeterminate = steps == 0
        progress.max = maxOf(1, steps)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        updateUi()
        worker.execute {
            val r = runCatching { task { done -> runOnUiThread { progress.progress = done } } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                progress.visibility = View.GONE
                updateUi()
                r.onSuccess(onSuccess).onFailure { Flow.error(this, failText, it) }
            }
        }
    }
}
