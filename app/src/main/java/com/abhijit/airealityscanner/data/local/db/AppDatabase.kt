package com.abhijit.airealityscanner.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.abhijit.airealityscanner.domain.model.ScanMode

@Database(
    entities = [
        ScanSessionEntity::class,
        ScannedImageEntity::class,
        ScanResultEntity::class,
        ConversationMessageEntity::class,
        ScanCategoryEntity::class,
        UserPreferenceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): ScanSessionDao
    abstract fun imageDao(): ScannedImageDao
    abstract fun resultDao(): ScanResultDao
    abstract fun conversationDao(): ConversationDao
    abstract fun categoryDao(): ScanCategoryDao
    abstract fun preferenceDao(): UserPreferenceDao

    companion object {
        const val NAME = "ai_reality_scanner.db"

        /** Seeds one category per scan mode on first creation. */
        val SeedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                ScanMode.entries.forEachIndexed { index, mode ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO scan_categories (id, name, colorHex, sortOrder) VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(mode.id, mode.title, mode.colorHex, index),
                    )
                }
            }
        }
    }
}
