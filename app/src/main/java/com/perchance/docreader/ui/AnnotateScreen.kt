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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.data.AnnotationStore
import com.perchance.docreader.pdf.ANNOTATION_COLORS
import com.perchance.docreader.pdf.AnnKind
import com.perchance.docreader.pdf.Overlay
import com.perchance.docreader.pdf.PdfDocumentHandle
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class AnnTool { MOVE, TEXT, PEN, HIGHLIGHT, UNDERLINE, STRIKEOUT }

/**
 * Page-by-page annotation editor. Annotations are kept in AnnotationStore in display space and
 * exported into a real PDF copy via PdfOps.writeAnnotations.
 *
 * Text notes are rendered as live composables so they can be dragged (with any tool, or the
 * dedicated Move tool) and tapped to edit. The pen/highlight/underline/strike tools are drawn on
 * a canvas, and the Move tool can drag those shapes too.
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
        var pendingText by remember { mutableStateOf<Offset?>(null) }
        var editing by remember { mutableStateOf<Overlay?>(null) }
        var busy by remember { mutableStateOf<String?>(null) }

        val page = pagerState.currentPage

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
            reloadKey++
        }

        val export = rememberCreateDocument("application/pdf") { dest ->
            scope.launch {
                busy = "Writing annotations…"
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfOps.writeAnnotations(context, Uri.parse(uri), annStore.list(uri), password, dest)
                    }.getOrDefault(-1)
                }
                busy = null
                if (written >= 0) {
                    annStore.clear(uri)
                    reloadKey++
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
                        IconButton(onClick = { overlays.lastOrNull()?.let { delete(it.id) } }) {
                            Icon(Icons.Filled.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = {
                            export.launch(name.replace(".pdf", "", ignoreCase = true) + "_annotated.pdf")
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save as PDF")
                        }
                        IconButton(onClick = { annStore.clear(uri); reloadKey++ }) {
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
                        onCommit = { commit(it) },
                        onUpdate = { update(it) },
                        onDelete = { delete(it) },
                        onTapText = { pendingText = it },
                        onEditText = { editing = it },
                    )
                }
                BusyOverlay(busy)
            }
        }

        pendingText?.let { point ->
            NoteDialog(
                title = "Add note",
                initial = "",
                onSave = { text ->
                    val x = point.x.coerceIn(0f, 0.55f)
                    val y = point.y.coerceIn(0f, 0.9f)
                    commit(
                        Overlay(
                            id = annStore.newId(),
                            page = page,
                            kind = AnnKind.TEXT,
                            color = color,
                            width = width,
                            left = x,
                            top = y,
                            right = (x + 0.4f).coerceAtMost(1f),
                            bottom = (y + 0.08f).coerceAtMost(1f),
                            text = text,
                        )
                    )
                    pendingText = null
                },
                onDismiss = { pendingText = null },
            )
        }

        editing?.let { note ->
            NoteDialog(
                title = "Edit note",
                initial = note.text,
                onSave = { text ->
                    update(note.copy(text = text))
                    editing = null
                },
                onDelete = {
                    delete(note.id)
                    editing = null
                },
                onDismiss = { editing = null },
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
    onCommit: (Overlay) -> Unit,
    onUpdate: (Overlay) -> Unit,
    onDelete: (String) -> Unit,
    onTapText: (Offset) -> Unit,
    onEditText: (Overlay) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, pageIndex) {
        value = withContext(Dispatchers.IO) { handle.render(pageIndex, 1400) }
    }
    var draft by remember(pageIndex) { mutableStateOf<Overlay?>(null) }
    var moving by remember(pageIndex) { mutableStateOf<Overlay?>(null) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
            ) {
                val pageWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val pageHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )

                // Pen / highlighter / underline / strike overlays (plus the live draft / move preview).
                val shapes = overlays.filter { it.kind != AnnKind.TEXT } +
                    listOfNotNull(draft?.takeIf { it.kind != AnnKind.TEXT }) +
                    listOfNotNull(moving)
                AnnotationLayer(
                    overlays = shapes,
                    activeId = moving?.id ?: draft?.id,
                )

                // Drawing / moving surface, sits under the text notes so they can handle their own drags.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tool, color, width, overlays, pageWidthPx, pageHeightPx) {
                            val normX = { x: Float -> (x / size.width).coerceIn(0f, 1f) }
                            val normY = { y: Float -> (y / size.height).coerceIn(0f, 1f) }
                            when (tool) {
                                AnnTool.MOVE -> awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val nx = normX(down.position.x)
                                    val ny = normY(down.position.y)
                                    val hit = hitTest(overlays, nx, ny) ?: return@awaitEachGesture
                                    down.consume()
                                    var current = hit
                                    var last = down.position
                                    var changed = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        val dx = (change.position.x - last.x) / size.width
                                        val dy = (change.position.y - last.y) / size.height
                                        if (dx != 0f || dy != 0f) {
                                            if (abs(dx) + abs(dy) > 0.001f) changed = true
                                            current = translate(current, dx, dy)
                                            moving = current
                                            last = change.position
                                        }
                                        change.consume()
                                    }
                                    if (changed) onUpdate(current) else if (hit.kind == AnnKind.TEXT) onEditText(hit)
                                    moving = null
                                }

                                AnnTool.TEXT -> detectTapGestures { offset ->
                                    onTapText(Offset(normX(offset.x), normY(offset.y)))
                                }

                                else -> {
                                    var start = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            start = offset
                                            draft = newOverlay(
                                                kind = toolToKind(tool),
                                                color = color,
                                                width = width,
                                                page = pageIndex,
                                                left = normX(offset.x),
                                                top = normY(offset.y),
                                                right = normX(offset.x),
                                                bottom = normY(offset.y),
                                                points = mutableListOf(normX(offset.x), normY(offset.y)),
                                            )
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val cur = draft ?: return@detectDragGestures
                                            draft = if (cur.kind == AnnKind.PEN) {
                                                cur.copy(
                                                    points = cur.points +
                                                        normX(change.position.x) + normY(change.position.y)
                                                )
                                            } else {
                                                cur.copy(
                                                    left = normX(minOf(start.x, change.position.x)),
                                                    top = normY(minOf(start.y, change.position.y)),
                                                    right = normX(maxOf(start.x, change.position.x)),
                                                    bottom = normY(maxOf(start.y, change.position.y)),
                                                )
                                            }
                                        },
                                        onDragEnd = {
                                            val finished = draft
                                            if (finished != null && isValid(finished)) onCommit(finished)
                                            draft = null
                                        },
                                        onDragCancel = { draft = null },
                                    )
                                }
                            }
                        },
                )

                // Live, draggable text notes.
                overlays.filter { it.kind == AnnKind.TEXT }.forEach { note ->
                    DraggableTextNote(
                        note = note,
                        pageWidthPx = pageWidthPx,
                        pageHeightPx = pageHeightPx,
                        onUpdate = onUpdate,
                        onEdit = { onEditText(note) },
                        onDelete = { onDelete(note.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraggableTextNote(
    note: Overlay,
    pageWidthPx: Float,
    pageHeightPx: Float,
    onUpdate: (Overlay) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    var live by remember(note.id) { mutableStateOf<Offset?>(null) }
    val leftNorm = live?.x ?: note.left
    val topNorm = live?.y ?: note.top
    val boxWidth = ((note.right - note.left) * pageWidthPx).coerceAtLeast(48f)
    val boxHeight = ((note.bottom - note.top) * pageHeightPx).coerceAtLeast(32f)
    val fontSize = with(density) { (boxHeight * 0.55f).toSp().value }.coerceIn(9f, 26f).sp

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (leftNorm * pageWidthPx).toDp() },
                y = with(density) { (topNorm * pageHeightPx).toDp() },
            )
            .width(with(density) { boxWidth.toDp() })
            .height(with(density) { boxHeight.toDp() })
            .background(Color(note.color).copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .border(1.dp, Color(note.color), RoundedCornerShape(4.dp))
            .pointerInput(note.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var last = down.position
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val dx = change.position.x - last.x
                        val dy = change.position.y - last.y
                        if (abs(dx) + abs(dy) > 0f) {
                            if (abs(dx) + abs(dy) > 2f) moved = true
                            val cur = live ?: Offset(note.left, note.top)
                            val maxX = (1f - (note.right - note.left)).coerceAtLeast(0f)
                            val maxY = (1f - (note.bottom - note.top)).coerceAtLeast(0f)
                            live = Offset(
                                (cur.x + dx / pageWidthPx).coerceIn(0f, maxX),
                                (cur.y + dy / pageHeightPx).coerceIn(0f, maxY),
                            )
                            last = change.position
                        }
                        change.consume()
                    }
                    val settled = live
                    if (moved && settled != null) {
                        val w = note.right - note.left
                        val h = note.bottom - note.top
                        onUpdate(
                            note.copy(
                                left = settled.x,
                                top = settled.y,
                                right = settled.x + w,
                                bottom = settled.y + h,
                            )
                        )
                    } else if (!moved) {
                        onEdit()
                    }
                    live = null
                }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = note.text.ifBlank { "Note" },
            color = Color(note.color),
            fontSize = fontSize,
            maxLines = 3,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun toolToKind(tool: AnnTool): AnnKind = when (tool) {
    AnnTool.PEN -> AnnKind.PEN
    AnnTool.HIGHLIGHT -> AnnKind.HIGHLIGHT
    AnnTool.UNDERLINE -> AnnKind.UNDERLINE
    AnnTool.STRIKEOUT -> AnnKind.STRIKEOUT
    else -> AnnKind.TEXT
}

private fun hitTest(overlays: List<Overlay>, nx: Float, ny: Float): Overlay? {
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
