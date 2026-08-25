package com.replyassistant.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AutomaticMemorySummary(
    val snapshotCount: Int,
    val history: String
)

class AutomaticConversationMemory(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun append(
        conversationName: String,
        visibleText: String,
        sourceApp: String = "Messaging app"
    ): AutomaticMemorySummary {
        val name = conversationName.trim()
        val text = visibleText.trim()
        if (name.isBlank() || text.isBlank() || text == "No readable text found.") {
            return load(name)
        }

        val entries = readEntries(name).toMutableList()
        val normalized = normalize(text)
        val isDuplicate = entries.takeLast(8).any { entry ->
            val previous = normalize(entry.text)
            previous == normalized || previous.contains(normalized) || normalized.contains(previous)
        }
        if (!isDuplicate) {
            entries += MemoryEntry(System.currentTimeMillis(), sourceApp.trim().ifBlank { "Messaging app" }, text)
        }

        while (entries.size > MAX_SNAPSHOTS || entries.sumOf { it.text.length } > MAX_STORED_CHARS) {
            entries.removeAt(0)
        }
        writeEntries(name, entries)
        return summary(entries)
    }

    fun load(conversationName: String): AutomaticMemorySummary {
        return summary(readEntries(conversationName.trim()))
    }

    fun clear(conversationName: String) {
        preferences.edit().remove(keyFor(conversationName.trim())).apply()
    }

    private fun readEntries(conversationName: String): List<MemoryEntry> {
        if (conversationName.isBlank()) return emptyList()
        val raw = preferences.getString(keyFor(conversationName), "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val text = item.optString("text").trim()
                    if (text.isNotBlank()) {
                        add(
                            MemoryEntry(
                                capturedAt = item.optLong("captured_at"),
                                sourceApp = item.optString("source_app", "Messaging app"),
                                text = text
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(conversationName: String, entries: List<MemoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("captured_at", entry.capturedAt)
                    .put("source_app", entry.sourceApp)
                    .put("text", entry.text)
            )
        }
        preferences.edit().putString(keyFor(conversationName), array.toString()).apply()
    }

    private fun summary(entries: List<MemoryEntry>): AutomaticMemorySummary {
        val history = entries.joinToString("\n\n") { entry ->
            "[Viewed ${entry.sourceApp} context]\n${entry.text}"
        }.takeLast(MAX_REQUEST_CHARS)
        return AutomaticMemorySummary(entries.size, history)
    }

    private fun keyFor(conversationName: String): String {
        val normalizedName = conversationName.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        return "conversation_${normalizedName}_${conversationName.lowercase().hashCode()}"
    }

    private fun normalize(text: String): String {
        return text.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private data class MemoryEntry(val capturedAt: Long, val sourceApp: String, val text: String)

    companion object {
        // Keep the original preference file name so existing local memories survive the app's
        // transition from WhatsApp-only support to general messaging support.
        private const val PREFS_NAME = "automatic_whatsapp_memory"
        private const val MAX_SNAPSHOTS = 120
        private const val MAX_STORED_CHARS = 100_000
        private const val MAX_REQUEST_CHARS = 80_000
    }
}
