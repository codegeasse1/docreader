package com.perchance.docreader.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.data.AnnotationStore
import com.perchance.docreader.data.BookmarkStore
import com.perchance.docreader.data.RecentStore
import com.perchance.docreader.data.formatSize
import com.perchance.docreader.data.queryFileMeta
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileMenuScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenAnnotate: () -> Unit,
    onOpenOrganize: () -> Unit,
    onOpenFillForm: () -> Unit,
    onOpenSign: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { RecentStore(context.applicationContext) }
    val annStore = remember { AnnotationStore(context.applicationContext) }
    val bookmarkStore = remember { BookmarkStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val documentUri = remember(uri) { Uri.parse(uri) }

    val (_, size) = remember(uri) { queryFileMeta(context, documentUri) }
    var starred by remember(uri) {
        mutableStateOf(store.list().firstOrNull { it.uri == uri }?.starred == true)
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var infoText by remember { mutableStateOf<String?>(null) }
    var annotationsOpen by remember { mutableStateOf(false) }

    // ---- pending operation payloads (consumed by the launchers below) ----
    var compressWidth by remember { mutableStateOf(1400) }
    var compressQuality by remember { mutableStateOf(72) }
    val pendingResult = remember { mutableStateOf<File?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var pendingSuggested by remember { mutableStateOf("") }
    var pendingNote by remember { mutableStateOf<String?>(null) }

    fun base() = name.replace(".pdf", "", ignoreCase = true)

    /**
     * Runs [op] into a private cache file and shows the result, so the user reviews the file
     * before being asked where to save it. [op] may return a short note shown in the preview.
     */
    fun produce(title: String, suggested: String, op: (Uri) -> String?) {
        scope.launch {
            busy = "Working…"
            val out = File.createTempFile("docreader_result_", ".pdf", context.cacheDir)
            val outcome = withContext(Dispatchers.IO) { runCatching { op(Uri.fromFile(out)) } }
            busy = null
            outcome.onSuccess { note ->
                if (out.length() > 0L) {
                    pendingTitle = title
                    pendingSuggested = suggested
                    pendingNote = note
                    pendingResult.value = out
                } else {
                    snackbar.showSnackbar("Operation failed")
                }
            }.onFailure { snackbar.showSnackbar("Operation failed: ${it.message}") }
        }
    }

    val mergePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val sources = listOf(documentUri) + uris
            produce("Merged PDF", "${base()}_merged.pdf") { dest ->
                PdfOps.merge(context, sources, null, dest)
                "Merged ${sources.size} files"
            }
        }
    }
    val folderPick = rememberPickFolder { dir ->
        scope.launch {
            busy = "Splitting pages…"
            val written = withContext(Dispatchers.IO) {
                runCatching { PdfOps.splitToPages(context, documentUri, null, dir, name) }.getOrDefault(emptyList())
            }
            busy = null
            snackbar.showSnackbar("Wrote ${written.size} file(s)")
        }
    }

    var dialog by remember { mutableStateOf<String?>(null) }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, documentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share document"))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, fontSize = 15.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PictureAsPdf,
                        contentDescription = null,
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            formatSize(size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundAction(Icons.Filled.List, "Content") { onOpenReader() }
                    RoundAction(Icons.Filled.Share, "Share") { share() }
                    RoundAction(Icons.Filled.GridView, "Thumbnail") { onOpenReader() }
                    RoundAction(Icons.Filled.Save, "Save as") {
                        produce("Copy of $name", "${base()}_copy.pdf") { dest ->
                            context.contentResolver.openInputStream(documentUri)?.use { input ->
                                context.contentResolver.openOutputStream(dest)?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            null
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    MenuRow(Icons.Filled.Add, "Merge Documents") { mergePick.launch(arrayOf("application/pdf")) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.ContentCut, "Split Document") { dialog = "split" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Compress, "File Compressor") { dialog = "compress" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Print, "Print") {
                        runCatching { printPdf(context, documentUri, name, null) }
                            .onFailure { scope.launch { snackbar.showSnackbar("Print failed") } }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Bookmark, "Add Bookmark") { dialog = "bookmark" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Lock, "Set password") { dialog = "password" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.LockOpen, "Remove password") {
                        produce("Unlocked PDF", "${base()}_unlocked.pdf") { dest ->
                            PdfOps.removePassword(context, documentUri, null, dest)
                            null
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.ViewModule, "Organize pages") { onOpenOrganize() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Comment, "Annotation list") { annotationsOpen = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (starred) "Unstar" else "Star",
                        tint = if (starred) Color(0xFFF9A825) else null,
                    ) {
                        store.toggleStar(uri)
                        starred = !starred
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Info, "File info") {
                        scope.launch {
                            busy = "Reading info…"
                            val info = withContext(Dispatchers.IO) {
                                runCatching { PdfOps.info(context, documentUri) }.getOrNull()
                            }
                            busy = null
                            infoText = info?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "Could not read info"
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Save, "Edit & annotate") { onOpenAnnotate() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Add, "Fill form") { onOpenFillForm() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    MenuRow(Icons.Filled.Info, "Sign document") { onOpenSign() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            BusyOverlay(busy)
        }
    }

    when (dialog) {
        "split" -> SplitDialog(
            onExtractRange = { from, to ->
                dialog = null
                produce("Extracted pages", "${base()}_pages.pdf") { dest ->
                    PdfOps.extractRange(context, documentUri, from, to, null, dest)
                    null
                }
            },
            onSplitFolder = {
                dialog = null
                folderPick.launch(null)
            },
            onDismiss = { dialog = null },
        )

        "compress" -> CompressDialog(
            onConfirm = { choice ->
                dialog = null
                if (choice == 0) {
                    compressWidth = 1400
                    compressQuality = 72
                } else {
                    compressWidth = 900
                    compressQuality = 52
                }
                produce("Compressed PDF", "${base()}_compressed.pdf") { dest ->
                    val (before, after) = PdfOps.compress(
                        context,
                        documentUri,
                        null,
                        compressWidth,
                        compressQuality,
                        dest,
                    )
                    "Compressed: ${formatSize(before)} → ${formatSize(after)}"
                }
            },
            onDismiss = { dialog = null },
        )

        "bookmark" -> BookmarkDialog(
            onAdd = { page ->
                dialog = null
                bookmarkStore.toggle(uri, (page - 1).coerceAtLeast(0), "Page $page")
                scope.launch { snackbar.showSnackbar("Bookmark added") }
            },
            onDismiss = { dialog = null },
        )

        "password" -> PasswordSetDialog(
            onSet = { pwd ->
                dialog = null
                produce("Protected PDF", "${base()}_protected.pdf") { dest ->
                    PdfOps.setPassword(context, documentUri, null, pwd, pwd, dest)
                    null
                }
            },
            onDismiss = { dialog = null },
        )
    }

    infoText?.let { text ->
        AlertDialog(
            onDismissRequest = { infoText = null },
            title = { Text("Result") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { infoText = null }) { Text("OK") } },
        )
    }

    if (annotationsOpen) {
        val overlays = remember(uri) { annStore.list(uri) }
        AlertDialog(
            onDismissRequest = { annotationsOpen = false },
            title = { Text("Annotations (${overlays.size})") },
            text = {
                if (overlays.isEmpty()) {
                    Text("No annotations yet. Use Edit & annotate to add some.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        items(overlays.size) { i ->
                            val o = overlays[i]
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.size(14.dp).background(Color(o.color), CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(o.kind.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 13.sp)
                                    Text(
                                        "Page ${o.page + 1}${if (o.text.isNotBlank()) " · ${o.text}" else ""}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = { annStore.remove(uri, o.id); annotationsOpen = false }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { annotationsOpen = false }) { Text("Close") } },
        )
    }

    pendingResult.value?.let { result ->
        ResultPreview(
            file = result,
            title = pendingTitle,
            suggestedName = pendingSuggested,
            note = pendingNote,
            onDiscard = {
                runCatching { result.delete() }
                pendingResult.value = null
            },
        )
    }
}

@Composable
private fun SplitDialog(
    onExtractRange: (Int, Int) -> Unit,
    onSplitFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split document") },
        text = {
            Column {
                Text("Extract a page range into a new PDF:")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it.filter(Char::isDigit) },
                        label = { Text("From") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it.filter(Char::isDigit) },
                        label = { Text("To") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onSplitFolder) {
                    Text("Or split every page into a folder…")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val f = ((from.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
                val t = ((to.toIntOrNull() ?: from.toIntOrNull() ?: 1) - 1).coerceAtLeast(f)
                onExtractRange(f, t)
            }) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CompressDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = listOf("Balanced (1400 px, JPEG 72)", "Smallest file")
    var choice by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress PDF") },
        text = {
            Column {
                Text("Pages are rasterised, so text stops being selectable but the file shrinks a lot.")
                Spacer(Modifier.height(10.dp))
                options.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == index, onClick = { choice = index })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text("Compress") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BookmarkDialog(onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bookmark") },
        text = {
            OutlinedTextField(
                value = page,
                onValueChange = { page = it.filter(Char::isDigit) },
                label = { Text("Page number") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(page.toIntOrNull() ?: 1) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordSetDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set password") },
        text = {
            Column {
                Text("A copy of the document will be protected with this password (AES-128).")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("Password") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pwd.isNotBlank()) onSet(pwd) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clickable { onClick() }
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(20.dp))
        Text(label, fontWeight = FontWeight.Medium)
    }
}
