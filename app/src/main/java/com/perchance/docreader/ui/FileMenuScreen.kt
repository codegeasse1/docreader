package com.perchance.docreader.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.perchance.docreader.data.RecentStore
import com.perchance.docreader.data.formatSize
import com.perchance.docreader.data.queryFileMeta
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileMenuScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
    onOpenReader: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { RecentStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val (_, size) = remember(uri) { queryFileMeta(context, Uri.parse(uri)) }
    var starred by remember(uri) {
        mutableStateOf(store.list().firstOrNull { it.uri == uri }?.starred == true)
    }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share document"))
    }

    fun soon(label: String) {
        scope.launch { snackbar.showSnackbar("$label — coming soon in this scaffold") }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundAction(Icons.Filled.List, "Content") { onOpenReader() }
                RoundAction(Icons.Filled.Share, "Share") { share() }
                RoundAction(Icons.Filled.GridView, "Thumbnail") { onOpenReader() }
                RoundAction(Icons.Filled.Save, "Save as") { soon("Save as") }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val entries = listOf(
                    MenuEntry(Icons.Filled.Add, "Merge Documents"),
                    MenuEntry(Icons.Filled.ContentCut, "Split Document"),
                    MenuEntry(Icons.Filled.Compress, "File Compressor"),
                    MenuEntry(Icons.Filled.Print, "Print"),
                    MenuEntry(Icons.Filled.Bookmark, "Add Bookmark"),
                    MenuEntry(Icons.Filled.Lock, "Set password"),
                    MenuEntry(Icons.Filled.ViewModule, "Organize pages"),
                    MenuEntry(Icons.Filled.Comment, "Annotation list"),
                )
                items(entries.size) { i ->
                    val entry = entries[i]
                    MenuRow(entry.icon, entry.label) { soon(entry.label) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                item {
                    MenuRow(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (starred) "Unstar" else "Star",
                        tint = if (starred) Color(0xFFF9A825) else null,
                    ) {
                        store.toggleStar(uri)
                        starred = !starred
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                item {
                    MenuRow(Icons.Filled.Info, "File info") { soon("File info") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

private data class MenuEntry(val icon: ImageVector, val label: String)

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
