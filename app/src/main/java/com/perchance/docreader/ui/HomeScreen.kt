package com.perchance.docreader.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.data.RecentFile
import com.perchance.docreader.data.RecentStore
import com.perchance.docreader.data.formatDate
import com.perchance.docreader.data.formatSize
import com.perchance.docreader.data.queryFileMeta

private enum class HomeTab { RECENT, STARRED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    store: RecentStore,
    onOpen: (uri: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(HomeTab.RECENT) }
    var showSheet by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val (name, size) = queryFileMeta(context, uri)
            store.add(
                RecentFile(
                    uri = uri.toString(),
                    name = name,
                    size = size,
                    lastOpened = System.currentTimeMillis(),
                )
            )
            refreshKey++
            onOpen(uri.toString(), name)
        }
    }

    // Re-read the list whenever refreshKey changes.
    val files = remember(refreshKey, tab) {
        when (tab) {
            HomeTab.RECENT -> store.list()
            HomeTab.STARRED -> store.starred()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSheet = true },
                containerColor = Color(0xFFC62828),
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HomeHeader()
            QuickActions(
                onOpenFile = { picker.launch(arrayOf("application/pdf")) },
                onComingSoon = { },
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            TabRow(tab = tab, onTab = { tab = it })
            if (files.isEmpty()) {
                EmptyState(tab)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files, key = { it.uri }) { file ->
                        FileRow(
                            file = file,
                            onClick = {
                                store.add(file.copy(lastOpened = System.currentTimeMillis()))
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
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            SheetSection(title = "Create") {
                SheetItem(Icons.Filled.PictureAsPdf, "PDF") {
                    showSheet = false
                    picker.launch(arrayOf("application/pdf"))
                }
                SheetItem(Icons.Filled.CameraAlt, "Scan") { showSheet = false }
            }
            SheetSection(title = "From") {
                SheetItem(Icons.Filled.Image, "Photos") { showSheet = false }
                SheetItem(Icons.Filled.Description, "Documents") {
                    showSheet = false
                    picker.launch(arrayOf("application/pdf"))
                }
                SheetItem(Icons.Filled.Cloud, "Cloud") { showSheet = false }
                SheetItem(Icons.Filled.Folder, "Browse") { showSheet = false }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Welcome",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { }) {
            Icon(Icons.Filled.Search, contentDescription = "Search")
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF8E2440), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("DR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun QuickActions(onOpenFile: () -> Unit, onComingSoon: () -> Unit) {
    val actions = listOf(
        Triple("Open File", Icons.Filled.Folder, onOpenFile),
        Triple("Annotate", Icons.Filled.Edit, onComingSoon),
        Triple("Convert", Icons.Filled.SwapHoriz, onComingSoon),
        Triple("Fill Form", Icons.Filled.CheckCircle, onComingSoon),
        Triple("Sign", Icons.Filled.Description, onComingSoon),
        Triple("Scan", Icons.Filled.CameraAlt, onComingSoon),
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
private fun TabRow(tab: HomeTab, onTab: (HomeTab) -> Unit) {
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
        IconButton(onClick = { }) {
            Icon(Icons.Filled.Menu, contentDescription = "Sort")
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
private fun EmptyState(tab: HomeTab) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (tab == HomeTab.RECENT) "No recent documents yet" else "No starred documents",
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
                    text = { Text("Remove from list") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() },
                )
                DropdownMenuItem(
                    text = { Text("Info") },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = { menuOpen = false },
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
