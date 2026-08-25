package dev.networkstorage.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.ScanStatus

class Converters {
    @TypeConverter fun folderMode(value: String) = FolderMode.valueOf(value)
    @TypeConverter fun folderMode(value: FolderMode) = value.name
    @TypeConverter fun scanStatus(value: String) = ScanStatus.valueOf(value)
    @TypeConverter fun scanStatus(value: ScanStatus) = value.name
}

@Database(entities = [ConnectionEntity::class, FolderRuleEntity::class, IndexedEntryEntity::class, ScanRunEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): AppDao }
