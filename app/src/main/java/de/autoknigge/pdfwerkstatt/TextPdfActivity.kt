package de.autoknigge.pdfwerkstatt

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Zwei kleine Werkzeuge:
 * CONVERT – Word, ODT, EPUB, HTML, RTF oder Text als PDF speichern.
 * APPEND  – eine oder mehrere Textseiten an eine PDF anhängen (oder als Deckblatt voranstellen).
 */
class TextPdfActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val CONVERT = "convert"
        const val APPEND = "append"
        private const val GOLD = 0xFFD4AF37.toInt()
        private const val CREME = 0xFFF8E7A4.toInt()
        private const val TANNE = 0xFF1A2C20.toInt()
    }

    private lateinit var mode: String
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var tvFile: TextView
    private lateinit var btnRun: TextView
    private lateinit var progress: ProgressBar
    private lateinit var etText: EditText
    private lateinit var rgPos: RadioGroup
    private lateinit var rgSize: RadioGroup
    private lateinit var rgMargin: RadioGroup
    private lateinit var cbHeading: CheckBox

    private class Opts(val fontSize: Float, val margin: Int, val headingBreak: Boolean)
    private lateinit var adManager: AdManager

    private var busy = false
    private var loading = false
    private var docName = ""
    private var docText: CharSequence? = null   // Ergebnis der Umwandlung (CONVERT)
    private var source: PdfSource? = null       // gewählte PDF (APPEND)

    private val docPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) openDoc(u) }
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            loading = true
            update()
            Flow.load(this, worker, u) { s ->
                loading = false
                if (s != null) source = s
                update()
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: CONVERT
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(GOLD); gravity = Gravity.CENTER
            contentDescription = "Zurück"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(TextView(this).apply {
            text = if (mode == CONVERT) "Dokument in PDF" else "Textseite anhängen"
            textSize = 22f; setTextColor(GOLD); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(10)) }

        val pick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_btn_gold)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener {
                if (busy || loading) return@setOnClickListener
                if (mode == CONVERT) docPicker.launch(arrayOf(
                    "text/*", "application/rtf", "application/xhtml+xml", "application/epub+zip",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.oasis.opendocument.text", "application/octet-stream"
                )) else pdfPicker.launch(arrayOf("application/pdf"))
            }
        }
        pick.addView(TextView(this).apply {
            text = if (mode == CONVERT) "📂  Datei auswählen" else "📂  PDF auswählen"
            textSize = 19f; setTextColor(resources.getColor(R.color.auf_gold, null)); setTypeface(typeface, Typeface.BOLD)
        })
        tvFile = TextView(this).apply {
            textSize = 14f; setTextColor(resources.getColor(R.color.auf_gold_sub, null))
        }
        pick.addView(tvFile)
        col.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        etText = EditText(this)
        rgPos = RadioGroup(this)
        if (mode == CONVERT) {
            col.addView(TextView(this).apply {
                text = "Word (.docx), OpenOffice (.odt), E-Book (.epub), Webseite (.html), .rtf und Text (.txt). " +
                    "Fett, kursiv, Überschriften und Listen aus Word-Dateien bleiben erhalten. Bilder und Tabellen werden nicht übernommen."
                textSize = 13f; setTextColor(CREME); setPadding(dp(2), dp(10), dp(2), 0)
            })
        } else {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_tile_gruen)
                setPadding(dp(16), dp(10), dp(16), dp(12))
            }
            box.addView(TextView(this).apply { text = "Text der neuen Seite"; textSize = 13f; setTextColor(GOLD); setTypeface(typeface, Typeface.BOLD) })
            etText = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                gravity = Gravity.TOP or Gravity.START
                minLines = 6
                setTextColor(CREME)
                setHintTextColor(0xFFCFC38E.toInt())
                hint = "Hier schreiben …"
            }
            box.addView(etText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rgPos = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
            val rbEnd = RadioButton(this).apply { id = View.generateViewId(); text = "Am Ende anhängen"; setTextColor(CREME); isChecked = true }
            val rbStart = RadioButton(this).apply { id = View.generateViewId(); text = "Am Anfang einfügen (Deckblatt)"; setTextColor(CREME) }
            rgPos.addView(rbEnd); rgPos.addView(rbStart)
            rgPos.tag = rbStart.id
            box.addView(rgPos)
            col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        val setBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile_gruen)
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        setBox.addView(TextView(this).apply { text = "Seitenlayout"; textSize = 13f; setTextColor(GOLD); setTypeface(typeface, Typeface.BOLD) })
        fun group(label: String, vararg names: String): RadioGroup {
            setBox.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(CREME); setPadding(0, dp(8), 0, 0) })
            val g = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
            names.forEachIndexed { i, n ->
                g.addView(RadioButton(this).apply { id = View.generateViewId(); text = n; setTextColor(CREME); isChecked = i == 1 })
            }
            setBox.addView(g)
            return g
        }
        rgSize = group("Schriftgröße", "Klein", "Normal", "Groß")
        rgMargin = group("Seitenrand", "Schmal", "Normal", "Breit")
        cbHeading = CheckBox(this).apply { text = "Jede Überschrift beginnt eine neue Seite"; setTextColor(CREME) }
        setBox.addView(cbHeading)
        setBox.addView(TextView(this).apply {
            text = "Eigener Seitenumbruch: eine Zeile mit ---Seite--- im Text. In Word-Dateien werden harte Seitenumbrüche übernommen."
            textSize = 12f; setTextColor(CREME); setPadding(0, dp(6), 0, 0)
        })
        col.addView(setBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        btnRun = TextView(this).apply {
            text = if (mode == CONVERT) "Als PDF speichern" else "Seite einfügen"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.auf_gold, null))
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_btn_gold)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            setOnClickListener { run() }
        }
        col.addView(btnRun, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        col.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })

        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val adBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ad)
            minimumHeight = dp(60)
        }
        root.addView(adBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }
        update()
    }

    private fun update() {
        tvFile.text = when {
            loading -> "Wird geöffnet …"
            mode == CONVERT && docText != null -> "$docName\n${docText!!.length} Zeichen Text"
            mode == APPEND && source != null -> "${source!!.name}\n${Docs.pagesText(source!!.pages)}"
            mode == CONVERT -> "Tippen, um eine Datei zu wählen"
            else -> "Tippen, um eine PDF zu wählen"
        }
        val ready = !busy && !loading && (if (mode == CONVERT) docText != null else source != null)
        btnRun.isEnabled = ready
        btnRun.alpha = if (ready) 1f else 0.5f
    }

    private fun queryName(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun openDoc(u: Uri) {
        loading = true
        docText = null
        update()
        val name = queryName(u) ?: "Dokument"
        worker.execute {
            val r = runCatching<CharSequence> {
                val bytes = contentResolver.openInputStream(u)?.use { DocConverter.readAll(it, DocConverter.MAX_FILE) }
                    ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
                val kind = DocConverter.kindOf(name, contentResolver.getType(u))
                if (kind == DocConverter.Kind.DOCX) buildSpannable(DocConverter.docxRich(bytes))
                else if (DocConverter.isSheet(kind)) throw IOException("Tabellen werden hier noch nicht unterstützt.")
                else SpannableString(DocConverter.convert(kind, bytes))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                r.onSuccess { docText = it; docName = name }.onFailure { Flow.toast(this, it.message ?: "Die Datei konnte nicht gelesen werden.") }
                update()
            }
        }
    }

    private fun options(): Opts {
        fun idx(g: RadioGroup): Int {
            for (i in 0 until g.childCount) if (g.getChildAt(i).id == g.checkedRadioButtonId) return i
            return 1
        }
        return Opts(
            listOf(10f, 12f, 14f)[idx(rgSize)],
            listOf(36, 56, 72)[idx(rgMargin)],
            cbHeading.isChecked
        )
    }

    private fun run() {
        if (busy || loading) return
        val o = options()
        if (mode == CONVERT) {
            val t = docText ?: return
            val base = Docs.sanitize(docName.substringBeforeLast('.', docName)).ifBlank { "Dokument" }
            work("PDF konnte nicht erstellt werden", {
                val bytes = makePdf(t, o)
                val name = "$base.pdf"
                val uri = Docs.save(this, name, "application/pdf") { it.write(bytes) }
                SaveResult(listOf(uri), listOf(name))
            }) { Flow.showDone(this, "PDF erstellt", it, "application/pdf", "Downloads/${Docs.FOLDER}") }
        } else {
            val src = source ?: return
            val text = etText.text.toString()
            if (text.isBlank()) return Flow.toast(this, "Bitte zuerst Text eingeben.")
            val atStart = rgPos.checkedRadioButtonId == (rgPos.tag as? Int)
            work("Die Seite konnte nicht eingefügt werden", {
                val textPdf = makePdf(SpannableString(text), o)
                val name = "${src.baseName}_mit_Text.pdf"
                val orig = PDDocument.load(src.file, Docs.memory())
                val extra = PDDocument.load(ByteArrayInputStream(textPdf), Docs.memory())
                try {
                    val m = PDFMergerUtility()
                    val dest = if (atStart) extra else orig
                    if (atStart) m.appendDocument(extra, orig) else m.appendDocument(orig, extra)
                    val uri = Docs.save(this, name, "application/pdf") { dest.save(it) }
                    SaveResult(listOf(uri), listOf(name))
                } finally {
                    try { orig.close() } catch (ignored: Exception) {}
                    try { extra.close() } catch (ignored: Exception) {}
                }
            }) { Flow.showDone(this, "Seite eingefügt", it, "application/pdf", "Downloads/${Docs.FOLDER}") }
        }
    }

    private fun <T> work(fail: String, task: () -> T, ok: (T) -> Unit) {
        busy = true
        progress.visibility = View.VISIBLE
        update()
        worker.execute {
            val r = runCatching { task() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                progress.visibility = View.GONE
                update()
                r.onSuccess(ok).onFailure { Flow.error(this, fail, it) }
            }
        }
    }

    private fun buildSpannable(paras: List<RichPara>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        paras.forEachIndexed { i, p ->
            if (i > 0) sb.append('\n')
            if (p.bullet) sb.append("• ")
            val contentStart = sb.length
            for (r in p.runs) {
                val s = sb.length
                sb.append(r.text)
                val e = sb.length
                if (e > s) {
                    if (r.bold && p.level == 0) sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val end = sb.length
            if (p.level > 0 && end > contentStart) {
                sb.setSpan(RelativeSizeSpan(if (p.level == 1) 1.6f else 1.3f), contentStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), contentStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun isBreakLine(l: String): Boolean {
        val t = l.trim()
        return t.equals("---Seite---", ignoreCase = true) || t.contains('\u000C')
    }

    /** Teilt den Text an eigenen Seitenumbrüchen und (optional) vor Überschriften in Abschnitte; jeder beginnt eine neue Seite. */
    private fun sections(text: CharSequence, o: Opts): List<CharSequence> {
        val str = text.toString()
        val sp = text as? Spanned
        val cuts = ArrayList<IntArray>()   // [von, bis) je Abschnitt
        var secStart = 0
        var pos = 0
        while (pos <= str.length) {
            val nl = str.indexOf('\n', pos)
            val e = if (nl < 0) str.length else nl
            val line = str.substring(pos, e)
            if (isBreakLine(line)) {
                cuts.add(intArrayOf(secStart, pos))
                secStart = minOf(e + 1, str.length)
            } else if (o.headingBreak && sp != null && e > pos && pos > secStart) {
                val h = sp.getSpans(pos, e, RelativeSizeSpan::class.java).any {
                    it.sizeChange >= 1.5f && sp.getSpanStart(it) <= pos + 2 && sp.getSpanEnd(it) >= e
                }
                if (h) { cuts.add(intArrayOf(secStart, pos)); secStart = pos }
            }
            if (nl < 0) break
            pos = nl + 1
        }
        cuts.add(intArrayOf(secStart, str.length))
        return cuts.filter { it[1] > it[0] }
            .map { text.subSequence(it[0], it[1]) }
            .filter { it.isNotBlank() }
    }

    /** Text (mit Fett/Kursiv/Überschriften) als A4-PDF mit Seitenumbruch. */
    private fun makePdf(text: CharSequence, o: Opts): ByteArray {
        if (text.isBlank()) throw IOException("In der Datei steht kein Text.")
        val pageW = 595
        val pageH = 842
        val margin = o.margin
        val width = pageW - 2 * margin
        val usable = pageH - 2 * margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = o.fontSize; color = 0xFF000000.toInt() }
        val doc = PdfDocument()
        try {
            var pageNo = 1
            for (sec in sections(text, o)) {
                val str = sec.toString()
                val lay = StaticLayout.Builder.obtain(sec, 0, sec.length, paint, width)
                    .setLineSpacing(0f, 1.15f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                val n = lay.lineCount
                fun paraStart(l: Int): Boolean = lay.getLineStart(l) == 0 || str[lay.getLineStart(l) - 1] == '\n'
                fun paraEnd(l: Int): Boolean = lay.getLineEnd(l) >= str.length || str[lay.getLineEnd(l)] == '\n' ||
                    (lay.getLineEnd(l) > 0 && str[lay.getLineEnd(l) - 1] == '\n')
                var line = 0
                while (line < n) {
                    val top = lay.getLineTop(line)
                    var end = line
                    while (end < n && lay.getLineBottom(end) - top <= usable) end++
                    if (end == line) end = line + 1
                    if (end < n) {
                        // keine einzelne Zeile eines Absatzes allein unten oder oben auf einer Seite
                        if (end - 1 > line && paraStart(end - 1) && !paraEnd(end - 1)) end--
                        else if (end - 1 > line && paraEnd(end) && !paraStart(end) && !paraStart(end - 1) && end - 2 > line) end--
                    }
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                    val c = page.canvas
                    c.save()
                    c.clipRect(margin.toFloat(), margin.toFloat(), (margin + width).toFloat(), (margin + (lay.getLineBottom(end - 1) - top)).toFloat())
                    c.translate(margin.toFloat(), (margin - top).toFloat())
                    lay.draw(c)
                    c.restore()
                    doc.finishPage(page)
                    line = end
                    pageNo++
                }
            }
            if (pageNo == 1) throw IOException("In der Datei steht kein Text.")
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    override fun onPause() { adManager.pause(); super.onPause() }
    override fun onResume() { super.onResume(); adManager.resume() }
    override fun onDestroy() { adManager.destroy(); worker.shutdown(); super.onDestroy() }
}
