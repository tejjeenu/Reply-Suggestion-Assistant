package com.replyassistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class SuggestionRequest(
    val sourceApp: String,
    val responseMode: String,
    val tone: String,
    val contextText: String,
    val scannedHistory: String = "",
    val chatHistory: String = "",
    val chatParticipants: List<String> = emptyList(),
    val userName: String = "",
    val conversationName: String = "",
    val automaticHistory: String = "",
    val images: List<SuggestionImage> = emptyList()
)

data class SuggestionImage(
    val mimeType: String,
    val base64: String,
    val role: String = "history",
    val title: String = ""
)

object SuggestionApi {
    fun normalizeSuggestEndpoint(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isBlank()) return ""

        val suffixStart = listOf(trimmed.indexOf('?'), trimmed.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull() ?: trimmed.length
        val base = trimmed.substring(0, suffixStart).trimEnd('/')
        val suffix = trimmed.substring(suffixStart)

        return if (base.endsWith("/suggest")) {
            "$base$suffix"
        } else {
            "$base/suggest$suffix"
        }
    }

    suspend fun suggestReplies(
        endpoint: String,
        request: SuggestionRequest
    ): List<String> = withContext(Dispatchers.IO) {
        val normalizedEndpoint = normalizeSuggestEndpoint(endpoint)
        if (normalizedEndpoint.isBlank()) {
            return@withContext localSuggestions(request.responseMode)
        }

        val connection = URL(normalizedEndpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 150_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val payload = JSONObject()
                .put("task", "suggest_reply")
                .put("source_app", request.sourceApp)
                .put("response_mode", request.responseMode)
                .put("tone", request.tone)
                .put("context_text", request.contextText)
                .put("scanned_history", request.scannedHistory)
                .put("chat_history", request.chatHistory)
                .put("chat_participants", JSONArray(request.chatParticipants))
                .put("user_name", request.userName)
                .put("conversation_name", request.conversationName)
                .put("automatic_history", request.automaticHistory)

            if (request.images.isNotEmpty()) {
                val images = JSONArray()
                request.images.take(12).forEach { image ->
                    images.put(
                        JSONObject()
                            .put("mime_type", image.mimeType)
                            .put("base64", image.base64)
                            .put("role", image.role)
                            .put("title", image.title)
                    )
                }
                payload.put("images", images)
            }

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw IOException("Backend returned HTTP $responseCode: $responseBody")
            }

            parseSuggestions(responseBody, request.responseMode)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSuggestions(responseBody: String, responseMode: String): List<String> {
        return runCatching {
            val json = JSONObject(responseBody)
            val array = when {
                json.has("suggestions") -> json.getJSONArray("suggestions")
                json.has("replies") -> json.getJSONArray("replies")
                else -> JSONArray()
            }

            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }.getOrElse {
            responseBody
                .lines()
                .map { it.trim().trimStart('-', '*', '1', '2', '3', '4', '5', '.', ')') }
                .filter { it.length > 2 }
                .take(5)
        }.ifEmpty {
            localSuggestions(responseMode)
        }
    }

    private fun localSuggestions(responseMode: String): List<String> {
        return when (responseMode.trim().lowercase()) {
            "flirty" -> listOf(
                "okay, that was dangerously charming 😏",
                "keep talking like that and i might get attached",
                "bold of you to be this cute in my messages"
            )
            "funny" -> listOf(
                "plot twist: i was pretending to know what was happening",
                "fair point, my last brain cell agrees",
                "i'll allow it, but only because that made me laugh"
            )
            "serious" -> listOf(
                "I hear you. Let’s talk it through properly.",
                "Thanks for being honest with me. I want to understand.",
                "This matters to me, so I’d rather be direct about it."
            )
            "supportive" -> listOf(
                "I’m here with you. You don’t have to handle it alone.",
                "That sounds really hard. Want to talk about what happened?",
                "Take your time—I’m listening whenever you’re ready."
            )
            "professional" -> listOf(
                "Thanks for the update. I’ll review it and follow up shortly.",
                "That works for me. Please send the details when convenient.",
                "Understood. I’ll confirm the next steps by tomorrow."
            )
            else -> listOf(
                "Haha fair, I get what you mean.",
                "That sounds interesting. Tell me more.",
                "I like that. What made you think of it?"
            )
        }
    }
}
