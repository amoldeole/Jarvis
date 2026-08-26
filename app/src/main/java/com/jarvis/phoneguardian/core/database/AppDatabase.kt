package com.jarvis.phoneguardian.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jarvis.phoneguardian.core.model.BackupManifestEntity
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.OperationEntity
import com.jarvis.phoneguardian.core.model.ProtectedFolderEntity
import com.jarvis.phoneguardian.core.model.ScanRunEntity
import com.jarvis.phoneguardian.core.model.TrashEntity

@Database(
    entities = [
        FileEntity::class,
        ProtectedFolderEntity::class,
        OperationEntity::class,
        TrashEntity::class,
        BackupManifestEntity::class,
        ScanRunEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun protectionDao(): ProtectionDao
    abstract fun operationDao(): OperationDao
    abstract fun trashDao(): TrashDao
    abstract fun backupDao(): BackupDao
    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "guardian.db"
            ).fallbackToDestructiveMigrationOnDowngrade().build().also { instance = it }
        }
    }
}
