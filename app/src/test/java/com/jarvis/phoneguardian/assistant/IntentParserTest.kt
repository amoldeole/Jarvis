package com.jarvis.phoneguardian.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    @Test fun `dangerous command is explicit confirmation intent`() {
        assertTrue(IntentParser.parse("Jarvis, delete all duplicate videos").intent is AssistantIntent.DeleteDuplicates)
    }

    @Test fun `large file command parses units`() {
        val command = IntentParser.parse("show videos larger than 1 GB").intent
        assertEquals(1L shl 30, (command as AssistantIntent.LargeFiles).minimumBytes)
    }

    @Test fun `natural search never becomes an operation`() {
        assertTrue(IntentParser.parse("show me my wedding photos").intent is AssistantIntent.SearchFiles)
    }
}
