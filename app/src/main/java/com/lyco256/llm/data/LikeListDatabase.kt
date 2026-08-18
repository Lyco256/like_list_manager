package com.lyco256.llm.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ClipEntity::class,
        AssetEntity::class,
        TagGroupEntity::class,
        TagEntity::class,
        ClipTagEntity::class,
        SyncStateEntity::class,
        ApiUsageMonthEntity::class,
        UndoEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class LikeListDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun tagDao(): TagDao
    abstract fun undoDao(): UndoDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `undo_slot` (
                        `id` INTEGER NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TEMP TABLE `assets_backup` AS SELECT * FROM `assets`")
                database.execSQL("CREATE TEMP TABLE `clip_tags_backup_v8` AS SELECT * FROM `clip_tags`")
                database.execSQL("DROP TABLE `assets`")
                database.execSQL("DROP TABLE `clip_tags`")
                database.execSQL(
                    """
                    CREATE TABLE `clips_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `xPostId` TEXT NOT NULL,
                        `authorId` TEXT,
                        `authorName` TEXT NOT NULL,
                        `authorUsername` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `postUrl` TEXT NOT NULL,
                        `xCreatedAt` TEXT NOT NULL,
                        `savedAt` TEXT NOT NULL,
                        `syncedAt` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `ocrText` TEXT NOT NULL,
                        `ocrUpdatedAt` TEXT,
                        `likeCount` INTEGER,
                        `likeCountFetchedAt` TEXT,
                        `likeCountFetchFailedAt` TEXT,
                        `likeCountFetchError` TEXT
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `clips_new` (
                        `id`, `xPostId`, `authorId`, `authorName`, `authorUsername`, `text`, `postUrl`,
                        `xCreatedAt`, `savedAt`, `syncedAt`, `summary`, `ocrText`, `ocrUpdatedAt`,
                        `likeCount`, `likeCountFetchedAt`, `likeCountFetchFailedAt`, `likeCountFetchError`
                    )
                    SELECT
                        `id`, `xPostId`, `authorId`, `authorName`, `authorUsername`, `text`, `postUrl`,
                        `xCreatedAt`, `savedAt`, `syncedAt`, `summary`, `ocrText`, `ocrUpdatedAt`,
                        `likeCount`, `likeCountFetchedAt`, `likeCountFetchFailedAt`, `likeCountFetchError`
                    FROM `clips`
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE `clips`")
                database.execSQL("ALTER TABLE `clips_new` RENAME TO `clips`")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_clips_xPostId` ON `clips` (`xPostId`)")
                database.execSQL(
                    """
                    CREATE TABLE `assets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `clipId` INTEGER NOT NULL,
                        `mediaKey` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `remoteUrl` TEXT,
                        `previewUrl` TEXT,
                        `localPath` TEXT,
                        `width` INTEGER,
                        `height` INTEGER,
                        `sizeBytes` INTEGER,
                        `downloadState` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        FOREIGN KEY(`clipId`) REFERENCES `clips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("INSERT INTO `assets` SELECT * FROM `assets_backup`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_assets_clipId` ON `assets` (`clipId`)")
                database.execSQL(
                    """
                    CREATE TABLE `clip_tags` (
                        `clipId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`clipId`, `tagId`),
                        FOREIGN KEY(`clipId`) REFERENCES `clips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("INSERT INTO `clip_tags` SELECT * FROM `clip_tags_backup_v8`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_clip_tags_clipId` ON `clip_tags` (`clipId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_clip_tags_tagId` ON `clip_tags` (`tagId`)")
                database.execSQL("DROP TABLE `assets_backup`")
                database.execSQL("DROP TABLE `clip_tags_backup_v8`")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `clips` ADD COLUMN `ocrText` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `clips` ADD COLUMN `ocrUpdatedAt` TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("PRAGMA foreign_keys=OFF")
                database.execSQL("ALTER TABLE `tag_groups` ADD COLUMN `colorId` TEXT NOT NULL DEFAULT 'standard'")
                database.execSQL(
                    """
                    CREATE TABLE `tags_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `parentGroupId` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        `colorId` TEXT NOT NULL DEFAULT 'standard',
                        FOREIGN KEY(`parentGroupId`) REFERENCES `tag_groups`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `tags_new` (`id`, `name`, `parentGroupId`, `sortOrder`, `createdAt`, `updatedAt`, `colorId`)
                    SELECT `id`, `name`, `parentGroupId`, `sortOrder`, `createdAt`, `updatedAt`, 'standard'
                    FROM `tags`
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE `tags`")
                database.execSQL("ALTER TABLE `tags_new` RENAME TO `tags`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_parentGroupId` ON `tags` (`parentGroupId`)")
                database.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val currentMonth = java.time.YearMonth.now().toString()
                val now = java.time.Instant.now().toString()
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `api_usage_months` (
                        `usageMonth` TEXT NOT NULL,
                        `billableReadCount` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`usageMonth`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `api_usage_months` (`usageMonth`, `billableReadCount`, `createdAt`, `updatedAt`)
                    SELECT COALESCE(NULLIF(`usageMonth`, ''), '$currentMonth'), COALESCE(`monthlyFetchedCount`, 0), '$now', '$now'
                    FROM `sync_state`
                    WHERE `id` = 1
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `sync_state` ADD COLUMN `likedPostsNextToken` TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `clips` ADD COLUMN `likeCount` INTEGER")
                database.execSQL("ALTER TABLE `clips` ADD COLUMN `likeCountFetchedAt` TEXT")
                database.execSQL("ALTER TABLE `clips` ADD COLUMN `likeCountFetchFailedAt` TEXT")
                database.execSQL("ALTER TABLE `clips` ADD COLUMN `likeCountFetchError` TEXT")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `parentGroupId` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`parentGroupId`) REFERENCES `tag_groups`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_groups_parentGroupId` ON `tag_groups` (`parentGroupId`)")
                database.execSQL(
                    """
                    CREATE TABLE `tags_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `color` INTEGER NOT NULL,
                        `parentGroupId` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        FOREIGN KEY(`parentGroupId`) REFERENCES `tag_groups`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO `tags_new` (`id`, `name`, `color`, `parentGroupId`, `sortOrder`, `createdAt`, `updatedAt`) " +
                        "SELECT `id`, `name`, `color`, NULL, `sortOrder`, `createdAt`, `updatedAt` FROM `tags`",
                )
                database.execSQL(
                    """
                    CREATE TABLE `clip_tags_backup` (
                        `clipId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`clipId`, `tagId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL("INSERT INTO `clip_tags_backup` SELECT `clipId`, `tagId`, `createdAt` FROM `clip_tags`")
                database.execSQL("DROP TABLE `clip_tags`")
                database.execSQL("DROP TABLE `tags`")
                database.execSQL("ALTER TABLE `tags_new` RENAME TO `tags`")
                database.execSQL(
                    """
                    CREATE TABLE `clip_tags` (
                        `clipId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`clipId`, `tagId`),
                        FOREIGN KEY(`clipId`) REFERENCES `clips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("INSERT INTO `clip_tags` SELECT `clipId`, `tagId`, `createdAt` FROM `clip_tags_backup`")
                database.execSQL("DROP TABLE `clip_tags_backup`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_parentGroupId` ON `tags` (`parentGroupId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_clip_tags_clipId` ON `clip_tags` (`clipId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_clip_tags_tagId` ON `clip_tags` (`tagId`)")
            }
        }
    }
}
