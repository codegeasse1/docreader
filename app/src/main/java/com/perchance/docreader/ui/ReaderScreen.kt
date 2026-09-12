package com.perchance.docreader.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.pdf.PdfDocumentHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ZOOM_LEVELS = listOf(1.0f, 1.5f, 2.0f, 3.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
    onOpenFileMenu: () -> Unit,
) {
    val context = LocalContext.current
    val handle = remember(uri) { PdfDocumentHandle(context, android.net.Uri.parse(uri)) }
    DisposableEffect(uri) { onDispose { handle.close() } }

    val listState = rememberLazyListState()
    var zoomIndex by remember { mutableStateOf(0) }
    var showTools by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val density = LocalDensity.current
    val screenWidthPx = with(density) { (context.resources.displayMetrics.widthPixels).toFloat() }.toInt()
    val renderWidthPx = (screenWidthPx * ZOOM_LEVELS[zoomIndex]).toInt()

    val currentPage = listState.firstVisibleItemIndex + 1

    Scaffold(
        containerColor = Color(0xFF3A3A3A),
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, fontSize = 15.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFileMenu) {
                        Icon(Icons.Filled.List, contentDescription = "File menu")
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = onOpenFileMenu) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                },
            )
        },
        floatingActionButton = { },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "$currentPage/${handle.pageCount}",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.size }) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom", tint = Color.White)
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFFC62828), CircleShape)
                        .clickable { showTools = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Tools", tint = Color.White)
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(List(handle.pageCount) { it }, key = { _, i -> i }) { _, index ->
                    PageView(handle = handle, index = index, widthPx = renderWidthPx)
                    Spacer(Modifier.height(8.dp))
                }
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
                ToolItem(Icons.Filled.Edit, "Annotate") { showTools = false }
                ToolItem(Icons.Filled.Description, "Edit") { showTools = false }
                ToolItem(Icons.Filled.SwapHoriz, "Convert") { showTools = false }
                ToolItem(Icons.Filled.CheckCircle, "Fill") { showTools = false }
                ToolItem(Icons.Filled.Description, "Sign") { showTools = false }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PageView(handle: PdfDocumentHandle, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, index, widthPx) {
        value = withContext(Dispatchers.IO) { handle.render(index, widthPx) }
    }

    val hScroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        val bmp = bitmap
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
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .width(with(LocalDensity.current) { widthPx.toDp() })
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
            )
        }
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

@Suppress("unused")
private fun shareIntent(uri: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
    }
