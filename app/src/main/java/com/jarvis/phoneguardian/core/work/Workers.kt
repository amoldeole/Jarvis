package com.jarvis.phoneguardian.core.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.phoneguardian.PhoneGuardianApplication
import com.jarvis.phoneguardian.core.cleanup.EmptyFolderFinder
import com.jarvis.phoneguardian.core.security.GuardianSettings
import com.jarvis.phoneguardian.core.storage.StorageScanner
import java.io.File

class IndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhoneGuardianApplication
        return try {
            val trees = applicationContext.getSharedPreferences("guardian_trees", Context.MODE_PRIVATE)
                .getStringSet("trees", emptySet()).orEmpty().map(Uri::parse)
            StorageScanner(applicationContext, app.database).scan(StorageScanner.Options(selectedTrees = trees)) { progress ->
                setProgress(workDataOf("scanned" to progress.scanned, "discovered" to progress.discovered, "message" to progress.message))
            }
            val settings = GuardianSettings(applicationContext)
            if (settings.autoRemoveEmptyFolders) {
                val finder = EmptyFolderFinder(applicationContext, app.database.operationDao())
                val trees = applicationContext.getSharedPreferences("guardian_trees", Context.MODE_PRIVATE)
                    .getStringSet("trees", emptySet()).orEmpty()
                for (tree in trees) {
                    val empty = finder.find(Uri.parse(tree))
                    finder.deleteConfirmed(empty)
                }
            }
            Result.success()
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("Index work cancelled")
        } catch (_: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class TrashExpiryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhoneGuardianApplication
        return try {
            for (item in app.database.trashDao().expired(System.currentTimeMillis())) {
                val trashFile = File(Uri.parse(item.trashUri).path.orEmpty())
                if (!trashFile.exists() || trashFile.delete()) app.database.trashDao().deleteExpiredItem(item.id)
            }
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
