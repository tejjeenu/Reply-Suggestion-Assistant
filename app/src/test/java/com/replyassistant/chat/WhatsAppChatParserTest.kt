package com.replyassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryParserTest {
    @Test
    fun parsesAndroidExportAndMultilineMessages() {
        val parsed = ChatHistoryParser.parse(
            "Chat with Alex.txt",
            """
                18/08/2026, 21:04 - Alex: are you still coming?
                18/08/2026, 21:05 - Jamie: yep
                just leaving now
            """.trimIndent()
        )

        assertEquals(listOf("Alex", "Jamie"), parsed.participants)
        assertEquals(2, parsed.messageCount)
        assertTrue(parsed.historyExcerpt.contains("Jamie: yep\njust leaving now"))
    }

    @Test
    fun parsesIosExport() {
        val parsed = ChatHistoryParser.parse(
            "chat.txt",
            "[18/08/2026, 9:04:12 PM] Alex: hello\n[18/08/2026, 9:05:01 PM] Jamie: hey"
        )

        assertEquals(2, parsed.messageCount)
        assertEquals(listOf("Alex", "Jamie"), parsed.participants)
    }

    @Test
    fun parsesGenericMessagingTranscript() {
        val parsed = ChatHistoryParser.parse(
            "signal-conversation.txt",
            "Alex: Are you free later?\nJamie: after seven works\nAlex: perfect"
        )

        assertEquals(3, parsed.messageCount)
        assertEquals(listOf("Alex", "Jamie"), parsed.participants)
        assertTrue(parsed.historyExcerpt.contains("Jamie: after seven works"))
    }
}
