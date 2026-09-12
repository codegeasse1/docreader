@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.perchance.docreader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.data.AnnotationStore
import com.perchance.docreader.data.BookmarkStore
import com.perchance.docreader.pdf.AnnKind
import com.perchance.docreader.pdf.Overlay
import com.perchance.docreader.pdf.PdfDocumentHandle
import com.perchance.docreader.pdf.PdfOps
import com.perchance.docreader.pdf.SearchHit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PREVIEW_BG = Color(0xFF3A3A3A)

/**
 * The document viewer: continuous vertical pages, pinch/button zoom, page navigation, text search,
 * thumbnails, bookmarks and an annotation list. Editing lives in the sibling screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
    onOpenFileMenu: () -> Unit,
    onOpenAnnotate: (Int) -> Unit,
    onOpenOrganize: () -> Unit,
    onOpenFillForm: () -> Unit,
    onOpenSign: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documentUri = remember(uri) { Uri.parse(uri) }

    var password by remember(uri) { mutableStateOf<String?>(null) }
    var retry by remember(uri) { mutableStateOf(0) }
    val result = remember(uri, password, retry) {
        runCatching { PdfDocumentHandle(context, documentUri, password) }
    }
    DisposableEffect(result) {
        onDispose { result.getOrNull()?.close() }
    }

    val encrypted by produceState(initialValue = false, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfOps.isEncrypted(context, documentUri) }.getOrDefault(false)
        }
    }

    val handle = result.getOrNull()
    if (handle == null) {
        if (encrypted) {
            PasswordDialog(
                submit = { entered ->
                    password = entered
                    retry++
                },
                cancel = onBack,
            )
        } else {
            ErrorScreen(name, result.exceptionOrNull()?.message ?: "Could not open this document", onBack)
        }
        return
    }

    val annStore = remember { AnnotationStore(context.applicationContext) }
    val bookmarkStore = remember { BookmarkStore(context.applicationContext) }
    var reloadKey by remember { mutableStateOf(0) }
    val overlays = remember(uri, reloadKey) { annStore.list(uri) }
    val bookmarks = remember(uri, reloadKey) { bookmarkStore.list(uri) }

    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val zoomState = remember { mutableStateOf(1f) }
    val zoom = zoomState.value

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val baseWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val gapBasePx = with(density) { 8.dp.toPx() }
    val renderWidth = (baseWidthPx * zoom).toInt().coerceIn(240, 6000)
    // The bitmap is only re-rendered when the zoom crosses one of these buckets. Re-rendering every
    // page on every pinch step is what made zooming stutter: each new bitmap briefly replaced the
    // page with a placeholder, which re-measured the whole list under the user's fingers.
    val renderBucket = when {
        zoom <= 1.25f -> 1f
        zoom <= 2.2f -> 1.5f
        zoom <= 3.5f -> 2.1f
        else -> 2.8f
    }
    // Capped so a deep zoom can't allocate a 100 MB bitmap per page (ARGB_8888 is 4 bytes/px);
    // 2200 px over an A4 page is still ~3.4 px/pt, which stays readable at 6x.
    val bitmapWidth = (baseWidthPx * renderBucket).toInt().coerceIn(240, 2200)
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Base-size (zoom == 1) height of each page plus the gap below it. Everything scales linearly
    // with zoom, which lets us keep the pinch focal point fixed while the pages are re-rendered.
    val itemBaseHeights = remember(handle, baseWidthPx) {
        (0 until handle.pageCount).map { baseWidthPx.toFloat() * handle.pageAspect(it) + gapBasePx }
    }

    /** Zooms to [target], keeping the point under [focal] (in viewport px) anchored. */
    fun applyZoom(target: Float, focal: Offset) {
        val old = zoomState.value
        // Snap to 2.5% steps: a pinch fires far more events than the layout needs to follow, and
        // every change re-measures the pages and re-anchors the scroll offsets. Snapping means most
        // events are no-ops, which is what keeps the pinch smooth instead of shaky.
        val next = (target.coerceIn(1f, 6f) * 40f).roundToInt() / 40f
        if (abs(next - old) < 0.0001f) return
        val ratio = next / old
        val focalX = focal.x.coerceAtLeast(0f)
        val focalY = focal.y.coerceAtLeast(0f)

        val firstIndex = listState.firstVisibleItemIndex
            .coerceIn(0, (handle.pageCount - 1).coerceAtLeast(0))
        var contentBaseY = 0f
        for (i in 0 until firstIndex) contentBaseY += itemBaseHeights.getOrElse(i) { gapBasePx }
        contentBaseY += (listState.firstVisibleItemScrollOffset + focalY) / old

        val targetPx = contentBaseY * next - focalY
        var acc = 0f
        var idx = itemBaseHeights.lastIndex.coerceAtLeast(0)
        var offsetPx = 0f
        for (i in itemBaseHeights.indices) {
            val h = itemBaseHeights[i] * next
            if (acc + h > targetPx) {
                idx = i
                offsetPx = targetPx - acc
                break
            }
            acc += h
        }

        val newScrollX = (hScroll.value + focalX) * ratio - focalX
        zoomState.value = next
        scope.launch {
            // No frame delay: the coroutine runs at the start of the next frame, so the new zoom
            // and the corrected scroll offsets are adopted by the same measure pass.
            runCatching { hScroll.scrollTo(newScrollX.roundToInt().coerceAtLeast(0)) }
            runCatching { listState.scrollToItem(idx, offsetPx.roundToInt().coerceAtLeast(0)) }
        }
    }

    /** Two-finger pan while zoomed in (scrolls the document and the horizontal viewport). */
    fun applyPan(pan: Offset) {
        if (zoomState.value <= 1.001f) return
        // dispatchRawDelta applies immediately, with no coroutine queue to fall behind the fingers.
        runCatching { hScroll.dispatchRawDelta(-pan.x) }
        runCatching { listState.dispatchRawDelta(-pan.y) }
    }

    /** Middle of the visible page area, used as the focal point for the +/- zoom buttons. */
    fun viewportCenter() = Offset(viewportSize.width / 2f, viewportSize.height / 2f)

    val currentPage = listState.firstVisibleItemIndex.coerceIn(0, (handle.pageCount - 1).coerceAtLeast(0))

    var showTools by remember { mutableStateOf(false) }
    var showThumbnails by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
    var goToPage by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Reading chrome: the top and bottom bars slide away on their own so nothing interrupts the
    // page. A tap toggles them back; scrolling hides them (and never counts as a tap, so a drag
    // cannot bring them back).
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeEpoch by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf<ExportResult?>(null) }
    val anySheetOpen = showTools || showThumbnails || showSearch || showBookmarks ||
        showAnnotations || goToPage

    fun showChrome() {
        chromeVisible = true
        chromeEpoch++
    }

    fun toggleChrome() {
        if (chromeVisible) chromeVisible = false else showChrome()
    }

    LaunchedEffect(chromeVisible, chromeEpoch, anySheetOpen) {
        if (chromeVisible && !anySheetOpen) {
            delay(3000)
            chromeVisible = false
        }
    }

    LaunchedEffect(listState.isScrollInProgress, hScroll.isScrollInProgress) {
        if (listState.isScrollInProgress || hScroll.isScrollInProgress) chromeVisible = false
    }

    var hits by remember(uri) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var activeHits by remember(uri) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val hitOverlays = remember(activeHits) {
        activeHits.map { hit ->
            Overlay(
                id = "hit_${hit.page}_${hit.left}_${hit.top}",
                page = hit.page,
                kind = AnnKind.HIGHLIGHT,
                color = 0xFFFFC107,
                width = 0.02f,
                left = hit.left,
                top = hit.top,
                right = hit.right,
                bottom = hit.bottom,
            )
        }
    }

    fun jumpTo(page: Int) {
        scope.launch { listState.animateScrollToItem(page.coerceIn(0, (handle.pageCount - 1).coerceAtLeast(0))) }
    }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, documentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share document"))
    }

    val exportTxt = rememberCreateDocument("text/plain") { dest ->
        scope.launch {
            busy = "Exporting text…"
            withContext(Dispatchers.IO) {
                runCatching { PdfOps.exportText(context, documentUri, password, dest, false) }
            }
            busy = null
        }
    }
    val exportHtml = rememberCreateDocument("text/html") { dest ->
        scope.launch {
            busy = "Exporting HTML…"
            withContext(Dispatchers.IO) {
                runCatching { PdfOps.exportText(context, documentUri, password, dest, true) }
            }
            busy = null
        }
    }

    /**
     * Writes the document — with the annotations baked in — to a temporary file and shows it, so
     * the result is visible before the user is asked where to save it.
     */
    fun exportPdf() {
        scope.launch {
            busy = "Preparing the PDF…"
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File.createTempFile("docreader_pdf_", ".pdf", context.cacheDir)
                    if (overlays.isEmpty() && !encrypted) {
                        val input = context.contentResolver.openInputStream(documentUri)
                            ?: error("Could not read the document")
                        input.use { stream -> f.outputStream().use { stream.copyTo(it) } }
                    } else if (overlays.isEmpty()) {
                        // A protected document is decrypted on the way out, so the preview and any
                        // other app can open the exported copy.
                        PdfOps.removePassword(context, documentUri, password, Uri.fromFile(f))
                    } else {
                        PdfOps.writeAnnotations(
                            context,
                            documentUri,
                            overlays,
                            password,
                            Uri.fromFile(f),
                        )
                    }
                    f
                }.getOrNull()
            }
            busy = null
            if (out == null || out.length() == 0L) {
                out?.delete()
                notice = "Could not export this document as a PDF."
            } else {
                export = ExportResult(
                    file = out,
                    title = "PDF export",
                    name = if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf",
                    note = if (overlays.isEmpty()) {
                        "Original document"
                    } else {
                        "${overlays.size} annotation${if (overlays.size == 1) "" else "s"} included"
                    },
                )
            }
        }
    }

    /** Renders one page (annotations included) to a temporary PNG and previews it. */
    fun exportPageImage(page: Int) {
        scope.launch {
            busy = "Rendering page ${page + 1} as an image…"
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File.createTempFile("docreader_png_", ".png", context.cacheDir)
                    val written = PdfOps.exportImages(
                        ctx = context,
                        uri = documentUri,
                        overlays = overlays,
                        password = password,
                        pages = listOf(page),
                        targetWidth = 1600,
                        dest = Uri.fromFile(f),
                        folder = false,
                        baseName = name.substringBeforeLast('.'),
                    )
                    if (written <= 0) error("Could not render the page")
                    f
                }.getOrNull()
            }
            busy = null
            if (out == null || out.length() == 0L) {
                out?.delete()
                notice = "Could not export this page as an image."
            } else {
                export = ExportResult(
                    file = out,
                    title = "Page image",
                    name = "${name.substringBeforeLast('.')}_page${page + 1}.png",
                    mime = "image/png",
                    note = "Page ${page + 1} of ${handle.pageCount}",
                )
            }
        }
    }

    /** Picks a folder, then writes every page (annotations included) into it as a PNG. */
    val pickImageFolder = rememberPickFolder { tree ->
        scope.launch {
            busy = "Exporting images…"
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    PdfOps.exportImages(
                        ctx = context,
                        uri = documentUri,
                        overlays = overlays,
                        password = password,
                        pages = emptyList(),
                        targetWidth = 1600,
                        dest = tree,
                        folder = true,
                        baseName = name.substringBeforeLast('.'),
                        onProgress = { done, total ->
                            scope.launch { busy = "Exporting image $done/$total…" }
                        },
                    )
                }.getOrDefault(-1)
            }
            busy = null
            notice = if (written <= 0) {
                "Could not export the pages as images."
            } else {
                "Exported $written image${if (written == 1) "" else "s"} to the folder you chose."
            }
        }
    }

    Scaffold(containerColor = PREVIEW_BG) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Observe in the Initial pass so a two-finger pinch is claimed before the
                        // scrollable children can consume it as an ordinary scroll.
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2) {
                                val change = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (change != 1f) {
                                    applyZoom(zoomState.value * change, centroid)
                                } else {
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) applyPan(pan)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(with(density) { renderWidth.toDp() })
                        .fillMaxHeight(),
                ) {
                    items(handle.pageCount, key = { it }) { index ->
                        PageImage(
                            handle = handle,
                            index = index,
                            renderPx = bitmapWidth,
                            overlays = overlays.filter { it.page == index },
                            searchHits = hitOverlays.filter { it.page == index },
                            onClick = { toggleChrome() },
                        )
                        Spacer(Modifier.height(with(density) { (gapBasePx * zoom).toDp() }))
                    }
                }
            }
            BusyOverlay(busy)
            if (searching) BusyOverlay("Searching…")

            // The bars float over the page (no content re-flow, no jump when they slide away).
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .clipToBounds()
                    .pointerInput(Unit) { keepChromeAlive { chromeEpoch++ } },
            ) {
                ReaderTopBar(
                    title = name,
                    bookmarked = bookmarks.any { it.page == currentPage },
                    onBack = onBack,
                    onToggleBookmark = {
                        bookmarkStore.toggle(uri, currentPage, "Page ${currentPage + 1}")
                        reloadKey++
                    },
                    onSearch = { showSearch = true },
                    onShare = { share() },
                    onFileMenu = onOpenFileMenu,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .clipToBounds()
                    .pointerInput(Unit) { keepChromeAlive { chromeEpoch++ } },
            ) {
                ReaderBottomBar(
                    currentPage = currentPage,
                    pageCount = handle.pageCount,
                    zoom = zoom,
                    onGoToPage = { goToPage = true },
                    onThumbnails = { showThumbnails = true },
                    onZoomOut = { applyZoom(zoom / 1.25f, viewportCenter()) },
                    onZoomIn = { applyZoom(zoom * 1.25f, viewportCenter()) },
                    onTools = { showTools = true },
                )
            }
        }
    }

    if (showTools) {
        ModalBottomSheet(onDismissRequest = { showTools = false }, sheetState = sheetState) {
            Text(
                "Tools",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolItem(Icons.Filled.Draw, "Annotate") { showTools = false; onOpenAnnotate(currentPage) }
                ToolItem(Icons.Filled.CheckCircle, "Fill form") { showTools = false; onOpenFillForm() }
                ToolItem(Icons.Filled.Description, "Sign") { showTools = false; onOpenSign() }
                ToolItem(Icons.Filled.SwapHoriz, "Organize") { showTools = false; onOpenOrganize() }
                ToolItem(Icons.Filled.Comment, "Notes") { showTools = false; showAnnotations = true }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SheetRow(Icons.Filled.Bookmark, "Bookmarks") { showTools = false; showBookmarks = true }
            SheetRow(Icons.Filled.PictureAsPdf, "Export as PDF (annotations included)") {
                showTools = false
                exportPdf()
            }
            SheetRow(Icons.Filled.Photo, "Export this page as an image (PNG)") {
                showTools = false
                exportPageImage(currentPage)
            }
            SheetRow(Icons.Filled.GridView, "Export all pages as images (PNG)") {
                showTools = false
                pickImageFolder.launch(null)
            }
            SheetRow(Icons.Filled.List, "Export text (.txt)") { showTools = false; exportTxt.launch("${name.substringBeforeLast('.')}.txt") }
            SheetRow(Icons.Filled.Info, "Export HTML (.html)") { showTools = false; exportHtml.launch("${name.substringBeforeLast('.')}.html") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showThumbnails) {
        ModalBottomSheet(onDismissRequest = { showThumbnails = false }, sheetState = sheetState) {
            Text("Pages", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(440.dp).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(handle.pageCount) { index ->
                    ThumbnailTile(handle = handle, index = index, selected = index == currentPage) {
                        showThumbnails = false
                        jumpTo(index)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showSearch) {
        ModalBottomSheet(onDismissRequest = { showSearch = false }, sheetState = sheetState) {
            SearchSheet(
                initialQuery = "",
                hits = hits,
                searching = searching,
                onSearch = { query ->
                    scope.launch {
                        searching = true
                        hits = withContext(Dispatchers.IO) {
                            runCatching { PdfOps.search(context, documentUri, query, password) }.getOrDefault(emptyList())
                        }
                        searching = false
                    }
                },
                onPick = { hit ->
                    activeHits = hits
                    showSearch = false
                    jumpTo(hit.page)
                },
            )
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }, sheetState = sheetState) {
            Text("Bookmarks", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet", modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    items(bookmarks, key = { it.page }) { mark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showBookmarks = false; jumpTo(mark.page) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color(0xFFF9A825))
                            Spacer(Modifier.width(16.dp))
                            Text(mark.label.ifBlank { "Page ${mark.page + 1}" }, modifier = Modifier.weight(1f))
                            IconButton(onClick = { bookmarkStore.remove(uri, mark.page); reloadKey++ }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAnnotations) {
        ModalBottomSheet(onDismissRequest = { showAnnotations = false }, sheetState = sheetState) {
            Text("Annotations", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            if (overlays.isEmpty()) {
                Text("No annotations yet", modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    items(overlays, key = { it.id }) { o ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAnnotations = false; jumpTo(o.page) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color(o.color), CircleShape),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(o.kind.name.lowercase().replaceFirstChar { it.uppercase() })
                                Text(
                                    "Page ${o.page + 1}${if (o.text.isNotBlank()) " · ${o.text}" else ""}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { annStore.remove(uri, o.id); reloadKey++ }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (goToPage) {
        GoToPageDialog(
            maxPage = handle.pageCount,
            current = currentPage,
            onGo = { page ->
                goToPage = false
                jumpTo(page)
            },
            onDismiss = { goToPage = false },
        )
    }

    export?.let { result ->
        ResultPreview(
            file = result.file,
            title = result.title,
            suggestedName = result.name,
            mime = result.mime,
            note = result.note,
            onDiscard = {
                runCatching { result.file.delete() }
                export = null
            },
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("Export") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
        )
    }
}

/** A finished export waiting to be previewed and saved. */
private data class ExportResult(
    val file: File,
    val title: String,
    val name: String,
    val mime: String = "application/pdf",
    val note: String? = null,
)

/** A finger landing on a bar restarts its auto-hide timer, so the chrome stays while in use. */
private suspend fun PointerInputScope.keepChromeAlive(bump: () -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed && !it.previousPressed }) bump()
        }
    }
}

/** The reader's top bar. [windowInsets] is zeroed because the parent already applies them. */
@Composable
private fun ReaderTopBar(
    title: String,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
    onFileMenu: () -> Unit,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, fontSize = 15.sp) },
        windowInsets = WindowInsets(0, 0, 0, 0),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "Bookmark",
                    tint = if (bookmarked) Color(0xFFF9A825) else Color.Unspecified,
                )
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
            IconButton(onClick = onFileMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "File menu")
            }
        },
    )
}

/** The reader's bottom bar: page number, thumbnails, zoom and the tools sheet. */
@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    pageCount: Int,
    zoom: Float,
    onGoToPage: () -> Unit,
    onThumbnails: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onTools: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                .clickable { onGoToPage() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("${currentPage + 1}/$pageCount", color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onThumbnails) {
            Icon(Icons.Filled.GridView, contentDescription = "Thumbnails", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (zoom > 1.005f) "${(zoom * 100).roundToInt()}%" else "fit",
            color = Color(0xCCFFFFFF),
            fontSize = 11.sp,
        )
        IconButton(onClick = onZoomOut) {
            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out", tint = Color.White)
        }
        IconButton(onClick = onZoomIn) {
            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in", tint = Color.White)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFFC62828), CircleShape)
                .clickable { onTools() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Tools", tint = Color.White)
        }
    }
}

@Composable
private fun PageImage(
    handle: PdfDocumentHandle,
    index: Int,
    renderPx: Int,
    overlays: List<Overlay>,
    searchHits: List<Overlay>,
    onClick: () -> Unit,
) {
    // Keep the bitmap that is already on screen while a sharper one is being rendered, so zooming
    // never collapses the page into a placeholder.
    val bitmap = remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(handle, index, renderPx) {
        val fresh = withContext(Dispatchers.IO) { handle.render(index, renderPx) }
        if (fresh != null) bitmap.value = fresh
    }
    val bmp = bitmap.value
    if (bmp == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                .clickable { onClick() },
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            AnnotationLayer(overlays = overlays, searchHits = searchHits)
        }
    }
}

@Composable
private fun ThumbnailTile(handle: PdfDocumentHandle, index: Int, selected: Boolean, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, index) {
        value = withContext(Dispatchers.IO) { handle.render(index, 220) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(if (selected) Color(0xFFC62828) else Color(0xFFE0E0E0), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${index + 1}", fontSize = 11.sp)
    }
}

@Composable
private fun SearchSheet(
    initialQuery: String,
    hits: List<SearchHit>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onPick: (SearchHit) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { if (query.isNotBlank()) onSearch(query) }, enabled = !searching) {
            Text("Search")
        }
        if (hits.isNotEmpty()) {
            Text(
                "${hits.size} result${if (hits.size == 1) "" else "s"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(340.dp)) {
            items(hits.size) { i ->
                val hit = hits[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(hit) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(hit.snippet, maxLines = 2)
                    Text(
                        "Page ${hit.page + 1}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun GoToPageDialog(maxPage: Int, current: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf((current + 1).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() } },
                label = { Text("1 – $maxPage") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val page = text.toIntOrNull() ?: return@TextButton
                onGo((page - 1).coerceIn(0, (maxPage - 1).coerceAtLeast(0)))
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(submit: (String) -> Unit, cancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Password required") },
        text = {
            Column {
                Text("This document is protected. Enter its password to open it.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Password") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit(text) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorScreen(name: String, message: String, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        InfoMessage(message, modifier = Modifier.padding(padding))
    }
}

@Composable
private fun ToolItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(label, fontWeight = FontWeight.Medium)
    }
}
