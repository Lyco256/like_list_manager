package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun migration2To3KeepsClipsAndAddsNullableLikeCountColumns() {
        val name = "migration-2-3-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE clips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, xPostId TEXT NOT NULL, authorId TEXT, authorName TEXT NOT NULL, authorUsername TEXT NOT NULL, text TEXT NOT NULL, postUrl TEXT NOT NULL, xCreatedAt TEXT NOT NULL, savedAt TEXT NOT NULL, syncedAt TEXT NOT NULL, summary TEXT NOT NULL, isDeleted INTEGER NOT NULL)",
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO clips (id, xPostId, authorId, authorName, authorUsername, text, postUrl, xCreatedAt, savedAt, syncedAt, summary, isDeleted) VALUES (5, '123', NULL, 'name', 'user', 'text', 'url', '2026-06-01T00:00:00Z', 'now', 'now', '', 0)")
            LikeListDatabase.MIGRATION_2_3.migrate(this)
            query("SELECT xPostId, likeCount, likeCountFetchedAt, likeCountFetchFailedAt, likeCountFetchError FROM clips WHERE id = 5").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("123", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
            }
            execSQL("UPDATE clips SET likeCount = 42, likeCountFetchedAt = '2026-06-20T00:00:00Z' WHERE id = 5")
            query("SELECT likeCount FROM clips WHERE id = 5").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42L, cursor.getLong(0))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migration3To4KeepsSyncStateAndAddsNullableContinuationToken() {
        val name = "migration-3-4-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sync_state (id INTEGER PRIMARY KEY NOT NULL, monthlyFetchedCount INTEGER NOT NULL)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO sync_state (id, monthlyFetchedCount) VALUES (1, 123)")
            LikeListDatabase.MIGRATION_3_4.migrate(this)
            query("SELECT monthlyFetchedCount, likedPostsNextToken FROM sync_state WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(123, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
            }
            execSQL("UPDATE sync_state SET likedPostsNextToken = 'next-token' WHERE id = 1")
            query("SELECT likedPostsNextToken FROM sync_state WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("next-token", cursor.getString(0))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migration4To5BackfillsCurrentMonthlyUsageHistory() {
        val name = "migration-4-5-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE sync_state (
                                id INTEGER PRIMARY KEY NOT NULL,
                                xUserId TEXT,
                                newestSeenPostId TEXT,
                                likedPostsNextToken TEXT,
                                lastSyncAt TEXT,
                                monthlyFetchedCount INTEGER NOT NULL,
                                monthlyBudgetLimit INTEGER NOT NULL,
                                monthlyWarningLimit INTEGER NOT NULL,
                                monthlyStopLimit INTEGER NOT NULL,
                                usageMonth TEXT,
                                rateLimitRemaining INTEGER,
                                rateLimitLimit INTEGER,
                                rateLimitResetEpochSeconds INTEGER
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO sync_state (
                    id, xUserId, newestSeenPostId, likedPostsNextToken, lastSyncAt,
                    monthlyFetchedCount, monthlyBudgetLimit, monthlyWarningLimit, monthlyStopLimit,
                    usageMonth, rateLimitRemaining, rateLimitLimit, rateLimitResetEpochSeconds
                ) VALUES (
                    1, 'user-1', 'post-9', 'next-token', '2026-07-02T00:00:00Z',
                    321, 1800, 1500, 2000,
                    '2026-06', 12, 15, 1234567890
                )
                """.trimIndent(),
            )
            LikeListDatabase.MIGRATION_4_5.migrate(this)
            query("SELECT billableReadCount, createdAt, updatedAt FROM api_usage_months WHERE usageMonth = '2026-06'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(321L, cursor.getLong(0))
                assertTrue(cursor.getString(1).isNotBlank())
                assertTrue(cursor.getString(2).isNotBlank())
            }
            query("SELECT monthlyFetchedCount, likedPostsNextToken FROM sync_state WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(321, cursor.getInt(0))
                assertEquals("next-token", cursor.getString(1))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migration5To6BackfillsStandardColorIdsForExistingTagsAndGroups() {
        val name = "migration-5-6-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE tag_groups (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                parentGroupId INTEGER,
                                sortOrder INTEGER NOT NULL,
                                createdAt TEXT NOT NULL,
                                updatedAt TEXT NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE tags (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                color INTEGER NOT NULL,
                                parentGroupId INTEGER,
                                sortOrder INTEGER NOT NULL,
                                createdAt TEXT NOT NULL,
                                updatedAt TEXT NOT NULL
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO tag_groups (id, name, parentGroupId, sortOrder, createdAt, updatedAt) VALUES (1, 'Group', NULL, 0, 'now', 'now')")
            execSQL("INSERT INTO tags (id, name, color, parentGroupId, sortOrder, createdAt, updatedAt) VALUES (2, 'Tag', 123, 1, 0, 'now', 'now')")
            LikeListDatabase.MIGRATION_5_6.migrate(this)
            query("SELECT colorId FROM tag_groups WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("standard", cursor.getString(0))
            }
            query("SELECT colorId FROM tags WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("standard", cursor.getString(0))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migration6To7AddsOcrColumnsWithDefaults() {
        val name = "migration-6-7-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE clips (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                xPostId TEXT NOT NULL,
                                authorId TEXT,
                                authorName TEXT NOT NULL,
                                authorUsername TEXT NOT NULL,
                                text TEXT NOT NULL,
                                postUrl TEXT NOT NULL,
                                xCreatedAt TEXT NOT NULL,
                                savedAt TEXT NOT NULL,
                                syncedAt TEXT NOT NULL,
                                summary TEXT NOT NULL,
                                isDeleted INTEGER NOT NULL,
                                likeCount INTEGER,
                                likeCountFetchedAt TEXT,
                                likeCountFetchFailedAt TEXT,
                                likeCountFetchError TEXT
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO clips (id, xPostId, authorId, authorName, authorUsername, text, postUrl, xCreatedAt, savedAt, syncedAt, summary, isDeleted) VALUES (9, 'ocr-post', NULL, 'name', 'user', 'text', 'url', '2026-07-01T00:00:00Z', 'now', 'now', 'summary', 0)",
            )
            LikeListDatabase.MIGRATION_6_7.migrate(this)
            query("SELECT summary, ocrText, ocrUpdatedAt FROM clips WHERE id = 9").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("summary", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            execSQL("UPDATE clips SET ocrText = 'manual OCR', ocrUpdatedAt = '2026-07-01T01:23:45Z' WHERE id = 9")
            query("SELECT ocrText, ocrUpdatedAt FROM clips WHERE id = 9").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("manual OCR", cursor.getString(0))
                assertEquals("2026-07-01T01:23:45Z", cursor.getString(1))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }
}
