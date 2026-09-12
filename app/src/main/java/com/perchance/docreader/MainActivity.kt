package com.perchance.docreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.perchance.docreader.data.RecentStore
import com.perchance.docreader.ui.FileMenuScreen
import com.perchance.docreader.ui.HomeScreen
import com.perchance.docreader.ui.ReaderScreen
import com.perchance.docreader.ui.theme.DocReaderTheme

/** Which screen the app is showing. A plain sealed interface keeps navigation dependency-free. */
sealed interface Screen {
    data object Home : Screen
    data class Reader(val uri: String, val name: String) : Screen
    data class FileMenu(val uri: String, val name: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DocReaderTheme {
                DocReaderApp()
            }
        }
    }
}

@Composable
fun DocReaderApp() {
    val context = LocalContext.current
    val store = remember { RecentStore(context.applicationContext) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

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
            )

            is Screen.FileMenu -> FileMenuScreen(
                uri = current.uri,
                name = current.name,
                onBack = { screen = Screen.Reader(current.uri, current.name) },
                onOpenReader = { screen = Screen.Reader(current.uri, current.name) },
            )
        }
    }
}
