package com.jarvis.phoneguardian.core.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jarvis.phoneguardian.core.database.AppDatabase
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.OperationEntity
import com.jarvis.phoneguardian.core.model.TrashEntity
import com.jarvis.phoneguardian.core.storage.FolderSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

sealed class OperationResult {
    data class Success(val operationId: Long, val destination: String) : OperationResult()
    data class Failure(val operationId: Long?, val message: String) : OperationResult()
}

/**
 * Copy-verify-delete engine. The source is never removed until the destination has been
 * closed and verified. For user files, SAF is the authority and can refuse a delete safely.
 */
class FileOperationEngine(
    private val context: Context,
    private val database: AppDatabase
) {
    private val resolver = context.contentResolver

    suspend fun moveToSafFolder(file: FileEntity, destinationTree: Uri, destinationSubfolder: String? = null): OperationResult = withContext(Dispatchers.IO) {
        if (file.isProtected || FolderSafety.isLikelyUserCreated(file.displayPath)) {
            return@withContext OperationResult.Failure(null, "This file is inside a protected user folder and was not changed.")
        }
        val opId = database.operationDao().insert(
            OperationEntity(
                kind = "move",
                sourceUri = file.uri,
                sourcePath = file.displayPath,
                destinationPath = destinationTree.toString(),
                bytes = file.size,
                status = "pending"
            )
        )
        database.operationDao().updateStatus(opId, "running")
        try {
            var folder = DocumentFile.fromTreeUri(context, destinationTree)
                ?: error("Android could not open the selected destination folder.")
            require(folder.canWrite()) { "The selected folder is read-only. Choose a writable folder." }
            destinationSubfolder.orEmpty().split('/').filter { it.isNotBlank() }.forEach { segment ->
                folder = folder.findFile(segment)?.takeIf { it.isDirectory } ?: folder.createDirectory(segment)
                    ?: error("Android could not create the $segment organization folder.")
            }
            val targetName = uniqueName(folder, file.fileName)
            val target = folder.createFile(file.mimeType.ifBlank { "application/octet-stream" }, targetName)
                ?: error("Android could not create the destination file.")
            database.operationDao().setDestination(opId, target.uri.toString(), "${destinationSubfolder.orEmpty()}/$targetName")
            copyAndVerify(Uri.parse(file.uri), target.uri, file.size)
            if (!resolver.delete(Uri.parse(file.uri), null, null).let { it > 0 }) {
                // A copy without source deletion is safer than pretending this was a move.
                resolver.delete(target.uri, null, null)
                error("The destination was verified, but Android refused to remove the original. No data was lost.")
            }
            database.operationDao().updateStatus(opId, "completed", completedAt = System.currentTimeMillis())
            database.fileDao().delete(file.uri)
            OperationResult.Success(opId, target.uri.toString())
        } catch (error: Throwable) {
            database.operationDao().updateStatus(opId, "failed", error.userMessage(), System.currentTimeMillis())
            OperationResult.Failure(opId, error.userMessage())
        }
    }

    suspend fun moveToTrash(file: FileEntity, keepDays: Int = 30): OperationResult = withContext(Dispatchers.IO) {
        if (file.isProtected || FolderSafety.isLikelyUserCreated(file.displayPath)) {
            return@withContext OperationResult.Failure(null, "This file is inside a protected user folder and was not changed.")
        }
        val trashDir = File(context.filesDir, "trash").apply { mkdirs() }
        val opId = database.operationDao().insert(
            OperationEntity(
                kind = "trash",
                sourceUri = file.uri,
                sourcePath = file.displayPath,
                destinationPath = trashDir.absolutePath,
                bytes = file.size
            )
        )
        database.operationDao().updateStatus(opId, "running")
        val destination = File(trashDir, "${opId}_${safeFileName(file.fileName)}")
        try {
            resolver.openInputStream(Uri.parse(file.uri))?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Android could not read the selected file.")
            require(destination.length() == file.size) { "Trash verification failed; the original was kept." }
            require(resolver.delete(Uri.parse(file.uri), null, null) > 0) {
                "Android refused to remove the original. The file was not deleted."
            }
            database.trashDao().insert(
                TrashEntity(
                    originalUri = file.uri,
                    originalPath = file.displayPath,
                    trashUri = Uri.fromFile(destination).toString(),
                    fileName = file.fileName,
                    size = file.size,
                    mediaType = file.mediaType,
                    expiresAt = if (keepDays > 0) System.currentTimeMillis() + keepDays * 86_400_000L else null
                )
            )
            database.operationDao().updateStatus(opId, "completed", completedAt = System.currentTimeMillis())
            database.fileDao().delete(file.uri)
            OperationResult.Success(opId, destination.absolutePath)
        } catch (error: Throwable) {
            destination.delete()
            database.operationDao().updateStatus(opId, "failed", error.userMessage(), System.currentTimeMillis())
            OperationResult.Failure(opId, error.userMessage())
        }
    }

    suspend fun restoreFromTrash(item: TrashEntity, destinationTree: Uri): OperationResult = withContext(Dispatchers.IO) {
        val source = File(Uri.parse(item.trashUri).path.orEmpty())
        val opId = database.operationDao().insert(
            OperationEntity(
                kind = "restore",
                sourceUri = item.trashUri,
                sourcePath = item.originalPath,
                destinationPath = destinationTree.toString(),
                bytes = item.size
            )
        )
        try {
            require(source.exists()) { "This Trash item is no longer available." }
            val folder = DocumentFile.fromTreeUri(context, destinationTree)
                ?: error("Android could not open the restore folder.")
            val target = folder.createFile("application/octet-stream", uniqueName(folder, item.fileName))
                ?: error("Android could not create the restore file.")
            source.inputStream().use { input -> resolver.openOutputStream(target.uri, "w")!!.use { input.copyTo(it) } }
            require(target.length() == source.length()) { "Restore verification failed." }
            source.delete()
            database.trashDao().delete(item.id)
            database.operationDao().updateStatus(opId, "completed", completedAt = System.currentTimeMillis())
            OperationResult.Success(opId, target.uri.toString())
        } catch (error: Throwable) {
            database.operationDao().updateStatus(opId, "failed", error.userMessage(), System.currentTimeMillis())
            OperationResult.Failure(opId, error.userMessage())
        }
    }

    private fun uniqueName(folder: DocumentFile, requested: String): String {
        if (folder.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (folder.findFile("$base ($index)$extension") != null) index++
        return "$base ($index)$extension"
    }

    private fun copyAndVerify(source: Uri, destination: Uri, expectedSize: Long) {
        resolver.openInputStream(source)?.use { input ->
            resolver.openOutputStream(destination, "w")?.use { output -> input.copyTo(output) }
                ?: error("Android could not open the destination for writing.")
        } ?: error("Android could not open the source file.")
        val actual = resolver.openInputStream(destination)?.use { countBytes(it) } ?: -1
        require(actual == expectedSize) { "Destination verification failed; the original was kept." }
    }

    private fun countBytes(input: InputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) if (read > 0) total += read
        return total
    }

    private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)

    private fun Throwable.userMessage(): String = when (this) {
        is SecurityException -> "Android denied access. Re-select the folder and grant access when prompted."
        else -> message?.takeIf { it.isNotBlank() } ?: "The operation failed. Your original file was kept."
    }
}
