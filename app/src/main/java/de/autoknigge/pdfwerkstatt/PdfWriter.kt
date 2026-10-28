package de.autoknigge.pdfwerkstatt

import java.io.OutputStream
import java.util.Locale

/**
 * Minimaler PDF-Schreiber: jede Seite enthält genau ein JPEG-Bild (DCTDecode).
 * Dadurch bleiben PDFs klein, und es werden bewusst keine Metadaten (Info-Dictionary) geschrieben.
 */
class PdfWriter(private val out: OutputStream) {

    private var pos = 0L
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3 // 1 = Catalog, 2 = Pages

    val pageCount: Int get() = pageIds.size

    init {
        write("%PDF-1.4\n")
        write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    private fun write(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun write(b: ByteArray) {
        out.write(b)
        pos += b.size
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.2f", v)

    /**
     * @param pageW,pageH Seitengröße in PDF-Punkten
     * @param x,y,w,h     Position/Größe des Bildes auf der Seite (Ursprung unten links)
     */
    fun addPage(
        jpeg: ByteArray, imgW: Int, imgH: Int,
        pageW: Float, pageH: Float,
        x: Float, y: Float, w: Float, h: Float
    ) {
        val imgId = nextId++
        val contId = nextId++
        val pageId = nextId++

        offsets[imgId] = pos
        write("$imgId 0 obj\n<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        write(jpeg)
        write("\nendstream\nendobj\n")

        val content = "q ${f(w)} 0 0 ${f(h)} ${f(x)} ${f(y)} cm /Im0 Do Q\n"
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        offsets[contId] = pos
        write("$contId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
        write(contentBytes)
        write("endstream\nendobj\n")

        offsets[pageId] = pos
        write("$pageId 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] " +
            "/Resources << /XObject << /Im0 $imgId 0 R >> >> /Contents $contId 0 R >>\nendobj\n")
        pageIds.add(pageId)
    }

    fun finish() {
        offsets[2] = pos
        val kids = pageIds.joinToString(" ") { "$it 0 R" }
        write("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pageIds.size} >>\nendobj\n")

        offsets[1] = pos
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val xrefPos = pos
        val sb = StringBuilder()
        sb.append("xref\n0 $nextId\n")
        sb.append("0000000000 65535 f \n")
        for (id in 1 until nextId) {
            sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[id] ?: 0L))
        }
        sb.append("trailer\n<< /Size $nextId /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        write(sb.toString())
        out.flush()
    }
}
