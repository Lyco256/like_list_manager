package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    private fun cleanup() {
        context.deleteDatabase(storageConfig.databaseName)
        File(context.getDatabasePath(storageConfig.databaseName).path + ".moving").delete()
        File(context.getDatabasePath(storageConfig.databaseName).path + ".moving-wal").delete()
        File(context.getDatabasePath(storageConfig.databaseName).path + ".moving-shm").delete()
        File(context.filesDir, storageConfig.imagesDirectory).deleteRecursively()
        File(context.filesDir, "${storageConfig.imagesDirectory}.moving").deleteRecursively()
        File(context.filesDir, storageConfig.dataDirectory).deleteRecursively()
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
