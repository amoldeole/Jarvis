package com.jarvis.phoneguardian.core.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.GsonBuilder
import com.jarvis.phoneguardian.core.database.AppDatabase
import com.jarvis.phoneguardian.core.model.BackupManifestEntity
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.storage.FileClassifier
import com.jarvis.phoneguardian.core.storage.StorageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import kotlin.coroutines.coroutineContext

interface BackupProvider {
    val id: String
    suspend fun backup(files: List<FileEntity>, destination: Uri, onProgress: suspend (BackupProgress) -> Unit): BackupResult
}

data class BackupProgress(val completed: Int, val total: Int, val bytesCopied: Long, val bytesSkipped: Long, val current: String = "")
data class BackupResult(val copied: Int, val skipped: Int, val failed: List<String>, val verifiedBytes: Long)

/** SAF backup provider. It is offline, resumable at file granularity and manifest-driven. */
class LocalSafBackupProvider(
    private val context: Context,
    private val database: AppDatabase
) : BackupProvider {
    override val id: String = "local_saf"
    private val resolver = context.contentResolver

    override suspend fun backup(
        files: List<FileEntity>,
        destination: Uri,
        onProgress: suspend (BackupProgress) -> Unit
    ): BackupResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, destination)
            ?: return@withContext BackupResult(0, 0, listOf("The selected backup folder is unavailable."), 0)
        require(root.canWrite()) { "The selected backup folder is read-only." }
        val phoneRoot = root.findFile("Phone Guardian") ?: root.createDirectory("Phone Guardian")
            ?: error("Could not create the Phone Guardian backup folder.")
        val deviceName = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9 _-]"), "").take(80).ifBlank { "Android device" }
        val deviceRoot = phoneRoot.findFile(deviceName)
            ?: phoneRoot.createDirectory(deviceName)
            ?: error("Could not create the device backup folder.")

        var copied = 0
        var skipped = 0
        var verifiedBytes = 0L
        val failures = mutableListOf<String>()
        for ((index, file) in files.withIndex()) {
            coroutineContext.ensureActive()
            try {
                val existingHash = database.backupDao().find(file.uri)
                val hash = file.sha256 ?: resolver.openInputStream(Uri.parse(file.uri))?.use { input -> StorageScanner.sha256(input) }
                    ?: error("File cannot be read by Android.")
                if (existingHash?.sha256 == hash && existingHash.size == file.size && existingHash.modifiedTime == file.modifiedTime) {
                    skipped++
                    onProgress(BackupProgress(index + 1, files.size, verifiedBytes, file.size, file.fileName))
                    continue
                }
                val category = category(file)
                val categoryDir = deviceRoot.findFile(category) ?: deviceRoot.createDirectory(category)
                    ?: error("Could not create the $category backup folder.")
                val targetName = uniqueName(categoryDir, file.fileName)
                val target = categoryDir.createFile(file.mimeType, targetName)
                    ?: error("Could not create the backup file.")
                resolver.openInputStream(Uri.parse(file.uri))?.use { input ->
                    resolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) }
                        ?: error("Could not write the backup file.")
                } ?: error("Could not read the source file.")
                val copiedHash = resolver.openInputStream(target.uri)?.use { input -> StorageScanner.sha256(input) }
                require(copiedHash == hash) { "Checksum verification failed." }
                database.backupDao().upsert(
                    BackupManifestEntity(
                        originalUri = file.uri,
                        originalPath = file.displayPath,
                        size = file.size,
                        sha256 = hash,
                        modifiedTime = file.modifiedTime,
                        backupLocation = target.uri.toString(),
                        verified = true
                    )
                )
                copied++
                verifiedBytes += file.size
            } catch (error: Throwable) {
                failures += "${file.displayPath}: ${error.message ?: "unknown error"}"
            }
            onProgress(BackupProgress(index + 1, files.size, verifiedBytes, 0, file.fileName))
        }
        writeManifest(deviceRoot, files)
        BackupResult(copied, skipped, failures, verifiedBytes)
    }

    private fun writeManifest(root: DocumentFile, files: List<FileEntity>) {
        val manifest = root.findFile("manifest.json") ?: root.createFile("application/json", "manifest.json") ?: return
        val content = GsonBuilder().setPrettyPrinting().create().toJson(
            files.map { mapOf("filename" to it.fileName, "originalPath" to it.displayPath, "size" to it.size, "sha256" to it.sha256, "modifiedTime" to it.modifiedTime) }
        )
        resolver.openOutputStream(manifest.uri, "wt")?.use { output -> OutputStreamWriter(output).use { it.write(content) } }
    }

    private fun category(file: FileEntity): String = FileClassifier.destinationFor(file).first.substringBefore('/')

    private fun uniqueName(folder: DocumentFile, requested: String): String {
        if (folder.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (folder.findFile("$base ($index)$extension") != null) index++
        return "$base ($index)$extension"
    }
}

/** Cloud is intentionally an explicit provider boundary; no credentials or bytes are sent by default. */
class DisabledCloudBackupProvider : BackupProvider {
    override val id: String = "cloud_disabled"
    override suspend fun backup(files: List<FileEntity>, destination: Uri, onProgress: suspend (BackupProgress) -> Unit): BackupResult =
        BackupResult(0, 0, listOf("Cloud backup is disabled. Connect a provider explicitly in Settings."), 0)
}
