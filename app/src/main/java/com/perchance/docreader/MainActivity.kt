package com.perchance.docreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.perchance.docreader.data.RecentFile
import com.perchance.docreader.data.RecentStore
import com.perchance.docreader.data.queryFileMeta
import com.perchance.docreader.ui.AnnotateScreen
import com.perchance.docreader.ui.FillFormScreen
import com.perchance.docreader.ui.FileMenuScreen
import com.perchance.docreader.ui.HomeScreen
import com.perchance.docreader.ui.OrganizeScreen
import com.perchance.docreader.ui.ReaderScreen
import com.perchance.docreader.ui.SignScreen
import com.perchance.docreader.ui.theme.DocReaderTheme

/** Which screen the app is showing. A plain sealed interface keeps navigation dependency-free. */
sealed interface Screen {
    data object Home : Screen
    data class Reader(val uri: String, val name: String) : Screen
    data class FileMenu(val uri: String, val name: String) : Screen
    data class Annotate(val uri: String, val name: String, val page: Int) : Screen
    data class Organize(val uri: String, val name: String) : Screen
    data class FillForm(val uri: String, val name: String) : Screen
    data class Sign(val uri: String, val name: String) : Screen
}

class MainActivity : ComponentActivity() {

    private val pendingOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent {
            DocReaderTheme {
                DocReaderApp(pendingOpen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    /** Records an ACTION_VIEW uri so the Compose tree can open it once it is ready. */
    private fun readIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { pendingOpen.value = it.toString() }
        }
    }
}

@Composable
fun DocReaderApp(pendingOpen: MutableState<String?>) {
    val context = LocalContext.current
    val store = remember { RecentStore(context.applicationContext) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    val pending = pendingOpen.value
    LaunchedEffect(pending) {
        if (pending != null) {
            val uri = Uri.parse(pending)
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
            screen = Screen.Reader(uri.toString(), name)
            pendingOpen.value = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                store = store,
                onOpen = { uri, name -> screen = Screen.Reader(uri, name) },
            )

            is Screen.Reader -> ReaderScreen(
                uri = current.uri,
                name = current.name,
                onBack = { screen = Screen.Home },
                onOpenFileMenu = { screen = Screen.FileMenu(current.uri, current.name) },
                onOpenAnnotate = { page -> screen = Screen.Annotate(current.uri, current.name, page) },
                onOpenOrganize = { screen = Screen.Organize(current.uri, current.name) },
                onOpenFillForm = { screen = Screen.FillForm(current.uri, current.name) },
                onOpenSign = { screen = Screen.Sign(current.uri, current.name) },
            )

            is Screen.FileMenu -> FileMenuScreen(
                uri = current.uri,
                name = current.name,
                onBack = { screen = Screen.Reader(current.uri, current.name) },
                onOpenReader = { screen = Screen.Reader(current.uri, current.name) },
                onOpenAnnotate = { screen = Screen.Annotate(current.uri, current.name, 0) },
                onOpenOrganize = { screen = Screen.Organize(current.uri, current.name) },
                onOpenFillForm = { screen = Screen.FillForm(current.uri, current.name) },
                onOpenSign = { screen = Screen.Sign(current.uri, current.name) },
            )

            is Screen.Annotate -> AnnotateScreen(
                uri = current.uri,
                name = current.name,
                initialPage = current.page,
                onBack = { screen = Screen.Reader(current.uri, current.name) },
            )

            is Screen.Organize -> OrganizeScreen(
                uri = current.uri,
                name = current.name,
                onBack = { screen = Screen.Reader(current.uri, current.name) },
            )

            is Screen.FillForm -> FillFormScreen(
                uri = current.uri,
                name = current.name,
                onBack = { screen = Screen.Reader(current.uri, current.name) },
            )

            is Screen.Sign -> SignScreen(
                uri = current.uri,
                name = current.name,
                onBack = { screen = Screen.Reader(current.uri, current.name) },
            )
        }
    }
}
