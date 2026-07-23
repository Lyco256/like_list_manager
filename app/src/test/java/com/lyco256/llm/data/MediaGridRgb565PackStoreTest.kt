package com.lyco256.llm.data

import java.io.IOException
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaGridRgb565PackStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun addressUsesAssetIdOnlyAndFixedPackLayout() {
        assertEquals(MediaGridRgb565Address(0, 0, 4096), mediaGridRgb565Address(1))
        assertEquals(
            MediaGridRgb565Address(
                0,
                127,
                MEDIA_GRID_RGB565_PACK_HEADER_SIZE.toLong() +
                    127L * MEDIA_GRID_RGB565_SLOT_STRIDE,
            ),
            mediaGridRgb565Address(128),
        )
        assertEquals(MediaGridRgb565Address(1, 0, 4096), mediaGridRgb565Address(129))
        assertEquals(128, MEDIA_GRID_RGB565_SLOT_COUNT)
        assertEquals(131_072, MEDIA_GRID_RGB565_PAYLOAD_SIZE)
        assertEquals(
            MEDIA_GRID_RGB565_BANK_METADATA_SIZE * 2 +
                MEDIA_GRID_RGB565_PAYLOAD_SIZE * 2,
            MEDIA_GRID_RGB565_SLOT_STRIDE,
        )
    }

    @Test
    fun publishCreatesFixedPackAndReadsExactPayload() {
        val store = store()
        val payload = payload(0x25)
        val slot = store.publish(1, payload, source(10))

        assertEquals(BitmapConfigRgb565, slotConfigForContract())
        assertEquals(MEDIA_GRID_RGB565_PACK_SIZE, store.packFile(0).length())
        assertArrayEquals(payload.bytes, store.readPayloadForTest(slot))
        assertTrue(store.validatePayloadCrc(slot))
    }

    @Test
    fun interruptedNewBankKeepsPreviousCompleteBank() {
        val store = store()
        val oldPayload = payload(0x11)
        val oldSlot = store.publish(7, oldPayload, source(10))

        try {
            store.publish(7, payload(0x22), source(20)) { stage ->
                if (stage == MediaGridRgb565PublishStage.PAYLOAD_FORCED) {
                    throw IOException("simulated process interruption")
                }
            }
        } catch (_: IOException) {
            // Expected: complete metadata was never published.
        }

        val current = store.readSlot(7, listOf(source(10), source(20)))
        assertEquals(oldSlot.generation, current?.generation)
        assertArrayEquals(oldPayload.bytes, store.readPayloadForTest(current!!))
    }

    @Test
    fun eitherCorruptBankFallsBackToOtherCompleteBank() {
        val storeA = store()
        val firstA = storeA.publish(8, payload(0x31), source(10))
        val secondA = storeA.publish(8, payload(0x32), source(20))
        corruptMetadataByte(storeA, secondA, 0)
        assertEquals(firstA.generation, storeA.readSlot(8, listOf(source(10), source(20)))?.generation)

        val storeB = store()
        val firstB = storeB.publish(9, payload(0x41), source(10))
        val secondB = storeB.publish(9, payload(0x42), source(20))
        corruptMetadataByte(storeB, firstB, 0)
        assertEquals(secondB.generation, storeB.readSlot(9, listOf(source(10), source(20)))?.generation)
    }

    @Test
    fun newestValidGenerationWinsAndChangesCacheKey() {
        val store = store()
        val first = store.publish(11, payload(0x51), source(10))
        val second = store.publish(11, payload(0x52), source(20))

        assertEquals(second.generation, store.readSlot(11, listOf(source(10), source(20)))?.generation)
        assertTrue(second.generation > first.generation)
        assertNotEquals(first.cacheKey, second.cacheKey)
    }

    @Test
    fun assetMismatchStaleSourceAndBadChecksumAreRejected() {
        val staleStore = store()
        staleStore.publish(12, payload(0x61), source(10))
        assertNull(staleStore.readSlot(12, listOf(source(99))))

        val assetStore = store()
        val assetSlot = assetStore.publish(13, payload(0x62), source(10))
        corruptMetadataLong(assetStore, assetSlot, 24, 999)
        assertNull(assetStore.readSlot(13, listOf(source(10))))

        val checksumStore = store()
        val checksumSlot = checksumStore.publish(14, payload(0x63), source(10))
        corruptMetadataByte(
            checksumStore,
            checksumSlot,
            MEDIA_GRID_RGB565_BANK_METADATA_SIZE - 4,
        )
        assertNull(checksumStore.readSlot(14, listOf(source(10))))
    }

    @Test
    fun packPathCannotEscapeFilesDirAndMappingLruHoldsAtMostFourPacks() {
        val root = temporary.newFolder("files-lru")
        val store = MediaGridRgb565PackStore(root)
        repeat(5) { pack ->
            val assetId = pack.toLong() * MEDIA_GRID_RGB565_SLOT_COUNT + 1L
            store.publish(assetId, payload(pack), source(pack + 1L))
        }

        assertTrue(store.packFile(0).canonicalPath.startsWith(root.canonicalPath))
        assertTrue(store.mappingCountForTest() <= 4)
        assertFalse(store.isMappedForTest(0))
        try {
            store.packFile(-1)
            throw AssertionError("negative pack index was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun writeInvalidatesOnlyItsPackMappingBeforeVerificationRemaps() {
        val store = store()
        store.publish(1, payload(1), source(1))
        store.publish(129, payload(2), source(2))
        assertTrue(store.isMappedForTest(0))
        assertTrue(store.isMappedForTest(1))

        var otherPackRemainedMapped = false
        var writtenPackWasInvalidated = false
        store.publish(1, payload(3), source(3)) { stage ->
            if (stage == MediaGridRgb565PublishStage.MAPPING_INVALIDATED) {
                writtenPackWasInvalidated = !store.isMappedForTest(0)
                otherPackRemainedMapped = store.isMappedForTest(1)
            }
        }
        assertTrue(writtenPackWasInvalidated)
        assertTrue(otherPackRemainedMapped)
    }

    @Test
    fun samePackWritesSerializeWhileDifferentPacksCanEnterConcurrently() {
        val store = store()
        val samePackEntered = CountDownLatch(1)
        val releaseSamePack = CountDownLatch(1)
        val secondSamePackEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit {
                store.publish(1, payload(1), source(1)) { stage ->
                    if (stage == MediaGridRgb565PublishStage.INCOMPLETE_METADATA_WRITTEN) {
                        samePackEntered.countDown()
                        releaseSamePack.await(5, TimeUnit.SECONDS)
                    }
                }
            }
            assertTrue(samePackEntered.await(5, TimeUnit.SECONDS))
            val second = pool.submit {
                store.publish(2, payload(2), source(2)) { stage ->
                    if (stage == MediaGridRgb565PublishStage.INCOMPLETE_METADATA_WRITTEN) {
                        secondSamePackEntered.countDown()
                    }
                }
            }
            assertFalse(secondSamePackEntered.await(150, TimeUnit.MILLISECONDS))

            val differentPackEntered = CountDownLatch(1)
            val different = pool.submit {
                store.publish(129, payload(3), source(3)) { stage ->
                    if (stage == MediaGridRgb565PublishStage.INCOMPLETE_METADATA_WRITTEN) {
                        differentPackEntered.countDown()
                    }
                }
            }
            assertTrue(differentPackEntered.await(5, TimeUnit.SECONDS))
            releaseSamePack.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            different.get(5, TimeUnit.SECONDS)
            assertTrue(secondSamePackEntered.await(5, TimeUnit.SECONDS))
        } finally {
            releaseSamePack.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun rawCandidatePrecedesJpegLocalAndUrls() {
        val store = store()
        val raw = store.publish(15, payload(9), source(9))
        val local = temporary.newFile("local.webp").apply { writeBytes(byteArrayOf(1)) }
        val input = MediaGridImageCandidateInput(
            assetId = 15,
            mediaKey = "media-15",
            localPath = local.absolutePath,
            previewUrl = "https://preview",
            remoteUrl = "https://remote",
            displayUrl = "https://display",
            rgb565Slot = raw,
            persistentPreview = MediaGridPersistentPreviewMetadata("/tmp/15.jpg", 10, 20),
        )

        assertEquals(
            listOf(
                MediaGridImageSourceKind.Rgb565Pack,
                MediaGridImageSourceKind.PersistentPreview,
                MediaGridImageSourceKind.Local,
                MediaGridImageSourceKind.Preview,
                MediaGridImageSourceKind.Remote,
                MediaGridImageSourceKind.Display,
            ),
            buildMediaGridImageCandidates(input).map { it.kind },
        )
    }

    @Test
    fun rgb565ImplementationUsesBufferCopiesDitherAndNoTimingThrottle() {
        val storeSource = File(
            "src/main/java/com/lyco256/llm/data/MediaGridRgb565PackStore.kt",
        ).readText()
        val coilSource = File(
            "src/main/java/com/lyco256/llm/data/MediaGridRgb565Coil.kt",
        ).readText()
        val repairSource = File(
            "src/main/java/com/lyco256/llm/data/MediaGridRgb565RepairWork.kt",
        ).readText()

        assertTrue(storeSource.contains("copyPixelsToBuffer"))
        assertTrue(storeSource.contains("copyPixelsFromBuffer"))
        assertTrue(storeSource.contains("Paint.DITHER_FLAG"))
        assertFalse(storeSource.contains(".getPixels("))
        assertFalse(coilSource.contains("Decoder"))
        listOf(storeSource, coilSource, repairSource).forEach { source ->
            assertFalse(source.contains("Thread.sleep"))
            assertFalse(source.contains("delay("))
        }
    }

    private fun store(): MediaGridRgb565PackStore =
        MediaGridRgb565PackStore(temporary.newFolder())

    private fun payload(value: Int): MediaGridRgb565Payload =
        MediaGridRgb565Payload(ByteArray(MEDIA_GRID_RGB565_PAYLOAD_SIZE) { value.toByte() })

    private fun source(stamp: Long): MediaGridRgb565SourceSignature =
        MediaGridRgb565SourceSignature(
            MediaGridRgb565SourceKind.LOCAL_WEBP,
            1_000 + stamp,
            2_000 + stamp,
        )

    private fun corruptMetadataByte(
        store: MediaGridRgb565PackStore,
        slot: MediaGridRgb565Slot,
        byteOffset: Int,
    ) {
        val address = mediaGridRgb565Address(slot.assetId)
        val offset = address.slotOffset +
            slot.bankIndex.toLong() * MEDIA_GRID_RGB565_BANK_STRIDE +
            byteOffset
        RandomAccessFile(store.packFile(slot.packIndex), "rw").use {
            it.seek(offset)
            val original = it.read()
            it.seek(offset)
            it.writeByte(original.xor(0x5A))
        }
        store.invalidateMappingForTest(slot.packIndex)
    }

    private fun corruptMetadataLong(
        store: MediaGridRgb565PackStore,
        slot: MediaGridRgb565Slot,
        byteOffset: Int,
        value: Long,
    ) {
        val address = mediaGridRgb565Address(slot.assetId)
        val offset = address.slotOffset +
            slot.bankIndex.toLong() * MEDIA_GRID_RGB565_BANK_STRIDE +
            byteOffset
        RandomAccessFile(store.packFile(slot.packIndex), "rw").use {
            it.seek(offset)
            it.writeLong(value)
        }
        store.invalidateMappingForTest(slot.packIndex)
    }

    private fun slotConfigForContract(): Int = BitmapConfigRgb565

    companion object {
        private const val BitmapConfigRgb565 = 565
    }
}
