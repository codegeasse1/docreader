@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.perchance.docreader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.data.AnnotationStore
import com.perchance.docreader.pdf.ANNOTATION_COLORS
import com.perchance.docreader.pdf.AnnKind
import com.perchance.docreader.pdf.DEFAULT_TEXT_SIZE
import com.perchance.docreader.pdf.MAX_TEXT_SIZE
import com.perchance.docreader.pdf.MIN_TEXT_SIZE
import com.perchance.docreader.pdf.Overlay
import com.perchance.docreader.pdf.PdfDocumentHandle
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class AnnTool { MOVE, TEXT, PEN, HIGHLIGHT, UNDERLINE, STRIKEOUT }

/** A point in normalized page space where a new note was requested. */
private data class TextSpot(val x: Float, val y: Float)

/** Size of the box a brand-new note gets, as a fraction of the page width / height. */
private const val DEFAULT_NOTE_W = 0.45f
private const val DEFAULT_NOTE_H = 0.13f
private const val MIN_NOTE_W = 0.08f
private const val MIN_NOTE_H = 0.025f
private const val MAX_NOTE_ZOOM = 5f

/**
 * Page-by-page annotation editor. Annotations are kept in AnnotationStore in display space and
 * exported into a real PDF copy via PdfOps.writeAnnotations.
 *
 * Everything that touches the page happens in a single pointer handler on the page itself:
 * one finger draws / moves / taps according to the selected tool, two fingers pinch and pan the
 * whole page. Text notes are plain composables and never handle pointers themselves — they used to,
 * which meant measuring drags in a coordinate space that moved with the finger (that is what made
 * them jitter).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(
    uri: String,
    name: String,
    initialPage: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DocumentGate(uri = uri, onAbort = onBack) { handle, password ->
        val annStore = remember { AnnotationStore(context.applicationContext) }
        var reloadKey by remember { mutableStateOf(0) }
        val overlays = remember(uri, reloadKey) { annStore.list(uri) }

        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, (handle.pageCount - 1).coerceAtLeast(0)),
        ) { handle.pageCount }

        var tool by remember { mutableStateOf(AnnTool.PEN) }
        var color by remember { mutableStateOf(ANNOTATION_COLORS.first()) }
        var width by remember { mutableStateOf(0.02f) }
        var textSize by remember { mutableStateOf(DEFAULT_TEXT_SIZE) }
        var selectedId by remember { mutableStateOf<String?>(null) }
        var pageAspect by remember { mutableStateOf(0.72f) }
        var pendingText by remember { mutableStateOf<TextSpot?>(null) }
        var editing by remember { mutableStateOf<Overlay?>(null) }
        var busy by remember { mutableStateOf<String?>(null) }
        val pendingResult = remember { mutableStateOf<File?>(null) }

        val page = pagerState.currentPage
        val selected = overlays.firstOrNull { it.id == selectedId }
        val sizeLabel = "${(textSize / DEFAULT_TEXT_SIZE * 100f).roundToInt()}%"

        fun commit(overlay: Overlay) {
            annStore.add(uri, overlay)
            reloadKey++
        }

        fun update(overlay: Overlay) {
            annStore.replace(uri, overlay)
            reloadKey++
        }

        fun delete(id: String) {
            annStore.remove(uri, id)
            if (selectedId == id) selectedId = null
            reloadKey++
        }

        /**
         * Grows or shrinks the selected note (box *and* text together, so the text never spills out
         * of its box), or — when nothing is selected — just sets the size of the next note.
         */
        fun stepTextSize(factor: Float) {
            val target = selected
            if (target == null) {
                textSize = (textSize * factor).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
                return
            }
            val newFont = (target.fontSize * factor).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            val scale = newFont / target.fontSize.coerceAtLeast(0.0001f)
            val w = ((target.right - target.left) * scale).coerceIn(MIN_NOTE_W, 1f)
            val h = ((target.bottom - target.top) * scale).coerceIn(MIN_NOTE_H, 1f)
            val left = target.left.coerceIn(0f, (1f - w).coerceAtLeast(0f))
            val top = target.top.coerceIn(0f, (1f - h).coerceAtLeast(0f))
            textSize = newFont
            update(
                target.copy(
                    left = left,
                    top = top,
                    right = left + w,
                    bottom = top + h,
                    fontSize = newFont,
                )
            )
        }

        /** Puts the selected note back to the default note size (and text size). */
        fun resetTextSize() {
            textSize = DEFAULT_TEXT_SIZE
            val target = selected ?: return
            val w = DEFAULT_NOTE_W
            val h = noteHeightFor(target.text, w, DEFAULT_TEXT_SIZE, pageAspect)
                .coerceAtLeast(DEFAULT_NOTE_H)
            val left = target.left.coerceIn(0f, (1f - w).coerceAtLeast(0f))
            val top = target.top.coerceIn(0f, (1f - h).coerceAtLeast(0f))
            update(
                target.copy(
                    left = left,
                    top = top,
                    right = left + w,
                    bottom = top + h,
                    fontSize = DEFAULT_TEXT_SIZE,
                )
            )
        }

        fun exportPdf() {
            scope.launch {
                busy = "Writing annotations…"
                val out = File.createTempFile("docreader_annotated_", ".pdf", context.cacheDir)
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfOps.writeAnnotations(
                            context,
                            Uri.parse(uri),
                            annStore.list(uri),
                            password,
                            Uri.fromFile(out),
                        )
                    }.getOrDefault(-1)
                }
                busy = null
                if (written >= 0 && out.length() > 0L) {
                    pendingResult.value = out
                } else {
                    runCatching { out.delete() }
                }
            }
        }

        Scaffold(
            containerColor = Color(0xFF3A3A3A),
            topBar = {
                TopAppBar(
                    title = { Text("Page ${page + 1}/${handle.pageCount}", maxLines = 1, fontSize = 15.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) }
                            },
                        ) {
                            Icon(Icons.Filled.NavigateBefore, contentDescription = "Previous page")
                        }
                        IconButton(
                            onClick = {
                                if (page < handle.pageCount - 1) {
                                    scope.launch { pagerState.animateScrollToPage(page + 1) }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.NavigateNext, contentDescription = "Next page")
                        }
                        IconButton(onClick = { overlays.lastOrNull()?.let { delete(it.id) } }) {
                            Icon(Icons.Filled.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = { exportPdf() }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save as PDF")
                        }
                        IconButton(
                            onClick = {
                                annStore.clear(uri)
                                selectedId = null
                                reloadKey++
                            },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear all")
                        }
                    },
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF262626))
                        .padding(vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ANNOTATION_COLORS.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(Color(c), CircleShape)
                                    .border(
                                        width = if (c == color) 3.dp else 1.dp,
                                        color = if (c == color) Color.White else Color(0x55FFFFFF),
                                        shape = CircleShape,
                                    )
                                    .clickable { color = c },
                            )
                        }
                    }
                    if (tool == AnnTool.TEXT) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Text size", color = Color.White, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                            StepButton("A-") { stepTextSize(1f / 1.25f) }
                            Spacer(Modifier.width(6.dp))
                            StepButton("A+") { stepTextSize(1.25f) }
                            Spacer(Modifier.width(6.dp))
                            StepButton("Reset") { resetTextSize() }
                            Spacer(Modifier.width(10.dp))
                            Text(sizeLabel, color = Color(0xFF8AB4F8), fontSize = 12.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (selected != null) {
                                    "Adjusts the selected note"
                                } else {
                                    "Sets new notes — tap a note to select it"
                                },
                                color = Color(0x99FFFFFF),
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AnnTool.values().forEach { t ->
                            ToolButton(t, tool) { tool = it }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Drag a note to move it, drag its corner dot to resize, pinch with two fingers to zoom the page",
                        color = Color(0x88FFFFFF),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
                    AnnotatedPage(
                        handle = handle,
                        pageIndex = pageIndex,
                        overlays = overlays.filter { it.page == pageIndex },
                        tool = tool,
                        color = color,
                        width = width,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        onCommit = { commit(it) },
                        onUpdate = { update(it) },
                        onTapText = { pendingText = it },
                        onEditText = { editing = it },
                        onAspect = { if (abs(pageAspect - it) > 0.001f) pageAspect = it },
                    )
                }
                BusyOverlay(busy)
            }
        }

        pendingText?.let { spot ->
            NoteDialog(
                title = "Add note",
                initial = "",
                onSave = { text ->
                    if (text.isNotBlank()) {
                        val w = DEFAULT_NOTE_W
                        val h = noteHeightFor(text, w, textSize, pageAspect)
                            .coerceAtLeast(DEFAULT_NOTE_H)
                        val x = spot.x.coerceIn(0f, (1f - w).coerceAtLeast(0f))
                        val y = spot.y.coerceIn(0f, (1f - h).coerceAtLeast(0f))
                        val created = Overlay(
                            id = annStore.newId(),
                            page = page,
                            kind = AnnKind.TEXT,
                            color = color,
                            width = width,
                            left = x,
                            top = y,
                            right = x + w,
                            bottom = y + h,
                            text = text,
                            fontSize = textSize,
                        )
                        commit(created)
                        selectedId = created.id
                    }
                    pendingText = null
                },
                onDismiss = { pendingText = null },
            )
        }

        editing?.let { note ->
            val freshest = overlays.firstOrNull { it.id == note.id } ?: note
            NoteDialog(
                title = "Edit note",
                initial = freshest.text,
                onSave = { text ->
                    // Grow the box if the text got longer; never shrink it (that used to make the
                    // note jump to a different size as soon as it was edited).
                    val h = noteHeightFor(text, freshest.right - freshest.left, freshest.fontSize, pageAspect)
                        .coerceAtLeast(freshest.bottom - freshest.top)
                    update(freshest.copy(text = text, bottom = (freshest.top + h).coerceAtMost(1f)))
                    selectedId = freshest.id
                    editing = null
                },
                onDelete = {
                    delete(freshest.id)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }

        pendingResult.value?.let { result ->
            ResultPreview(
                file = result,
                title = "Annotated PDF",
                suggestedName = name.replace(".pdf", "", ignoreCase = true) + "_annotated.pdf",
                onSaved = {
                    annStore.clear(uri)
                    selectedId = null
                    reloadKey++
                },
                onDiscard = {
                    runCatching { result.delete() }
                    pendingResult.value = null
                },
            )
        }
    }
}

@Composable
private fun NoteDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun AnnotatedPage(
    handle: PdfDocumentHandle,
    pageIndex: Int,
    overlays: List<Overlay>,
    tool: AnnTool,
    color: Long,
    width: Float,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCommit: (Overlay) -> Unit,
    onUpdate: (Overlay) -> Unit,
    onTapText: (TextSpot) -> Unit,
    onEditText: (Overlay) -> Unit,
    onAspect: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, pageIndex) {
        value = withContext(Dispatchers.IO) { handle.render(pageIndex, 1600) }
    }
    var draft by remember(pageIndex) { mutableStateOf<Overlay?>(null) }
    var ghost by remember(pageIndex) { mutableStateOf<Overlay?>(null) }
    var zoom by remember(pageIndex) { mutableStateOf(1f) }
    var pan by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var viewport by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

    // The gesture handler is created once per page, so it must read these through state holders
    // rather than capturing the values it was composed with.
    val currentOverlays by rememberUpdatedState(overlays)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentTool by rememberUpdatedState(tool)
    val currentColor by rememberUpdatedState(color)
    val currentWidth by rememberUpdatedState(width)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 0.72f
        val vW = viewport.width.toFloat()
        val vH = viewport.height.toFloat()
        val baseW = if (vW > 1f && vH > 1f) minOf(vW, vH * aspect) else 1f
        val baseH = (baseW / aspect).coerceAtLeast(1f)
        val pageW = baseW * zoom
        val pageH = baseH * zoom

        LaunchedEffect(aspect) { onAspect(aspect) }

        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }
                    .requiredSize(
                        with(density) { pageW.toDp() },
                        with(density) { pageH.toDp() },
                    )
                    .background(Color.White)
                    .pointerInput(pageIndex, aspect, viewport) {
                        // Keyed on the layout, so the page size / pinch math below is re-captured
                        // whenever the viewport or the page aspect changes — but a running gesture
                        // is never interrupted (zoom/pan don't change any of these keys).
                        val pageWpx = size.width.toFloat().coerceAtLeast(1f)
                        val pageHpx = size.height.toFloat().coerceAtLeast(1f)
                        val normX = { x: Float -> (x / pageWpx).coerceIn(0f, 1f) }
                        val normY = { y: Float -> (y / pageHpx).coerceIn(0f, 1f) }
                        val handleTolX = 64f / pageWpx
                        val handleTolY = 64f / pageHpx

                        fun clampPan(candidate: Offset, z: Float): Offset {
                            val pw = baseW * z
                            val ph = baseH * z
                            val x = if (pw <= vW + 0.5f) 0f
                            else candidate.x.coerceIn((vW - pw) / 2f, (pw - vW) / 2f)
                            val y = if (ph <= vH + 0.5f) 0f
                            else candidate.y.coerceIn((vH - ph) / 2f, (ph - vH) / 2f)
                            return Offset(x, y)
                        }

                        /** Zooms to [target] keeping the page point under [focal] (page px) in place. */
                        fun zoomTo(target: Float, focal: Offset) {
                            val z0 = zoom
                            val z1 = target.coerceIn(1f, MAX_NOTE_ZOOM)
                            if (abs(z1 - z0) < 0.0001f) return
                            val p0 = pan
                            pan = clampPan(
                                Offset(
                                    p0.x + (z1 - z0) * (baseW / 2f - focal.x / z0),
                                    p0.y + (z1 - z0) * (baseH / 2f - focal.y / z0),
                                ),
                                z1,
                            )
                            zoom = z1
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val startPos = down.position
                            val downX = normX(startPos.x)
                            val downY = normY(startPos.y)
                            var last = startPos
                            var multi = false
                            var moved = false
                            var changed = false
                            var transformed = false

                            val handleNote = currentOverlays.firstOrNull {
                                it.id == currentSelectedId && it.kind == AnnKind.TEXT
                            }
                            val onHandle = handleNote != null &&
                                downX >= handleNote.right - handleTolX &&
                                downY >= handleNote.bottom - handleTolY
                            var resizing = onHandle
                            val downHit = hitNote(currentOverlays, downX, downY)
                            var moving: Overlay? = when {
                                onHandle -> handleNote
                                downHit == null -> null
                                // The note/move tools always grab a note; a drawing tool only grabs the
                                // selected one, so the pen still draws over (and around) other notes.
                                currentTool == AnnTool.MOVE ||
                                    currentTool == AnnTool.TEXT ||
                                    downHit.id == currentSelectedId -> downHit
                                else -> null
                            }
                            if (moving == null && !onHandle && currentTool == AnnTool.MOVE) {
                                moving = hitShape(currentOverlays, downX, downY)
                            }
                            moving?.let { if (it.kind == AnnKind.TEXT) onSelect(it.id) }
                            var sketch: Overlay? = null

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    if (!transformed) {
                                        // A second finger turns the gesture into a page transform;
                                        // whatever was being drawn or dragged is abandoned.
                                        transformed = true
                                        moving = null
                                        resizing = false
                                        sketch = null
                                        draft = null
                                        ghost = null
                                    }
                                    multi = true
                                    val zoomChange = event.calculateZoom()
                                    val panDelta = event.calculatePan()
                                    val focal = event.calculateCentroid(useCurrent = true)
                                    if (zoomChange != 1f) {
                                        zoomTo(zoom * zoomChange, focal)
                                    } else if (panDelta != Offset.Zero) {
                                        pan = clampPan(pan + panDelta, zoom)
                                    }
                                    last = pressed.first().position
                                    event.changes.forEach { it.consume() }
                                    continue
                                }

                                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                if (multi) {
                                    multi = false
                                    last = change.position
                                }
                                val dx = change.position.x - last.x
                                val dy = change.position.y - last.y
                                if (abs(dx) + abs(dy) > 2f) moved = true

                                val live = moving
                                if (live != null) {
                                    if (moved) {
                                        val next = if (resizing) {
                                            resizeNote(live, normX(change.position.x), normY(change.position.y))
                                        } else {
                                            translate(live, dx / pageWpx, dy / pageHpx)
                                        }
                                        moving = next
                                        ghost = next
                                        changed = true
                                    }
                                } else if (moved && currentTool != AnnTool.MOVE && currentTool != AnnTool.TEXT) {
                                    val fresh = sketch
                                    sketch = if (fresh == null) {
                                        val sx = normX(startPos.x)
                                        val sy = normY(startPos.y)
                                        newOverlay(
                                            kind = toolToKind(currentTool),
                                            color = currentColor,
                                            width = currentWidth,
                                            page = pageIndex,
                                            left = sx,
                                            top = sy,
                                            right = sx,
                                            bottom = sy,
                                            points = mutableListOf(sx, sy),
                                        )
                                    } else if (fresh.kind == AnnKind.PEN) {
                                        fresh.copy(
                                            points = fresh.points +
                                                normX(change.position.x) + normY(change.position.y)
                                        )
                                    } else {
                                        fresh.copy(
                                            left = minOf(normX(startPos.x), normX(change.position.x)),
                                            top = minOf(normY(startPos.y), normY(change.position.y)),
                                            right = maxOf(normX(startPos.x), normX(change.position.x)),
                                            bottom = maxOf(normY(startPos.y), normY(change.position.y)),
                                        )
                                    }
                                    draft = sketch
                                }

                                last = change.position
                                change.consume()
                            }

                            val finished = moving
                            if (!transformed) {
                                if (finished != null) {
                                    if (changed) {
                                        onUpdate(finished)
                                    } else if (finished.kind == AnnKind.TEXT) {
                                        onEditText(finished)
                                    }
                                } else if (sketch != null) {
                                    if (isValid(sketch!!)) onCommit(sketch!!)
                                } else if (!moved && currentTool == AnnTool.TEXT) {
                                    onTapText(TextSpot(downX, downY))
                                } else if (!moved && downHit != null && downHit.kind == AnnKind.TEXT) {
                                    // With a drawing tool down on a note: a tap selects it (so it can
                                    // then be dragged/resized with any tool), without drawing.
                                    onSelect(downHit.id)
                                }
                            }
                            draft = null
                            ghost = null
                        }
                    },
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )

                // Pen / highlighter / underline / strike overlays (plus the live draft / drag preview).
                val shapes = overlays.filter { it.kind != AnnKind.TEXT } +
                    listOfNotNull(draft?.takeIf { it.kind != AnnKind.TEXT }) +
                    listOfNotNull(ghost?.takeIf { it.kind != AnnKind.TEXT })
                AnnotationLayer(
                    overlays = shapes,
                    activeId = ghost?.id ?: draft?.id,
                )

                // Text notes: plain visuals, the page handler above moves them.
                overlays.filter { it.kind == AnnKind.TEXT }.forEach { note ->
                    val shown = if (ghost?.id == note.id) ghost!! else note
                    TextNote(
                        note = shown,
                        pageWidthPx = pageW,
                        pageHeightPx = pageH,
                        selected = shown.id == selectedId,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .clickable {
                        zoom = 1f
                        pan = Offset.Zero
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (zoom > 1.005f) "${(zoom * 100).roundToInt()}% · fit" else "100%",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun TextNote(
    note: Overlay,
    pageWidthPx: Float,
    pageHeightPx: Float,
    selected: Boolean,
) {
    val density = LocalDensity.current
    val widthNorm = (note.right - note.left).coerceAtLeast(MIN_NOTE_W)
    val heightNorm = (note.bottom - note.top).coerceAtLeast(MIN_NOTE_H)
    val boxWidth = (widthNorm * pageWidthPx).coerceAtLeast(28f)
    val boxHeight = (heightNorm * pageHeightPx).coerceAtLeast(22f)
    val fontSp = with(density) {
        (note.fontSize * pageHeightPx).coerceAtLeast(5f).toSp()
    }.value.coerceIn(6f, 160f).sp

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (note.left * pageWidthPx).toDp() },
                y = with(density) { (note.top * pageHeightPx).toDp() },
            )
            .size(
                width = with(density) { boxWidth.toDp() },
                height = with(density) { boxHeight.toDp() },
            )
            .background(Color(note.color).copy(alpha = 0.16f), RoundedCornerShape(3.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White else Color(note.color),
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text = note.text.ifBlank { "Note" },
            color = Color(note.color),
            fontSize = fontSp,
            fontWeight = FontWeight.Medium,
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .background(Color.White, CircleShape)
                    .border(2.dp, Color(note.color), CircleShape),
            )
        }
    }
}

/**
 * Estimates the height (fraction of the page) a note's box needs to hold [text] at [fontSize].
 * Only a rough model — its job is to stop a fresh note from clipping its own text.
 * [aspect] is the page's width/height ratio.
 */
private fun noteHeightFor(text: String, widthNorm: Float, fontSize: Float, aspect: Float): Float {
    if (text.isBlank()) return DEFAULT_NOTE_H
    val charsPerLine = (
        (widthNorm * aspect.coerceAtLeast(0.1f)) /
            (fontSize.coerceAtLeast(0.001f) * 0.55f)
        ).coerceAtLeast(4f)
    val lines = ceil(text.length / charsPerLine).coerceAtLeast(1f)
    return (lines * fontSize * 1.4f).coerceIn(MIN_NOTE_H, 0.9f)
}

/** The note whose box corner was grabbed: resizing scales the text with the box. */
private fun resizeNote(note: Overlay, nx: Float, ny: Float): Overlay {
    val left = note.left
    val top = note.top
    val oldH = (note.bottom - note.top).coerceAtLeast(MIN_NOTE_H)
    val w = (nx - left).coerceIn(MIN_NOTE_W, (1f - left).coerceAtLeast(MIN_NOTE_W))
    val h = (ny - top).coerceIn(MIN_NOTE_H, (1f - top).coerceAtLeast(MIN_NOTE_H))
    val font = (note.fontSize * (h / oldH)).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    return note.copy(right = left + w, bottom = top + h, fontSize = font)
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF3A3A3A), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

private fun toolToKind(tool: AnnTool): AnnKind = when (tool) {
    AnnTool.PEN -> AnnKind.PEN
    AnnTool.HIGHLIGHT -> AnnKind.HIGHLIGHT
    AnnTool.UNDERLINE -> AnnKind.UNDERLINE
    AnnTool.STRIKEOUT -> AnnKind.STRIKEOUT
    else -> AnnKind.TEXT
}

/** Topmost text note under the given normalized point. */
private fun hitNote(overlays: List<Overlay>, nx: Float, ny: Float): Overlay? {
    for (o in overlays.asReversed()) {
        if (o.kind != AnnKind.TEXT) continue
        if (withinBox(o, nx, ny, 0.005f)) return o
    }
    return null
}

private fun hitShape(overlays: List<Overlay>, nx: Float, ny: Float): Overlay? {
    for (o in overlays.asReversed()) {
        if (o.kind == AnnKind.TEXT) continue
        if (o.kind == AnnKind.PEN) {
            var i = 0
            val tol = 0.03f
            while (i + 1 < o.points.size) {
                if (abs(o.points[i] - nx) < tol && abs(o.points[i + 1] - ny) < tol) return o
                i += 2
            }
        }
        if (withinBox(o, nx, ny, 0.02f)) return o
    }
    return null
}

private fun withinBox(o: Overlay, nx: Float, ny: Float, pad: Float): Boolean =
    nx >= o.left - pad && nx <= o.right + pad && ny >= o.top - pad && ny <= o.bottom + pad

private fun translate(o: Overlay, dx: Float, dy: Float): Overlay {
    val ddx = dx.coerceIn(-o.left, 1f - o.right)
    val ddy = dy.coerceIn(-o.top, 1f - o.bottom)
    return if (o.kind == AnnKind.PEN) {
        o.copy(points = o.points.mapIndexed { index, value -> value + if (index % 2 == 0) ddx else ddy })
    } else {
        o.copy(left = o.left + ddx, right = o.right + ddx, top = o.top + ddy, bottom = o.bottom + ddy)
    }
}

private var overlaySeq = 0

private fun newOverlay(
    kind: AnnKind,
    color: Long,
    width: Float,
    page: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    points: List<Float>,
): Overlay = Overlay(
    id = "draft_${overlaySeq++}",
    page = page,
    kind = kind,
    color = color,
    width = width,
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    points = points,
)

private fun isValid(o: Overlay): Boolean = when (o.kind) {
    AnnKind.PEN -> o.points.size >= 4
    else -> (o.right - o.left) > 0.01f || (o.bottom - o.top) > 0.005f
}

@Composable
private fun ToolButton(tool: AnnTool, selected: AnnTool, onSelect: (AnnTool) -> Unit) {
    val icon: ImageVector = when (tool) {
        AnnTool.MOVE -> Icons.Filled.PanTool
        AnnTool.TEXT -> Icons.Filled.TextFields
        AnnTool.PEN -> Icons.Filled.Brush
        AnnTool.HIGHLIGHT -> Icons.Filled.Check
        AnnTool.UNDERLINE -> Icons.Filled.StrikethroughS
        AnnTool.STRIKEOUT -> Icons.Filled.StrikethroughS
    }
    val label = when (tool) {
        AnnTool.MOVE -> "Move"
        AnnTool.TEXT -> "Note"
        AnnTool.PEN -> "Pen"
        AnnTool.HIGHLIGHT -> "Highlight"
        AnnTool.UNDERLINE -> "Underline"
        AnnTool.STRIKEOUT -> "Strike"
    }
    Row(
        modifier = Modifier
            .background(
                if (tool == selected) Color(0xFFC62828) else Color(0xFF3A3A3A),
                RoundedCornerShape(10.dp),
            )
            .clickable { onSelect(tool) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
