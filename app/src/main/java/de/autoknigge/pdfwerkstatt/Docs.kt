package de.autoknigge.pdfwerkstatt

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** Eine geöffnete Quell-PDF als unverschlüsselte Kopie im App-Speicher. */
class PdfSource(
    val id: Long,
    val file: File,
    val name: String,
    val pages: Int,
    val size: Long,
    val wasEncrypted: Boolean
) {
    /** Dateiname ohne „.pdf“, als Grundlage für die Namen der Ergebnisse. */
    val baseName: String get() = Docs.sanitize(name).ifBlank { "Dokument" }
}

/** Die PDF ist mit einem Passwort geschützt; [wrong] = das eingegebene Passwort stimmte nicht. */
class PasswordNeeded(val name: String, val wrong: Boolean) : Exception()

object Docs {

    const val FOLDER = "PDF-Werkstatt"
    private val counter = AtomicLong(System.currentTimeMillis())

    /** Große PDFs nicht komplett im Arbeitsspeicher halten. */
    fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(48L * 1024 * 1024)

    private fun sourceDir(ctx: Context) = File(ctx.cacheDir, "quellen").apply { mkdirs() }

    /** Löscht alte Zwischendateien (älter als einen Tag). */
    fun cleanup(ctx: Context) {
        val limit = System.currentTimeMillis() - 24 * 3600 * 1000L
        listOf(sourceDir(ctx), File(ctx.cacheDir, "arbeit")).forEach { dir ->
            dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }
    }

    fun tempFile(ctx: Context, suffix: String): File {
        val dir = File(ctx.cacheDir, "arbeit").apply { mkdirs() }
        return File(dir, "t${counter.incrementAndGet()}$suffix")
    }

    /**
     * Kopiert die PDF in den App-Speicher. Ist sie verschlüsselt, wird sie mit [password]
     * geöffnet und ohne Schutz gespeichert, damit alle Werkzeuge sie lesen können.
     */
    fun prepare(ctx: Context, uri: Uri, password: String?): PdfSource {
        val name = displayName(ctx, uri)
        val id = counter.incrementAndGet()
        val raw = File(sourceDir(ctx), "q$id.pdf")
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IOException("Die Datei konnte nicht gelesen werden.")
        input.use { inp -> raw.outputStream().use { inp.copyTo(it, 64 * 1024) } }
        val size = raw.length()

        val doc = try {
            PDDocument.load(raw, password ?: "", memory())
        } catch (e: InvalidPasswordException) {
            raw.delete()
            throw PasswordNeeded(name, password != null)
        } catch (e: IOException) {
            raw.delete()
            throw IOException("„$name“ ist keine gültige PDF-Datei oder beschädigt.")
        } catch (e: OutOfMemoryError) {
            raw.delete()
            throw IOException("„$name“ ist zu groß für dieses Gerät.")
        }

        return doc.use {
            val pages = it.numberOfPages
            if (pages == 0) {
                raw.delete()
                throw IOException("„$name“ enthält keine Seiten.")
            }
            if (!it.isEncrypted) {
                PdfSource(id, raw, name, pages, size, false)
            } else {
                it.isAllSecurityToBeRemoved = true
                val plain = File(sourceDir(ctx), "q${id}_offen.pdf")
                it.save(plain)
                raw.delete()
                PdfSource(id, plain, name, pages, size, true)
            }
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
            }
        } catch (e: Exception) {
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return name?.takeIf { it.isNotBlank() } ?: "Dokument.pdf"
    }

    fun sanitize(name: String): String =
        name.trim().replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).trim()

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
        else -> "$bytes Byte"
    }

    fun pagesText(n: Int) = if (n == 1) "1 Seite" else "$n Seiten"

    /**
     * Seitenangabe wie „1-3, 5, 8-“ (1-basiert). Jeder Teil wird ein Bereich.
     * Gibt null zurück, wenn die Angabe ungültig ist.
     */
    fun parseRanges(text: String, pageCount: Int): List<IntRange>? {
        val parts = text.split(',', ';').map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val result = ArrayList<IntRange>()
        for (p in parts) {
            val m = Regex("^(\\d*)[-–](\\d*)$").find(p)
            val range = if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: 1
                val b = m.groupValues[2].toIntOrNull() ?: pageCount
                a..b
            } else {
                val n = p.toIntOrNull() ?: return null
                n..n
            }
            if (range.first < 1 || range.last > pageCount || range.first > range.last) return null
            result.add(range)
        }
        return result
    }

    /**
     * Legt eine Datei in „Downloads/PDF-Werkstatt“ bzw. „Bilder/PDF-Werkstatt“ an und schreibt sie mit [block].
     * Schlägt das Schreiben fehl, wird die halbe Datei wieder entfernt.
     */
    fun save(ctx: Context, fileName: String, mime: String, block: (OutputStream) -> Unit): Uri {
        val image = mime.startsWith("image/")
        val resolver = ctx.contentResolver
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val dir = (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/" + FOLDER
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Die Datei konnte nicht angelegt werden.")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Die Datei konnte nicht geschrieben werden.")
            BufferedOutputStream(stream, 64 * 1024).use { block(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            throw t
        }
    }

    fun saveFile(ctx: Context, file: File, fileName: String): Uri =
        save(ctx, fileName, "application/pdf") { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
}

/** Zeichnet Seiten einer PDF als Bild (Android-eigener PDF-Renderer). Nicht threadsicher: nur von einem Thread nutzen. */
class PageRenderer(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw IOException("Die PDF lässt sich nicht darstellen.")
    }

    val pageCount: Int get() = renderer.pageCount

    /** Seitengröße in PDF-Punkten (1/72 Zoll). */
    fun pageSize(index: Int): Pair<Int, Int> = renderer.openPage(index).use { it.width to it.height }

    /** Zeichnet die Seite auf weißem Grund, die längere Seite hat höchstens [longSide] Pixel. */
    fun render(index: Int, longSide: Int, forPrint: Boolean = false): Bitmap {
        return renderer.openPage(index).use { page ->
            val s = longSide.toFloat() / max(page.width, page.height)
            val w = max(1, (page.width * s).roundToInt())
            val h = max(1, (page.height * s).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(
                bmp, null, null,
                if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            bmp
        }
    }

    /** Pixel der längeren Seite bei [dpi], begrenzt auf [cap]. */
    fun longSideAt(index: Int, dpi: Int, cap: Int): Int {
        val (w, h) = pageSize(index)
        return (max(w, h) * dpi / 72f).roundToInt().coerceIn(64, cap)
    }

    override fun close() {
        try {
            renderer.close()
        } finally {
            pfd.close()
        }
    }
}
