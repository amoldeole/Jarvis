package com.jarvis.phoneguardian.core.organizer

import com.jarvis.phoneguardian.core.ai.AIProvider
import com.jarvis.phoneguardian.core.ai.DisabledAIProvider
import com.jarvis.phoneguardian.core.database.AppDatabase
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.MediaTypes
import com.jarvis.phoneguardian.core.model.OrganizationSuggestion
import com.jarvis.phoneguardian.core.storage.FileClassifier
import com.jarvis.phoneguardian.core.storage.FolderSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds a reviewable plan only. It never touches a file. */
class OrganizationEngine(private val database: AppDatabase) {
    suspend fun buildPreview(aiProvider: AIProvider = DisabledAIProvider()): List<OrganizationSuggestion> = withContext(Dispatchers.Default) {
        val protections = database.protectionDao().getAll()
        val candidates = database.fileDao().getAll()
            .filter { file -> !file.isProtected && !FolderSafety.isLikelyUserCreated(file.displayPath) && protections.none { it.state == "protected" && file.displayPath.startsWith(it.key) } }
            .filter { it.mediaType != MediaTypes.OTHER }
        val aiCategories = aiProvider.classify(candidates)
        candidates.map { file ->
            val (ruleLabel, ruleDestination) = FileClassifier.destinationFor(file)
            val aiLabel = aiCategories[file.uri]
            val label = if (aiLabel.isNullOrBlank() || aiLabel == ruleLabel) ruleLabel else "Documents/$aiLabel"
            val destination = if (label == ruleLabel) ruleDestination else "Phone/$label/${file.fileName}"
            OrganizationSuggestion(
                source = file,
                destinationLabel = label,
                destinationPath = destination,
                reason = if (aiLabel != null && aiLabel != ruleLabel) "On-device AI suggested $aiLabel from the filename and safe metadata; review before approval." else reasonFor(file, label)
            )
        }
    }

    private fun reasonFor(file: FileEntity, destination: String): String = when {
        file.displayPath.contains("screenshot", ignoreCase = true) -> "The folder name identifies a screenshot."
        file.displayPath.contains("whatsapp", ignoreCase = true) -> "The source folder identifies WhatsApp media."
        file.displayPath.contains("camera", ignoreCase = true) -> "The source folder identifies camera media."
        file.mediaType == MediaTypes.DOCUMENT -> "The MIME type and file signature identify a document."
        else -> "The file type and safe metadata match this category."
    }
}
