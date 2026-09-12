package com.perchance.docreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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

/**
 * Root composable. Screens are kept on a small in-memory back stack so the system back button
 * returns to the previous screen instead of closing the app.
 */
@Composable
fun DocReaderApp(pendingOpen: MutableState<String?>) {
    val context = LocalContext.current
    val store = remember { RecentStore(context.applicationContext) }
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

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
            push(Screen.Reader(uri.toString(), name))
            pendingOpen.value = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = stack.last()) {
            is Screen.Home -> HomeScreen(
                store = store,
                onOpen = { uri, name -> push(Screen.Reader(uri, name)) },
                onAnnotate = { uri, name -> push(Screen.Annotate(uri, name, 0)) },
                onFillForm = { uri, name -> push(Screen.FillForm(uri, name)) },
                onSign = { uri, name -> push(Screen.Sign(uri, name)) },
            )

            is Screen.Reader -> ReaderScreen(
                uri = current.uri,
                name = current.name,
                onBack = { pop() },
                onOpenFileMenu = { push(Screen.FileMenu(current.uri, current.name)) },
                onOpenAnnotate = { page -> push(Screen.Annotate(current.uri, current.name, page)) },
                onOpenOrganize = { push(Screen.Organize(current.uri, current.name)) },
                onOpenFillForm = { push(Screen.FillForm(current.uri, current.name)) },
                onOpenSign = { push(Screen.Sign(current.uri, current.name)) },
            )

            is Screen.FileMenu -> FileMenuScreen(
                uri = current.uri,
                name = current.name,
                onBack = { pop() },
                onOpenReader = { push(Screen.Reader(current.uri, current.name)) },
                onOpenAnnotate = { push(Screen.Annotate(current.uri, current.name, 0)) },
                onOpenOrganize = { push(Screen.Organize(current.uri, current.name)) },
                onOpenFillForm = { push(Screen.FillForm(current.uri, current.name)) },
                onOpenSign = { push(Screen.Sign(current.uri, current.name)) },
            )

            is Screen.Annotate -> AnnotateScreen(
                uri = current.uri,
                name = current.name,
                initialPage = current.page,
                onBack = { pop() },
            )

            is Screen.Organize -> OrganizeScreen(
                uri = current.uri,
                name = current.name,
                onBack = { pop() },
            )

            is Screen.FillForm -> FillFormScreen(
                uri = current.uri,
                name = current.name,
                onBack = { pop() },
            )

            is Screen.Sign -> SignScreen(
                uri = current.uri,
                name = current.name,
                onBack = { pop() },
            )
        }
    }
}
