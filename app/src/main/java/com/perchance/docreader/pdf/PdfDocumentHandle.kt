package com.perchance.docreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper around Android's built-in PdfRenderer (API 21+), so page rendering needs no
 * third-party library at all. When [password] is given the document is first decrypted by
 * PdfBox into a private cache file, because PdfRenderer cannot open encrypted files.
 *
 * Keep one instance open per document and call [close] when leaving the reader.
 */
class PdfDocumentHandle(context: Context, uri: Uri, password: String? = null) : Closeable {

    private var tempFile: File? = null
    private val descriptor: ParcelFileDescriptor
    private val renderer: PdfRenderer

    init {
        var tmp: File? = null
        val desc: ParcelFileDescriptor
        if (password.isNullOrEmpty()) {
            desc = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Could not open the document")
        } else {
            val decrypted = File.createTempFile("docreader_dec_", ".pdf", context.cacheDir)
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not read the document")
            input.use { stream ->
                PDDocument.load(stream, password).use { doc -> doc.save(decrypted) }
            }
            tmp = decrypted
            desc = ParcelFileDescriptor.open(decrypted, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        tempFile = tmp
        descriptor = desc
        renderer = PdfRenderer(desc)
    }

    val pageCount: Int get() = renderer.pageCount

    /** Page size in points (72 dpi), used by the print adapter. */
    fun pageSize(index: Int): Pair<Int, Int>? = runCatching {
        renderer.openPage(index).use { it.width to it.height }
    }.getOrNull()

    /** Renders [index] scaled to [targetWidth] px wide. Returns null if rendering fails. */
    fun render(index: Int, targetWidth: Int): Bitmap? {
        if (index < 0 || index >= pageCount) return null
        return runCatching {
            renderer.openPage(index).use { page ->
                val width = targetWidth.coerceAtLeast(1)
                val height = (page.height * (width.toFloat() / page.width)).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        runCatching { tempFile?.delete() }
        tempFile = null
    }
}
