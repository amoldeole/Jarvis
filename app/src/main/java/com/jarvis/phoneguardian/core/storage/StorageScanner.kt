package com.jarvis.phoneguardian.core.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.jarvis.phoneguardian.core.database.AppDatabase
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.ScanPhases
import com.jarvis.phoneguardian.core.model.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Two-tier scanner. MediaStore provides fast, indexed metadata; a user-selected SAF tree
 * provides access to non-media files without requesting the restricted all-files permission.
 */
class StorageScanner(
    private val context: Context,
    private val database: AppDatabase
) {
    private val resolver: ContentResolver = context.contentResolver

    data class Options(
        val selectedTrees: List<Uri> = emptyList(),
        val deepHash: Boolean = false,
        val replaceIndex: Boolean = false
    )

    suspend fun scan(options: Options = Options(), onProgress: suspend (ScanProgress) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val fileDao = database.fileDao()
        val protectedFolders = database.protectionDao().getAll().filter { it.state == "protected" }.map { it.key }
        if (options.replaceIndex) fileDao.clear()
        val runId = database.scanDao().insert(
            com.jarvis.phoneguardian.core.model.ScanRunEntity(phase = ScanPhases.FAST, status = "running")
        )
        var scanned = 0
        var discovered = 0
        val seenPaths = HashSet<String>()
        val batch = ArrayList<FileEntity>(250)
        try {
            try {
                queryMediaStore(protectedFolders) { file ->
                    if (seenPaths.add(file.displayPath.lowercase())) {
                        fileDao.removeStaleUri(file.displayPath, file.uri)
                        batch += file
                        discovered++
                        scanned++
                        if (batch.size >= 250) {
                            fileDao.upsertAll(batch.toList())
                            batch.clear()
                        }
                    }
                }
                onProgress(ScanProgress(ScanPhases.FAST, scanned, discovered, true, "Reading phone index…"))
            } catch (_: SecurityException) {
                onProgress(ScanProgress(ScanPhases.FAST, scanned, discovered, true, "Media access is limited; scanning selected folders…"))
            }
            for (tree in options.selectedTrees) {
                coroutineContext.ensureActive()
                walkTree(tree, options.deepHash, protectedFolders) { file ->
                    if (seenPaths.add(file.displayPath.lowercase())) {
                        fileDao.removeStaleUri(file.displayPath, file.uri)
                        batch += file
                        discovered++
                        scanned++
                        if (batch.size >= 250) {
                            fileDao.upsertAll(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) fileDao.upsertAll(batch)
            database.scanDao().finish(runId, "completed", scanned, discovered, System.currentTimeMillis())
            onProgress(ScanProgress(ScanPhases.FAST, scanned, discovered, false, "Scan complete"))
            discovered
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (batch.isNotEmpty()) fileDao.upsertAll(batch)
            database.scanDao().finish(runId, "paused", scanned, discovered, null)
            throw cancelled
        } catch (error: Throwable) {
            if (batch.isNotEmpty()) fileDao.upsertAll(batch)
            database.scanDao().finish(runId, "failed", scanned, discovered, null, error.userMessage())
            onProgress(ScanProgress(ScanPhases.FAST, scanned, discovered, false, error.userMessage()))
            discovered
        }
    }

    suspend fun deepHash(files: List<FileEntity>, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): List<FileEntity> = withContext(Dispatchers.IO) {
        val result = ArrayList<FileEntity>(files.size)
        val batch = ArrayList<FileEntity>(250)
        for ((index, file) in files.withIndex()) {
            coroutineContext.ensureActive()
            val hash = resolver.openInputStream(Uri.parse(file.uri))?.use { sha256(it) }
            val updated = file.copy(sha256 = hash, lastIndexedAt = System.currentTimeMillis())
            result += updated
            batch += updated
            if (batch.size >= 250) {
                database.fileDao().upsertAll(batch.toList())
                batch.clear()
            }
            if (index % 10 == 0 || index == files.lastIndex) onProgress(index + 1, files.size)
        }
        if (batch.isNotEmpty()) database.fileDao().upsertAll(batch)
        result
    }

    private suspend fun queryMediaStore(protectedFolders: List<String>, consume: suspend (FileEntity) -> Unit) {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        )
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val cursor = resolver.query(uri, projection, null, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC") ?: return
        try {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val addedIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndex(projection.last())
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (name == ".nomedia") continue
                    val contentUri = Uri.withAppendedPath(uri, cursor.getLong(idIndex).toString())
                    val displayParent = cursor.getString(pathIndex).orEmpty().trimEnd('/')
                    val displayPath = if (displayParent.isBlank()) name else "$displayParent/$name"
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    val signature = resolver.openInputStream(contentUri)?.use { it.readAtMost(16) } ?: byteArrayOf()
                    val classification = FileClassifier.classify(name, mime, displayPath, signature)
                    val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) * 1000 else 0L
                    val created = if (addedIndex >= 0) cursor.getLong(addedIndex) * 1000 else null
                    consume(
                        FileEntity(
                            uri = contentUri.toString(),
                            displayPath = displayPath,
                            fileName = name,
                            extension = FileClassifier.extension(name),
                            mimeType = classification.normalizedMime,
                            size = cursor.getLong(sizeIndex).coerceAtLeast(0),
                            createdTime = created,
                            modifiedTime = modified,
                            mediaType = classification.mediaType,
                            parentKey = displayParent,
                            isProtected = protectedFolders.any { displayParent.startsWith(it) } || FolderSafety.isLikelyUserCreated(displayPath)
                        )
                    )
                }
        } finally {
            cursor.close()
        }
    }

    private suspend fun walkTree(treeUri: Uri, deepHash: Boolean, protectedFolders: List<String>, consume: suspend (FileEntity) -> Unit) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        suspend fun visit(directory: DocumentFile, directoryPath: String) {
            for (child in directory.listFiles()) {
                coroutineContext.ensureActive()
                val childPath = "$directoryPath/${child.name.orEmpty()}"
                if (child.isDirectory) {
                    visit(child, childPath)
                    continue
                }
                if (!child.isFile) continue
                val name = child.name ?: continue
                val path = child.uri.toString()
                val signature = resolver.openInputStream(child.uri)?.use { it.readAtMost(16) } ?: byteArrayOf()
                val result = FileClassifier.classify(name, child.type, childPath, signature)
                val parent = directoryPath
                var entity = FileEntity(
                    uri = child.uri.toString(),
                    displayPath = childPath,
                    fileName = name,
                    extension = FileClassifier.extension(name),
                    mimeType = result.normalizedMime,
                    size = child.length().coerceAtLeast(0),
                    createdTime = null,
                    modifiedTime = child.lastModified(),
                    mediaType = result.mediaType,
                    parentKey = parent,
                        isProtected = protectedFolders.any { parent.startsWith(it) } || FolderSafety.isLikelyUserCreated("$parent/$name")
                )
                if (deepHash) {
                    val hash = resolver.openInputStream(child.uri)?.use { sha256(it) }
                    entity = entity.copy(sha256 = hash)
                }
                consume(entity)
            }
        }
        visit(root, root.name ?: "Selected folder")
    }

    companion object {
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun InputStream.readAtMost(max: Int): ByteArray {
            val buffer = ByteArray(max)
            val count = read(buffer)
            return if (count <= 0) byteArrayOf() else buffer.copyOf(count)
        }

        private fun Throwable.userMessage(): String = when (this) {
            is SecurityException -> "Android did not allow access to this location. Grant the folder permission and try again."
            else -> message?.takeIf { it.isNotBlank() } ?: "The scan could not finish. You can safely resume it."
        }
    }
}
