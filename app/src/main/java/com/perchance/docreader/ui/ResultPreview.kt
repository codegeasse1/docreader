package com.perchance.docreader.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.FileProvider
import com.perchance.docreader.data.formatSize
import com.perchance.docreader.pdf.PdfDocumentHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a private (cache/file) PDF out to a destination the user picked through the SAF. */
fun copyFileTo(context: Context, src: File, dest: Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(dest)?.use { out ->
        src.inputStream().use { it.copyTo(out) }
    } != null
}.getOrDefault(false)

/** Shares a private file by tunnelling it through our own FileProvider. */
fun shareFile(context: Context, file: File, mime: String, title: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
    true
}.getOrDefault(false)

/**
 * Shows the file an operation just produced, *before* the user is asked where to save it.
 *
 * The work goes into a temporary cache file, this overlay renders it (with page navigation for
 * PDFs, or a scrollable text body for text exports), and only then can the user pick a
 * destination with "Save". "Share" hands the file to another app, and closing discards it.
 */
@Composable
fun ResultPreview(
    file: File,
    title: String,
    suggestedName: String,
    mime: String = "application/pdf",
    note: String? = null,
    onOpen: ((Uri, String) -> Unit)? = null,
    onSaved: (() -> Unit)? = null,
    onDiscard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val isPdf = mime == "application/pdf"

    val handle = remember(file) {
        if (isPdf) runCatching { PdfDocumentHandle(context, Uri.fromFile(file)) }.getOrNull() else null
    }
    DisposableEffect(handle) {
        onDispose { handle?.close() }
    }

    var pageIndex by remember(file) { mutableStateOf(0) }
    val pageCount = (handle?.pageCount ?: 1).coerceAtLeast(1)
    val bitmap by produceState<Bitmap?>(initialValue = null, handle, pageIndex) {
        value = handle?.let { h -> withContext(Dispatchers.IO) { h.render(pageIndex, 1000) } }
    }
    val text by produceState<String?>(initialValue = null, file, isPdf) {
        value = if (isPdf) null else withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrDefault("")
        }
    }

    var savedUri by remember { mutableStateOf<Uri?>(null) }
    val save = rememberCreateDocument(mime) { dest ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { copyFileTo(context, file, dest) }
            if (ok) {
                savedUri = dest
                onSaved?.invoke()
            } else {
                snackbar.showSnackbar("Could not save the file")
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                        Text(
                            "Preview before saving",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDiscard) {
                        Icon(Icons.Filled.Close, contentDescription = "Discard")
                    }
                }

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFE9E9EE), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isPdf -> {
                            val bmp = bitmap
                            if (bmp == null) {
                                CircularProgressIndicator()
                            } else {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Preview page ${pageIndex + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                )
                            }
                        }

                        else -> {
                            val body = text
                            if (body == null) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    text = body.take(20000).ifBlank { "(no readable text)" },
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isPdf && pageCount > 1) {
                        IconButton(
                            onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                            enabled = pageIndex > 0,
                        ) {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(if (isPdf) "$pageCount page${if (pageCount == 1) "" else "s"}" else "Text export")
                                append("  ·  ")
                                append(formatSize(file.length()))
                                append("  ·  ")
                                append(suggestedName)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        if (note != null) {
                            Text(
                                note,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                            )
                        }
                    }
                    if (isPdf && pageCount > 1) {
                        IconButton(
                            onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) },
                            enabled = pageIndex < pageCount - 1,
                        ) {
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next page")
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDiscard) { Text("Discard") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (!shareFile(context, file, mime, title)) {
                            scope.launch { snackbar.showSnackbar("Could not share the file") }
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                    Button(onClick = { save.launch(suggestedName) }) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    savedUri?.let { dest ->
        AlertDialog(
            onDismissRequest = {
                savedUri = null
                onDiscard()
            },
            title = { Text("Saved") },
            text = { Text("\"$suggestedName\" was saved to the folder you chose.") },
            confirmButton = {
                TextButton(onClick = {
                    savedUri = null
                    if (onOpen != null) onOpen(dest, suggestedName) else onDiscard()
                }) {
                    Text(if (onOpen != null) "Open" else "Done")
                }
            },
            dismissButton = if (onOpen != null) {
                { TextButton(onClick = { savedUri = null; onDiscard() }) { Text("Done") } }
            } else {
                null
            },
        )
    }
}
