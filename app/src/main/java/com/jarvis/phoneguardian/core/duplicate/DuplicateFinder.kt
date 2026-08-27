package com.jarvis.phoneguardian.core.duplicate

import com.jarvis.phoneguardian.core.database.AppDatabase
import com.jarvis.phoneguardian.core.model.DuplicateGroup
import com.jarvis.phoneguardian.core.model.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DuplicateFinder(private val database: AppDatabase) {
    enum class Mode { EXACT, METADATA }
    enum class KeepPolicy { OLDEST, NEWEST, LARGEST, ORIGINAL_LOCATION }

    suspend fun find(mode: Mode = Mode.EXACT): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val files = database.fileDao().getAll()
        val groups = when (mode) {
            Mode.EXACT -> files.filter { !it.sha256.isNullOrBlank() }.groupBy { it.sha256!! }
            Mode.METADATA -> files.groupBy { "${it.fileName.lowercase()}|${it.size}|${it.mediaType}|${it.modifiedTime / 60_000}" }
        }
        groups.filterValues { it.size > 1 }.map { (key, members) ->
            DuplicateGroup(
                key = key,
                files = members.sortedWith(compareByDescending<FileEntity> { it.size }.thenBy { it.displayPath }),
                reclaimableBytes = members.sumOf { it.size } - members.maxOf { it.size }
            )
        }.sortedByDescending { it.reclaimableBytes }
    }

    fun recommendedKeep(group: DuplicateGroup, policy: KeepPolicy): FileEntity = when (policy) {
        KeepPolicy.OLDEST -> group.files.minBy { it.createdTime ?: it.modifiedTime }
        KeepPolicy.NEWEST -> group.files.maxBy { it.modifiedTime }
        KeepPolicy.LARGEST -> group.files.maxBy { it.size }
        KeepPolicy.ORIGINAL_LOCATION -> group.files.minBy { locationScore(it.displayPath) }
    }

    private fun locationScore(path: String): Int = when {
        path.contains("DCIM", true) || path.contains("Camera", true) -> 0
        path.contains("Pictures", true) -> 1
        path.contains("Download", true) -> 2
        else -> 3
    }
}
