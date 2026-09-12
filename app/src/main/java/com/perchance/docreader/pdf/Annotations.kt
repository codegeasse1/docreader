package com.perchance.docreader.pdf

/** The annotation tools available in the editor. */
enum class AnnKind { TEXT, PEN, HIGHLIGHT, UNDERLINE, STRIKEOUT }

/**
 * One user annotation, stored in *display space*: normalized 0..1 coordinates relative to the
 * rendered page bitmap with the origin at the top-left. Keeping display space means the same
 * overlay can be drawn on top of any zoom level, thumbnails included.
 *
 * [points] is a flattened list of x,y pairs (normalized) and is only used by [AnnKind.PEN].
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
)

/** Preset colours shown in the annotation toolbar (WPS-style palette). */
val ANNOTATION_COLORS = listOf(
    0xFFE53935, // red
    0xFFFDD835, // yellow
    0xFF43A047, // green
    0xFF1E88E5, // blue
    0xFF8E24AA, // purple
    0xFF000000, // black
)
