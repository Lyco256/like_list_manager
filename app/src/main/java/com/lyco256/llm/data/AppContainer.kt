package com.lyco256.llm.data

import android.content.Context
import androidx.room.Room

class AppContainer(context: Context) {
    val database: LikeListDatabase = Room.databaseBuilder(
        context,
        LikeListDatabase::class.java,
        "like_list_manager.db",
    ).build()

    val apiSettingsStore = ApiSettingsStore(context)
    val repository = ClipRepository(
        context = context,
        clipDao = database.clipDao(),
        tagDao = database.tagDao(),
        apiSettingsStore = apiSettingsStore,
    )
}
