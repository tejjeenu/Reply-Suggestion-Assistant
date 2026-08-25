package com.replyassistant.chat

data class ChatHistoryContext(
    val fileName: String,
    val participants: List<String>,
    val messageCount: Int,
    val historyExcerpt: String
)

object ChatHistoryParser {
    private const val MAX_HISTORY_CHARS = 60_000
    private const val EARLY_MESSAGE_COUNT = 80

    private val androidLine = Regex(
        """^\s*[\u200e\u200f]?\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4},?\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:[ap]\.?m\.?)?\s+-\s+([^:]+):\s?(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val iosLine = Regex(
        """^\s*[\u200e\u200f]?\[[^]]+]\s*([^:]+):\s?(.*)$"""
    )
    private val genericLine = Regex(
        """^\s*([^:\n]{1,80}):\s+(.+)$"""
    )

    fun parse(fileName: String, rawText: String): ChatHistoryContext {
        val messages = mutableListOf<ChatMessage>()
        var current: ChatMessage? = null

        rawText.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val match = androidLine.matchEntire(line)
                ?: iosLine.matchEntire(line)
                ?: genericLine.matchEntire(line)
            if (match != null) {
                current?.let(messages::add)
                current = ChatMessage(
                    sender = match.groupValues[1].trim(),
                    text = match.groupValues[2].trim()
                )
            } else if (current != null && line.isNotBlank()) {
                current = current!!.copy(text = "${current!!.text}\n${line.trim()}")
            }
        }
        current?.let(messages::add)

        if (messages.isEmpty()) {
            throw IllegalArgumentException(
                "No messages were found. Choose a text transcript with lines such as 'Alex: hello'."
            )
        }

        val participants = messages.map { it.sender }.distinct()
        val excerpt = buildExcerpt(messages)
        return ChatHistoryContext(
            fileName = fileName.ifBlank { "Conversation history.txt" },
            participants = participants,
            messageCount = messages.size,
            historyExcerpt = excerpt
        )
    }

    private fun buildExcerpt(messages: List<ChatMessage>): String {
        val selected = if (messages.size <= EARLY_MESSAGE_COUNT) {
            messages
        } else {
            val early = messages.take(EARLY_MESSAGE_COUNT)
            val recent = messages.drop(EARLY_MESSAGE_COUNT)
            early + ChatMessage("System", "[older messages omitted]") + recent
        }

        val lines = selected.map { message -> "${message.sender}: ${message.text}" }
        if (lines.sumOf { it.length + 1 } <= MAX_HISTORY_CHARS) {
            return lines.joinToString("\n")
        }

        val earlyLines = lines.take(EARLY_MESSAGE_COUNT)
        val budgetForRecent = MAX_HISTORY_CHARS - earlyLines.sumOf { it.length + 1 } - 32
        val recentLines = mutableListOf<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (line in earlyLines || used + line.length + 1 > budgetForRecent) continue
            recentLines.add(line)
            used += line.length + 1
        }

        return (earlyLines + "System: [older messages omitted]" + recentLines.asReversed())
            .joinToString("\n")
            .take(MAX_HISTORY_CHARS)
    }

    private data class ChatMessage(val sender: String, val text: String)
}
