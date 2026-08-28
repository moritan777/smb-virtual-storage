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
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "network-storage.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
}
