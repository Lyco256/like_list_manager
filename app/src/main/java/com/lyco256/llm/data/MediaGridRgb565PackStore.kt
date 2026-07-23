package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write

internal const val MEDIA_GRID_RGB565_FORMAT_VERSION = 1
internal const val MEDIA_GRID_RGB565_SIZE = 256
internal const val MEDIA_GRID_RGB565_SLOT_COUNT = 128
internal const val MEDIA_GRID_RGB565_PAYLOAD_SIZE =
    MEDIA_GRID_RGB565_SIZE * MEDIA_GRID_RGB565_SIZE * 2
internal const val MEDIA_GRID_RGB565_PACK_HEADER_SIZE = 4 * 1024
internal const val MEDIA_GRID_RGB565_BANK_METADATA_SIZE = 256
internal const val MEDIA_GRID_RGB565_BANK_STRIDE =
    MEDIA_GRID_RGB565_BANK_METADATA_SIZE + MEDIA_GRID_RGB565_PAYLOAD_SIZE
internal const val MEDIA_GRID_RGB565_SLOT_STRIDE = MEDIA_GRID_RGB565_BANK_STRIDE * 2
internal const val MEDIA_GRID_RGB565_PACK_SIZE =
    MEDIA_GRID_RGB565_PACK_HEADER_SIZE.toLong() +
        MEDIA_GRID_RGB565_SLOT_COUNT.toLong() * MEDIA_GRID_RGB565_SLOT_STRIDE

internal data class MediaGridRgb565Address(
    val packIndex: Long,
    val slotIndex: Int,
    val slotOffset: Long,
)

internal fun mediaGridRgb565Address(assetId: Long): MediaGridRgb565Address {
    require(assetId >= 1L) { "asset ID must be positive" }
    val zeroBasedId = assetId - 1L
    val packIndex = zeroBasedId / MEDIA_GRID_RGB565_SLOT_COUNT
    val slotIndex = (zeroBasedId % MEDIA_GRID_RGB565_SLOT_COUNT).toInt()
    return MediaGridRgb565Address(
        packIndex = packIndex,
        slotIndex = slotIndex,
        slotOffset = MEDIA_GRID_RGB565_PACK_HEADER_SIZE.toLong() +
            slotIndex.toLong() * MEDIA_GRID_RGB565_SLOT_STRIDE,
    )
}

internal enum class MediaGridRgb565SourceKind(val code: Int) {
    LOCAL_WEBP(1),
    PERSISTENT_JPEG(2),
    ;

    companion object {
        fun fromCode(code: Int): MediaGridRgb565SourceKind? = entries.firstOrNull { it.code == code }
    }
}

internal data class MediaGridRgb565SourceSignature(
    val kind: MediaGridRgb565SourceKind,
    val length: Long,
    val lastModified: Long,
)

internal data class MediaGridRgb565Slot(
    val assetId: Long,
    val packIndex: Long,
    val slotIndex: Int,
    val bankIndex: Int,
    val generation: Long,
    val source: MediaGridRgb565SourceSignature,
    val payloadCrc32: Long,
) {
    val cacheKey: String
        get() = buildString {
            append("media-grid-rgb565-v")
            append(MEDIA_GRID_RGB565_FORMAT_VERSION)
            append('|')
            append(assetId)
            append('|')
            append(packIndex)
            append('|')
            append(slotIndex)
            append('|')
            append(generation)
            append('|')
            append(source.kind.code)
            append('|')
            append(source.length)
            append('|')
            append(source.lastModified)
        }
}

internal data class MediaGridRgb565Payload(
    val bytes: ByteArray,
) {
    init {
        require(bytes.size == MEDIA_GRID_RGB565_PAYLOAD_SIZE)
    }
}

internal enum class MediaGridRgb565PublishStage {
    INCOMPLETE_METADATA_WRITTEN,
    PAYLOAD_WRITTEN,
    PAYLOAD_FORCED,
    COMPLETE_METADATA_WRITTEN,
    METADATA_FORCED,
    MAPPING_INVALIDATED,
}

internal interface MediaGridRgb565Publisher {
    fun publish(
        assetId: Long,
        payload: MediaGridRgb565Payload,
        source: MediaGridRgb565SourceSignature,
        afterStage: ((MediaGridRgb565PublishStage) -> Unit)? = null,
    ): MediaGridRgb565Slot

    fun validatePayloadCrc(slot: MediaGridRgb565Slot): Boolean
}

internal interface MediaGridRgb565BitmapReader {
    fun readBitmap(slot: MediaGridRgb565Slot): Bitmap
}

internal class MediaGridRgb565PackStore(
    filesDir: File,
) : MediaGridRgb565Publisher, MediaGridRgb565BitmapReader {
    private data class BankMetadata(
        val bankIndex: Int,
        val complete: Boolean,
        val generation: Long,
        val assetId: Long,
        val source: MediaGridRgb565SourceSignature,
        val payloadLength: Int,
        val payloadCrc32: Long,
    )

    private class SharedState {
        private val lockReferences = ConcurrentHashMap<Long, WeakReference<ReentrantReadWriteLock>>()
        val mappingMutex = Any()
        var mappingCreations = 0L
        val mappings = object : LinkedHashMap<Long, MappedByteBuffer>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, MappedByteBuffer>?,
            ): Boolean = size > MAX_MAPPED_PACKS
        }

        fun lock(packIndex: Long): ReentrantReadWriteLock {
            while (true) {
                lockReferences[packIndex]?.get()?.let { return it }
                val created = ReentrantReadWriteLock()
                val previous = lockReferences.putIfAbsent(packIndex, WeakReference(created))
                if (previous == null) return created
                previous.get()?.let { return it }
                if (lockReferences.replace(packIndex, previous, WeakReference(created))) return created
            }
        }

        fun invalidateMapping(packIndex: Long) {
            synchronized(mappingMutex) {
                mappings.remove(packIndex)
            }
        }

        fun mappingCount(): Int = synchronized(mappingMutex) { mappings.size }
    }

    private val canonicalFilesDir = filesDir.canonicalFile
    private val packDirectory = File(canonicalFilesDir, "media_grid_rgb565_packs/v1").canonicalFile
    private val sharedState = sharedStateFor(packDirectory)

    fun packFile(packIndex: Long): File {
        require(packIndex >= 0L) { "pack index must be non-negative" }
        val file = File(packDirectory, "pack_%016x.mgrp".format(packIndex)).canonicalFile
        check(file.parentFile == packDirectory) { "pack path escaped filesDir" }
        check(file.path.startsWith(canonicalFilesDir.path + File.separator)) {
            "pack path is outside filesDir"
        }
        return file
    }

    fun readSlot(
        assetId: Long,
        currentSources: Collection<MediaGridRgb565SourceSignature>,
    ): MediaGridRgb565Slot? {
        val address = mediaGridRgb565Address(assetId)
        val sourceSet = currentSources.toSet()
        if (sourceSet.isEmpty()) return null
        return sharedState.lock(address.packIndex).read {
            val mapping = mapping(address.packIndex) ?: return@read null
            if (!validPackHeader(mapping, address.packIndex)) return@read null
            validBanks(mapping, address, assetId)
                .asSequence()
                .filter { it.source in sourceSet }
                .maxByOrNull { it.generation }
                ?.toSlot(address)
        }
    }

    override fun publish(
        assetId: Long,
        payload: MediaGridRgb565Payload,
        source: MediaGridRgb565SourceSignature,
        afterStage: ((MediaGridRgb565PublishStage) -> Unit)?,
    ): MediaGridRgb565Slot {
        val address = mediaGridRgb565Address(assetId)
        return sharedState.lock(address.packIndex).write {
            val file = ensurePack(address.packIndex)
            RandomAccessFile(file, "rw").use { randomAccess ->
                val channel = randomAccess.channel
                val valid = validBanks(channel, address, assetId)
                val current = valid.maxByOrNull { it.generation }
                val targetBank = when (current?.bankIndex) {
                    0 -> 1
                    else -> 0
                }
                val generation = (valid.maxOfOrNull { it.generation } ?: 0L) + 1L
                writeMetadata(
                    channel = channel,
                    address = address,
                    metadata = BankMetadata(
                        bankIndex = targetBank,
                        complete = false,
                        generation = generation,
                        assetId = assetId,
                        source = source,
                        payloadLength = MEDIA_GRID_RGB565_PAYLOAD_SIZE,
                        payloadCrc32 = 0L,
                    ),
                )
                afterStage?.invoke(MediaGridRgb565PublishStage.INCOMPLETE_METADATA_WRITTEN)

                val payloadOffset = bankPayloadOffset(address, targetBank)
                writeFully(channel, ByteBuffer.wrap(payload.bytes), payloadOffset)
                afterStage?.invoke(MediaGridRgb565PublishStage.PAYLOAD_WRITTEN)
                val payloadCrc = crc32(payload.bytes)
                channel.force(false)
                afterStage?.invoke(MediaGridRgb565PublishStage.PAYLOAD_FORCED)

                val complete = BankMetadata(
                    bankIndex = targetBank,
                    complete = true,
                    generation = generation,
                    assetId = assetId,
                    source = source,
                    payloadLength = MEDIA_GRID_RGB565_PAYLOAD_SIZE,
                    payloadCrc32 = payloadCrc,
                )
                writeMetadata(channel, address, complete)
                afterStage?.invoke(MediaGridRgb565PublishStage.COMPLETE_METADATA_WRITTEN)
                channel.force(true)
                afterStage?.invoke(MediaGridRgb565PublishStage.METADATA_FORCED)
            }
            sharedState.invalidateMapping(address.packIndex)
            afterStage?.invoke(MediaGridRgb565PublishStage.MAPPING_INVALIDATED)
            val verified = readSlot(assetId, listOf(source))
                ?: throw IOException("RGB_565 slot publish verification failed")
            if (!validatePayloadCrc(verified)) {
                throw IOException("RGB_565 slot payload verification failed")
            }
            verified
        }
    }

    override fun validatePayloadCrc(slot: MediaGridRgb565Slot): Boolean {
        val address = mediaGridRgb565Address(slot.assetId)
        if (address.packIndex != slot.packIndex || address.slotIndex != slot.slotIndex) return false
        return sharedState.lock(address.packIndex).read {
            val mapping = mapping(address.packIndex) ?: return@read false
            val metadata = readMetadata(mapping, address, slot.bankIndex) ?: return@read false
            if (!metadata.complete ||
                metadata.assetId != slot.assetId ||
                metadata.generation != slot.generation ||
                metadata.source != slot.source ||
                metadata.payloadCrc32 != slot.payloadCrc32
            ) {
                return@read false
            }
            crc32(payloadSlice(mapping, address, slot.bankIndex)) == slot.payloadCrc32
        }
    }

    override fun readBitmap(slot: MediaGridRgb565Slot): Bitmap {
        val address = mediaGridRgb565Address(slot.assetId)
        require(address.packIndex == slot.packIndex && address.slotIndex == slot.slotIndex) {
            "slot address does not match asset ID"
        }
        return sharedState.lock(address.packIndex).read {
            val mapping = mapping(address.packIndex) ?: throw IOException("RGB_565 pack is unavailable")
            val metadata = readMetadata(mapping, address, slot.bankIndex)
                ?: throw IOException("RGB_565 metadata is unavailable")
            if (!metadata.complete ||
                metadata.assetId != slot.assetId ||
                metadata.generation != slot.generation ||
                metadata.source != slot.source ||
                metadata.payloadCrc32 != slot.payloadCrc32
            ) {
                throw IOException("RGB_565 slot changed")
            }
            val bitmap = Bitmap.createBitmap(
                MEDIA_GRID_RGB565_SIZE,
                MEDIA_GRID_RGB565_SIZE,
                Bitmap.Config.RGB_565,
            )
            try {
                bitmap.copyPixelsFromBuffer(payloadSlice(mapping, address, slot.bankIndex))
                check(bitmap.config == Bitmap.Config.RGB_565)
                bitmap
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }
    }

    internal fun readPayloadForTest(slot: MediaGridRgb565Slot): ByteArray {
        val address = mediaGridRgb565Address(slot.assetId)
        return sharedState.lock(address.packIndex).read {
            val mapping = mapping(address.packIndex) ?: throw EOFException()
            val payload = payloadSlice(mapping, address, slot.bankIndex)
            ByteArray(payload.remaining()).also(payload::get)
        }
    }

    internal fun mappingCountForTest(): Int = sharedState.mappingCount()

    internal fun mappingCreationsForTest(): Long =
        synchronized(sharedState.mappingMutex) { sharedState.mappingCreations }

    internal fun isMappedForTest(packIndex: Long): Boolean =
        synchronized(sharedState.mappingMutex) { packIndex in sharedState.mappings }

    internal fun invalidateMappingForTest(packIndex: Long) = sharedState.invalidateMapping(packIndex)

    private fun ensurePack(packIndex: Long): File {
        packDirectory.mkdirs()
        if (!packDirectory.isDirectory) throw IOException("RGB_565 pack directory is unavailable")
        val file = packFile(packIndex)
        RandomAccessFile(file, "rw").use { randomAccess ->
            if (randomAccess.length() > MEDIA_GRID_RGB565_PACK_SIZE) {
                throw IOException("RGB_565 pack has an unexpected length")
            }
            if (randomAccess.length() < MEDIA_GRID_RGB565_PACK_SIZE) {
                randomAccess.setLength(MEDIA_GRID_RGB565_PACK_SIZE)
            }
            val channel = randomAccess.channel
            if (!validPackHeader(channel, packIndex)) {
                val zeroMetadata = ByteBuffer.allocate(MEDIA_GRID_RGB565_BANK_METADATA_SIZE)
                repeat(MEDIA_GRID_RGB565_SLOT_COUNT) { slotIndex ->
                    val address = MediaGridRgb565Address(
                        packIndex,
                        slotIndex,
                        MEDIA_GRID_RGB565_PACK_HEADER_SIZE.toLong() +
                            slotIndex.toLong() * MEDIA_GRID_RGB565_SLOT_STRIDE,
                    )
                    repeat(2) { bankIndex ->
                        zeroMetadata.clear()
                        writeFully(channel, zeroMetadata, bankMetadataOffset(address, bankIndex))
                    }
                }
                channel.force(false)
                writeFully(channel, buildPackHeader(packIndex), 0L)
                channel.force(true)
                sharedState.invalidateMapping(packIndex)
            }
        }
        return file
    }

    private fun mapping(packIndex: Long): MappedByteBuffer? {
        synchronized(sharedState.mappingMutex) {
            sharedState.mappings[packIndex]?.let { return it }
            val file = packFile(packIndex)
            if (!file.isFile || file.length() != MEDIA_GRID_RGB565_PACK_SIZE) return null
            val mapped = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0L, MEDIA_GRID_RGB565_PACK_SIZE)
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            sharedState.mappings[packIndex] = mapped
            sharedState.mappingCreations += 1L
            return mapped
        }
    }

    private fun validBanks(
        buffer: ByteBuffer,
        address: MediaGridRgb565Address,
        assetId: Long,
    ): List<BankMetadata> = buildList {
        repeat(2) { bankIndex ->
            readMetadata(buffer, address, bankIndex)
                ?.takeIf { it.complete && it.assetId == assetId }
                ?.let(::add)
        }
    }

    private fun validBanks(
        channel: FileChannel,
        address: MediaGridRgb565Address,
        assetId: Long,
    ): List<BankMetadata> = buildList {
        repeat(2) { bankIndex ->
            val buffer = ByteBuffer.allocate(MEDIA_GRID_RGB565_BANK_METADATA_SIZE)
            if (readFully(channel, buffer, bankMetadataOffset(address, bankIndex))) {
                buffer.flip()
                readMetadata(buffer, bankIndex)
                    ?.takeIf { it.complete && it.assetId == assetId }
                    ?.let(::add)
            }
        }
    }

    private fun readMetadata(
        buffer: ByteBuffer,
        address: MediaGridRgb565Address,
        bankIndex: Int,
    ): BankMetadata? {
        val offset = bankMetadataOffset(address, bankIndex)
        if (offset < 0L || offset + MEDIA_GRID_RGB565_BANK_METADATA_SIZE > buffer.capacity()) return null
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(offset.toInt())
        duplicate.limit(offset.toInt() + MEDIA_GRID_RGB565_BANK_METADATA_SIZE)
        return readMetadata(duplicate.slice().order(ByteOrder.LITTLE_ENDIAN), bankIndex)
    }

    private fun readMetadata(buffer: ByteBuffer, bankIndex: Int): BankMetadata? {
        if (buffer.remaining() < MEDIA_GRID_RGB565_BANK_METADATA_SIZE) return null
        val bytes = ByteArray(MEDIA_GRID_RGB565_BANK_METADATA_SIZE)
        buffer.duplicate().get(bytes)
        val expectedChecksum = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt(METADATA_CHECKSUM_OFFSET)
        if (crc32(bytes, 0, METADATA_CHECKSUM_OFFSET).toInt() != expectedChecksum) return null
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (data.int != SLOT_MAGIC || data.int != MEDIA_GRID_RGB565_FORMAT_VERSION) return null
        val complete = data.int == 1
        data.int // reserved
        val generation = data.long
        val assetId = data.long
        val sourceKind = MediaGridRgb565SourceKind.fromCode(data.int) ?: return null
        data.int // reserved
        val sourceLength = data.long
        val sourceLastModified = data.long
        val width = data.int
        val height = data.int
        val config = data.int
        val payloadLength = data.int
        val payloadCrc = data.long
        if (generation <= 0L ||
            assetId <= 0L ||
            sourceLength <= 0L ||
            sourceLastModified <= 0L ||
            width != MEDIA_GRID_RGB565_SIZE ||
            height != MEDIA_GRID_RGB565_SIZE ||
            config != CONFIG_RGB_565 ||
            payloadLength != MEDIA_GRID_RGB565_PAYLOAD_SIZE
        ) {
            return null
        }
        return BankMetadata(
            bankIndex = bankIndex,
            complete = complete,
            generation = generation,
            assetId = assetId,
            source = MediaGridRgb565SourceSignature(sourceKind, sourceLength, sourceLastModified),
            payloadLength = payloadLength,
            payloadCrc32 = payloadCrc,
        )
    }

    private fun writeMetadata(
        channel: FileChannel,
        address: MediaGridRgb565Address,
        metadata: BankMetadata,
    ) {
        val bytes = ByteArray(MEDIA_GRID_RGB565_BANK_METADATA_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(SLOT_MAGIC)
        buffer.putInt(MEDIA_GRID_RGB565_FORMAT_VERSION)
        buffer.putInt(if (metadata.complete) 1 else 0)
        buffer.putInt(0)
        buffer.putLong(metadata.generation)
        buffer.putLong(metadata.assetId)
        buffer.putInt(metadata.source.kind.code)
        buffer.putInt(0)
        buffer.putLong(metadata.source.length)
        buffer.putLong(metadata.source.lastModified)
        buffer.putInt(MEDIA_GRID_RGB565_SIZE)
        buffer.putInt(MEDIA_GRID_RGB565_SIZE)
        buffer.putInt(CONFIG_RGB_565)
        buffer.putInt(metadata.payloadLength)
        buffer.putLong(metadata.payloadCrc32)
        buffer.putInt(METADATA_CHECKSUM_OFFSET, crc32(bytes, 0, METADATA_CHECKSUM_OFFSET).toInt())
        writeFully(channel, ByteBuffer.wrap(bytes), bankMetadataOffset(address, metadata.bankIndex))
    }

    private fun BankMetadata.toSlot(address: MediaGridRgb565Address) = MediaGridRgb565Slot(
        assetId = assetId,
        packIndex = address.packIndex,
        slotIndex = address.slotIndex,
        bankIndex = bankIndex,
        generation = generation,
        source = source,
        payloadCrc32 = payloadCrc32,
    )

    private fun payloadSlice(
        buffer: ByteBuffer,
        address: MediaGridRgb565Address,
        bankIndex: Int,
    ): ByteBuffer {
        val offset = bankPayloadOffset(address, bankIndex)
        if (offset < 0L || offset + MEDIA_GRID_RGB565_PAYLOAD_SIZE > buffer.capacity()) {
            throw IOException("RGB_565 payload offset is outside pack")
        }
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        duplicate.position(offset.toInt())
        duplicate.limit(offset.toInt() + MEDIA_GRID_RGB565_PAYLOAD_SIZE)
        return duplicate.slice().order(ByteOrder.nativeOrder())
    }

    private fun validPackHeader(channel: FileChannel, packIndex: Long): Boolean {
        val buffer = ByteBuffer.allocate(MEDIA_GRID_RGB565_PACK_HEADER_SIZE)
        if (!readFully(channel, buffer, 0L)) return false
        buffer.flip()
        return validPackHeader(buffer, packIndex)
    }

    private fun validPackHeader(buffer: ByteBuffer, packIndex: Long): Boolean {
        if (buffer.capacity() < MEDIA_GRID_RGB565_PACK_HEADER_SIZE) return false
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(0)
        duplicate.limit(MEDIA_GRID_RGB565_PACK_HEADER_SIZE)
        val bytes = ByteArray(MEDIA_GRID_RGB565_PACK_HEADER_SIZE)
        duplicate.get(bytes)
        val expectedChecksum = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt(PACK_HEADER_CHECKSUM_OFFSET)
        if (crc32(bytes, 0, PACK_HEADER_CHECKSUM_OFFSET).toInt() != expectedChecksum) return false
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return header.int == PACK_MAGIC &&
            header.int == MEDIA_GRID_RGB565_FORMAT_VERSION &&
            header.long == packIndex &&
            header.int == MEDIA_GRID_RGB565_SLOT_COUNT &&
            header.int == MEDIA_GRID_RGB565_SLOT_STRIDE &&
            header.int == MEDIA_GRID_RGB565_PAYLOAD_SIZE &&
            header.int == MEDIA_GRID_RGB565_SIZE &&
            header.int == MEDIA_GRID_RGB565_SIZE &&
            header.int == CONFIG_RGB_565 &&
            header.long == MEDIA_GRID_RGB565_PACK_SIZE
    }

    private fun buildPackHeader(packIndex: Long): ByteBuffer {
        val bytes = ByteArray(MEDIA_GRID_RGB565_PACK_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PACK_MAGIC)
        buffer.putInt(MEDIA_GRID_RGB565_FORMAT_VERSION)
        buffer.putLong(packIndex)
        buffer.putInt(MEDIA_GRID_RGB565_SLOT_COUNT)
        buffer.putInt(MEDIA_GRID_RGB565_SLOT_STRIDE)
        buffer.putInt(MEDIA_GRID_RGB565_PAYLOAD_SIZE)
        buffer.putInt(MEDIA_GRID_RGB565_SIZE)
        buffer.putInt(MEDIA_GRID_RGB565_SIZE)
        buffer.putInt(CONFIG_RGB_565)
        buffer.putLong(MEDIA_GRID_RGB565_PACK_SIZE)
        buffer.putInt(
            PACK_HEADER_CHECKSUM_OFFSET,
            crc32(bytes, 0, PACK_HEADER_CHECKSUM_OFFSET).toInt(),
        )
        return ByteBuffer.wrap(bytes)
    }

    private fun bankMetadataOffset(address: MediaGridRgb565Address, bankIndex: Int): Long {
        require(bankIndex in 0..1)
        return address.slotOffset + bankIndex.toLong() * MEDIA_GRID_RGB565_BANK_STRIDE
    }

    private fun bankPayloadOffset(address: MediaGridRgb565Address, bankIndex: Int): Long =
        bankMetadataOffset(address, bankIndex) + MEDIA_GRID_RGB565_BANK_METADATA_SIZE

    companion object {
        private const val MAX_MAPPED_PACKS = 4
        private const val PACK_MAGIC = 0x4D475250
        private const val SLOT_MAGIC = 0x4D475253
        private const val CONFIG_RGB_565 = 565
        private const val PACK_HEADER_CHECKSUM_OFFSET = MEDIA_GRID_RGB565_PACK_HEADER_SIZE - 4
        private const val METADATA_CHECKSUM_OFFSET = MEDIA_GRID_RGB565_BANK_METADATA_SIZE - 4
        private val sharedStates = HashMap<String, WeakReference<SharedState>>()

        private fun sharedStateFor(directory: File): SharedState = synchronized(sharedStates) {
            val key = directory.path
            sharedStates[key]?.get()?.let { return@synchronized it }
            SharedState().also { sharedStates[key] = WeakReference(it) }
        }
    }
}

internal fun createMediaGridRgb565Payload(source: Bitmap): MediaGridRgb565Payload {
    require(source.width > 0 && source.height > 0)
    val output = Bitmap.createBitmap(
        MEDIA_GRID_RGB565_SIZE,
        MEDIA_GRID_RGB565_SIZE,
        Bitmap.Config.RGB_565,
    )
    val crop = mediaGridPreviewSourceRect(source.width, source.height)
    try {
        Canvas(output).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                source,
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                Rect(0, 0, MEDIA_GRID_RGB565_SIZE, MEDIA_GRID_RGB565_SIZE),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
        }
        return copyMediaGridRgb565Payload(output)
    } finally {
        output.recycle()
    }
}

internal fun copyMediaGridRgb565Payload(bitmap: Bitmap): MediaGridRgb565Payload {
    require(bitmap.width == MEDIA_GRID_RGB565_SIZE)
    require(bitmap.height == MEDIA_GRID_RGB565_SIZE)
    require(bitmap.config == Bitmap.Config.RGB_565)
    val buffer = ByteBuffer.allocateDirect(MEDIA_GRID_RGB565_PAYLOAD_SIZE)
        .order(ByteOrder.nativeOrder())
    bitmap.copyPixelsToBuffer(buffer)
    buffer.flip()
    val bytes = ByteArray(MEDIA_GRID_RGB565_PAYLOAD_SIZE)
    buffer.get(bytes)
    return MediaGridRgb565Payload(bytes)
}

private fun readFully(channel: FileChannel, buffer: ByteBuffer, offset: Long): Boolean {
    var position = offset
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, position)
        if (read < 0) return false
        if (read == 0) continue
        position += read
    }
    return true
}

private fun writeFully(channel: FileChannel, buffer: ByteBuffer, offset: Long) {
    var position = offset
    while (buffer.hasRemaining()) {
        val written = channel.write(buffer, position)
        if (written <= 0) throw IOException("unable to write RGB_565 pack")
        position += written
    }
}

private fun crc32(bytes: ByteArray): Long = crc32(bytes, 0, bytes.size)

private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long =
    CRC32().apply { update(bytes, offset, length) }.value

private fun crc32(buffer: ByteBuffer): Long {
    val crc = CRC32()
    val duplicate = buffer.duplicate()
    val scratch = ByteArray(16 * 1024)
    while (duplicate.hasRemaining()) {
        val count = minOf(duplicate.remaining(), scratch.size)
        duplicate.get(scratch, 0, count)
        crc.update(scratch, 0, count)
    }
    return crc.value
}
