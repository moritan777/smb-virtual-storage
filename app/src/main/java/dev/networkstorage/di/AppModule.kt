package dev.networkstorage.di

import android.content.Context
import androidx.room.Room
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
import dev.networkstorage.data.smb.SmbjClient
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
abstract class Bindings {
    @Binds abstract fun credentialStore(value: KeystoreCredentialStore): CredentialStore
    @Binds abstract fun smbClient(value: SmbjClient): SmbClient
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "network-storage.db").build()
    @Provides fun dao(database: AppDatabase): AppDao = database.dao()
}
