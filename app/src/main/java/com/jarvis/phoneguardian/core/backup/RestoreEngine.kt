package com.jarvis.phoneguardian.core.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jarvis.phoneguardian.core.storage.StorageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Restores into a user-selected SAF folder and never overwrites an existing destination. */
class RestoreEngine(private val context: Context) {
    data class Result(val restored: Int, val failed: List<String>)

    suspend fun restore(sourceTree: Uri, destinationTree: Uri): Result = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromTreeUri(context, sourceTree) ?: return@withContext Result(0, listOf("Backup source is unavailable."))
        val destination = DocumentFile.fromTreeUri(context, destinationTree) ?: return@withContext Result(0, listOf("Restore destination is unavailable."))
        val errors = mutableListOf<String>()
        var restored = 0
        suspend fun copyDir(from: DocumentFile, to: DocumentFile) {
            for (child in from.listFiles()) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    val childTarget = to.findFile(child.name.orEmpty())?.takeIf { it.isDirectory } ?: to.createDirectory(child.name.orEmpty())
                    if (childTarget != null) copyDir(child, childTarget) else errors += "${child.name}: destination folder could not be created"
                    continue
                }
                if (!child.isFile) continue
                val requested = child.name ?: continue
                val targetName = uniqueName(to, requested)
                val target = to.createFile(child.type ?: "application/octet-stream", targetName)
                if (target == null) {
                    errors += requested
                    continue
                }
                try {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) }
                            ?: error("destination is not writable")
                    } ?: error("source is not readable")
                    val sourceHash = context.contentResolver.openInputStream(child.uri)?.use { StorageScanner.sha256(it) }
                    val targetHash = context.contentResolver.openInputStream(target.uri)?.use { StorageScanner.sha256(it) }
                    require(sourceHash == targetHash) { "checksum mismatch" }
                    restored++
                } catch (error: Throwable) {
                    target.delete()
                    errors += "$requested: ${error.message ?: "restore failed"}"
                }
            }
        }
        copyDir(source, destination)
        Result(restored, errors)
    }

    private fun uniqueName(folder: DocumentFile, requested: String): String {
        if (folder.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var n = 1
        while (folder.findFile("$base ($n)$extension") != null) n++
        return "$base ($n)$extension"
    }
}
