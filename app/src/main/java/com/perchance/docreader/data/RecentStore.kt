package com.perchance.docreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single entry in the "Recent files" list. */
data class RecentFile(
    val uri: String,
    val name: String,
    val size: Long,
    val lastOpened: Long,
    val starred: Boolean = false,
)

/**
 * Tiny persistence layer for the recent-files list.
 * Uses SharedPreferences + JSON so the app has zero storage dependencies.
 * Swap for DataStore/Room later if the list grows.
 */
class RecentStore(context: Context) {

    private val prefs = context.getSharedPreferences("docreader_recents", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> = read().sortedByDescending { it.lastOpened }

    fun starred(): List<RecentFile> = list().filter { it.starred }

    fun add(file: RecentFile) {
        val current = read().filterNot { it.uri == file.uri }
        val merged = listOf(file) + current
        write(merged.take(MAX_ENTRIES))
    }

    fun toggleStar(uri: String) {
        write(read().map { if (it.uri == uri) it.copy(starred = !it.starred) else it })
    }

    fun remove(uri: String) {
        write(read().filterNot { it.uri == uri })
    }

    private fun read(): List<RecentFile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(
                    uri = o.getString("uri"),
                    name = o.optString("name", "document.pdf"),
                    size = o.optLong("size", 0L),
                    lastOpened = o.optLong("lastOpened", 0L),
                    starred = o.optBoolean("starred", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(files: List<RecentFile>) {
        val arr = JSONArray()
        files.forEach { f ->
            arr.put(
                JSONObject()
                    .put("uri", f.uri)
                    .put("name", f.name)
                    .put("size", f.size)
                    .put("lastOpened", f.lastOpened)
                    .put("starred", f.starred)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "files"
        private const val MAX_ENTRIES = 100
    }
}

/** Reads the display name + size for a content Uri picked from the system file picker. */
fun queryFileMeta(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "document.pdf"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    }
    return name to size
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
        else -> String.format(Locale.US, "%.2f KB", kb)
    }
}

fun formatDate(millis: Long): String =
    SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date(millis))
