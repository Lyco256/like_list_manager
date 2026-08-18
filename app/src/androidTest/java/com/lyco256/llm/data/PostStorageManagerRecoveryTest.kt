package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class PostStorageManagerRecoveryTest {
    private lateinit var context: Context
    private val storageConfig = PostStorageConfig(
        databaseName = "post_storage_recovery_test.db",
        imagesDirectory = "post_storage_recovery_images",
        dataDirectory = "post_storage_recovery_data",
        preferencesName = "post_storage_recovery_preferences",
    )

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
    }

    @Test
    fun startupRecoversCopyingMigrationByDeletingTemporaryFilesAndClearingState() {
        val tempDatabase = File(context.getDatabasePath(storageConfig.databaseName).parentFile, "${storageConfig.databaseName}.moving")
        val tempWal = File(tempDatabase.path + "-wal")
        val tempShm = File(tempDatabase.path + "-shm")
        val tempImages = File(context.filesDir, "${storageConfig.imagesDirectory}.moving")
        tempDatabase.parentFile?.mkdirs()
        tempImages.mkdirs()
        tempDatabase.writeText("partial db")
        tempWal.writeText("partial wal")
        tempShm.writeText("partial shm")
        File(tempImages, "partial.webp").writeBytes(byteArrayOf(1, 2, 3))
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("migration_phase", "copying")
            .putString("migration_source", "internal")
            .putString("migration_target", "internal")
            .commit()

        val manager = PostStorageManager(context, storageConfig)

        assertFalse(tempDatabase.exists())
        assertFalse(tempWal.exists())
        assertFalse(tempShm.exists())
        assertFalse(tempImages.exists())
        val preferences = context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE)
        assertNull(preferences.getString("migration_phase", null))
        assertNull(preferences.getString("migration_source", null))
        assertNull(preferences.getString("migration_target", null))
        assertTrue(manager.state.value.isAvailable)
        manager.database.value?.close()
    }

    @Test
    fun startupFallsBackToSourceWhenSwitchedTargetCannotBeOpened() {
        val missingTargetId = "external:${File(context.filesDir, "missing_after_switch").absolutePath}"
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("selected_id", missingTargetId)
            .putString("selected_path", File(context.filesDir, "missing_after_switch").absolutePath)
            .putString("migration_phase", "switched")
            .putString("migration_source", "internal")
            .putString("migration_target", missingTargetId)
            .commit()

        val manager = PostStorageManager(context, storageConfig)

        val preferences = context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE)
        assertEquals(PostStorageState.INTERNAL_ID, manager.state.value.currentLocationId)
        assertTrue(manager.state.value.isAvailable)
        assertEquals("前回の移動を完了できなかったため元の保存先へ戻しました", manager.state.value.migrationMessage)
        assertEquals(PostStorageState.INTERNAL_ID, preferences.getString("selected_id", null))
        assertNull(preferences.getString("migration_phase", null))
        assertNull(preferences.getString("migration_source", null))
        assertNull(preferences.getString("migration_target", null))
        manager.database.value?.close()
    }

    @Test
    fun refreshLocationsKeepsUsageUnknownUntilRefreshAndExcludesUserPreferences() = runBlocking {
        val manager = PostStorageManager(context, storageConfig)
        try {
            assertNull(manager.state.value.locations.single { it.isCurrent }.usedBytes)
            val now = Instant.now().toString()
            manager.withDatabase { database ->
                database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "usage-1",
                        authorName = "usage author",
                        authorUsername = "usage_author",
                        text = "usage text",
                        postUrl = "https://x.com/usage_author/status/usage-1",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
            }
            val image = File(manager.imageDirectory(), "usage-counted.webp").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            }

            manager.refreshLocations()

            val usedBeforeUserPrefs = requireNotNull(manager.state.value.locations.single { it.isCurrent }.usedBytes)
            assertTrue(usedBeforeUserPrefs >= image.length())
            assertEquals(1, manager.countManagedImages())

            context.getSharedPreferences("post_storage_user_data_preferences", Context.MODE_PRIVATE)
                .edit()
                .putString("large_user_setting", "x".repeat(50_000))
                .commit()
            manager.refreshLocations()

            val usedAfterUserPrefs = requireNotNull(manager.state.value.locations.single { it.isCurrent }.usedBytes)
            assertEquals(usedBeforeUserPrefs, usedAfterUserPrefs)
        } finally {
            manager.database.value?.close()
        }
    }

    @Test
    fun moveToCopiesPendingUndoSlotWithDatabase() = runBlocking {
        val manager = PostStorageManager(context, storageConfig)
        val external = manager.state.value.locations.firstOrNull {
            it.type == PostStorageType.EXTERNAL && it.isAvailable
        }
        if (external == null) {
            manager.database.value?.close()
            assumeTrue("removable external storage is not available", false)
            return@runBlocking
        }
        try {
            val expected = UndoEntity(
                actionType = "delete_clip",
                payloadJson = "{\"version\":1,\"clipId\":42}",
                message = "投稿を削除しました",
                createdAt = "2026-08-12T10:02:00Z",
            )
            manager.withDatabase { it.undoDao().replaceSlot(expected) }

            assertTrue(manager.moveTo(external.id).isSuccess)

            assertEquals(expected, manager.withDatabase { it.undoDao().getSlot() })
        } finally {
            manager.database.value?.close()
        }
    }

    private fun cleanup() {
        context.deleteDatabase(storageConfig.databaseName)
        File(context.getDatabasePath(storageConfig.databaseName).path + ".moving").delete()
        File(context.getDatabasePath(storageConfig.databaseName).path + ".moving-wal").delete()
        File(context.getDatabasePath(storageConfig.databaseName).path + ".moving-shm").delete()
        File(context.filesDir, storageConfig.imagesDirectory).deleteRecursively()
        File(context.filesDir, "${storageConfig.imagesDirectory}.moving").deleteRecursively()
        File(context.filesDir, storageConfig.dataDirectory).deleteRecursively()
        File(context.filesDir, "missing_after_switch").deleteRecursively()
        context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
            File(directory, storageConfig.dataDirectory).deleteRecursively()
        }
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("post_storage_user_data_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
