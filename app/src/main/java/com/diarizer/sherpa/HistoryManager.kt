package com.diarizer.sherpa

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val fileName: String,
    val speakerText: String,
    val fullText: String,
)

object HistoryManager {
    private const val KEY = "transcription_history"
    private const val MAX_ENTRIES = 50
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    fun load(prefs: SharedPreferences): List<HistoryEntry> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val cutoff = System.currentTimeMillis() - TTL_MS
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val ts = o.getLong("timestamp")
                if (ts < cutoff) null
                else HistoryEntry(
                    id = o.getString("id"),
                    timestamp = ts,
                    fileName = o.getString("fileName"),
                    speakerText = o.getString("speakerText"),
                    fullText = o.getString("fullText"),
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun add(prefs: SharedPreferences, entry: HistoryEntry) {
        val current = load(prefs).toMutableList()
        current.removeAll { it.id == entry.id }
        current.add(0, entry)
        save(prefs, current.take(MAX_ENTRIES))
    }

    fun delete(prefs: SharedPreferences, id: String) {
        save(prefs, load(prefs).filter { it.id != id })
    }

    private fun save(prefs: SharedPreferences, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject()
                .put("id", e.id)
                .put("timestamp", e.timestamp)
                .put("fileName", e.fileName)
                .put("speakerText", e.speakerText)
                .put("fullText", e.fullText))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
