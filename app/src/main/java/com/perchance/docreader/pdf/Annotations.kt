package com.perchance.docreader.pdf

/** The annotation tools available in the editor. */
enum class AnnKind { TEXT, PEN, HIGHLIGHT, UNDERLINE, STRIKEOUT }

/**
 * One user annotation, stored in *display space*: normalized 0..1 coordinates relative to the
 * rendered page bitmap with the origin at the top-left. Keeping display space means the same
 * overlay can be drawn on top of any zoom level, thumbnails included.
 *
 * [points] is a flattened list of x,y pairs (normalized) and is only used by [AnnKind.PEN].
 *
 * [fontSize] is only used by [AnnKind.TEXT]: it is the text height as a fraction of the page
 * height (display space), so a note keeps its apparent size at any zoom level. The A+ / A- / Reset
 * buttons scale the selected note's box and text together, and dragging its corner dot resizes it.
 */
data class Overlay(
    val id: String,
    val page: Int,
    val kind: AnnKind,
    val color: Long,
    val width: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val points: List<Float> = emptyList(),
    val text: String = "",
    val fontSize: Float = DEFAULT_TEXT_SIZE,
)

/** Default text-note height, as a fraction of the page height. */
const val DEFAULT_TEXT_SIZE = 0.05f

/** Smallest / largest text-note height (fraction of the page height) the buttons allow. */
const val MIN_TEXT_SIZE = 0.012f
const val MAX_TEXT_SIZE = 0.24f

/** Stroke width for the pen / highlighter / underline / strike tools, as a fraction of page width. */
const val DEFAULT_STROKE_WIDTH = 0.02f
const val MIN_STROKE_WIDTH = 0.004f
const val MAX_STROKE_WIDTH = 0.14f

/** Eraser radius, as a fraction of the page width. */
const val DEFAULT_ERASER_SIZE = 0.05f
const val MIN_ERASER_SIZE = 0.015f
const val MAX_ERASER_SIZE = 0.30f

/** Preset colours shown in the annotation toolbar (WPS-style palette). */
val ANNOTATION_COLORS = listOf(
    0xFFE53935, // red
    0xFFFDD835, // yellow
    0xFF43A047, // green
    0xFF1E88E5, // blue
    0xFF8E24AA, // purple
    0xFF000000, // black
    0xFFFFFFFF, // white
)
