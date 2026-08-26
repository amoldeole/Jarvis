package com.jarvis.phoneguardian.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.phoneguardian.core.model.BackupManifestEntity
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.OperationEntity
import com.jarvis.phoneguardian.core.model.ProtectedFolderEntity
import com.jarvis.phoneguardian.core.model.ScanRunEntity
import com.jarvis.phoneguardian.core.model.TrashEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY modifiedTime DESC")
    fun observeAll(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files ORDER BY modifiedTime DESC")
    suspend fun getAll(): List<FileEntity>

    @Query("SELECT * FROM files ORDER BY modifiedTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FileEntity>>

    @Query("SELECT * FROM files ORDER BY modifiedTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FileEntity>

    @Query("SELECT * FROM files WHERE displayPath LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%' ORDER BY modifiedTime DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 1000): List<FileEntity>

    @Query("SELECT * FROM files WHERE mediaType = :mediaType ORDER BY size DESC")
    fun observeByMediaType(mediaType: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE size >= :minimum ORDER BY size DESC")
    suspend fun largerThan(minimum: Long): List<FileEntity>

    @Query("SELECT * FROM files WHERE modifiedTime < :before ORDER BY modifiedTime ASC")
    suspend fun olderThan(before: Long): List<FileEntity>

    @Query("SELECT * FROM files WHERE sha256 IS NOT NULL GROUP BY sha256 HAVING COUNT(*) > 1")
    suspend fun duplicateHashes(): List<FileEntity>

    @Query("SELECT * FROM files WHERE sha256 = :sha256 ORDER BY displayPath")
    suspend fun byHash(sha256: String): List<FileEntity>

    @Query("SELECT * FROM files WHERE uri = :uri LIMIT 1")
    suspend fun find(uri: String): FileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<FileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileEntity)

    @Query("UPDATE files SET isProtected = 1 WHERE displayPath LIKE :prefix || '%'")
    suspend fun markProtected(prefix: String)

    @Query("DELETE FROM files WHERE displayPath = :path AND uri != :uri")
    suspend fun removeStaleUri(path: String, uri: String)

    @Query("DELETE FROM files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM files")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM files")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM files")
    suspend fun totalSize(): Long
}

@Dao
interface ProtectionDao {
    @Query("SELECT * FROM protected_folders ORDER BY label")
    fun observeAll(): Flow<List<ProtectedFolderEntity>>

    @Query("SELECT * FROM protected_folders")
    suspend fun getAll(): List<ProtectedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: ProtectedFolderEntity)

    @Query("DELETE FROM protected_folders WHERE folder_key = :key")
    suspend fun delete(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM protected_folders WHERE :path LIKE folder_key || '%' AND state = 'protected')")
    suspend fun isProtected(path: String): Boolean
}

@Dao
interface OperationDao {
    @Query("SELECT * FROM operations ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<OperationEntity>>

    @Insert
    suspend fun insert(operation: OperationEntity): Long

    @Query("UPDATE operations SET status = :status, errorMessage = :error, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null, completedAt: Long? = null)

    @Query("UPDATE operations SET destinationUri = :uri, destinationPath = :path WHERE id = :id")
    suspend fun setDestination(id: Long, uri: String, path: String)

    @Query("SELECT * FROM operations WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): OperationEntity?

    @Query("UPDATE operations SET status = 'failed', errorMessage = 'Interrupted before verification; source was kept where possible.', completedAt = :now WHERE status = 'running'")
    suspend fun markInterrupted(now: Long)
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Insert
    suspend fun insert(item: TrashEntity): Long

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM trash WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun expired(now: Long): List<TrashEntity>

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun deleteExpiredItem(id: Long)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_manifest WHERE originalUri = :uri LIMIT 1")
    suspend fun find(uri: String): BackupManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(manifest: BackupManifestEntity)

    @Query("SELECT * FROM backup_manifest ORDER BY backupTime DESC")
    fun observeAll(): Flow<List<BackupManifestEntity>>
}

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(run: ScanRunEntity): Long

    @Query("UPDATE scan_runs SET status = :status, scanned = :scanned, discovered = :discovered, finishedAt = :finishedAt, errorMessage = :error WHERE id = :id")
    suspend fun finish(id: Long, status: String, scanned: Int, discovered: Int, finishedAt: Long?, error: String? = null)
}
