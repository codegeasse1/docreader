package com.perchance.docreader.data

import android.content.Context
import com.perchance.docreader.pdf.AnnKind
import com.perchance.docreader.pdf.Overlay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Per-document annotation storage. Annotations live here until the user exports them into a PDF
 * copy, so they survive leaving the reader and coming back.
 */
class AnnotationStore(context: Context) {

    private val prefs = context.getSharedPreferences("docreader_annotations", Context.MODE_PRIVATE)

    fun newId(): String = UUID.randomUUID().toString()

    fun list(uri: String): List<Overlay> {
        val raw = prefs.getString(key(uri), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun forPage(uri: String, page: Int): List<Overlay> = list(uri).filter { it.page == page }

    fun add(uri: String, overlay: Overlay) = write(uri, list(uri) + overlay)

    fun replace(uri: String, overlay: Overlay) =
        write(uri, list(uri).map { if (it.id == overlay.id) overlay else it })

    fun remove(uri: String, id: String) = write(uri, list(uri).filterNot { it.id == id })

    fun clear(uri: String) {
        prefs.edit().remove(key(uri)).apply()
    }

    private fun write(uri: String, overlays: List<Overlay>) {
        val arr = JSONArray()
        overlays.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(key(uri), arr.toString()).apply()
    }

    private fun key(uri: String) = "doc:" + uri.hashCode().toString(16)

    private fun toJson(o: Overlay): JSONObject = JSONObject()
        .put("id", o.id)
        .put("page", o.page)
        .put("kind", o.kind.name)
        .put("color", o.color)
        .put("width", o.width.toDouble())
        .put("left", o.left.toDouble())
        .put("top", o.top.toDouble())
        .put("right", o.right.toDouble())
        .put("bottom", o.bottom.toDouble())
        .put("text", o.text)
        .put("points", JSONArray().apply { o.points.forEach { put(it.toDouble()) } })

    private fun fromJson(o: JSONObject): Overlay {
        val pts = mutableListOf<Float>()
        o.optJSONArray("points")?.let { arr ->
            for (i in 0 until arr.length()) pts.add(arr.optDouble(i, 0.0).toFloat())
        }
        return Overlay(
            id = o.optString("id"),
            page = o.optInt("page", 0),
            kind = runCatching { AnnKind.valueOf(o.optString("kind", "PEN")) }.getOrDefault(AnnKind.PEN),
            color = o.optLong("color", 0xFFE53935),
            width = o.optDouble("width", 0.02).toFloat(),
            left = o.optDouble("left", 0.0).toFloat(),
            top = o.optDouble("top", 0.0).toFloat(),
            right = o.optDouble("right", 0.0).toFloat(),
            bottom = o.optDouble("bottom", 0.0).toFloat(),
            points = pts,
            text = o.optString("text", ""),
        )
    }
}
