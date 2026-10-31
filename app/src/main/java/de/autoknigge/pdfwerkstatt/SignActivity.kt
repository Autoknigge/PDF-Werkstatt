package de.autoknigge.pdfwerkstatt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/** Formulare unterschreiben: Unterschrift zeichnen oder fotografieren, auf die Seite schieben, als neue PDF speichern. */
class SignActivity : AppCompatActivity(), SignHost {

    private lateinit var ctl: SignController
    private lateinit var adManager: AdManager
    private var source: PdfSource? = null
    private var renderer: PageRenderer? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) open(uri) }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) loadPhoto(uri) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override val canPick = true
    override fun pickFile() = picker.launch(arrayOf("application/pdf"))
    override fun pickPhoto() = photoPicker.launch("image/*")

    override fun render(index: Int): Bitmap = renderer!!.render(index, 1800)

    override fun sign(placements: List<Placement>, progress: (Int) -> Unit): SignResult {
        val r = PdfOps.sign(this, source!!, placements, progress)
        return SignResult(r.uris[0], r.names[0])
    }

    override fun showDone(result: SignResult) {
        Flow.showDone(this, "PDF unterschrieben", SaveResult(listOf(result.uri), listOf(result.name)), "application/pdf", "Downloads/${Docs.FOLDER}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "‹"; textSize = 34f; setTextColor(0xFFD4AF37.toInt()); gravity = Gravity.CENTER
            contentDescription = "Zurück"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(TextView(this).apply {
            text = "PDF unterschreiben"; textSize = 22f; setTextColor(0xFFD4AF37.toInt()); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ctl = SignController(this, this)
        root.addView(ctl.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val adBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ad)
            minimumHeight = dp(60)
        }
        root.addView(adBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(root)
        adManager = AdManager(this, adBox, null)
        Intro.run(this) { adManager.start() }
        if (savedInstanceState == null) Flow.incomingUris(intent).firstOrNull()?.let { open(it) }
    }

    private fun open(uri: Uri) {
        ctl.setStatus("Wird geöffnet …")
        Flow.load(this, ctl.worker, uri) { src ->
            if (src == null) {
                ctl.setStatus("Tippen, um ein Formular (PDF) zu wählen")
                return@load
            }
            ctl.worker.execute {
                val old = renderer
                val r = runCatching { PageRenderer(src.file) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    r.onSuccess {
                        runCatching { old?.close() }
                        renderer = it
                        source = src
                        ctl.fileReady(src.name, it.pageCount)
                    }.onFailure {
                        ctl.setStatus("Tippen, um ein Formular (PDF) zu wählen")
                        Flow.toast(this, it.message ?: "Die PDF lässt sich nicht darstellen.")
                    }
                }
            }
        }
    }

    /** Foto im Hintergrund laden (verkleinert) und der Freistell-Vorschau übergeben. */
    private fun loadPhoto(uri: Uri) {
        ctl.worker.execute {
            val bmp = runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            }.getOrNull()
            runOnUiThread { if (!isFinishing && !isDestroyed) ctl.onPhoto(bmp) }
        }
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
        ctl.worker.execute { runCatching { r?.close() } }
        ctl.shutdown()
        super.onDestroy()
    }
}
