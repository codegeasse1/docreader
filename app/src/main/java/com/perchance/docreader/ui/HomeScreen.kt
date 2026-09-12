package com.perchance.docreader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.perchance.docreader.data.RecentFile
import com.perchance.docreader.data.RecentStore
import com.perchance.docreader.data.formatDate
import com.perchance.docreader.data.formatSize
import com.perchance.docreader.data.queryFileMeta
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HomeTab { RECENT, STARRED }
private enum class SortMode { DATE, NAME, SIZE }

/** What to do with the PDF the user is about to pick. */
private enum class PickAction { OPEN, ANNOTATE, CONVERT, FILL_FORM, SIGN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    store: RecentStore,
    onOpen: (uri: String, name: String) -> Unit,
    onAnnotate: (uri: String, name: String) -> Unit,
    onFillForm: (uri: String, name: String) -> Unit,
    onSign: (uri: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(HomeTab.RECENT) }
    var sort by remember { mutableStateOf(SortMode.DATE) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var showSheet by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var infoFile by remember { mutableStateOf<RecentFile?>(null) }
    var convertTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val pickAction = remember { mutableStateOf(PickAction.OPEN) }
    val destAction = remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    val scanPages = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val captureUri = remember { mutableStateOf<Uri?>(null) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Registers a freshly produced file in the recents list and opens it. */
    fun openCreated(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val (name, size) = queryFileMeta(context, uri)
        store.add(RecentFile(uri.toString(), name, size, System.currentTimeMillis()))
        refreshKey++
        onOpen(uri.toString(), name)
    }

    /** Runs [into] with the chosen destination, then opens the produced file. */
    fun produce(into: (Uri) -> Unit) {
        destAction.value = { dest ->
            scope.launch {
                busy = "Writing PDF…"
                val ok = withContext(Dispatchers.IO) { runCatching { into(dest) }.isSuccess }
                busy = null
                if (ok) openCreated(dest) else notify("Could not create the PDF")
            }
        }
    }

    // The destination launchers are declared first because the picker callbacks below use them.
    val createDest = rememberCreateDocument("application/pdf") { dest -> destAction.value?.invoke(dest) }
    val txtDest = rememberCreateDocument("text/plain") { dest -> destAction.value?.invoke(dest) }
    val htmlDest = rememberCreateDocument("text/html") { dest -> destAction.value?.invoke(dest) }

    // ---- pickers -------------------------------------------------------------------------
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = queryFileMeta(context, uri)
            store.add(RecentFile(uri.toString(), name, size, System.currentTimeMillis()))
            refreshKey++
            when (pickAction.value) {
                PickAction.OPEN -> onOpen(uri.toString(), name)
                PickAction.ANNOTATE -> onAnnotate(uri.toString(), name)
                PickAction.FILL_FORM -> onFillForm(uri.toString(), name)
                PickAction.SIGN -> onSign(uri.toString(), name)
                PickAction.CONVERT -> convertTarget = uri.toString() to name
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            produce { dest -> PdfOps.createFromImages(context, uris, dest) }
            createDest.launch(timestampName("Photos"))
        }
    }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            produce { dest -> PdfOps.merge(context, uris, null, dest) }
            createDest.launch(timestampName("Merged"))
        }
    }
    val scanGalleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scanPages.value = scanPages.value + uris
    }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri.value
        if (ok && uri != null) scanPages.value = scanPages.value + uri
    }

    fun pick(action: PickAction) = filePicker.launch(arrayOf("application/pdf"))

    val files = remember(refreshKey, tab, sort, query) {
        val base = when (tab) {
            HomeTab.RECENT -> store.list()
            HomeTab.STARRED -> store.starred()
        }
        val filtered = if (query.isBlank()) base else base.filter { it.name.contains(query, ignoreCase = true) }
        when (sort) {
            SortMode.DATE -> filtered.sortedByDescending { it.lastOpened }
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortMode.SIZE -> filtered.sortedByDescending { it.size }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSheet = true },
                containerColor = Color(0xFFC62828),
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                HomeHeader(
                    searching = searchOpen,
                    query = query,
                    onQuery = { query = it },
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    },
                )
                QuickActions(
                    onOpenFile = { pickAction.value = PickAction.OPEN; pick(PickAction.OPEN) },
                    onAnnotate = { pickAction.value = PickAction.ANNOTATE; pick(PickAction.ANNOTATE) },
                    onConvert = { pickAction.value = PickAction.CONVERT; pick(PickAction.CONVERT) },
                    onFillForm = { pickAction.value = PickAction.FILL_FORM; pick(PickAction.FILL_FORM) },
                    onSign = { pickAction.value = PickAction.SIGN; pick(PickAction.SIGN) },
                    onScan = { showScan = true },
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                TabRow(
                    tab = tab,
                    onTab = { tab = it },
                    sort = sort,
                    onSort = { sort = it },
                )
                if (files.isEmpty()) {
                    EmptyState(tab, query.isNotBlank())
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files, key = { it.uri }) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    store.add(file.copy(lastOpened = System.currentTimeMillis()))
                                    refreshKey++
                                    onOpen(file.uri, file.name)
                                },
                                onStar = {
                                    store.toggleStar(file.uri)
                                    refreshKey++
                                },
                                onRemove = {
                                    store.remove(file.uri)
                                    refreshKey++
                                },
                                onInfo = { infoFile = file },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
            BusyOverlay(busy)
        }
    }

    // ---- create / import sheet -----------------------------------------------------------
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Text(
                "Create a PDF",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )
            SheetSection(title = "Create") {
                SheetItem(Icons.Filled.PictureAsPdf, "Blank PDF") {
                    showSheet = false
                    produce { dest -> PdfOps.createBlank(context, dest) }
                    createDest.launch(timestampName("Blank"))
                }
                SheetItem(Icons.Filled.CameraAlt, "Scan") {
                    showSheet = false
                    showScan = true
                }
            }
            SheetSection(title = "From") {
                SheetItem(Icons.Filled.Image, "Photos") {
                    showSheet = false
                    photoPicker.launch("image/*")
                }
                SheetItem(Icons.Filled.Description, "Documents") {
                    showSheet = false
                    mergePicker.launch(arrayOf("application/pdf"))
                }
                SheetItem(Icons.Filled.Folder, "Open a PDF") {
                    showSheet = false
                    pickAction.value = PickAction.OPEN
                    pick(PickAction.OPEN)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- scan sheet ----------------------------------------------------------------------
    if (showScan) {
        ModalBottomSheet(onDismissRequest = { showScan = false }, sheetState = sheetState) {
            ScanSheet(
                pages = scanPages.value,
                onCapture = {
                    val dir = File(context.cacheDir, "scans").apply { mkdirs() }
                    val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
                    val uri = runCatching {
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    }.getOrNull()
                    if (uri == null) {
                        notify("Could not start the camera")
                    } else {
                        captureUri.value = uri
                        captureLauncher.launch(uri)
                    }
                },
                onGallery = { scanGalleryPicker.launch("image/*") },
                onRemove = { index -> scanPages.value = scanPages.value.filterIndexed { i, _ -> i != index } },
                onClear = { scanPages.value = emptyList() },
                onSave = {
                    val pages = scanPages.value
                    if (pages.isEmpty()) {
                        notify("Capture at least one page first")
                    } else {
                        showScan = false
                        produce { dest -> PdfOps.createFromImages(context, pages, dest) }
                        createDest.launch(timestampName("Scan"))
                        scanPages.value = emptyList()
                    }
                },
                onDismiss = { showScan = false },
            )
        }
    }

    // ---- convert dialog ------------------------------------------------------------------
    convertTarget?.let { (_, name) ->
        ConvertDialog(
            name = name,
            onPick = { asHtml ->
                val target = convertTarget
                convertTarget = null
                if (target != null) {
                    val (uriString, baseName) = target
                    destAction.value = { dest ->
                        scope.launch {
                            busy = "Converting…"
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    PdfOps.exportText(context, Uri.parse(uriString), null, dest, asHtml)
                                }.isSuccess
                            }
                            busy = null
                            notify(if (ok) "Saved" else "Conversion failed")
                        }
                    }
                    val stem = baseName.substringBeforeLast('.')
                    if (asHtml) htmlDest.launch("$stem.html") else txtDest.launch("$stem.txt")
                }
            },
            onDismiss = { convertTarget = null },
        )
    }

    infoFile?.let { file ->
        AlertDialog(
            onDismissRequest = { infoFile = null },
            title = { Text(file.name, maxLines = 2) },
            text = {
                Column {
                    InfoLine("Location", file.uri)
                    InfoLine("Size", formatSize(file.size))
                    InfoLine("Last opened", formatDate(file.lastOpened))
                    InfoLine("Starred", if (file.starred) "Yes" else "No")
                }
            },
            confirmButton = { TextButton(onClick = { infoFile = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp)
    }
}

private fun timestampName(prefix: String): String =
    "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"

@Composable
private fun HomeHeader(
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search documents") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleSearch) {
                Icon(Icons.Filled.Close, contentDescription = "Close search")
            }
        } else {
            Text(
                text = "DocReader",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
    }
}

@Composable
private fun QuickActions(
    onOpenFile: () -> Unit,
    onAnnotate: () -> Unit,
    onConvert: () -> Unit,
    onFillForm: () -> Unit,
    onSign: () -> Unit,
    onScan: () -> Unit,
) {
    val actions = listOf(
        Triple("Open", Icons.Filled.Folder, onOpenFile),
        Triple("Annotate", Icons.Filled.Edit, onAnnotate),
        Triple("Convert", Icons.Filled.SwapHoriz, onConvert),
        Triple("Fill Form", Icons.Filled.CheckCircle, onFillForm),
        Triple("Sign", Icons.Filled.Description, onSign),
        Triple("Scan", Icons.Filled.CameraAlt, onScan),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        actions.forEach { (label, icon, action) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(84.dp)
                    .clickable { action() }
                    .padding(vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text(label, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TabRow(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabLabel("Recent", tab == HomeTab.RECENT) { onTab(HomeTab.RECENT) }
        Spacer(Modifier.width(20.dp))
        TabLabel("Starred", tab == HomeTab.STARRED) { onTab(HomeTab.STARRED) }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.Menu, contentDescription = "Sort")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SortMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (mode) {
                                    SortMode.DATE -> "Sort by date"
                                    SortMode.NAME -> "Sort by name"
                                    SortMode.SIZE -> "Sort by size"
                                }
                            )
                        },
                        trailingIcon = if (mode == sort) {
                            { Icon(Icons.Filled.CheckCircle, contentDescription = null) }
                        } else null,
                        onClick = {
                            menuOpen = false
                            onSort(mode)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 28.dp else 0.dp)
                .background(if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent)
        )
    }
}

@Composable
private fun EmptyState(tab: HomeTab, searching: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                searching -> "No documents match your search"
                tab == HomeTab.RECENT -> "No recent documents yet.\nTap + to open or create one."
                else -> "No starred documents"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    file: RecentFile,
    onClick: () -> Unit,
    onStar: () -> Unit,
    onRemove: () -> Unit,
    onInfo: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PictureAsPdf,
            contentDescription = null,
            tint = Color(0xFFC62828),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${formatDate(file.lastOpened)}  |  ${formatSize(file.size)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (file.starred) "Unstar" else "Star") },
                    leadingIcon = {
                        Icon(
                            if (file.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                        )
                    },
                    onClick = { menuOpen = false; onStar() },
                )
                DropdownMenuItem(
                    text = { Text("File info") },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = { menuOpen = false; onInfo() },
                )
                DropdownMenuItem(
                    text = { Text("Remove from list") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ScanSheet(
    pages: List<Uri>,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Scan to PDF", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            if (pages.isEmpty()) {
                "Capture document pages with the camera, or add photos from your gallery."
            } else {
                "${pages.size} page${if (pages.size == 1) "" else "s"} ready"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (pages.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages.size) { index ->
                    Box {
                        UriThumb(
                            uri = pages[index],
                            modifier = Modifier.width(96.dp).aspectRatio(0.72f),
                        )
                        IconButton(
                            onClick = { onRemove(index) },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove page",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onCapture) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Camera")
            }
            TextButton(onClick = onGallery) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Gallery")
            }
            Spacer(Modifier.weight(1f))
            if (pages.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = onSave, enabled = pages.isNotEmpty()) { Text("Save as PDF") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun UriThumb(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.background(Color(0xFF2A2E36), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ConvertDialog(name: String, onPick: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convert", maxLines = 1) },
        text = {
            Column {
                Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                Text("Choose an output format:")
            }
        },
        confirmButton = { TextButton(onClick = { onPick(false) }) { Text("Plain text (.txt)") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(true) }) { Text("HTML") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
