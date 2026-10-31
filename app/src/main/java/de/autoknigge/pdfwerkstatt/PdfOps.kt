package de.autoknigge.pdfwerkstatt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Eine Seite in der Werkstatt-Liste: Seite [index] (0-basiert) aus Quelle [sourceId], zusätzlich gedreht um [rotation]. */
data class PageItem(val id: Long, val sourceId: Long, val index: Int, var rotation: Int = 0)

class SaveResult(val uris: List<Uri>, val names: List<String>, val note: String = "")

/** Die eigentliche PDF-Arbeit. Alle Funktionen laufen im Hintergrund-Thread. */
object PdfOps {

    // ---------- Zusammenfügen & bearbeiten ----------

    fun buildEdited(
        ctx: Context,
        sources: Map<Long, PdfSource>,
        pages: List<PageItem>,
        fileName: String,
        onProgress: (Int) -> Unit
    ): SaveResult {
        val opened = HashMap<Long, PDDocument>()
        try {
            val uri = PDDocument(Docs.memory()).use { target ->
                pages.forEachIndexed { i, item ->
                    val src = opened.getOrPut(item.sourceId) {
                        val s = sources[item.sourceId] ?: throw IOException("Quelldatei fehlt.")
                        PDDocument.load(s.file, Docs.memory())
                    }
                    copyPage(target, src, item.index, item.rotation)
                    onProgress(i + 1)
                }
                Docs.save(ctx, fileName, "application/pdf") { target.save(it) }
            }
            return SaveResult(listOf(uri), listOf(fileName))
        } finally {
            opened.values.forEach { closeQuietly(it) }
        }
    }

    /** Übernimmt eine Seite samt geerbter Angaben (Größe, Drehung, Ressourcen). */
    private fun copyPage(target: PDDocument, src: PDDocument, index: Int, extraRotation: Int) {
        val original = src.getPage(index)
        val page = target.importPage(original)
        page.mediaBox = original.mediaBox
        page.cropBox = original.cropBox
        if (!page.cosObject.containsKey(COSName.RESOURCES) && original.resources != null) {
            page.resources = original.resources
        }
        page.rotation = (((original.rotation + extraRotation) % 360) + 360) % 360
    }

    // ---------- Aufteilen ----------

    fun split(ctx: Context, source: PdfSource, ranges: List<IntRange>, onProgress: (Int) -> Unit): SaveResult {
        val uris = ArrayList<Uri>()
        val names = ArrayList<String>()
        PDDocument.load(source.file, Docs.memory()).use { src ->
            ranges.forEachIndexed { i, range ->
                val label = if (range.first == range.last) "S${range.first}" else "S${range.first}-${range.last}"
                val name = "${source.baseName}_$label.pdf"
                PDDocument(Docs.memory()).use { part ->
                    for (p in range) copyPage(part, src, p - 1, 0)
                    uris.add(Docs.save(ctx, name, "application/pdf") { part.save(it) })
                }
                names.add(name)
                onProgress(i + 1)
            }
        }
        return SaveResult(uris, names)
    }

    // ---------- Verkleinern ----------

    enum class Level(val maxImageSide: Int, val imageQuality: Float, val dpi: Int, val rasterQuality: Int) {
        LIGHT(2200, 0.80f, 170, 82),
        MEDIUM(1600, 0.68f, 130, 72),
        STRONG(1100, 0.55f, 100, 60)
    }

    class CompressResult(val result: SaveResult?, val before: Long, val after: Long)

    /**
     * [raster] = false: Bilder in der PDF werden verkleinert und neu komprimiert, Text bleibt Text.
     * [raster] = true: jede Seite wird zu einem JPEG-Bild (kleinste Datei, Text nicht mehr markierbar).
     * Wird die Datei nicht kleiner, wird nichts gespeichert (result = null).
     */
    fun compress(
        ctx: Context,
        source: PdfSource,
        level: Level,
        raster: Boolean,
        onProgress: (Int) -> Unit
    ): CompressResult {
        val temp = Docs.tempFile(ctx, ".pdf")
        try {
            if (raster) rasterize(source, level, temp, onProgress) else shrinkImages(source, level, temp, onProgress)
            val after = temp.length()
            if (after <= 0 || after >= source.size * 0.97) return CompressResult(null, source.size, after)
            val name = "${source.baseName}_klein.pdf"
            val uri = Docs.saveFile(ctx, temp, name)
            return CompressResult(SaveResult(listOf(uri), listOf(name)), source.size, after)
        } finally {
            temp.delete()
        }
    }

    private fun rasterize(source: PdfSource, level: Level, out: File, onProgress: (Int) -> Unit) {
        PageRenderer(source.file).use { r ->
            out.outputStream().buffered(64 * 1024).use { stream ->
                val pdf = PdfWriter(stream)
                for (i in 0 until r.pageCount) {
                    val (pw, ph) = r.pageSize(i)
                    val bmp = r.render(i, r.longSideAt(i, level.dpi, 3000), forPrint = true)
                    try {
                        val baos = ByteArrayOutputStream(bmp.width * bmp.height / 6)
                        bmp.compress(Bitmap.CompressFormat.JPEG, level.rasterQuality, baos)
                        pdf.addPage(baos.toByteArray(), bmp.width, bmp.height, pw.toFloat(), ph.toFloat(), 0f, 0f, pw.toFloat(), ph.toFloat())
                    } finally {
                        bmp.recycle()
                    }
                    onProgress(i + 1)
                }
                pdf.finish()
            }
        }
    }

    private fun shrinkImages(source: PdfSource, level: Level, out: File, onProgress: (Int) -> Unit) {
        PDDocument.load(source.file, Docs.memory()).use { doc ->
            val done = IdentityHashMap<COSStream, PDImageXObject?>()
            doc.pages.forEachIndexed { i, page ->
                shrinkResources(doc, page.resources, level, done, 0)
                onProgress(i + 1)
            }
            doc.save(out)
        }
    }

    private fun shrinkResources(
        doc: PDDocument,
        res: PDResources?,
        level: Level,
        done: IdentityHashMap<COSStream, PDImageXObject?>,
        depth: Int
    ) {
        if (res == null || depth > 6) return
        for (name in res.xObjectNames.toList()) {
            val x = try {
                res.getXObject(name)
            } catch (e: Exception) {
                null
            }
            when (x) {
                is PDFormXObject -> shrinkResources(doc, x.resources, level, done, depth + 1)
                is PDImageXObject -> {
                    val key = x.cosObject
                    val replacement = if (done.containsKey(key)) done[key] else shrinkImage(doc, x, level).also { done[key] = it }
                    if (replacement != null) res.put(name, replacement)
                }
            }
        }
    }

    /** Gibt ein kleineres JPEG-Bild zurück oder null, wenn sich das Bild nicht lohnt oder nicht sicher ersetzbar ist. */
    private fun shrinkImage(doc: PDDocument, img: PDImageXObject, level: Level): PDImageXObject? {
        try {
            if (img.isStencil || img.bitsPerComponent == 1) return null
            if (img.cosObject.containsKey(COSName.SMASK) || img.cosObject.containsKey(COSName.MASK)) return null
            val w = img.width
            val h = img.height
            if (w <= 0 || h <= 0 || w.toLong() * h < 200_000) return null
            val oldLength = img.cosObject.length

            val sub = max(1, max(w, h) / (level.maxImageSide * 2))
            val decoded = img.getImage(null, sub) ?: return null
            val s = minOf(1f, level.maxImageSide.toFloat() / max(decoded.width, decoded.height))
            val tw = max(1, (decoded.width * s).roundToInt())
            val th = max(1, (decoded.height * s).roundToInt())

            val flat = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                val scaled = if (tw != decoded.width || th != decoded.height) Bitmap.createScaledBitmap(decoded, tw, th, true) else decoded
                drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                if (scaled !== decoded) scaled.recycle()
            }
            decoded.recycle()
            flat.setHasAlpha(false)

            val result = try {
                JPEGFactory.createFromImage(doc, flat, level.imageQuality)
            } finally {
                flat.recycle()
            }
            return if (result.cosObject.length < oldLength * 0.9) result else null
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    // ---------- PDF zu Bildern ----------

    fun toImages(
        ctx: Context,
        source: PdfSource,
        pages: List<Int>,
        dpi: Int,
        png: Boolean,
        onProgress: (Int) -> Unit
    ): SaveResult {
        val uris = ArrayList<Uri>()
        val names = ArrayList<String>()
        val digits = source.pages.toString().length
        PageRenderer(source.file).use { r ->
            pages.forEachIndexed { i, p ->
                val bmp = r.render(p - 1, r.longSideAt(p - 1, dpi, 5000), forPrint = true)
                try {
                    val name = "${source.baseName}_S${p.toString().padStart(digits, '0')}" + if (png) ".png" else ".jpg"
                    val uri = Docs.save(ctx, name, if (png) "image/png" else "image/jpeg") {
                        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
                    }
                    uris.add(uri)
                    names.add(name)
                } finally {
                    bmp.recycle()
                }
                onProgress(i + 1)
            }
        }
        return SaveResult(uris, names)
    }

    // ---------- Passwort & Metadaten ----------

    fun protect(ctx: Context, source: PdfSource, stripMeta: Boolean, password: String?): SaveResult {
        val suffix = when {
            password != null -> "geschuetzt"
            stripMeta -> "bereinigt"
            else -> "ohne_Passwort"
        }
        val name = "${source.baseName}_$suffix.pdf"
        return PDDocument.load(source.file, Docs.memory()).use { doc ->
            if (stripMeta) {
                doc.documentInformation = PDDocumentInformation()
                doc.documentCatalog.metadata = null
                doc.pages.forEach { it.cosObject.removeItem(COSName.METADATA) }
                doc.documentCatalog.cosObject.removeItem(COSName.PIECE_INFO)
            }
            if (password != null) {
                // Gleiches Passwort zum Öffnen und für alle Rechte: Wer es kennt, darf alles.
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 256
                doc.protect(policy)
            }
            val uri = Docs.save(ctx, name, "application/pdf") { doc.save(it) }
            SaveResult(listOf(uri), listOf(name))
        }
    }

    // ---------- Seitenzahlen & Wasserzeichen ----------

    enum class NumPos { BOTTOM_CENTER, BOTTOM_RIGHT, TOP_RIGHT }

    /** Nur Zeichen, die die Standardschrift darstellen kann (sonst „?“). */
    private fun printable(font: PDType1Font, text: String): String = buildString {
        for (c in text) {
            try {
                font.encode(c.toString())
                append(c)
            } catch (e: Exception) {
                append('?')
            }
        }
    }

    /**
     * Fügt Seitenzahlen ([numbers], Lage [pos], „Seite 3 von 20“ wenn [withTotal]) und/oder ein diagonales
     * Wasserzeichen ([watermark]) ein. Berücksichtigt gedrehte Seiten.
     */
    fun stamp(
        ctx: Context,
        source: PdfSource,
        numbers: Boolean,
        pos: NumPos,
        withTotal: Boolean,
        watermark: String,
        progress: (Int) -> Unit
    ): SaveResult {
        val name = "${source.baseName}_beschriftet.pdf"
        val wm = watermark.trim()
        return PDDocument.load(source.file, Docs.memory()).use { doc ->
            val font = PDType1Font.HELVETICA
            val bold = PDType1Font.HELVETICA_BOLD
            val total = doc.numberOfPages
            val wmText = if (wm.isNotEmpty()) printable(bold, wm) else ""
            for (i in 0 until total) {
                val page = doc.getPage(i)
                val box = page.cropBox
                val w = box.width
                val h = box.height
                val rot = ((page.rotation % 360) + 360) % 360
                val vw = if (rot == 90 || rot == 270) h else w
                val vh = if (rot == 90 || rot == 270) w else h
                val m = when (rot) {
                    90 -> Matrix(0f, 1f, -1f, 0f, w + box.lowerLeftX, box.lowerLeftY)
                    180 -> Matrix(-1f, 0f, 0f, -1f, w + box.lowerLeftX, h + box.lowerLeftY)
                    270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, h + box.lowerLeftY)
                    else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(m)
                    if (wmText.isNotEmpty()) {
                        val gs = PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = 0.13f
                        }
                        cs.saveGraphicsState()
                        cs.setGraphicsStateParameters(gs)
                        val diag = Math.sqrt((vw * vw + vh * vh).toDouble()).toFloat()
                        val unit = bold.getStringWidth(wmText) / 1000f
                        val size = minOf(120f, 0.72f * diag / unit)
                        val tw = unit * size
                        val ang = Math.atan2(vh.toDouble(), vw.toDouble()).toFloat()
                        val cx = vw / 2f
                        val cy = vh / 2f
                        val sx = cx - (tw / 2f) * Math.cos(ang.toDouble()).toFloat() + (size * 0.35f) * Math.sin(ang.toDouble()).toFloat()
                        val sy = cy - (tw / 2f) * Math.sin(ang.toDouble()).toFloat() - (size * 0.35f) * Math.cos(ang.toDouble()).toFloat()
                        cs.beginText()
                        cs.setNonStrokingColor(0.35f, 0.35f, 0.35f)
                        cs.setFont(bold, size)
                        cs.setTextMatrix(Matrix.getRotateInstance(ang.toDouble(), sx, sy))
                        cs.showText(wmText)
                        cs.endText()
                        cs.restoreGraphicsState()
                    }
                    if (numbers) {
                        val label = if (withTotal) "Seite ${i + 1} von $total" else "${i + 1}"
                        val size = 11f
                        val tw = font.getStringWidth(label) / 1000f * size
                        val margin = 26f
                        val x = when (pos) {
                            NumPos.BOTTOM_CENTER -> (vw - tw) / 2f
                            else -> vw - margin - tw
                        }
                        val y = if (pos == NumPos.TOP_RIGHT) vh - margin else margin - 4f
                        cs.beginText()
                        cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                        cs.setFont(font, size)
                        cs.newLineAtOffset(x, y)
                        cs.showText(label)
                        cs.endText()
                    }
                }
                progress(i + 1)
            }
            val uri = Docs.save(ctx, name, "application/pdf") { doc.save(it) }
            SaveResult(listOf(uri), listOf(name))
        }
    }

    /**
     * Setzt die Bilder in [placements] (Unterschriften, Datum und Ort; Mittelpunkt und Breite als Bruchteil
     * der sichtbaren Seite) in die PDF. Berücksichtigt gedrehte Seiten.
     */
    fun sign(
        ctx: Context,
        source: PdfSource,
        placements: List<Placement>,
        progress: (Int) -> Unit
    ): SaveResult {
        val name = "${source.baseName}_unterschrieben.pdf"
        val byPage = placements.groupBy { it.page }
        return PDDocument.load(source.file, Docs.memory()).use { doc ->
            val images = IdentityHashMap<Bitmap, PDImageXObject>()
            var done = 0
            for ((index, list) in byPage) {
                val page = doc.getPage(index)
                val box = page.cropBox
                val w = box.width
                val h = box.height
                val rot = ((page.rotation % 360) + 360) % 360
                val vw = if (rot == 90 || rot == 270) h else w
                val vh = if (rot == 90 || rot == 270) w else h
                val m = when (rot) {
                    90 -> Matrix(0f, 1f, -1f, 0f, w + box.lowerLeftX, box.lowerLeftY)
                    180 -> Matrix(-1f, 0f, 0f, -1f, w + box.lowerLeftX, h + box.lowerLeftY)
                    270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, h + box.lowerLeftY)
                    else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(m)
                    for (p in list) {
                        val img = images.getOrPut(p.bmp) { LosslessFactory.createFromImage(doc, p.bmp) }
                        val sw = p.w * vw
                        val sh = sw * p.bmp.height / p.bmp.width
                        cs.drawImage(img, p.cx * vw - sw / 2f, vh - p.cy * vh - sh / 2f, sw, sh)
                    }
                }
                done++
                progress(done)
            }
            val uri = Docs.save(ctx, name, "application/pdf") { doc.save(it) }
            SaveResult(listOf(uri), listOf(name))
        }
    }

    private fun closeQuietly(doc: PDDocument) {
        try {
            doc.close()
        } catch (ignored: Exception) {
        }
    }
}
