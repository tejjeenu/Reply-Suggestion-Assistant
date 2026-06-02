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
    val tone: String,
    val contextText: String
)

object SuggestionApi {
    suspend fun suggestReplies(
        endpoint: String,
        request: SuggestionRequest
    ): List<String> = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) {
            return@withContext localSuggestions(request.contextText)
        }

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val payload = JSONObject()
                .put("task", "suggest_reply")
                .put("source_app", request.sourceApp)
                .put("tone", request.tone)
                .put("context_text", request.contextText)

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

            parseSuggestions(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSuggestions(responseBody: String): List<String> {
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
            localSuggestions("")
        }
    }

    private fun localSuggestions(contextText: String): List<String> {
        val lastUsefulLine = contextText
            .lines()
            .map { it.trim() }
            .lastOrNull { it.length >= 8 }

        return if (lastUsefulLine == null) {
            listOf(
                "Sounds good, tell me more.",
                "That makes sense. What happened next?",
                "I get you. How are you feeling about it?"
            )
        } else {
            listOf(
                "Haha fair, I get what you mean.",
                "That sounds interesting. Tell me more.",
                "I like that. What made you think of it?"
            )
        }
    }
}
