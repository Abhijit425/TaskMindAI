package com.taskmind.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration

class Converters {
    // Priority is stored as its weight so SQL "ORDER BY priority DESC" sorts correctly.
    @TypeConverter
    fun priorityToInt(p: Priority): Int = p.weight

    @TypeConverter
    fun intToPriority(i: Int): Priority = Priority.fromWeight(i)
}

@Database(
    entities = [
        CategoryEntity::class,
        ContactReferenceEntity::class,
        CallSessionEntity::class,
        TranscriptEntity::class,
        TaskEntity::class,
        TaskDependencyEntity::class,
        TaskAttachmentEntity::class,
        ReminderEntity::class,
        ExtractedActionEntity::class,
        UserSettingsEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun reminderDao(): ReminderDao
    abstract fun callDao(): CallDao
    abstract fun contactDao(): ContactDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        const val NAME = "taskmind.db"
        const val VERSION = 1

        /**
         * Schema history lives in app/schemas (exported by Room). When the schema changes:
         * bump VERSION, add a Migration(old, new) here, and never fall back to destructive
         * migration — user tasks and transcripts must survive app updates.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
