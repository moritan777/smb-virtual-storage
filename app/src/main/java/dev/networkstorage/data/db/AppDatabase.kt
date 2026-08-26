package dev.networkstorage.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.ScanStatus
import dev.networkstorage.domain.CacheState

class Converters {
    @TypeConverter fun folderMode(value: String) = FolderMode.valueOf(value)
    @TypeConverter fun folderMode(value: FolderMode) = value.name
    @TypeConverter fun scanStatus(value: String) = ScanStatus.valueOf(value)
    @TypeConverter fun scanStatus(value: ScanStatus) = value.name
    @TypeConverter fun cacheState(value: String) = CacheState.valueOf(value)
    @TypeConverter fun cacheState(value: CacheState) = value.name
}

@Database(entities = [ConnectionEntity::class, FolderRuleEntity::class, IndexedEntryEntity::class, ScanRunEntity::class, CacheEntryEntity::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): AppDao }
