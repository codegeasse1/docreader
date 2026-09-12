package com.perchance.docreader.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.perchance.docreader.pdf.PdfOps
import java.io.File
import java.io.FileOutputStream

/** Streams a plain (unencrypted) PDF straight into the Android print spooler. */
class PdfPrintAdapter(
    private val context: Context,
    private val uri: Uri,
    private val name: String,
    private val pageCount: Int,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?,
    ) {
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount.coerceAtLeast(1))
            .build()
        callback?.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
    ) {
        try {
            val out = destination ?: error("No print destination")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out.fileDescriptor).use { input.copyTo(it) }
            } ?: error("Could not read the document")
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (t: Throwable) {
            callback?.onWriteFailed(t.message ?: "Print failed")
        }
    }
}

/** Opens the Android print dialog for [uri]. Encrypted files are decrypted into a temp copy first. */
fun printPdf(context: Context, uri: Uri, name: String, password: String?) {
    val printable = if (PdfOps.isEncrypted(context, uri)) {
        val temp = File(context.cacheDir, "docreader_print.pdf")
        PdfOps.removePassword(context, uri, password, Uri.fromFile(temp))
        Uri.fromFile(temp)
    } else {
        uri
    }
    val pages = runCatching { PdfOps.info(context, printable)["Pages"]?.toIntOrNull() }.getOrNull() ?: 1
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    manager.print(name, PdfPrintAdapter(context, printable, name, pages), null)
}
