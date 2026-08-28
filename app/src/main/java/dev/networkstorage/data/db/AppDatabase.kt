package dev.networkstorage.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.networkstorage.data.copy.CopyConflictPolicy
import dev.networkstorage.domain.CacheState
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.ScanStatus

class Converters {
    @TypeConverter fun folderMode(value: String) = FolderMode.valueOf(value)
    @TypeConverter fun folderMode(value: FolderMode) = value.name
    @TypeConverter fun scanStatus(value: String) = ScanStatus.valueOf(value)
    @TypeConverter fun scanStatus(value: ScanStatus) = value.name
    @TypeConverter fun cacheState(value: String) = CacheState.valueOf(value)
    @TypeConverter fun cacheState(value: CacheState) = value.name
    @TypeConverter fun copyConflictPolicy(value: String) = CopyConflictPolicy.valueOf(value)
    @TypeConverter fun copyConflictPolicy(value: CopyConflictPolicy) = value.name
    @TypeConverter fun copyNetworkPolicy(value: String) = CopyNetworkPolicy.valueOf(value)
    @TypeConverter fun copyNetworkPolicy(value: CopyNetworkPolicy) = value.name
    @TypeConverter fun copyHistoryStatus(value: String) = CopyHistoryStatus.valueOf(value)
    @TypeConverter fun copyHistoryStatus(value: CopyHistoryStatus) = value.name
    @TypeConverter fun copyErrorCode(value: String) = CopyErrorCode.valueOf(value)
    @TypeConverter fun copyErrorCode(value: CopyErrorCode) = value.name
}

@Database(
    entities = [
        ConnectionEntity::class,
        FolderRuleEntity::class,
        IndexedEntryEntity::class,
        ScanRunEntity::class,
        CacheEntryEntity::class,
        CopyRuleEntity::class,
        CopyHistoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}
