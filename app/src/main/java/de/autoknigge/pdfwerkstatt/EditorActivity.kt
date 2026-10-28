package de.autoknigge.pdfwerkstatt

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Zusammenfügen & bearbeiten: PDFs hinzufügen, Seiten sortieren, drehen, löschen und als neue PDF speichern. */
class EditorActivity : AppCompatActivity() {

    companion object {
        private const val MAX_PAGES = 1000
    }

    private val sources = HashMap<Long, PdfSource>()
    private val items = mutableListOf<PageItem>()
    private var nextId = 1L
    private var busy = false
    private var loading = 0

    private lateinit var adapter: PageAdapter
    private lateinit var adManager: AdManager
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAdd: View
    private lateinit var btnSave: View
    private lateinit var etName: EditText
    private lateinit var progress: ProgressBar

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_editor)
        Ui.insets(findViewById(R.id.root))

        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnAdd = findViewById(R.id.btnAdd)
        btnSave = findViewById(R.id.btnSave)
        etName = findViewById(R.id.etName)
        progress = findViewById(R.id.progress)

        etName.setText("PDF_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date()))

        adapter = PageAdapter(items, sources) { updateUi() }
        val rv = findViewById<RecyclerView>(R.id.rvPages)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.touchHelper.attachToRecyclerView(rv)

        btnAdd.setOnClickListener { if (!busy) picker.launch(arrayOf("application/pdf")) }
        btnSave.setOnClickListener { save() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        adManager = AdManager(this, findViewById<FrameLayout>(R.id.adContainer), null)
        Intro.run(this) { adManager.start() }

        updateUi()
        if (savedInstanceState == null) addUris(Flow.incomingUris(intent))
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
        adapter.shutdown()
        worker.shutdown()
        super.onDestroy()
    }

    private fun addUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        loading++
        updateUi()
        val first = sources.isEmpty()
        Flow.loadAll(this, worker, uris, { src -> addSource(src, first) }) {
            loading--
            updateUi()
        }
    }

    private fun addSource(src: PdfSource, first: Boolean) {
        val free = MAX_PAGES - items.size
        if (free <= 0) {
            Flow.toast(this, "Mehr als $MAX_PAGES Seiten sind nicht möglich.")
            return
        }
        // Erste Datei bestimmt den Vorschlag für den Dateinamen.
        if (first && sources.isEmpty()) etName.setText(src.baseName + "_bearbeitet")
        sources[src.id] = src
        val start = items.size
        val take = minOf(src.pages, free)
        for (i in 0 until take) items.add(PageItem(nextId++, src.id, i))
        adapter.notifyItemRangeInserted(start, take)
        if (take < src.pages) Flow.toast(this, "Es passen höchstens $MAX_PAGES Seiten in die Liste.")
        updateUi()
    }

    private fun updateUi() {
        val files = items.map { it.sourceId }.distinct().size
        tvCount.text = when {
            loading > 0 -> "Wird geöffnet …"
            items.isEmpty() -> "Eine oder mehrere PDFs"
            files == 1 -> "${Docs.pagesText(items.size)} – weitere PDF hinzufügen"
            else -> "${Docs.pagesText(items.size)} aus $files PDFs – weitere hinzufügen"
        }
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        btnSave.isEnabled = items.isNotEmpty() && !busy && loading == 0
        btnSave.alpha = if (btnSave.isEnabled) 1f else 0.5f
        btnAdd.isEnabled = !busy
    }

    private fun save() {
        if (busy || items.isEmpty() || loading > 0) return
        val base = Docs.sanitize(etName.text.toString()).ifBlank {
            "PDF_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date())
        }
        val fileName = "$base.pdf"
        val snapshot = items.map { it.copy() }
        val srcSnapshot = HashMap(sources)

        busy = true
        progress.max = snapshot.size
        progress.progress = 0
        progress.visibility = View.VISIBLE
        updateUi()

        worker.execute {
            val r = runCatching {
                PdfOps.buildEdited(this, srcSnapshot, snapshot, fileName) { done ->
                    runOnUiThread { progress.progress = done }
                }
            }
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                updateUi()
                r.onSuccess {
                    Flow.showDone(this, "PDF fertig (${Docs.pagesText(snapshot.size)})", it, "application/pdf", "Downloads/${Docs.FOLDER}")
                }.onFailure { Flow.error(this, "PDF konnte nicht gespeichert werden", it) }
            }
        }
    }
}
