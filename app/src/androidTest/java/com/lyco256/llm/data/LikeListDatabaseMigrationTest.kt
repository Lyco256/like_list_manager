package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun migration7To8To9RemovesIsDeletedWithoutDroppingClipsAssetsOrTags() {
        val name = "migration-7-8-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
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
                                ocrText TEXT NOT NULL,
                                ocrUpdatedAt TEXT,
                                isDeleted INTEGER NOT NULL,
                                likeCount INTEGER,
                                likeCountFetchedAt TEXT,
                                likeCountFetchFailedAt TEXT,
                                likeCountFetchError TEXT
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE UNIQUE INDEX index_clips_xPostId ON clips (xPostId)")
                        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                        db.execSQL(
                            """
                            CREATE TABLE assets (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                clipId INTEGER NOT NULL,
                                mediaKey TEXT NOT NULL,
                                type TEXT NOT NULL,
                                remoteUrl TEXT,
                                previewUrl TEXT,
                                localPath TEXT,
                                width INTEGER,
                                height INTEGER,
                                sizeBytes INTEGER,
                                downloadState TEXT NOT NULL,
                                createdAt TEXT NOT NULL,
                                FOREIGN KEY(clipId) REFERENCES clips(id) ON DELETE CASCADE
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE INDEX index_assets_clipId ON assets (clipId)")
                        db.execSQL(
                            """
                            CREATE TABLE clip_tags (
                                clipId INTEGER NOT NULL,
                                tagId INTEGER NOT NULL,
                                createdAt TEXT NOT NULL,
                                PRIMARY KEY(clipId, tagId),
                                FOREIGN KEY(clipId) REFERENCES clips(id) ON DELETE CASCADE,
                                FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE INDEX index_clip_tags_clipId ON clip_tags (clipId)")
                        db.execSQL("CREATE INDEX index_clip_tags_tagId ON clip_tags (tagId)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO tags (id, name) VALUES (41, 'kept-tag')")
            execSQL(
                """
                INSERT INTO clips (
                    id, xPostId, authorId, authorName, authorUsername, text, postUrl, xCreatedAt,
                    savedAt, syncedAt, summary, ocrText, ocrUpdatedAt, isDeleted, likeCount,
                    likeCountFetchedAt, likeCountFetchFailedAt, likeCountFetchError
                ) VALUES
                    (11, 'visible', 'a1', 'Visible', 'visible', 'visible-text', 'url-1', 'created-1',
                     'saved-1', 'synced-1', 'summary-1', 'ocr-1', 'ocr-at-1', 0, 12, 'fetched-1', NULL, NULL),
                    (12, 'formerly-deleted', 'a2', 'Restored', 'restored', 'restored-text', 'url-2', 'created-2',
                     'saved-2', 'synced-2', 'summary-2', 'ocr-2', 'ocr-at-2', 1, 34, 'fetched-2', 'failed-2', 'error-2')
                """.trimIndent(),
            )
            execSQL("INSERT INTO assets (id, clipId, mediaKey, type, remoteUrl, previewUrl, localPath, width, height, sizeBytes, downloadState, createdAt) VALUES (21, 11, 'media-1', 'photo', 'remote-1', NULL, 'local-1', 100, 200, 300, 'downloaded', 'asset-at-1'), (22, 12, 'media-2', 'video_thumbnail', 'remote-2', 'preview-2', NULL, 400, 500, 600, 'remote', 'asset-at-2')")
            execSQL("INSERT INTO clip_tags (clipId, tagId, createdAt) VALUES (11, 41, 'tag-at-1'), (12, 41, 'tag-at-2')")

            LikeListDatabase.MIGRATION_7_8.migrate(this)
            LikeListDatabase.MIGRATION_8_9.migrate(this)

            query("PRAGMA table_info(`clips`)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertFalse(columns.contains("isDeleted"))
            }
            query("SELECT id, xPostId, text, summary, ocrText, likeCount, likeCountFetchFailedAt FROM clips ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11L, cursor.getLong(0))
                assertEquals("visible", cursor.getString(1))
                assertTrue(cursor.moveToNext())
                assertEquals(12L, cursor.getLong(0))
                assertEquals("formerly-deleted", cursor.getString(1))
                assertEquals("restored-text", cursor.getString(2))
                assertEquals("summary-2", cursor.getString(3))
                assertEquals("ocr-2", cursor.getString(4))
                assertEquals(34L, cursor.getLong(5))
                assertEquals("failed-2", cursor.getString(6))
                assertFalse(cursor.moveToNext())
            }
            query("SELECT id, clipId, mediaKey, localPath, sizeBytes FROM assets ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(21L, cursor.getLong(0))
                assertEquals(11L, cursor.getLong(1))
                assertTrue(cursor.moveToNext())
                assertEquals(22L, cursor.getLong(0))
                assertEquals(12L, cursor.getLong(1))
                assertEquals("media-2", cursor.getString(2))
                assertEquals(600L, cursor.getLong(4))
                assertFalse(cursor.moveToNext())
            }
            query("SELECT clipId, tagId, createdAt FROM clip_tags ORDER BY clipId").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11L, cursor.getLong(0))
                assertTrue(cursor.moveToNext())
                assertEquals(12L, cursor.getLong(0))
                assertEquals(41L, cursor.getLong(1))
                assertEquals("tag-at-2", cursor.getString(2))
                assertFalse(cursor.moveToNext())
            }
            query("SELECT COUNT(*) FROM clips").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM clip_tags GROUP BY tagId").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM clips INNER JOIN assets ON assets.clipId = clips.id WHERE assets.type IN ('photo', 'video_thumbnail')").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            query("PRAGMA index_list(`clips`)").use { cursor ->
                var uniqueXPostId = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_clips_xPostId") {
                        uniqueXPostId = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                    }
                }
                assertTrue(uniqueXPostId)
            }
            query("PRAGMA foreign_key_list(`assets`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("clips", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            }
            query("PRAGMA foreign_key_list(`clip_tags`)").use { cursor ->
                val parents = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table")))
                }
                assertEquals(setOf("clips", "tags"), parents)
            }
            query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
            query("SELECT COUNT(*) FROM undo_slot").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migration8To9KeepsExistingRowsAndAddsIndependentUndoSlot() {
        val name = "migration-8-9-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE clips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, xPostId TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE assets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clipId INTEGER NOT NULL, mediaKey TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE tag_groups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE clip_tags (clipId INTEGER NOT NULL, tagId INTEGER NOT NULL, createdAt TEXT NOT NULL, PRIMARY KEY(clipId, tagId))")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO clips (id, xPostId) VALUES (1, 'kept-post')")
            execSQL("INSERT INTO assets (id, clipId, mediaKey) VALUES (2, 1, 'kept-media')")
            execSQL("INSERT INTO tag_groups (id, name) VALUES (3, 'kept-group')")
            execSQL("INSERT INTO tags (id, name) VALUES (4, 'kept-tag')")
            execSQL("INSERT INTO clip_tags (clipId, tagId, createdAt) VALUES (1, 4, 'kept-relation')")

            LikeListDatabase.MIGRATION_8_9.migrate(this)

            query("SELECT xPostId FROM clips WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept-post", cursor.getString(0))
            }
            query("SELECT mediaKey FROM assets WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept-media", cursor.getString(0))
            }
            query("SELECT name FROM tag_groups WHERE id = 3").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept-group", cursor.getString(0))
            }
            query("SELECT name FROM tags WHERE id = 4").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept-tag", cursor.getString(0))
            }
            query("SELECT createdAt FROM clip_tags WHERE clipId = 1 AND tagId = 4").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept-relation", cursor.getString(0))
            }
            execSQL("INSERT INTO undo_slot (id, actionType, payloadJson, message, createdAt) VALUES (1, 'delete', '{\"version\":1}', 'deleted', 'now')")
            query("SELECT actionType, payloadJson, message, createdAt FROM undo_slot WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("delete", cursor.getString(0))
                assertEquals("{\"version\":1}", cursor.getString(1))
                assertEquals("deleted", cursor.getString(2))
                assertEquals("now", cursor.getString(3))
            }
            query("PRAGMA foreign_key_list(`undo_slot`)").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
        helper.close()
        context.deleteDatabase(name)
    }
}
