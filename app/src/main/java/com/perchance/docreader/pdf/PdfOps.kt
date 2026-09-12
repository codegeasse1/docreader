package com.perchance.docreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.perchance.docreader.data.formatSize
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDFieldTree
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A text-search hit, with a normalized (display space) rectangle to highlight. */
data class SearchHit(
    val page: Int,
    val snippet: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** One fillable AcroForm field. */
data class FormFieldInfo(
    val name: String,
    val type: String,
    val value: String,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
)

/** A page in the "organize pages" editor: [index] into the source document, [rotation] absolute. */
data class PageRef(val index: Int, val rotation: Int)

/**
 * Every PDF manipulation that needs a real PDF library, built on PdfBox-Android.
 * The reader itself only needs [PdfDocumentHandle] (Android's PdfRenderer) — this file is for
 * editing: annotations, page surgery, encryption, forms, compression, signing and printing.
 */
object PdfOps {

    // ---------------------------------------------------------------- helpers

    private fun cacheFile(ctx: Context, uri: Uri, prefix: String = "in"): File {
        val f = File.createTempFile("docreader_${prefix}_", ".pdf", ctx.cacheDir)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: error("Could not read the document")
        return f
    }

    private fun outStream(ctx: Context, dest: Uri): OutputStream =
        ctx.contentResolver.openOutputStream(dest) ?: error("Could not open the output file")

    private fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun rgb(color: Long): PDColor = PDColor(
        floatArrayOf(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
        ),
        PDDeviceRGB.INSTANCE,
    )

    private fun norm(deg: Int): Int = ((deg % 360) + 360) % 360

    /** Maps a normalized display-space point onto unrotated PDF user space. */
    private fun toPdf(nx: Float, ny: Float, box: PDRectangle, rotation: Int): FloatArray {
        var ux: Float
        var uy: Float
        when (norm(rotation)) {
            90 -> {
                ux = ny
                uy = 1f - nx
            }
            180 -> {
                ux = 1f - nx
                uy = 1f - ny
            }
            270 -> {
                ux = 1f - ny
                uy = nx
            }
            else -> {
                ux = nx
                uy = ny
            }
        }
        return floatArrayOf(box.lowerLeftX + ux * box.width, box.lowerLeftY + (1f - uy) * box.height)
    }

    // ---------------------------------------------------------------- creation

    /** Writes a brand-new single-page blank A4 PDF to [dest]. */
    fun createBlank(ctx: Context, dest: Uri) {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            outStream(ctx, dest).use { doc.save(it) }
        }
    }

    /**
     * Builds a new PDF where each entry of [images] (a photo, a scan or any image Uri) becomes
     * its own page. Pages keep the image aspect ratio, scaled to A4 width. Returns the page count.
     */
    fun createFromImages(ctx: Context, images: List<Uri>, dest: Uri, maxDimension: Int = 2400): Int {
        if (images.isEmpty()) error("Pick at least one image")
        return createFromItems(ctx, images, dest, maxDimension)
    }

    /**
     * Builds a new PDF from a mixed page list: a `null` entry becomes a blank A4 page, any other
     * entry is treated as an image and becomes its own page. This is what the "New PDF" builder
     * uses, so a document can mix photos, scans and blank pages. Returns the page count.
     */
    fun createFromItems(ctx: Context, items: List<Uri?>, dest: Uri, maxDimension: Int = 2400): Int {
        if (items.isEmpty()) error("Add at least one page")
        var pages = 0
        PDDocument().use { doc ->
            for ((i, u) in items.withIndex()) {
                if (u == null) {
                    doc.addPage(PDPage(PDRectangle.A4))
                    pages++
                    continue
                }
                val raw = decodeScaled(ctx, u, maxDimension) ?: continue
                val bmp = flattenOnWhite(raw)
                val img = PDImageXObject.createFromByteArray(doc, jpegBytes(bmp, 88), "img$i")
                val widthPt = PDRectangle.A4.width
                val heightPt = widthPt * (img.height.toFloat() / img.width.toFloat())
                val page = PDPage(PDRectangle(widthPt, heightPt))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, 0f, 0f, widthPt, heightPt)
                }
                bmp.recycle()
                if (bmp !== raw) raw.recycle()
                pages++
            }
            if (pages == 0) error("None of the chosen pages could be read")
            outStream(ctx, dest).use { doc.save(it) }
        }
        return pages
    }

    /**
     * Appends one page per image in [images] to an existing document, writing the combined PDF to
     * [dest]. Used by "Add image pages", so an existing (e.g. freshly created blank) PDF can be
     * filled with photos without starting over. Returns the number of pages that were added.
     */
    fun insertImages(
        ctx: Context,
        uri: Uri,
        password: String?,
        images: List<Uri>,
        dest: Uri,
        maxDimension: Int = 2400,
    ): Int {
        if (images.isEmpty()) error("Pick at least one image")
        val src = cacheFile(ctx, uri, "edit")
        var added = 0
        try {
            PDDocument.load(src, password).use { doc ->
                for ((i, u) in images.withIndex()) {
                    val raw = decodeScaled(ctx, u, maxDimension) ?: continue
                    val bmp = flattenOnWhite(raw)
                    val img = PDImageXObject.createFromByteArray(doc, jpegBytes(bmp, 88), "ins$i")
                    val widthPt = PDRectangle.A4.width
                    val heightPt = widthPt * (img.height.toFloat() / img.width.toFloat())
                    val page = PDPage(PDRectangle(widthPt, heightPt))
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, widthPt, heightPt) }
                    bmp.recycle()
                    if (bmp !== raw) raw.recycle()
                    added++
                }
                if (added == 0) error("None of the chosen images could be read")
                outStream(ctx, dest).use { doc.save(it) }
            }
        } finally {
            src.delete()
        }
        return added
    }

    private fun decodeScaled(ctx: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun flattenOnWhite(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        return out
    }

    // ---------------------------------------------------------------- info

    /** Title/author/page-count style rows for the file-info screen. */
    fun info(ctx: Context, uri: Uri, password: String? = null): Map<String, String> {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                val di = doc.documentInformation
                return linkedMapOf(
                    "Pages" to doc.numberOfPages.toString(),
                    "Title" to (di.title ?: "—"),
                    "Author" to (di.author ?: "—"),
                    "Subject" to (di.subject ?: "—"),
                    "Producer" to (di.producer ?: "—"),
                    "Creator" to (di.creator ?: "—"),
                    "PDF version" to String.format("%.1f", doc.version),
                    "Encrypted" to if (doc.isEncrypted) "Yes" else "No",
                    "File size" to formatSize(f.length()),
                )
            }
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- search

    private class PositionCapture : PDFTextStripper() {
        val lines = mutableListOf<Pair<String, List<TextPosition>>>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            lines.add(text to textPositions.toList())
            super.writeString(text, textPositions)
        }
    }

    fun search(
        ctx: Context,
        uri: Uri,
        query: String,
        password: String? = null,
        maxHits: Int = 400,
    ): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        if (query.isBlank()) return hits
        val needle = query.lowercase()
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                for (p in 0 until doc.numberOfPages) {
                    if (hits.size >= maxHits) break
                    val lines = readPageLines(doc, p)
                    if (lines.isEmpty()) continue
                    val box = doc.getPage(p).cropBox
                    val rot = norm(doc.getPage(p).rotation)
                    val displayW = if (rot == 90 || rot == 270) box.height else box.width
                    val displayH = if (rot == 90 || rot == 270) box.width else box.height
                    if (displayW <= 0f || displayH <= 0f) continue

                    for ((text, positions) in lines) {
                        val owners = ArrayList<TextPosition>(text.length)
                        for (tp in positions) {
                            val u = tp.unicode ?: continue
                            repeat(u.length) { owners.add(tp) }
                        }
                        val lower = text.lowercase()
                        var at = lower.indexOf(needle)
                        while (at >= 0 && hits.size < maxHits) {
                            val first = owners.getOrNull(at)
                            val last = owners.getOrNull(at + needle.length - 1) ?: first
                            if (first != null && last != null) {
                                val x1 = min(first.xDirAdj, last.xDirAdj)
                                val x2 = max(
                                    first.xDirAdj + first.widthDirAdj,
                                    last.xDirAdj + last.widthDirAdj,
                                )
                                val base = max(first.yDirAdj, last.yDirAdj)
                                val h = max(first.heightDir, last.heightDir).coerceAtLeast(6f)
                                hits.add(
                                    SearchHit(
                                        page = p,
                                        snippet = snippetOf(text, at, needle.length),
                                        left = (x1 / displayW).coerceIn(0f, 1f),
                                        top = ((base - h) / displayH).coerceIn(0f, 1f),
                                        right = (x2 / displayW).coerceIn(0f, 1f),
                                        bottom = (base / displayH).coerceIn(0f, 1f),
                                    )
                                )
                            }
                            at = lower.indexOf(needle, at + needle.length)
                        }
                    }
                }
            }
        } finally {
            f.delete()
        }
        return hits
    }

    private fun readPageLines(doc: PDDocument, page: Int): List<Pair<String, List<TextPosition>>> {
        val capture = PositionCapture()
        capture.startPage = page + 1
        capture.endPage = page + 1
        capture.sortByPosition = true
        return runCatching {
            capture.getText(doc)
            capture.lines
        }.getOrDefault(emptyList())
    }

    private fun snippetOf(line: String, at: Int, len: Int): String {
        val from = (at - 24).coerceAtLeast(0)
        val to = (at + len + 24).coerceAtMost(line.length)
        return (if (from > 0) "…" else "") +
            line.substring(from, to).trim() +
            (if (to < line.length) "…" else "")
    }

    /** Exports the extracted text layer as .txt (or a minimal .html). Returns the character count. */
    fun exportText(ctx: Context, uri: Uri, password: String?, dest: Uri, asHtml: Boolean): Int {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                val text = PDFTextStripper().getText(doc)
                val body = if (asHtml) {
                    val escaped = text
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    "<!doctype html><meta charset=\"utf-8\">" +
                        "<body style=\"font-family:sans-serif;white-space:pre-wrap\">$escaped</body>"
                } else {
                    text
                }
                outStream(ctx, dest).use { it.write(body.toByteArray(Charsets.UTF_8)) }
                return body.length
            }
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- merge / split

    /**
     * Concatenates [uris] (in order) into one new PDF written to [dest].
     *
     * Uses [PDFMergerUtility], which deep-clones every page (including its resources) into the new
     * document, and falls back to a manual page import if the merger trips over an odd document.
     * The manual path keeps every source open until the result has been written — importing a page
     * and then closing its source document leaves the destination holding references into a closed
     * document, which makes `save` fail.
     */
    fun merge(ctx: Context, uris: List<Uri>, password: String?, dest: Uri) {
        require(uris.isNotEmpty()) { "Pick at least two documents" }
        val srcs = ArrayList<File>()
        try {
            for ((i, u) in uris.withIndex()) {
                val f = runCatching { cacheFile(ctx, u, "merge${i}_") }
                    .getOrElse { throw IOException("Could not read document ${i + 1}: ${it.message ?: "unknown error"}") }
                srcs.add(f)
            }
            // The merger only flushes the stream, so closing it ourselves is what guarantees the
            // bytes actually land in the destination file.
            val merged = runCatching {
                outStream(ctx, dest).use { out ->
                    val merger = PDFMergerUtility()
                    for (f in srcs) merger.addSource(f)
                    merger.destinationStream = out
                    merger.mergeDocuments(null)
                }
            }
            merged.getOrElse { first -> importMerge(ctx, srcs, password, dest, first) }
        } finally {
            for (f in srcs) runCatching { f.delete() }
        }
    }

    /** Fallback merge that imports pages by hand, keeping every source open until after [dest] is written. */
    private fun importMerge(
        ctx: Context,
        srcs: List<File>,
        password: String?,
        dest: Uri,
        firstFailure: Throwable,
    ) {
        val open = ArrayList<PDDocument>()
        try {
            for (f in srcs) {
                open.add(
                    runCatching { PDDocument.load(f, password) }.getOrElse {
                        throw IOException("Could not read one of the documents: ${it.message ?: firstFailure.message ?: "unknown error"}")
                    },
                )
            }
            PDDocument().use { out ->
                for (doc in open) {
                    for (i in 0 until doc.numberOfPages) out.importPage(doc.getPage(i))
                }
                if (out.numberOfPages == 0) throw IOException("The documents had no pages to merge")
                outStream(ctx, dest).use { out.save(it) }
            }
        } finally {
            for (doc in open) runCatching { doc.close() }
        }
    }

    /** "Split" by keeping only pages [from]..[to] (0-based, inclusive) in a new PDF. */
    fun extractRange(ctx: Context, uri: Uri, from: Int, to: Int, password: String?, dest: Uri) {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { src ->
                val lo = from.coerceIn(0, src.numberOfPages - 1)
                val hi = to.coerceIn(lo, src.numberOfPages - 1)
                PDDocument().use { out ->
                    for (i in lo..hi) out.importPage(src.getPage(i))
                    outStream(ctx, dest).use { out.save(it) }
                }
            }
        } finally {
            f.delete()
        }
    }

    /** Splits every page into its own PDF inside the SAF folder [dir]. Returns the file names. */
    fun splitToPages(ctx: Context, uri: Uri, password: String?, dir: Uri, baseName: String): List<String> {
        val names = mutableListOf<String>()
        val f = cacheFile(ctx, uri)
        try {
            val tree = DocumentFile.fromTreeUri(ctx, dir) ?: error("Could not open the chosen folder")
            PDDocument.load(f, password).use { src ->
                val safeBase = baseName.removeSuffix(".pdf").ifBlank { "document" }
                for (i in 0 until src.numberOfPages) {
                    val name = "${safeBase}_page_${i + 1}.pdf"
                    tree.findFile(name)?.delete()
                    val df = tree.createFile("application/pdf", name) ?: continue
                    PDDocument().use { one ->
                        one.importPage(src.getPage(i))
                        ctx.contentResolver.openOutputStream(df.uri)?.use { one.save(it) }
                    }
                    names.add(name)
                }
            }
        } finally {
            f.delete()
        }
        return names
    }

    /** Rebuilds the document from [pages] (already reordered/rotated/filtered). */
    fun applyPageOps(ctx: Context, uri: Uri, pages: List<PageRef>, password: String?, dest: Uri) {
        if (pages.isEmpty()) error("The document would end up with no pages")
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { src ->
                PDDocument().use { out ->
                    for (p in pages) {
                        if (p.index < 0 || p.index >= src.numberOfPages) continue
                        val np = out.importPage(src.getPage(p.index))
                        np.rotation = norm(p.rotation)
                    }
                    outStream(ctx, dest).use { out.save(it) }
                }
            }
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- encryption

    fun setPassword(
        ctx: Context,
        uri: Uri,
        password: String?,
        userPassword: String,
        ownerPassword: String,
        dest: Uri,
    ) {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                val ap = AccessPermission()
                ap.setCanPrint(true)
                ap.setCanModify(true)
                ap.setCanExtractContent(true)
                ap.setCanModifyAnnotations(true)
                ap.setCanFillInForm(true)
                ap.setCanAssembleDocument(true)
                ap.setCanExtractForAccessibility(true)
                ap.setCanPrintDegraded(true)
                val policy = StandardProtectionPolicy(
                    ownerPassword.ifBlank { userPassword },
                    userPassword,
                    ap,
                )
                doc.protect(policy)
                outStream(ctx, dest).use { doc.save(it) }
            }
        } finally {
            f.delete()
        }
    }

    fun removePassword(ctx: Context, uri: Uri, password: String?, dest: Uri) {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                doc.setAllSecurityToBeRemoved(true)
                outStream(ctx, dest).use { doc.save(it) }
            }
        } finally {
            f.delete()
        }
    }

    /** True when the document cannot be read without a password. */
    fun isEncrypted(ctx: Context, uri: Uri): Boolean {
        val f = cacheFile(ctx, uri)
        return try {
            runCatching { PDDocument.load(f).use { it.isEncrypted } }.getOrDefault(true)
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- forms

    fun listFields(ctx: Context, uri: Uri, password: String? = null): List<FormFieldInfo> {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                val form = doc.documentCatalog.acroForm ?: return emptyList()
                val out = mutableListOf<FormFieldInfo>()
                for (field in PDFieldTree(form)) {
                    val options: List<String> = when (field) {
                        is PDChoice -> field.optionsDisplayValues ?: emptyList()
                        is PDRadioButton -> field.exportValues ?: emptyList()
                        else -> emptyList()
                    }
                    out.add(
                        FormFieldInfo(
                            name = field.fullyQualifiedName ?: field.partialName ?: "field",
                            type = field.fieldType ?: "",
                            value = runCatching { field.valueAsString }.getOrNull() ?: "",
                            options = options,
                            readOnly = field.isReadOnly,
                        )
                    )
                }
                return out
            }
        } finally {
            f.delete()
        }
    }

    /**
     * Writes [values] into the document's form fields. Keys are fully qualified field names;
     * checkboxes take "true"/"false", radio buttons take one of their export values.
     */
    fun fillForm(
        ctx: Context,
        uri: Uri,
        values: Map<String, String>,
        password: String?,
        dest: Uri,
        flatten: Boolean,
    ) {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                val form: PDAcroForm =
                    doc.documentCatalog.acroForm ?: error("This PDF has no form fields")
                for ((name, value) in values) {
                    val field: PDField = form.getField(name) ?: continue
                    when (field) {
                        is PDTextField -> field.setValue(value)
                        is PDCheckBox ->
                            if (value.equals("true", true) || value == "1") field.check() else field.unCheck()
                        is PDRadioButton -> field.setValue(value)
                        is PDChoice -> field.setValue(value)
                        else -> runCatching { field.setValue(value) }
                    }
                }
                runCatching { form.refreshAppearances() }
                if (flatten) runCatching { form.flatten() }
                outStream(ctx, dest).use { doc.save(it) }
            }
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- compress

    /**
     * Rasterizes every page to JPEG at [targetWidth] px and rebuilds the PDF from those images.
     * Excellent for scans and photo-heavy documents; text stops being selectable.
     * Returns the size before and after, in bytes.
     */
    fun compress(
        ctx: Context,
        uri: Uri,
        password: String?,
        targetWidth: Int,
        quality: Int,
        dest: Uri,
    ): Pair<Long, Long> {
        val source = cacheFile(ctx, uri, "cmp_src")
        var decrypted: File? = null
        val built = File.createTempFile("docreader_cmp_out_", ".pdf", ctx.cacheDir)
        try {
            val readable = if (password.isNullOrEmpty()) {
                source
            } else {
                val d = File.createTempFile("docreader_cmp_dec_", ".pdf", ctx.cacheDir)
                PDDocument.load(source, password).use { doc -> doc.save(d) }
                decrypted = d
                d
            }

            PdfDocumentHandle(ctx, Uri.fromFile(readable)).use { handle ->
                PDDocument().use { out ->
                    for (i in 0 until handle.pageCount) {
                        val bmp = handle.render(i, targetWidth) ?: continue
                        val img = PDImageXObject.createFromByteArray(out, jpegBytes(bmp, quality), "page$i")
                        val page = PDPage(PDRectangle(img.width.toFloat(), img.height.toFloat()))
                        out.addPage(page)
                        PDPageContentStream(out, page).use { cs ->
                            cs.drawImage(img, 0f, 0f, img.width.toFloat(), img.height.toFloat())
                        }
                        bmp.recycle()
                    }
                    built.outputStream().use { out.save(it) }
                }
            }

            val after = built.length()
            outStream(ctx, dest).use { stream -> built.inputStream().use { it.copyTo(stream) } }
            return source.length() to after
        } finally {
            source.delete()
            decrypted?.delete()
            built.delete()
        }
    }

    // ---------------------------------------------------------------- annotations

    /** Bakes [overlays] (display space) into a copy of the document as real PDF annotations. */
    fun writeAnnotations(
        ctx: Context,
        uri: Uri,
        overlays: List<Overlay>,
        password: String?,
        dest: Uri,
    ): Int {
        var written = 0
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                for (o in overlays) {
                    if (o.page < 0 || o.page >= doc.numberOfPages) continue
                    val page = doc.getPage(o.page)
                    val box = page.cropBox
                    val rot = norm(page.rotation)
                    val p1 = toPdf(o.left, o.top, box, rot)
                    val p2 = toPdf(o.right, o.bottom, box, rot)
                    val l = min(p1[0], p2[0])
                    val r = max(p1[0], p2[0])
                    val b = min(p1[1], p2[1])
                    val t = max(p1[1], p2[1])
                    val rect = PDRectangle(l, b, (r - l).coerceAtLeast(1f), (t - b).coerceAtLeast(1f))

                    val ann: PDAnnotationMarkup = when (o.kind) {
                        AnnKind.HIGHLIGHT ->
                            PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
                        AnnKind.UNDERLINE ->
                            PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE)
                        AnnKind.STRIKEOUT ->
                            PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT)
                        else -> PDAnnotationMarkup()
                    }

                    when (o.kind) {
                        AnnKind.TEXT -> {
                            ann.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_FREETEXT)
                            val textSize = (o.fontSize * box.height).coerceIn(4f, 96f)
                            ann.setDefaultAppearance("/Helv ${String.format(Locale.US, "%.1f", textSize)} Tf 0 g")
                            ann.setContents(o.text)
                        }

                        AnnKind.PEN -> {
                            ann.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
                            val ink = ArrayList<Float>()
                            var i = 0
                            while (i + 1 < o.points.size) {
                                val p = toPdf(o.points[i], o.points[i + 1], box, rot)
                                ink.add(p[0])
                                ink.add(p[1])
                                i += 2
                            }
                            if (ink.size >= 4) {
                                ann.setInkList(arrayOf(ink.toFloatArray()))
                                val bs = PDBorderStyleDictionary()
                                bs.setWidth((o.width * box.width).coerceIn(0.5f, 24f))
                                ann.setBorderStyle(bs)
                            }
                        }

                        else -> {
                            (ann as PDAnnotationTextMarkup)
                                .setQuadPoints(floatArrayOf(l, t, r, t, l, b, r, b))
                            if (o.text.isNotBlank()) ann.setContents(o.text)
                        }
                    }

                    ann.setRectangle(rect)
                    ann.setColor(rgb(o.color))
                    if (o.kind != AnnKind.TEXT) ann.setConstantOpacity(0.4f)
                    page.annotations.add(ann)
                    runCatching { ann.constructAppearances(doc) }
                    written++
                }
                outStream(ctx, dest).use { doc.save(it) }
            }
        } finally {
            f.delete()
        }
        return written
    }

    /** Draws [stamp] (usually a signature) onto a page over a display-space rectangle. */
    fun stampImage(
        ctx: Context,
        uri: Uri,
        pageIndex: Int,
        stamp: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        password: String?,
        dest: Uri,
    ) {
        val f = cacheFile(ctx, uri)
        try {
            PDDocument.load(f, password).use { doc ->
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) error("No such page")
                val page = doc.getPage(pageIndex)
                val box = page.cropBox
                val rot = norm(page.rotation)
                val p1 = toPdf(left, top, box, rot)
                val p2 = toPdf(right, bottom, box, rot)
                val img = PDImageXObject.createFromByteArray(doc, jpegBytes(stamp, 92), "signature")
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(
                        img,
                        min(p1[0], p2[0]),
                        min(p1[1], p2[1]),
                        abs(p2[0] - p1[0]).coerceAtLeast(1f),
                        abs(p2[1] - p1[1]).coerceAtLeast(1f),
                    )
                }
                outStream(ctx, dest).use { doc.save(it) }
            }
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- signing

    /**
     * Adds a real cryptographic (detached CMS/PKCS#7) signature, using a fresh self-signed
     * certificate for this signature. [stamp], when given, is drawn onto [stampPage] first.
     * Returns a short description of the certificate that was used.
     */
    fun signDocument(
        ctx: Context,
        uri: Uri,
        password: String?,
        dest: Uri,
        signerName: String,
        reason: String,
        location: String,
        stampPage: Int = -1,
        stamp: Bitmap? = null,
        stampRect: FloatArray? = null,
    ): String {
        val f = cacheFile(ctx, uri)
        try {
            val keyGen = java.security.KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()

            val now = System.currentTimeMillis()
            val subject = org.bouncycastle.asn1.x500.X500Name("CN=$signerName, O=DocReader, C=US")
            val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                subject,
                java.math.BigInteger.valueOf(now),
                java.util.Date(now - 86_400_000L),
                java.util.Date(now + 3650L * 86_400_000L),
                subject,
                keyPair.public,
            )
            val contentSigner = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.private)
            val holder = certBuilder.build(contentSigner)

            PDDocument.load(f, password).use { doc ->
                if (stamp != null && stampRect != null && stampPage in 0 until doc.numberOfPages) {
                    val page = doc.getPage(stampPage)
                    val box = page.cropBox
                    val rot = norm(page.rotation)
                    val p1 = toPdf(stampRect[0], stampRect[1], box, rot)
                    val p2 = toPdf(stampRect[2], stampRect[3], box, rot)
                    val img = PDImageXObject.createFromByteArray(doc, jpegBytes(stamp, 92), "signature")
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.drawImage(
                            img,
                            min(p1[0], p2[0]),
                            min(p1[1], p2[1]),
                            abs(p2[0] - p1[0]).coerceAtLeast(1f),
                            abs(p2[1] - p1[1]).coerceAtLeast(1f),
                        )
                    }
                }

                val signature = PDSignature()
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                signature.setName(signerName)
                signature.setLocation(location)
                signature.setReason(reason)
                signature.setSignDate(Calendar.getInstance())

                val iface = SignatureInterface { content ->
                    val generator = org.bouncycastle.cms.CMSSignedDataGenerator()
                    val digestProvider =
                        org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build()
                    val signerInfo =
                        org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(digestProvider)
                            .build(contentSigner, holder)
                    generator.addSignerInfoGenerator(signerInfo)
                    generator.addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(holder)))
                    val signed = generator.generate(
                        org.bouncycastle.cms.CMSProcessableByteArray(content.readBytes()),
                        false,
                    )
                    signed.encoded
                }

                val options = SignatureOptions()
                options.setPreferredSignatureSize(SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2)
                doc.addSignature(signature, iface, options)
                outStream(ctx, dest).use { doc.saveIncremental(it) }
            }
            return "SHA-256 / RSA-2048 self-signed certificate for \"$signerName\""
        } finally {
            f.delete()
        }
    }
}
