package com.jarvis.phoneguardian.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Values stored as strings so schema migrations remain backwards compatible. */
object MediaTypes {
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val DOCUMENT = "document"
    const val ARCHIVE = "archive"
    const val INSTALLER = "installer"
    const val OTHER = "other"
}

object ScanPhases {
    const val FAST = "fast"
    const val DEEP = "deep"
}

@Entity(
    tableName = "files",
    indices = [
        Index(value = ["parentKey"]),
        Index(value = ["mediaType"]),
        Index(value = ["sha256"]),
        Index(value = ["size"]),
        Index(value = ["modifiedTime"])
    ]
)
data class FileEntity(
    @PrimaryKey val uri: String,
    val displayPath: String,
    val fileName: String,
    val extension: String,
    val mimeType: String,
    val size: Long,
    val createdTime: Long?,
    val modifiedTime: Long,
    val mediaType: String,
    val parentKey: String,
    val sha256: String? = null,
    val perceptualHash: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val isProtected: Boolean = false,
    val isDuplicate: Boolean = false,
    val backupStatus: String = "not_backed_up",
    val aiCategory: String? = null,
    val userCategory: String? = null,
    val lastIndexedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "protected_folders", indices = [Index(value = ["folder_key"], unique = true)])
data class ProtectedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "folder_key") val key: String,
    val label: String,
    /** protected, organize, ignore, review */
    val state: String = "protected",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "operations", indices = [Index(value = ["createdAt"])])
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val sourceUri: String,
    val sourcePath: String,
    val destinationUri: String? = null,
    val destinationPath: String? = null,
    /** pending, running, completed, failed, undone */
    val status: String = "pending",
    val bytes: Long = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "trash", indices = [Index(value = ["deletedAt"])])
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUri: String,
    val originalPath: String,
    val trashUri: String,
    val fileName: String,
    val size: Long,
    val mediaType: String,
    val deletedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
)

@Entity(tableName = "backup_manifest", indices = [Index(value = ["sha256"]), Index(value = ["originalUri"])])
data class BackupManifestEntity(
    @PrimaryKey val originalUri: String,
    val originalPath: String,
    val size: Long,
    val sha256: String,
    val modifiedTime: Long,
    val backupLocation: String,
    val backupTime: Long = System.currentTimeMillis(),
    val verified: Boolean = false
)

@Entity(tableName = "scan_runs", indices = [Index(value = ["startedAt"])])
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: String,
    val status: String,
    val scanned: Int = 0,
    val discovered: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val errorMessage: String? = null
)

data class StorageSummary(
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val usedBytes: Long = 0,
    val photosBytes: Long = 0,
    val videosBytes: Long = 0,
    val documentsBytes: Long = 0,
    val audioBytes: Long = 0,
    val downloadsBytes: Long = 0,
    val installersBytes: Long = 0,
    val archivesBytes: Long = 0,
    val otherBytes: Long = 0,
    val fileCount: Int = 0
) {
    val usedPercent: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class OrganizationSuggestion(
    val source: FileEntity,
    val destinationLabel: String,
    val destinationPath: String,
    val reason: String,
    val safeToSuggest: Boolean = true
)

data class DuplicateGroup(
    val key: String,
    val files: List<FileEntity>,
    val reclaimableBytes: Long
)

data class ScanProgress(
    val phase: String,
    val scanned: Int,
    val discovered: Int,
    val isRunning: Boolean,
    val message: String = ""
)
