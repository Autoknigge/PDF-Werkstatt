package de.autoknigge.pdfwerkstatt

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.IntentCompat
import java.util.concurrent.ExecutorService

/** Gemeinsame Abläufe der Werkzeuge: PDF öffnen (mit Passwortabfrage), Ergebnis zeigen, teilen. */
object Flow {

    /** PDFs, die per „Teilen“ oder „Öffnen mit“ an die App übergeben wurden. */
    fun incomingUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> {
                val clip = intent.clipData ?: return emptyList()
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }
        }
    }

    /** Übergibt PDFs samt Leserecht an ein Werkzeug der App. */
    fun withUris(intent: Intent, uris: List<Uri>): Intent {
        if (uris.isEmpty()) return intent
        val clip = ClipData.newRawUri("pdf", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /**
     * Öffnet die PDF im Hintergrund. Bei Passwortschutz wird nachgefragt.
     * [onDone] bekommt null, wenn die Datei nicht geöffnet werden konnte oder abgebrochen wurde.
     */
    fun load(
        activity: Activity,
        worker: ExecutorService,
        uri: Uri,
        password: String? = null,
        onDone: (PdfSource?) -> Unit
    ) {
        worker.execute {
            val r = runCatching { Docs.prepare(activity, uri, password) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                r.onSuccess { onDone(it) }.onFailure { e ->
                    if (e is PasswordNeeded) {
                        askPassword(activity, e.name, e.wrong) { pw ->
                            if (pw == null) onDone(null) else load(activity, worker, uri, pw, onDone)
                        }
                    } else {
                        toast(activity, e.message ?: "Die Datei konnte nicht geöffnet werden.")
                        onDone(null)
                    }
                }
            }
        }
    }

    /** Öffnet mehrere PDFs nacheinander (damit Passwortabfragen nicht übereinanderliegen). */
    fun loadAll(
        activity: Activity,
        worker: ExecutorService,
        uris: List<Uri>,
        onEach: (PdfSource) -> Unit,
        onFinished: () -> Unit
    ) {
        if (uris.isEmpty()) {
            onFinished()
            return
        }
        load(activity, worker, uris.first()) { src ->
            if (src != null) onEach(src)
            loadAll(activity, worker, uris.drop(1), onEach, onFinished)
        }
    }

    private fun askPassword(activity: Activity, name: String, wrong: Boolean, onResult: (String?) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Passwort"
        }
        val box = FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val msg = if (wrong) "Das Passwort stimmt nicht. Bitte noch einmal versuchen." else "„$name“ ist mit einem Passwort geschützt."
        AlertDialog.Builder(activity)
            .setTitle("Passwort eingeben")
            .setMessage(msg)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Öffnen") { _, _ -> onResult(input.text.toString()) }
            .setNegativeButton("Abbrechen") { _, _ -> onResult(null) }
            .show()
    }

    /** Ergebnis-Dialog mit Teilen und – bei einer einzelnen Datei – Öffnen. */
    fun showDone(activity: Activity, title: String, result: SaveResult, mime: String, place: String) {
        val count = result.uris.size
        var msg = if (count == 1) {
            "Gespeichert in „$place“:\n${result.names[0]}"
        } else {
            "$count Dateien gespeichert in „$place“."
        }
        if (result.note.isNotEmpty()) msg += "\n\n" + result.note

        val b = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Teilen") { _, _ -> share(activity, result.uris, mime) }
            .setNegativeButton("Schließen", null)
        if (count == 1) b.setNeutralButton("Öffnen") { _, _ -> open(activity, result.uris[0], mime) }
        b.show()
    }

    fun share(activity: Activity, uris: List<Uri>, mime: String) {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        withUris(send, uris)
        activity.startActivity(Intent.createChooser(send, "Teilen"))
    }

    fun open(activity: Activity, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast(activity, "Keine App zum Öffnen gefunden.")
        }
    }

    fun error(activity: Activity, what: String, t: Throwable) {
        val reason = when (t) {
            is OutOfMemoryError -> "zu wenig Arbeitsspeicher"
            else -> t.message ?: "unbekannter Fehler"
        }
        toast(activity, "$what: $reason")
    }

    fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
