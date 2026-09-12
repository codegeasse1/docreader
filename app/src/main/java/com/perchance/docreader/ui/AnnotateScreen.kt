@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.perchance.docreader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

private enum class AnnTool { MOVE, TEXT, PEN, HIGHLIGHT, UNDERLINE, STRIKEOUT }

/**
 * Page-by-page annotation editor. Annotations are kept in AnnotationStore in display space and
 * exported into a real PDF copy via PdfOps.writeAnnotations.
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
        var busy by remember { mutableStateOf<String?>(null) }

        val page = pagerState.currentPage

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
                        IconButton(onClick = {
                            overlays.lastOrNull()?.let { annStore.remove(uri, it.id); reloadKey++ }
                        }) {
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
                        onCommit = { o -> annStore.add(uri, o); reloadKey++ },
                        onTapText = { pendingText = it },
                    )
                }
                BusyOverlay(busy)
            }
        }

        pendingText?.let { point ->
            AddNoteDialog(
                onDismiss = { pendingText = null },
                onAdd = { text ->
                    val x = point.x.coerceIn(0f, 0.9f)
                    val y = point.y.coerceIn(0f, 0.9f)
                    annStore.add(
                        uri,
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
                        ),
                    )
                    pendingText = null
                    reloadKey++
                },
            )
        }
    }
}

@Composable
private fun AddNoteDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    onTapText: (Offset) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, pageIndex) {
        value = withContext(Dispatchers.IO) { handle.render(pageIndex, 1400) }
    }
    var localDraft by remember(pageIndex) { mutableStateOf<Overlay?>(null) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                AnnotationLayer(
                    overlays = overlays + listOfNotNull(localDraft),
                    activeId = localDraft?.id,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tool, color, width) {
                            if (tool == AnnTool.MOVE) return@pointerInput
                            if (tool == AnnTool.TEXT) {
                                detectTapGestures { offset ->
                                    onTapText(
                                        Offset(
                                            (offset.x / size.width).coerceIn(0f, 1f),
                                            (offset.y / size.height).coerceIn(0f, 1f),
                                        )
                                    )
                                }
                                return@pointerInput
                            }
                            val normX = { x: Float -> (x / size.width).coerceIn(0f, 1f) }
                            val normY = { y: Float -> (y / size.height).coerceIn(0f, 1f) }
                            var start = Offset.Zero
                            detectDragGestures(
                                onDragStart = { offset ->
                                    start = offset
                                    localDraft = newOverlay(
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
                                    val cur = localDraft ?: return@detectDragGestures
                                    localDraft = if (cur.kind == AnnKind.PEN) {
                                        cur.copy(points = cur.points + normX(change.position.x) + normY(change.position.y))
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
                                    val finished = localDraft
                                    if (finished != null && isValid(finished)) onCommit(finished)
                                    localDraft = null
                                },
                                onDragCancel = { localDraft = null },
                            )
                        },
                )
            }
        }
    }
}

private fun toolToKind(tool: AnnTool): AnnKind = when (tool) {
    AnnTool.PEN -> AnnKind.PEN
    AnnTool.HIGHLIGHT -> AnnKind.HIGHLIGHT
    AnnTool.UNDERLINE -> AnnKind.UNDERLINE
    AnnTool.STRIKEOUT -> AnnKind.STRIKEOUT
    else -> AnnKind.TEXT
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
