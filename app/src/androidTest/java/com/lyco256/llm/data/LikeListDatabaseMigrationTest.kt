package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LikeListDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migration1To2KeepsTagsAndAssignmentsAtRoot() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE clips (id INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL, sortOrder INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE clip_tags (clipId INTEGER NOT NULL, tagId INTEGER NOT NULL, createdAt TEXT NOT NULL, PRIMARY KEY(clipId, tagId))")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO clips (id) VALUES (1)")
            execSQL("INSERT INTO tags (id, name, color, sortOrder, createdAt, updatedAt) VALUES (7, 'Kotlin', 1, 3, 'now', 'now')")
            execSQL("INSERT INTO clip_tags (clipId, tagId, createdAt) VALUES (1, 7, 'now')")
            LikeListDatabase.MIGRATION_1_2.migrate(this)
            query("SELECT name, parentGroupId, sortOrder FROM tags WHERE id = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Kotlin", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
                assertEquals(3, cursor.getInt(2))
            }
            query("SELECT COUNT(*) FROM clip_tags WHERE clipId = 1 AND tagId = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            query("PRAGMA foreign_key_list(`clip_tags`)").use { cursor ->
                val referencedTables = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table")))
                }
                assertEquals(setOf("clips", "tags"), referencedTables)
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }
}
