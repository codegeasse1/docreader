package com.perchance.docreader.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.pdf.FormFieldInfo
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lists the document's AcroForm fields and lets the user fill them in, optionally flattening. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillFormScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    DocumentGate(uri = uri, onAbort = onBack) { _, password ->
        var fields by remember(uri) { mutableStateOf<List<FormFieldInfo>?>(null) }
        var values by remember(uri) { mutableStateOf<Map<String, String>>(emptyMap()) }
        var busy by remember { mutableStateOf<String?>(null) }
        val pendingResult = remember { mutableStateOf<File?>(null) }
        var pendingSuggested by remember { mutableStateOf("") }

        LaunchedEffect(uri, password) {
            fields = withContext(Dispatchers.IO) {
                runCatching { PdfOps.listFields(context, Uri.parse(uri), password) }.getOrDefault(emptyList())
            }
        }

        fun produceFilled(flatten: Boolean) {
            scope.launch {
                busy = "Saving form…"
                val out = File.createTempFile("docreader_filled_", ".pdf", context.cacheDir)
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfOps.fillForm(
                            context,
                            Uri.parse(uri),
                            values,
                            password,
                            Uri.fromFile(out),
                            flatten,
                        )
                    }.isSuccess
                }
                busy = null
                if (ok && out.length() > 0L) {
                    pendingSuggested = name.replace(".pdf", "", ignoreCase = true) +
                        if (flatten) "_flattened.pdf" else "_filled.pdf"
                    pendingResult.value = out
                } else {
                    snackbar.showSnackbar("Could not save")
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("Fill form", fontSize = 16.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { produceFilled(false) }) {
                            Icon(Icons.Filled.Save, contentDescription = "Preview & save")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                val list = fields
                when {
                    list == null -> InfoMessage("Reading form fields…")
                    list.isEmpty() -> InfoMessage("This document has no fillable form fields.")
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            items(list, key = { it.name }) { field ->
                                FieldRow(
                                    field = field,
                                    value = values[field.name] ?: field.value,
                                    onChange = { v -> values = values + (field.name to v) },
                                )
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextButton(onClick = { produceFilled(false) }) { Text("Preview") }
                            TextButton(onClick = { produceFilled(true) }) { Text("Flatten & preview") }
                        }
                    }
                }
                BusyOverlay(busy)
            }
        }

        pendingResult.value?.let { result ->
            ResultPreview(
                file = result,
                title = "Filled form",
                suggestedName = pendingSuggested,
                onDiscard = {
                    runCatching { result.delete() }
                    pendingResult.value = null
                },
            )
        }
    }
}

@Composable
private fun FieldRow(field: FormFieldInfo, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when {
            field.type == "Btn" && field.options.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(field.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(
                        checked = value.equals("true", true) || value == "1",
                        onCheckedChange = { onChange(if (it) "true" else "false") },
                        enabled = !field.readOnly,
                    )
                }
            }

            field.options.isNotEmpty() -> {
                DropdownField(field = field, value = value, onChange = onChange)
            }

            else -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(field.name) },
                    enabled = !field.readOnly,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun DropdownField(field: FormFieldInfo, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(field.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Box {
            TextButton(onClick = { open = true }, enabled = !field.readOnly) {
                Text(if (value.isBlank()) "Choose…" else value)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                field.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            open = false
                            onChange(option)
                        },
                    )
                }
            }
        }
    }
}
