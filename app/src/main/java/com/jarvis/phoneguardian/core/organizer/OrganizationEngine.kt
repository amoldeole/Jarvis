package com.jarvis.phoneguardian.core.organizer

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
    suspend fun buildPreview(): List<OrganizationSuggestion> = withContext(Dispatchers.Default) {
        val protections = database.protectionDao().getAll()
        database.fileDao().getAll().asSequence()
            .filter { file -> !file.isProtected && !FolderSafety.isLikelyUserCreated(file.displayPath) && protections.none { it.state == "protected" && file.displayPath.startsWith(it.key) } }
            .filter { it.mediaType != MediaTypes.OTHER }
            .map { file ->
                val (label, destination) = FileClassifier.destinationFor(file)
                OrganizationSuggestion(
                    source = file,
                    destinationLabel = label,
                    destinationPath = destination,
                    reason = reasonFor(file, label)
                )
            }.toList()
    }

    private fun reasonFor(file: FileEntity, destination: String): String = when {
        file.displayPath.contains("screenshot", ignoreCase = true) -> "The folder name identifies a screenshot."
        file.displayPath.contains("whatsapp", ignoreCase = true) -> "The source folder identifies WhatsApp media."
        file.displayPath.contains("camera", ignoreCase = true) -> "The source folder identifies camera media."
        file.mediaType == MediaTypes.DOCUMENT -> "The MIME type and file signature identify a document."
        else -> "The file type and safe metadata match this category."
    }
}
