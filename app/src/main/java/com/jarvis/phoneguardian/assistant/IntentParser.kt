package com.jarvis.phoneguardian.assistant

import java.util.Locale

sealed interface AssistantIntent {
    data class SearchFiles(val query: String) : AssistantIntent
    data object StorageSummary : AssistantIntent
    data object OrganizeFiles : AssistantIntent
    data object FindDuplicates : AssistantIntent
    data class LargeFiles(val minimumBytes: Long) : AssistantIntent
    data class OldFiles(val beforeMillis: Long) : AssistantIntent
    data object BackupDocuments : AssistantIntent
    data object BackupEverything : AssistantIntent
    data class OpenApp(val appName: String) : AssistantIntent
    data object Back : AssistantIntent
    data object Home : AssistantIntent
    data object ScrollUp : AssistantIntent
    data object ScrollDown : AssistantIntent
    data object ClickFirstResult : AssistantIntent
    data class VolumeDelta(val percent: Int) : AssistantIntent
    data object Mute : AssistantIntent
    data object MediaPlay : AssistantIntent
    data object MediaPause : AssistantIntent
    data class MediaSeek(val seconds: Int) : AssistantIntent
    data class CallContact(val name: String) : AssistantIntent
    data object DeleteDuplicates : AssistantIntent
    data object Unknown : AssistantIntent
}

data class ParsedCommand(val intent: AssistantIntent, val normalizedText: String)

/** A deliberately finite command grammar. Natural language is an input convenience, not authority. */
object IntentParser {
    fun parse(input: String, now: Long = System.currentTimeMillis()): ParsedCommand {
        val text = input.trim().lowercase(Locale.ROOT).removePrefix("jarvis").trim().trim(',', ':')
        val intent: AssistantIntent = when {
            text.contains("storage") && (text.contains("using") || text.contains("space") || text.contains("most")) -> AssistantIntent.StorageSummary
            text.contains("delete") && text.contains("duplicate") -> AssistantIntent.DeleteDuplicates
            text.contains("organize") || text.contains("organise") -> AssistantIntent.OrganizeFiles
            text.contains("duplicate") -> AssistantIntent.FindDuplicates
            text.contains("backup") && (text.contains("entire") || text.contains("everything") || text.contains("full")) -> AssistantIntent.BackupEverything
            text.contains("backup") && text.contains("document") -> AssistantIntent.BackupDocuments
            text.contains("backup") -> AssistantIntent.BackupEverything
            text.contains("larger than") || text.contains("larger then") -> AssistantIntent.LargeFiles(parseSize(text))
            text.contains("older than") -> AssistantIntent.OldFiles(parseAge(text, now))
            text == "back" || text.contains("go back") -> AssistantIntent.Back
            text == "home" || text.contains("go home") -> AssistantIntent.Home
            text.contains("scroll down") -> AssistantIntent.ScrollDown
            text.contains("scroll up") -> AssistantIntent.ScrollUp
            text.contains("click the first") || text.contains("click first") -> AssistantIntent.ClickFirstResult
            text.contains("mute") -> AssistantIntent.Mute
            text.contains("increase volume") || text.contains("volume up") -> AssistantIntent.VolumeDelta(parsePercent(text, 10))
            text.contains("decrease volume") || text.contains("volume down") -> AssistantIntent.VolumeDelta(-parsePercent(text, 10))
            text == "play" || text.contains("play music") -> AssistantIntent.MediaPlay
            text == "pause" || text.contains("pause music") -> AssistantIntent.MediaPause
            text.contains("forward") -> AssistantIntent.MediaSeek(parseSeconds(text, 30))
            text.contains("rewind") -> AssistantIntent.MediaSeek(-parseSeconds(text, 30))
            text.startsWith("open ") -> AssistantIntent.OpenApp(text.removePrefix("open ").trim())
            text.startsWith("send me to ") -> AssistantIntent.OpenApp(text.removePrefix("send me to ").trim())
            text.startsWith("call ") -> AssistantIntent.CallContact(text.removePrefix("call ").trim())
            text.isNotBlank() -> AssistantIntent.SearchFiles(text.removePrefix("find ").removePrefix("show me ").trim())
            else -> AssistantIntent.Unknown
        }
        return ParsedCommand(intent, text)
    }

    private fun parseSize(text: String): Long {
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*(kb|mb|gb|tb)?").find(text.substringAfter("larger than", text))
        val value = match?.groupValues?.get(1)?.toDoubleOrNull() ?: 500.0
        val unit = match?.groupValues?.get(2).orEmpty()
        val multiplier = when (unit) { "kb" -> 1L shl 10; "mb" -> 1L shl 20; "gb" -> 1L shl 30; "tb" -> 1L shl 40; else -> 1L shl 20 }
        return (value * multiplier).toLong()
    }

    private fun parseAge(text: String, now: Long): Long {
        val days = Regex("(\\d+)").find(text.substringAfter("older than", ""))?.groupValues?.get(1)?.toLongOrNull() ?: 90L
        return now - days * 86_400_000L
    }

    private fun parsePercent(text: String, fallback: Int): Int = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: fallback
    private fun parseSeconds(text: String, fallback: Int): Int = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 3600) ?: fallback
}
