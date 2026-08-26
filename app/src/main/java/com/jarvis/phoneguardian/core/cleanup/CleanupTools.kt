package com.jarvis.phoneguardian.core.cleanup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jarvis.phoneguardian.core.database.OperationDao
import com.jarvis.phoneguardian.core.model.OperationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** SAF-only empty-folder inspection. It never considers a folder empty if Android reports a child. */
class EmptyFolderFinder(
    private val context: Context,
    private val operationDao: OperationDao? = null
) {
    data class EmptyFolder(val uri: Uri, val path: String)

    suspend fun find(tree: Uri): List<EmptyFolder> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
        val result = mutableListOf<EmptyFolder>()
        suspend fun visit(directory: DocumentFile, path: String) {
            for (child in directory.listFiles()) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    val children = child.listFiles()
                    if (children.isEmpty()) result += EmptyFolder(child.uri, "$path/${child.name.orEmpty()}")
                    else visit(child, "$path/${child.name.orEmpty()}")
                }
            }
        }
        visit(root, root.name ?: "Selected folder")
        result
    }

    suspend fun deleteConfirmed(folders: List<EmptyFolder>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        for (folder in folders) {
            val document = DocumentFile.fromSingleUri(context, folder.uri)
            if (document?.isDirectory != true || document.listFiles().isNotEmpty()) continue
            val operationId = operationDao?.insert(
                OperationEntity(kind = "delete_empty_folder", sourceUri = folder.uri.toString(), sourcePath = folder.path)
            )
            val success = document.delete()
            if (success) {
                deleted++
                operationId?.let { operationDao.updateStatus(it, "completed", completedAt = System.currentTimeMillis()) }
            } else {
                operationId?.let { operationDao.updateStatus(it, "failed", "Android refused to delete the empty folder.", System.currentTimeMillis()) }
            }
        }
        deleted
    }
}
