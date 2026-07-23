package com.lyco256.llm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import com.lyco256.llm.rgb565backfill.BACKFILL_RGB565_BANK_STRIDE
import com.lyco256.llm.rgb565backfill.BACKFILL_RGB565_PACK_HEADER_SIZE
import com.lyco256.llm.rgb565backfill.BACKFILL_RGB565_SLOT_STRIDE
import com.lyco256.llm.rgb565backfill.Rgb565BackfillEngine
import com.lyco256.llm.rgb565backfill.Rgb565BackfillPackObserver
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaGridRgb565BackfillIntegrationTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File
    private lateinit var sourceRoot: File
    private lateinit var rawRoot: File
    private lateinit var jpegRoot: File

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath("rgb565_backfill_integration.db")
        sourceRoot = File(context.filesDir, "rgb565_backfill_integration_sources")
        rawRoot = File(context.filesDir, "media_grid_rgb565_packs")
        jpegRoot = File(context.filesDir, "media_grid_previews/v1")
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        sourceRoot.deleteRecursively()
        rawRoot.deleteRecursively()
        jpegRoot.deleteRecursively()
        sourceRoot.mkdirs()
        jpegRoot.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use {
            it.execSQL("CREATE TABLE assets (id INTEGER PRIMARY KEY, localPath TEXT)")
        }
    }

    @After
    fun tearDown() {
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        sourceRoot.deleteRecursively()
        rawRoot.deleteRecursively()
        jpegRoot.deleteRecursively()
    }

    @Test
    fun readOnlyBackfillResumesRepairsCorruptBankAndPublishesRestrictedReport() {
        val firstLocal = createWebp("first.webp", 640, 320, Color.RED)
        val secondLocal = createWebp("second.webp", 320, 640, Color.BLUE)
        insertAsset(1, firstLocal)
        insertAsset(2, secondLocal)
        createJpegPreview(1, Color.GREEN)
        val databaseHash = sha256(databaseFile)
        val firstEngine = Rgb565BackfillEngine(context, databaseFile)

        val first = firstEngine.run()

        assertTrue(first.readOnlyDatabase)
        assertTrue(first.complete)
        assertEquals(1, first.jpegConvertedCount)
        assertEquals(1, first.webpConvertedCount)
        assertEquals(databaseHash, sha256(databaseFile))
        val resumed = Rgb565BackfillEngine(context, databaseFile).run()
        assertTrue(resumed.complete)
        assertEquals(2, resumed.validSlotSkipCount)
        assertEquals(0, resumed.jpegConvertedCount + resumed.webpConvertedCount)

        corruptFirstBankMetadata(assetId = 1)
        val repaired = Rgb565BackfillEngine(context, databaseFile).run()
        assertTrue(repaired.complete)
        assertEquals(1, repaired.validSlotSkipCount)
        assertEquals(1, repaired.jpegConvertedCount)
        assertEquals(databaseHash, sha256(databaseFile))

        val report = File(rawRoot, "v1/backfill/latest-report.txt")
        assertTrue(report.isFile)
        val keys = report.readLines()
            .filter(String::isNotBlank)
            .map { it.substringBefore("=") }
            .toSet()
        assertEquals(
            setOf(
                "read_only_db",
                "asset_total",
                "valid_slot_skip",
                "jpeg_converted",
                "webp_converted",
                "source_missing",
                "decode_failed",
                "write_failed",
                "retry_success",
                "pack_count",
                "started_at",
                "finished_at",
                "complete",
            ),
            keys,
        )
        assertFalse(report.readText().contains(firstLocal.absolutePath))
        assertFalse(report.readText().contains(secondLocal.absolutePath))
    }

    @Test
    fun fivePacksUseAtMostFourWorkersAndStartFifthAfterPermitWithoutDelay() {
        repeat(5) { pack ->
            val assetId = pack.toLong() * 128L + 1L
            insertAsset(assetId, createWebp("pack-$pack.webp", 300, 300, Color.rgb(pack * 20, 80, 120)))
        }
        val firstFour = CountDownLatch(4)
        val allFive = CountDownLatch(5)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val observer = object : Rgb565BackfillPackObserver {
            override fun onPackStart(packIndex: Long) {
                val now = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                firstFour.countDown()
                allFive.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }

            override fun onPackFinished(packIndex: Long) {
                active.decrementAndGet()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Rgb565BackfillReportAdapter> {
                Rgb565BackfillReportAdapter(
                    Rgb565BackfillEngine(
                        context = context,
                        databaseFile = databaseFile,
                        packObserver = observer,
                    ).run().complete,
                )
            }
            assertTrue(firstFour.await(10, TimeUnit.SECONDS))
            assertEquals(4, maximum.get())
            assertEquals(1L, allFive.count)
            release.countDown()
            assertTrue(allFive.await(10, TimeUnit.SECONDS))
            assertTrue(future.get(30, TimeUnit.SECONDS).complete)
            assertTrue(maximum.get() <= 4)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun insertAsset(assetId: Long, local: File) {
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(
                "INSERT INTO assets(id, localPath) VALUES(?, ?)",
                arrayOf(assetId, local.absolutePath),
            )
        }
    }

    private fun createWebp(
        name: String,
        width: Int,
        height: Int,
        color: Int,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawColor(color)
            File(sourceRoot, name).also { file ->
                file.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createJpegPreview(assetId: Long, color: Int) {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            Canvas(bitmap).drawColor(color)
            File(jpegRoot, "$assetId.jpg").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun corruptFirstBankMetadata(assetId: Long) {
        val zeroBased = assetId - 1L
        val packIndex = zeroBased / 128L
        val slotIndex = (zeroBased % 128L).toInt()
        val pack = File(rawRoot, "v1/pack_%016x.mgrp".format(packIndex))
        val slotOffset = BACKFILL_RGB565_PACK_HEADER_SIZE.toLong() +
            slotIndex.toLong() * BACKFILL_RGB565_SLOT_STRIDE
        RandomAccessFile(pack, "rw").use {
            it.seek(slotOffset + 0L * BACKFILL_RGB565_BANK_STRIDE)
            it.writeInt(0)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Rgb565BackfillReportAdapter(val complete: Boolean)
}
