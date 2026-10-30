package de.autoknigge.pdfwerkstatt

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

/** Formulare unterschreiben: Unterschrift zeichnen, auf die Seite schieben, als neue PDF speichern. */
class SignActivity : AppCompatActivity() {

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val GOLD = 0xFFD4AF37.toInt()
        private const val TEXT = 0xFFF8E7A4.toInt()
        private const val DIM = 0xFFCFC38E.toInt()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var adManager: AdManager
    private var source: PdfSource? = null
    private var renderer: PageRenderer? = null
    private var pageIdx = 0
    private var pages = 0
    private var busy = false
    private var loading = false
    private val placements = ArrayList<Placement>()
    private var sig: Bitmap? = null

    private lateinit var place: PlaceView
    private lateinit var tvFile: TextView
    private lateinit var tvPage: TextView
    private lateinit var tvSize: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnSave: TextView
    private lateinit var btnAdd: TextView
    private lateinit var btnRemove: TextView
    private lateinit var progress: ProgressBar
    private var updatingSeek = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun darkButton(label: String, onClick: () -> Unit) = text(label, 15f, TEXT, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(0xFF1B2E22.toInt())
            setStroke(dp(2), 0xFFC9A648.toInt())
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        minHeight = dp(46)
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        sig = SignatureStore.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
        }
        Ui.insets(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(14), 0)
        }
        top.addView(text("‹", 34f, GOLD).apply {
            gravity = Gravity.CENTER
            contentDescription = "Zurück"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(text("PDF unterschreiben", 22f, GOLD, true))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // PDF wählen
        val pick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_btn_gold)
            setPadding(dp(14), dp(8), dp(14), dp(13))
            isClickable = true
            setOnClickListener { if (!busy && !loading) picker.launch(arrayOf("application/pdf")) }
        }
        pick.addView(text("📂", 24f, Color.BLACK), LinearLayout.LayoutParams(dp(40), WRAP))
        tvFile = text("Tippen, um ein Formular (PDF) zu wählen", 16f, resources.getColor(R.color.auf_gold, theme), true)
        pick.addView(tvFile, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(pick, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), 0) })

        // Seite mit Unterschrift
        place = PlaceView(this).apply {
            emptyText = "Wähle oben ein Formular.\nDanach schiebst du deine Unterschrift\ndorthin, wo sie gebraucht wird."
            placements = this@SignActivity.placements
            sig = this@SignActivity.sig
            onSelect = { updateControls() }
            onResize = { p -> setSeek(p) }
        }
        root.addView(place, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        // Seitenwechsel
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        nav.addView(darkButton("‹  Zurück") { gotoPage(pageIdx - 1) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(14), 0, dp(6), 0) })
        tvPage = text("", 15f, TEXT, true).apply { gravity = Gravity.CENTER }
        nav.addView(tvPage, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(darkButton("Weiter  ›") { gotoPage(pageIdx + 1) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), 0, dp(14), 0) })
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // Größe
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), 0)
        }
        tvSize = text("Größe", 14f, DIM)
        sizeRow.addView(tvSize, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
        seek = SeekBar(this).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (!user || updatingSeek) return
                    place.selected?.let {
                        it.w = 0.08f + 0.82f * p / 100f
                        place.invalidate()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        sizeRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(sizeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Aktionen
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(6), dp(14), 0)
        }
        actions.addView(darkButton("✍  Unterschrift") { showPad() }, LinearLayout.LayoutParams(0, WRAP, 1.15f).apply { rightMargin = dp(6) })
        btnAdd = darkButton("＋  Hier einsetzen") { addPlacement() }
        actions.addView(btnAdd, LinearLayout.LayoutParams(0, WRAP, 1.25f).apply { rightMargin = dp(6) })
        btnRemove = darkButton("🗑") { removeSelected() }.apply { contentDescription = "Diese Unterschrift entfernen" }
        actions.addView(btnRemove, LinearLayout.LayoutParams(0, WRAP, 0.5f))
        root.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

        btnSave = text("Speichern", 20f, resources.getColor(R.color.auf_gold, theme), true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_btn_gold)
            setPadding(dp(16), dp(12), dp(16), dp(18))
            minHeight = dp(58)
            isClickable = true
            setOnClickListener { save() }
        }
        root.addView(btnSave, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(8), dp(14), 0) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), 0) })

        val adBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ad)
            minimumHeight = dp(60)
        }
        root.addView(adBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        setContentView(root)

        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }
        updateControls()
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
        val r = renderer
        renderer = null
        worker.execute { runCatching { r?.close() } }
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ PDF und Seiten

    private fun open(uri: android.net.Uri) {
        loading = true
        tvFile.text = "Wird geöffnet …"
        Flow.load(this, worker, uri) { src ->
            if (src == null) {
                loading = false
                updateControls()
                return@load
            }
            worker.execute {
                val old = renderer
                val r = runCatching { PageRenderer(src.file) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading = false
                    r.onSuccess {
                        runCatching { old?.close() }
                        renderer = it
                        source = src
                        pages = it.pageCount
                        pageIdx = 0
                        placements.clear()
                        place.selected = null
                        tvFile.text = "${src.name}\n${Docs.pagesText(pages)}"
                        showPage()
                        if (sig == null) showPad()
                    }.onFailure {
                        tvFile.text = "Tippen, um ein Formular (PDF) zu wählen"
                        Flow.toast(this, it.message ?: "Die PDF lässt sich nicht darstellen.")
                    }
                    updateControls()
                }
            }
        }
    }

    private fun gotoPage(i: Int) {
        if (source == null || i !in 0 until pages || busy) return
        pageIdx = i
        place.selected = null
        showPage()
    }

    private fun showPage() {
        val r = renderer ?: return
        val idx = pageIdx
        worker.execute {
            val bmp = runCatching { r.render(idx, 1800) }
            runOnUiThread {
                if (isFinishing || isDestroyed || idx != pageIdx) return@runOnUiThread
                bmp.onSuccess { place.setPage(it, idx) }.onFailure { Flow.error(this, "Seite konnte nicht angezeigt werden", it) }
                updateControls()
            }
        }
    }

    // ------------------------------------------------------------------ Unterschrift

    private fun showPad() {
        val pad = SignaturePad(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        box.addView(pad, LinearLayout.LayoutParams(MATCH, dp(220)))
        val ink = LinearLayout(this).apply { gravity = Gravity.CENTER }
        ink.addView(darkButton("Blau") { pad.ink = 0xFF10205E.toInt() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, dp(8), dp(6), 0) })
        ink.addView(darkButton("Schwarz") { pad.ink = 0xFF111111.toInt() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), dp(8), 0, 0) })
        box.addView(ink)
        box.addView(text("Die Unterschrift bleibt nur auf deinem Gerät gespeichert.", 12f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        val dlg = AlertDialog.Builder(this)
            .setTitle("Unterschrift zeichnen")
            .setView(box)
            .setPositiveButton("Übernehmen", null)
            .setNeutralButton("Löschen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bmp = pad.toBitmap()
            if (bmp == null) {
                Flow.toast(this, "Bitte zuerst unterschreiben.")
            } else {
                SignatureStore.save(this, bmp)
                sig = bmp
                place.sig = bmp
                dlg.dismiss()
                if (source != null && placements.none { it.page == pageIdx }) addPlacement()
                updateControls()
            }
        }
    }

    private fun addPlacement() {
        if (source == null) return Flow.toast(this, "Wähle zuerst ein Formular.")
        if (sig == null) return showPad()
        val p = Placement(pageIdx, 0.5f, 0.82f, 0.30f)
        placements.add(p)
        place.selected = p
        setSeek(p)
        Flow.toast(this, "Mit dem Finger verschieben, mit zwei Fingern oder dem Regler die Größe ändern.")
    }

    private fun removeSelected() {
        val s = place.selected ?: return
        placements.remove(s)
        place.selected = null
    }

    private fun setSeek(p: Placement) {
        updatingSeek = true
        seek.progress = ((p.w - 0.08f) / 0.82f * 100f).toInt().coerceIn(0, 100)
        updatingSeek = false
    }

    private fun updateControls() {
        val src = source
        tvPage.text = if (src == null) "" else "Seite ${pageIdx + 1} von $pages"
        val sel = place.selected
        seek.isEnabled = sel != null
        seek.alpha = if (sel != null) 1f else 0.4f
        tvSize.alpha = seek.alpha
        if (sel != null) setSeek(sel)
        btnRemove.alpha = if (sel != null) 1f else 0.4f
        btnAdd.alpha = if (src != null) 1f else 0.5f
        val ready = src != null && placements.isNotEmpty() && !busy && !loading
        btnSave.isEnabled = ready
        btnSave.alpha = if (ready) 1f else 0.5f
        btnSave.text = if (placements.isEmpty()) "Speichern" else "Speichern (${placements.size} Unterschrift${if (placements.size == 1) "" else "en"})"
    }

    // ------------------------------------------------------------------ Speichern

    private fun save() {
        val src = source ?: return
        if (busy || placements.isEmpty()) return
        val sigFile = SignatureStore.file(this)
        if (!sigFile.exists()) return showPad()
        val list = placements.map { Placement(it.page, it.cx, it.cy, it.w) }
        busy = true
        val steps = list.map { it.page }.distinct().size
        progress.isIndeterminate = false
        progress.max = maxOf(1, steps)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        updateControls()
        worker.execute {
            val r = runCatching { PdfOps.sign(this, src, list, sigFile) { done -> runOnUiThread { progress.progress = done } } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                progress.visibility = View.GONE
                updateControls()
                r.onSuccess {
                    Flow.showDone(this, "PDF unterschrieben", it, "application/pdf", "Downloads/${Docs.FOLDER}")
                }.onFailure { Flow.error(this, "Unterschreiben fehlgeschlagen", it) }
            }
        }
    }
}
