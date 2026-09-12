package com.perchance.docreader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.perchance.docreader.pdf.AnnKind
import com.perchance.docreader.pdf.Overlay

/**
 * Draws annotations (display space, normalized) on top of a rendered page.
 * Used by the reader (read-only) and the annotation editor (live preview).
 */
@Composable
fun AnnotationLayer(
    overlays: List<Overlay>,
    modifier: Modifier = Modifier.fillMaxSize(),
    searchHits: List<Overlay> = emptyList(),
    activeId: String? = null,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        for (hit in searchHits) drawHighlight(hit, Color(0xFFFFC107))
        for (o in overlays) drawAnnotation(measurer, o, o.id == activeId)
    }
}

private fun DrawScope.drawHighlight(o: Overlay, color: Color) {
    drawRect(
        color = color.copy(alpha = 0.45f),
        topLeft = Offset(o.left * size.width, o.top * size.height),
        size = Size((o.right - o.left) * size.width, (o.bottom - o.top) * size.height),
    )
}

private fun DrawScope.drawAnnotation(
    measurer: androidx.compose.ui.text.TextMeasurer,
    o: Overlay,
    active: Boolean,
) {
    val left = o.left * size.width
    val top = o.top * size.height
    val right = o.right * size.width
    val bottom = o.bottom * size.height
    val width = (right - left).coerceAtLeast(1f)
    val height = (bottom - top).coerceAtLeast(1f)
    val color = Color(o.color)
    val stroke = (o.width * size.width).coerceIn(1f, 40f)

    when (o.kind) {
        AnnKind.HIGHLIGHT -> drawRect(
            color = color.copy(alpha = 0.35f),
            topLeft = Offset(left, top),
            size = Size(width, height),
        )

        AnnKind.UNDERLINE -> drawLine(
            color = color,
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = stroke,
        )

        AnnKind.STRIKEOUT -> drawLine(
            color = color,
            start = Offset(left, top + height / 2f),
            end = Offset(right, top + height / 2f),
            strokeWidth = stroke,
        )

        AnnKind.PEN -> {
            val path = Path()
            var started = false
            var i = 0
            while (i + 1 < o.points.size) {
                val x = o.points[i] * size.width
                val y = o.points[i + 1] * size.height
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
                i += 2
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        AnnKind.TEXT -> {
            drawRect(
                color = color.copy(alpha = if (active) 0.25f else 0.12f),
                topLeft = Offset(left, top),
                size = Size(width, height),
            )
            if (o.text.isNotBlank()) {
                drawText(
                    textMeasurer = measurer,
                    text = o.text,
                    topLeft = Offset(left + 2f, top + 2f),
                    style = TextStyle(
                        color = color,
                        fontSize = (o.fontSize * size.height).toSp(),
                    ),
                )
            }
        }
    }

    if (active) {
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 2f),
        )
    }
}
