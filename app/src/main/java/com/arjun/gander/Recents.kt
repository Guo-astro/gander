package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently opened files, limited to URIs whose read grant was persisted
 * (picker selections). Stored as JSON in SharedPreferences.
 *
 * The grant goes when the entry does, whether it is removed or pushed off the
 * end, since nothing else opens a file by it.
 */
object Recents {

    data class Entry(val uri: String, val name: String, val time: Long)

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 25

    fun all(context: Context): List<Entry> {
        val granted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return load(context).filter { it.uri in granted }
    }

    fun add(context: Context, uri: Uri, name: String) {
        val key = uri.toString()
        val items = load(context).filter { it.uri != key }.toMutableList()
        items.add(0, Entry(key, name, System.currentTimeMillis()))
        save(context, items.take(MAX))
        items.drop(MAX).forEach { release(context, it.uri) }
    }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
        release(context, uri)
    }

    /**
     * Gives back the grant on [uri], write as well as read: a build that could rename a picked
     * file took both, and giving back only the read half left the other behind, where the list,
     * which shows read grants only, could never reach it again. Android throws for a grant that
     * is already gone, and that is the same outcome.
     */
    private fun release(context: Context, uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun load(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]")!!
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("uri"), o.getString("name"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("name", e.name)
                    .put("time", e.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, arr.toString()) }
    }
}
