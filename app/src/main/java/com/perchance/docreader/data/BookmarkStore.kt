package com.perchance.docreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val page: Int, val label: String, val createdAt: Long)

/** Per-document page bookmarks (page index + optional label), kept in SharedPreferences. */
class BookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences("docreader_bookmarks", Context.MODE_PRIVATE)

    fun list(uri: String): List<Bookmark> {
        val raw = prefs.getString(key(uri), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(
                    page = o.optInt("page", 0),
                    label = o.optString("label", ""),
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList()).sortedBy { it.page }
    }

    fun has(uri: String, page: Int): Boolean = list(uri).any { it.page == page }

    /** Adds a bookmark, or removes it when the page is already bookmarked. Returns true if added. */
    fun toggle(uri: String, page: Int, label: String): Boolean {
        val current = list(uri)
        val existing = current.firstOrNull { it.page == page }
        return if (existing != null) {
            write(uri, current.filterNot { it.page == page })
            false
        } else {
            write(uri, current + Bookmark(page, label, System.currentTimeMillis()))
            true
        }
    }

    fun remove(uri: String, page: Int) = write(uri, list(uri).filterNot { it.page == page })

    private fun write(uri: String, marks: List<Bookmark>) {
        val arr = JSONArray()
        marks.forEach { b ->
            arr.put(
                JSONObject()
                    .put("page", b.page)
                    .put("label", b.label)
                    .put("createdAt", b.createdAt)
            )
        }
        prefs.edit().putString(key(uri), arr.toString()).apply()
    }

    private fun key(uri: String) = "doc:" + uri.hashCode().toString(16)
}
