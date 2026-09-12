package com.perchance.docreader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perchance.docreader.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Draw a signature, then apply a real detached CMS signature with a self-signed certificate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignScreen(
    uri: String,
    name: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DocumentGate(uri = uri, onAbort = onBack) { handle, password ->
        var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
        var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
        var signer by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf("Approved") }
        var location by remember { mutableStateOf("") }
        var pageText by remember { mutableStateOf("1") }
        var stampVisible by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf<String?>(null) }
        var resultText by remember { mutableStateOf<String?>(null) }

        val save = rememberCreateDocument("application/pdf") { dest ->
            scope.launch {
                busy = "Signing…"
                val page = ((pageText.toIntOrNull() ?: 1) - 1).coerceIn(0, (handle.pageCount - 1).coerceAtLeast(0))
                val stamp: Bitmap? = if (stampVisible && strokes.isNotEmpty()) renderSignature(strokes) else null
                val rect = if (stamp != null) floatArrayOf(0.55f, 0.80f, 0.95f, 0.94f) else null
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfOps.signDocument(
                            ctx = context,
                            uri = Uri.parse(uri),
                            password = password,
                            dest = dest,
                            signerName = signer.ifBlank { "DocReader User" },
                            reason = reason,
                            location = location,
                            stampPage = if (stamp != null) page else -1,
                            stamp = stamp,
                            stampRect = rect,
                        )
                    }
                }
                busy = null
                resultText = outcome.getOrElse { "Signing failed: ${it.message}" }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign document", fontSize = 16.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            save.launch(name.replace(".pdf", "", ignoreCase = true) + "_signed.pdf")
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = "Sign and save")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text("Draw your signature", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color.White, RoundedCornerShape(10.dp)),
                    ) {
                        SignaturePad(
                            strokes = strokes + listOf(current),
                            onStart = { current = listOf(it) },
                            onMove = { current = current + it },
                            onEnd = {
                                if (current.isNotEmpty()) strokes = strokes + listOf(current)
                                current = emptyList()
                            },
                        )
                        if (strokes.isEmpty() && current.isEmpty()) {
                            Text(
                                "Sign here with your finger",
                                color = Color(0x66000000),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { strokes = emptyList(); current = emptyList() }) {
                            Icon(Icons.Filled.Clear, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Clear")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Placement: bottom-right",
                            fontSize = 12.sp,
                            color = Color(0x99000000),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = signer,
                        onValueChange = { signer = it },
                        label = { Text("Signer name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Location") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { pageText = it.filter(Char::isDigit) },
                        label = { Text("Stamp page (1 – ${handle.pageCount})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = stampVisible,
                            onCheckedChange = { stampVisible = it },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Draw the visible signature onto the page")
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = {
                        save.launch(name.replace(".pdf", "", ignoreCase = true) + "_signed.pdf")
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sign & save copy")
                    }
                    Spacer(Modifier.height(24.dp))
                }
                BusyOverlay(busy)
            }
        }

        resultText?.let { text ->
            AlertDialog(
                onDismissRequest = { resultText = null },
                title = { Text("Signature added") },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { resultText = null }) { Text("Done") } },
            )
        }
    }
}

@Composable
private fun SignaturePad(
    strokes: List<List<Offset>>,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onStart(Offset((offset.x / size.width).coerceIn(0f, 1f), (offset.y / size.height).coerceIn(0f, 1f)))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onMove(
                            Offset(
                                (change.position.x / size.width).coerceIn(0f, 1f),
                                (change.position.y / size.height).coerceIn(0f, 1f),
                            )
                        )
                    },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() },
                )
            },
    ) {
        strokes.forEach { stroke ->
            if (stroke.size < 2) return@forEach
            val path = Path()
            path.moveTo(stroke[0].x * size.width, stroke[0].y * size.height)
            for (i in 1 until stroke.size) {
                path.lineTo(stroke[i].x * size.width, stroke[i].y * size.height)
            }
            drawPath(
                path = path,
                color = Color(0xFF102A43),
                style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

private fun renderSignature(strokes: List<List<Offset>>, width: Int = 900, height: Int = 300): Bitmap {
    val image = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    val paint = Paint()
    paint.color = Color.White
    paint.style = PaintingStyle.Fill
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    paint.style = PaintingStyle.Stroke
    paint.color = Color(0xFF102A43)
    paint.strokeWidth = 6f
    paint.strokeCap = StrokeCap.Round
    paint.strokeJoin = StrokeJoin.Round

    strokes.forEach { stroke ->
        if (stroke.size < 2) return@forEach
        val path = Path()
        path.moveTo(stroke[0].x * width, stroke[0].y * height)
        for (i in 1 until stroke.size) {
            path.lineTo(stroke[i].x * width, stroke[i].y * height)
        }
        canvas.drawPath(path, paint)
    }
    return image.asAndroidBitmap()
}
