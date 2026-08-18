package com.lyco256.llm.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UndoDaoIntegrationTest {
    private lateinit var context: Context
    private val databaseName = "undo-dao-integration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun slotCanBeObservedReplacedReopenedAndDeleted() = runBlocking {
        var database = openDatabase()
        val initialDao = database.undoDao()
        assertNull(initialDao.observeSlot().first())

        initialDao.replaceSlot(
            UndoEntity(
                actionType = "tag_change",
                payloadJson = "{\"version\":1,\"tagId\":10}",
                message = "タグを変更しました",
                createdAt = "2026-08-12T10:00:00Z",
            ),
        )
        assertEquals("tag_change", initialDao.observeSlot().first()?.actionType)
        assertEquals("{\"version\":1,\"tagId\":10}", initialDao.getSlot()?.payloadJson)

        initialDao.replaceSlot(
            UndoEntity(
                id = 99,
                actionType = "summary_change",
                payloadJson = "{\"version\":2,\"summary\":\"before\"}",
                message = "概要を変更しました",
                createdAt = "2026-08-12T10:01:00Z",
            ),
        )
        val replaced = requireNotNull(initialDao.getSlot())
        assertEquals(UndoEntity.SLOT_ID, replaced.id)
        assertEquals("summary_change", replaced.actionType)
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM undo_slot").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        database.close()
        database = openDatabase()
        val reopenedDao = database.undoDao()
        assertEquals(replaced, reopenedDao.getSlot())

        reopenedDao.deleteSlot()
        assertNull(reopenedDao.getSlot())
        assertNull(reopenedDao.observeSlot().first())
        database.close()
    }

    private fun openDatabase(): LikeListDatabase = Room.databaseBuilder(
        context,
        LikeListDatabase::class.java,
        databaseName,
    ).addMigrations(
        LikeListDatabase.MIGRATION_1_2,
        LikeListDatabase.MIGRATION_2_3,
        LikeListDatabase.MIGRATION_3_4,
        LikeListDatabase.MIGRATION_4_5,
        LikeListDatabase.MIGRATION_5_6,
        LikeListDatabase.MIGRATION_6_7,
        LikeListDatabase.MIGRATION_7_8,
        LikeListDatabase.MIGRATION_8_9,
    ).build()
}
