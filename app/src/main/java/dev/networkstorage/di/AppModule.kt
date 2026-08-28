package dev.networkstorage.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.networkstorage.data.copy.CopySourceTree
import dev.networkstorage.data.copy.SafCopySourceTree
import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.credential.KeystoreCredentialStore
import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.AppDatabase
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.smb.SmbCopyClient
import dev.networkstorage.data.smb.SmbjClient
import dev.networkstorage.data.smb.SmbjCopyClient
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
abstract class Bindings {
    @Binds abstract fun credentialStore(value: KeystoreCredentialStore): CredentialStore
    @Binds abstract fun smbClient(value: SmbjClient): SmbClient
    @Binds abstract fun smbCopyClient(value: SmbjCopyClient): SmbCopyClient
    @Binds abstract fun copySourceTree(value: SafCopySourceTree): CopySourceTree
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "network-storage.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun dao(database: AppDatabase): AppDao = database.dao()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS cache_entries (connectionId TEXT NOT NULL, relativePath TEXT NOT NULL, localDocumentUri TEXT NOT NULL, size INTEGER NOT NULL, remoteSize INTEGER NOT NULL, remoteLastModified INTEGER NOT NULL, state TEXT NOT NULL, lastAccessed INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(connectionId, relativePath), FOREIGN KEY(connectionId) REFERENCES connections(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cache_entries_connectionId ON cache_entries(connectionId)")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE connections SET rootMode = 'ON_DEMAND' WHERE rootMode = 'INDEX_ONLY'")
            db.execSQL("UPDATE folder_rules SET mode = 'ON_DEMAND' WHERE mode = 'INDEX_ONLY'")
            db.execSQL("UPDATE indexed_entries SET mode = 'ON_DEMAND' WHERE mode = 'INDEX_ONLY'")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS copy_rules (id TEXT NOT NULL, connectionId TEXT NOT NULL, sourceTreeUri TEXT NOT NULL, destinationPath TEXT NOT NULL, includeSubfolders INTEGER NOT NULL, conflictPolicy TEXT NOT NULL, automaticCopyEnabled INTEGER NOT NULL, networkPolicy TEXT NOT NULL, requiresCharging INTEGER NOT NULL, requiresBatteryNotLow INTEGER NOT NULL, requiresStorageNotLow INTEGER NOT NULL, periodicIntervalMinutes INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(connectionId) REFERENCES connections(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_copy_rules_connectionId ON copy_rules(connectionId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS copy_history (id TEXT NOT NULL, operationId TEXT NOT NULL, ruleId TEXT NOT NULL, connectionId TEXT NOT NULL, sourceRelativePath TEXT NOT NULL, destinationRelativePath TEXT NOT NULL, sourceSize INTEGER NOT NULL, sha256 TEXT, status TEXT NOT NULL, errorCode TEXT, backupRelativePath TEXT, completedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(connectionId) REFERENCES connections(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(ruleId) REFERENCES copy_rules(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_copy_history_connectionId ON copy_history(connectionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_copy_history_ruleId ON copy_history(ruleId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_copy_history_operationId ON copy_history(operationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_copy_history_ruleId_completedAt ON copy_history(ruleId, completedAt)")
        }
    }
}
