package dev.networkstorage.data.db

import android.content.Context
import androidx.room.Room
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.ScanStatus
import dev.networkstorage.data.IndexRepository
import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.StorageRootKind
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.smb.RemoteReadHandle
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.RemoteEntry
import dev.networkstorage.presentation.ExternalOpenService
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @Test fun successfulScanMarksOnlyUnseenEntriesMissing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val dao = db.dao()
        dao.saveConnection(ConnectionEntity("c", "NAS", "host", 445, "share", "", "user", null, FolderMode.INDEX_ONLY, 1))
        dao.saveScan(ScanRunEntity("new", "c", ScanStatus.RUNNING, 0, 1, null, null))
        dao.upsertEntry(IndexedEntryEntity("c", "old", "", "old", false, 0, 0, null, FolderMode.INDEX_ONLY, true, 1, "old-scan"))
        dao.upsertEntry(IndexedEntryEntity("c", "new", "", "new", false, 0, 0, null, FolderMode.INDEX_ONLY, true, 2, "new"))
        dao.completeScan("c", "new", 1, 3)
        val entries = dao.entriesForTest("c")
        assertFalse(entries.single { it.name == "old" }.remoteExists)
        assertTrue(entries.single { it.name == "new" }.remoteExists)
        db.close()
    }

    @Test fun connectionDeleteCascadesAllRelatedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val dao = db.dao()
        dao.saveConnection(ConnectionEntity("c", "NAS", "host", 445, "share", "", "user", null, FolderMode.INDEX_ONLY, 1))
        dao.saveRootRule(FolderRuleEntity("c", "", FolderMode.INDEX_ONLY))
        dao.saveScan(ScanRunEntity("scan", "c", ScanStatus.SUCCEEDED, 1, 1, 2, null))
        dao.upsertEntry(IndexedEntryEntity("c", "作品/一.cbz", "作品", "一.cbz", false, Long.MAX_VALUE, 2, null, FolderMode.INDEX_ONLY, false, 2, "scan"))
        dao.deleteConnection("c")
        assertTrue(dao.connection("c") == null)
        assertTrue(dao.entriesForTest("c").isEmpty())
        assertTrue(dao.folderRuleCount("c") == 0L)
        assertTrue(dao.scanRunCount("c") == 0L)
        db.close()
    }

    @Test fun deletingRootIndexKeepsConnectionButClearsIndexData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val dao = db.dao()
        dao.saveConnection(ConnectionEntity("c", "NAS", "host", 445, "share", "", "user", null, FolderMode.ON_DEMAND, 1))
        dao.saveRootRule(FolderRuleEntity("c", "", FolderMode.ON_DEMAND))
        dao.saveScan(ScanRunEntity("scan", "c", ScanStatus.SUCCEEDED, 0, 1, 2, null))
        dao.deleteRootIndex("c")
        assertTrue(dao.connection("c") != null)
        assertTrue(dao.folderRuleCount("c") == 0L)
        assertTrue(dao.scanRunCount("c") == 0L)
        assertTrue(dao.entriesForTest("c").isEmpty())
        db.close()
    }

    @Test fun childrenPagingQueriesOneParentAndSortsFoldersFirstWithUnicode() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val dao = db.dao()
        dao.saveConnection(ConnectionEntity("c", "NAS", "host", 445, "share", "", "user", null, FolderMode.MIRROR, 1))
        dao.upsertEntry(entry("c", "あFolder", "", true, true))
        dao.upsertEntry(entry("c", "z.txt", "", false, false))
        dao.upsertEntry(entry("c", "あFolder/一.cbz", "あFolder", false, true))
        repeat(120) { dao.upsertEntry(entry("c", "file-${it.toString().padStart(3, '0')}.txt", "", false, true)) }
        val root = dao.children("c", "").load(PagingSource.LoadParams.Refresh(null, 200, false)) as PagingSource.LoadResult.Page<Int, BrowserEntryRow>
        assertTrue(root.data.size == 122)
        assertTrue(root.data.first().entry.isDirectory)
        assertTrue(root.data.single { it.entry.name == "z.txt" }.entry.remoteExists.not())
        val nested = dao.children("c", "あFolder").load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page<Int, BrowserEntryRow>
        assertTrue(nested.data.single().entry.name == "一.cbz")
        db.close()
    }

    @Test fun repositoryDeleteRemovesCredentialWithoutAnyRemoteMutationApi() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val dao = db.dao()
        dao.saveConnection(ConnectionEntity("c", "NAS", "host", 445, "share", "", "user", null, FolderMode.INDEX_ONLY, 1))
        var credentialRemoved = false
        val credentials = object : CredentialStore {
            override fun put(connectionId: String, password: CharArray) = Unit
            override fun get(connectionId: String) = null
            override fun remove(connectionId: String) { credentialRemoved = connectionId == "c" }
        }
        val readOnlySmb = object : SmbClient {
            override suspend fun listShares(connection: ConnectionConfig, credential: Credential): List<String> = error("SMB must not be contacted by local deletion")
            override suspend fun list(connection: ConnectionConfig, credential: Credential, relativeDirectory: String): List<RemoteEntry> = error("SMB must not be contacted by local deletion")
            override suspend fun openRead(connection: ConnectionConfig, credential: Credential, relativePath: String): RemoteReadHandle = error("SMB must not be contacted by local deletion")
        }
        IndexRepository(dao, credentials, readOnlySmb).deleteConnection("c")
        assertTrue(credentialRemoved)
        assertTrue(dao.connection("c") == null)
        db.close()
    }

    @Test fun connectionEditKeepsBlankPasswordAndReplacesOnlyExplicitPassword() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val dao = db.dao()
        dao.saveConnection(ConnectionEntity("c", "Old", "host", 445, "share", "", "user", null, FolderMode.INDEX_ONLY, 1))
        var stored = "original"
        val credentialStore = object : CredentialStore {
            override fun put(connectionId: String, password: CharArray) { stored = String(password) }
            override fun get(connectionId: String) = Credential(stored.toCharArray())
            override fun remove(connectionId: String) = Unit
        }
        val readOnly = object : SmbClient {
            override suspend fun listShares(connection: ConnectionConfig, credential: Credential) = emptyList<String>()
            override suspend fun list(connection: ConnectionConfig, credential: Credential, relativeDirectory: String) = emptyList<RemoteEntry>()
            override suspend fun openRead(connection: ConnectionConfig, credential: Credential, relativePath: String): RemoteReadHandle = error("not used")
        }
        val repository = IndexRepository(dao, credentialStore, readOnly)
        repository.updateConnection("c", "New", "host", 445, "share", "folder", "user", null, null, FolderMode.ON_DEMAND)
        assertTrue(stored == "original")
        repository.updateConnection("c", "New", "host", 445, "share", "folder", "user", "replacement".toCharArray(), null, FolderMode.ON_DEMAND)
        assertTrue(stored == "replacement")
        assertTrue(dao.connection("c")?.name == "New")
        db.close()
    }

    @Test fun cacheLimitPersistsAsLongBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)
        repository.setCacheLimitBytes(20L * SettingsRepository.BYTES_PER_GIB)
        assertTrue(SettingsRepository(context).cacheLimitBytes.first() == 20L * SettingsRepository.BYTES_PER_GIB)
    }

    @Test fun storageRootsPersistAndRejectSameOrNestedTrees() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)
        val cache = "content://step4-cache/tree/root"
        repository.setStorageRoot(StorageRootKind.CACHE, cache)
        assertTrue(SettingsRepository(context).cacheRootUri.first() == cache)
        assertTrue(runCatching { repository.setStorageRoot(StorageRootKind.MIRROR, cache) }.isFailure)
        assertTrue(runCatching { repository.setStorageRoot(StorageRootKind.MIRROR, "content://step4-cache/tree/root%2Fmirror") }.isFailure)
    }

    @Test fun externalOpenIntentUsesMimeAndReadOnlyGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = ExternalOpenService(context).buildIntent(Uri.parse("content://cache/book.cbz"), "book.cbz")
        assertTrue(intent.action == Intent.ACTION_VIEW)
        assertTrue(intent.type == "application/zip")
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
    }

    private fun entry(connection: String, path: String, parent: String, directory: Boolean, exists: Boolean) = IndexedEntryEntity(connection, path, parent, path.substringAfterLast('/'), directory, 3_000_000_000L, 2, null, FolderMode.INDEX_ONLY, exists, 2, "scan")
}
