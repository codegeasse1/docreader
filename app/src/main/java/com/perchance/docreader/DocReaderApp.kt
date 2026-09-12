package com.perchance.docreader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * PdfBox-Android needs its asset bundle (fonts, glyph lists, cmaps) unpacked before first use.
 */
class DocReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { PDFBoxResourceLoader.init(applicationContext) }
    }
}
