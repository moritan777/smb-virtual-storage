package dev.networkstorage.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.ScanStatus
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
        val entries = dao.observePreview("c").first()
        assertFalse(entries.single { it.name == "old" }.remoteExists)
        assertTrue(entries.single { it.name == "new" }.remoteExists)
        db.close()
    }
}
