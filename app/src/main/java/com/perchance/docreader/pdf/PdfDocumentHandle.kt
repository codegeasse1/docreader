package com.perchance.docreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable

/**
 * Thin wrapper around Android's built-in PdfRenderer (API 21+).
 * No third-party PDF library is required, so the APK stays small.
 *
 * Keep one instance open per document and call [close] when leaving the reader.
 */
class PdfDocumentHandle(context: Context, uri: Uri) : Closeable {

    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open document: $uri")

    private val renderer = PdfRenderer(descriptor)

    val pageCount: Int get() = renderer.pageCount

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
    }
}
