package com.perchance.docreader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.pdf.PageRef
import com.perchance.docreader.pdf.PdfDocumentHandle
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rotate / reorder / delete pages, or carve the document into smaller files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    DocumentGate(uri = uri, onAbort = onBack) { handle, password ->
        var pages by remember(handle) {
            mutableStateOf(List(handle.pageCount) { PageRef(it, 0) })
        }
        var busy by remember { mutableStateOf<String?>(null) }
        var extractOpen by remember { mutableStateOf(false) }
        var pendingExtract by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        val save = rememberCreateDocument("application/pdf") { dest ->
            scope.launch {
                busy = "Saving…"
                val ok = withContext(Dispatchers.IO) {
                    runCatching { PdfOps.applyPageOps(context, Uri.parse(uri), pages, password, dest) }.isSuccess
                }
                busy = null
                snackbar.showSnackbar(if (ok) "Saved" else "Could not save")
            }
        }
        val extractSave = rememberCreateDocument("application/pdf") { dest ->
            val range = pendingExtract
            if (range != null) {
                scope.launch {
                    busy = "Extracting…"
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            PdfOps.extractRange(context, Uri.parse(uri), range.first, range.second, password, dest)
                        }.isSuccess
                    }
                    busy = null
                    pendingExtract = null
                    snackbar.showSnackbar(if (ok) "Extracted page range" else "Could not extract")
                }
            }
        }
        val splitFolder = rememberPickFolder { dir ->
            scope.launch {
                busy = "Splitting…"
                val names = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfOps.splitToPages(context, Uri.parse(uri), password, dir, name)
                    }.getOrDefault(emptyList())
                }
                busy = null
                snackbar.showSnackbar("Wrote ${names.size} file(s)")
            }
        }

        fun move(from: Int, to: Int) {
            if (to < 0 || to >= pages.size) return
            val list = pages.toMutableList()
            val item = list.removeAt(from)
            list.add(to, item)
            pages = list
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("Organize pages", fontSize = 16.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { extractOpen = true }) {
                            Icon(Icons.Filled.ContentCut, contentDescription = "Extract range")
                        }
                        IconButton(onClick = { splitFolder.launch(null) }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Split into folder")
                        }
                        IconButton(onClick = {
                            save.launch(name.replace(".pdf", "", ignoreCase = true) + "_edited.pdf")
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pages.size) { i ->
                        val ref = pages[i]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f)
                                    .background(Color(0xFFDDDDDD), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                PageThumb(handle = handle, sourceIndex = ref.index)
                                if (ref.rotation != 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                    ) {
                                        Text("${ref.rotation}°", color = Color.White, fontSize = 10.sp)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                ) {
                                    Text("${i + 1}", color = Color.White, fontSize = 10.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallIcon(Icons.Filled.RotateLeft) {
                                    pages = pages.toMutableList().also { it[i] = ref.copy(rotation = ref.rotation - 90) }
                                }
                                SmallIcon(Icons.Filled.RotateRight) {
                                    pages = pages.toMutableList().also { it[i] = ref.copy(rotation = ref.rotation + 90) }
                                }
                                SmallIcon(Icons.Filled.ArrowBack) { move(i, i - 1) }
                                SmallIcon(Icons.Filled.ArrowForward) { move(i, i + 1) }
                                SmallIcon(Icons.Filled.Delete, enabled = pages.size > 1) {
                                    pages = pages.toMutableList().also { it.removeAt(i) }
                                }
                            }
                        }
                    }
                }
                BusyOverlay(busy)
            }
        }

        if (extractOpen) {
            ExtractDialog(
                maxPage = handle.pageCount,
                onDismiss = { extractOpen = false },
                onExtract = { from, to ->
                    extractOpen = false
                    pendingExtract = from to to
                    extractSave.launch(name.replace(".pdf", "", ignoreCase = true) + "_pages.pdf")
                },
            )
        }
    }
}

@Composable
private fun PageThumb(handle: PdfDocumentHandle, sourceIndex: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, sourceIndex) {
        value = withContext(Dispatchers.IO) { handle.render(sourceIndex, 240) }
    }
    val bmp = bitmap
    if (bmp == null) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${sourceIndex + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ExtractDialog(maxPage: Int, onDismiss: () -> Unit, onExtract: (Int, Int) -> Unit) {
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(maxPage.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract page range") },
        text = {
            Column {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it.filter(Char::isDigit) },
                    label = { Text("From") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it.filter(Char::isDigit) },
                    label = { Text("To") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val f = (from.toIntOrNull() ?: 1).coerceIn(1, maxPage) - 1
                val t = (to.toIntOrNull() ?: maxPage).coerceIn(1, maxPage) - 1
                onExtract(f, t)
            }) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
