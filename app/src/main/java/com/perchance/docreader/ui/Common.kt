package com.perchance.docreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.perchance.docreader.pdf.PdfDocumentHandle

/** Full-screen blocking spinner used while a PDF operation runs. */
@Composable
fun BusyOverlay(text: String?) {
    if (text == null) return
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(14.dp))
            Text(text, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

/** Centered hint used by the empty / unsupported states. */
@Composable
fun InfoMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** ACTION_CREATE_DOCUMENT launcher that calls [onCreated] with the chosen destination. */
@Composable
fun rememberCreateDocument(
    mime: String = "application/pdf",
    onCreated: (Uri) -> Unit,
) = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri: Uri? ->
    if (uri != null) onCreated(uri)
}

/** ACTION_OPEN_DOCUMENT_TREE launcher that calls [onPicked] with the chosen folder. */
@Composable
fun rememberPickFolder(onPicked: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) onPicked(uri)
    }

/**
 * Opens a PDF for the editing screens, transparently asking for a password when the file is
 * protected, and hands the live [PdfDocumentHandle] (plus the password, for re-decryption) to
 * [content]. The handle is closed automatically when the screen leaves composition.
 */
@Composable
fun DocumentGate(
    uri: String,
    onAbort: () -> Unit,
    content: @Composable (PdfDocumentHandle, String?) -> Unit,
) {
    val context = LocalContext.current
    val documentUri = remember(uri) { Uri.parse(uri) }
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var retry by remember(uri) { mutableStateOf(0) }
    val result = remember(uri, password, retry) {
        runCatching { PdfDocumentHandle(context, documentUri, password) }
    }
    DisposableEffect(result) {
        onDispose { result.getOrNull()?.close() }
    }

    val handle = result.getOrNull()
    if (handle == null) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onAbort,
            title = { Text("Password required") },
            text = {
                Column {
                    Text("This document is protected. Enter its password to continue.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Password") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    password = text
                    retry++
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = onAbort) { Text("Cancel") } },
        )
    } else {
        content(handle, password)
    }
}
