package com.example.read.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        PageIndexEntity::class,
        ImportTaskEntity::class,
        ChapterFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReadDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
