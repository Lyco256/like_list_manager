package com.lyco256.llm.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ClipEntity::class,
        AssetEntity::class,
        TagEntity::class,
        ClipTagEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LikeListDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun tagDao(): TagDao
}
