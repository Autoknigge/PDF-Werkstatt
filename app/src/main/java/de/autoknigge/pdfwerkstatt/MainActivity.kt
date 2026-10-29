package de.autoknigge.pdfwerkstatt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

/** Startmenü mit den Werkzeugen der App. */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val URL_HOME = "https://autoknigge.de"
        private const val URL_APPS = "https://autoknigge.de/apps/"
        private const val URL_IMPRINT = "https://autoknigge.de/impressum"
        private const val URL_PRIVACY = "https://autoknigge.de/pdfwerkstatt/datenschutz.html"
    }

    private lateinit var adManager: AdManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        Ui.insets(findViewById(R.id.root))

        findViewById<View>(R.id.tileEdit).setOnClickListener { startTool(null, emptyList()) }
        findViewById<View>(R.id.tileSplit).setOnClickListener { startTool(ToolActivity.Mode.SPLIT, emptyList()) }
        findViewById<View>(R.id.tileCompress).setOnClickListener { startTool(ToolActivity.Mode.COMPRESS, emptyList()) }
        findViewById<View>(R.id.tileImages).setOnClickListener { startTool(ToolActivity.Mode.IMAGES, emptyList()) }
        findViewById<View>(R.id.tileProtect).setOnClickListener { startTool(ToolActivity.Mode.PROTECT, emptyList()) }
        findViewById<View>(R.id.tileStamp).setOnClickListener { startTool(ToolActivity.Mode.STAMP, emptyList()) }

        mapOf(
            R.id.tvAbout to URL_HOME,
            R.id.tvMoreApps to URL_APPS,
            R.id.tvPrivacy to URL_PRIVACY,
            R.id.tvImprint to URL_IMPRINT
        ).forEach { (id, url) ->
            findViewById<TextView>(id).apply {
                Ui.underline(this)
                setOnClickListener { Ui.openUrl(this@MainActivity, url) }
            }
        }
        val adLink = findViewById<TextView>(R.id.tvAdSettings)
        Ui.underline(adLink)

        val parts = (packageManager.getPackageInfo(packageName, 0).versionName ?: "").split(".")
        findViewById<TextView>(R.id.tvVersion).text = parts.take(2).joinToString(".")

        if (savedInstanceState == null) {
            val ctx = applicationContext
            Executors.newSingleThreadExecutor().apply {
                execute { Docs.cleanup(ctx) }
                shutdown()
            }
        }

        adManager = AdManager(this, findViewById<FrameLayout>(R.id.adContainer), adLink, true)
        Intro.run(this) {
            adManager.start()
            if (savedInstanceState == null) handleIncoming(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** PDF per „Teilen“ oder „Öffnen mit“ erhalten: Werkzeug auswählen lassen. */
    private fun handleIncoming(intent: Intent?) {
        val uris = Flow.incomingUris(intent)
        if (uris.isEmpty()) return
        val single = uris.size == 1
        val labels = arrayOf(
            if (single) "Seiten bearbeiten" else "Zusammenfügen & bearbeiten",
            "Aufteilen",
            "Verkleinern",
            "Zu Bildern",
            "Passwort & Metadaten",
            "Seitenzahlen & Wasserzeichen"
        )
        val modes = arrayOf(null, ToolActivity.Mode.SPLIT, ToolActivity.Mode.COMPRESS, ToolActivity.Mode.IMAGES, ToolActivity.Mode.PROTECT, ToolActivity.Mode.STAMP)
        val title = if (single) "Was möchtest du mit der PDF tun?" else "Was möchtest du mit den ${uris.size} PDFs tun?"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val mode = modes[which]
                startTool(mode, if (mode == null) uris else uris.take(1))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startTool(mode: ToolActivity.Mode?, uris: List<Uri>) {
        val intent = if (mode == null) {
            Intent(this, EditorActivity::class.java)
        } else {
            Intent(this, ToolActivity::class.java).putExtra(ToolActivity.EXTRA_MODE, mode.name)
        }
        startActivity(Flow.withUris(intent, uris))
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
        super.onDestroy()
    }
}
