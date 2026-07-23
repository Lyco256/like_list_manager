package com.lyco256.llm.rgb565backfill

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

internal const val BACKFILL_RGB565_SIZE = 256
internal const val BACKFILL_RGB565_PAYLOAD_SIZE = 131_072
internal const val BACKFILL_RGB565_SLOT_COUNT = 128
internal const val BACKFILL_RGB565_PACK_HEADER_SIZE = 4_096
internal const val BACKFILL_RGB565_BANK_METADATA_SIZE = 256
internal const val BACKFILL_RGB565_BANK_STRIDE =
    BACKFILL_RGB565_BANK_METADATA_SIZE + BACKFILL_RGB565_PAYLOAD_SIZE
internal const val BACKFILL_RGB565_SLOT_STRIDE = BACKFILL_RGB565_BANK_STRIDE * 2
internal const val BACKFILL_RGB565_PACK_SIZE =
    BACKFILL_RGB565_PACK_HEADER_SIZE.toLong() +
        BACKFILL_RGB565_SLOT_COUNT.toLong() * BACKFILL_RGB565_SLOT_STRIDE

internal data class Rgb565BackfillAsset(
    val id: Long,
    val localPath: String,
)

internal data class Rgb565BackfillPreflight(
    val readOnlyDatabase: Boolean,
    val assetCount: Int,
    val packCount: Int,
    val estimatedPackBytes: Long,
    val requiredFreeBytes: Long,
    val availableBytes: Long,
    val rawDirectoryInsideFilesDir: Boolean,
)

internal enum class Rgb565BackfillOutcome {
    SKIPPED_VALID,
    CONVERTED_JPEG,
    CONVERTED_WEBP,
    SOURCE_MISSING,
    DECODE_FAILED,
    WRITE_FAILED,
}

internal data class Rgb565BackfillAssetResult(
    val assetId: Long,
    val outcome: Rgb565BackfillOutcome,
)

internal data class Rgb565BackfillReport(
    val readOnlyDatabase: Boolean,
    val assetCount: Int,
    val validSlotSkipCount: Int,
    val jpegConvertedCount: Int,
    val webpConvertedCount: Int,
    val sourceMissingCount: Int,
    val decodeFailureCount: Int,
    val writeFailureCount: Int,
    val retrySuccessCount: Int,
    val packCount: Int,
    val startedAt: String,
    val finishedAt: String,
    val complete: Boolean,
) {
    fun asText(): String = buildString {
        appendLine("read_only_db=$readOnlyDatabase")
        appendLine("asset_total=$assetCount")
        appendLine("valid_slot_skip=$validSlotSkipCount")
        appendLine("jpeg_converted=$jpegConvertedCount")
        appendLine("webp_converted=$webpConvertedCount")
        appendLine("source_missing=$sourceMissingCount")
        appendLine("decode_failed=$decodeFailureCount")
        appendLine("write_failed=$writeFailureCount")
        appendLine("retry_success=$retrySuccessCount")
        appendLine("pack_count=$packCount")
        appendLine("started_at=$startedAt")
        appendLine("finished_at=$finishedAt")
        appendLine("complete=$complete")
    }
}

internal data class Rgb565BackfillVerification(
    val eligibleCount: Int,
    val validCount: Int,
    val crcValidCount: Int,
)

internal data class Rgb565StorageSelection(
    val databaseFile: File,
    val imagesDirectory: File,
)

internal interface Rgb565BackfillPackObserver {
    fun onPackStart(packIndex: Long)
    fun onPackFinished(packIndex: Long)
}

internal class Rgb565BackfillEngine(
    private val context: Context,
    private val databaseFile: File,
    private val packStore: TargetRgb565PackStore = TargetRgb565PackStore(context),
    private val reportDirectory: File = File(
        context.filesDir,
        "media_grid_rgb565_packs/v1/backfill",
    ),
    private val packObserver: Rgb565BackfillPackObserver? = null,
) {
    internal data class Source(
        val file: File,
        val kind: TargetRgb565SourceKind,
        val length: Long,
        val lastModified: Long,
    )

    fun preflight(): Rgb565BackfillPreflight {
        val assets = readAssets()
        val packCount = assets.map { packIndex(it.id) }.distinct().size
        val estimated = packCount.toLong() * BACKFILL_RGB565_PACK_SIZE
        val required = (estimated * 12L) / 10L + 512L * 1024L * 1024L
        val rawRoot = File(context.filesDir, "media_grid_rgb565_packs/v1").canonicalFile
        val filesDir = context.filesDir.canonicalFile
        return Rgb565BackfillPreflight(
            readOnlyDatabase = true,
            assetCount = assets.size,
            packCount = packCount,
            estimatedPackBytes = estimated,
            requiredFreeBytes = required,
            availableBytes = StatFs(filesDir.path).availableBytes,
            rawDirectoryInsideFilesDir = rawRoot.path.startsWith(filesDir.path + File.separator),
        )
    }

    fun run(): Rgb565BackfillReport {
        val startedAt = Instant.now().toString()
        val assets = readAssets()
        val protectedBefore = protectedFingerprint(assets)
        var results = processRound(assets)
        val initiallyFailed = results
            .filter { it.outcome.isRetryable() }
            .map(Rgb565BackfillAssetResult::assetId)
            .toSet()
        var retrySuccess = 0
        repeat(2) {
            val retryIds = results
                .filter { it.outcome.isRetryable() }
                .map(Rgb565BackfillAssetResult::assetId)
                .toSet()
            if (retryIds.isEmpty()) return@repeat
            val retryAssets = assets.filter { it.id in retryIds }
            val retried = processRound(retryAssets).associateBy(Rgb565BackfillAssetResult::assetId)
            results = results.map { old -> retried[old.assetId] ?: old }
        }
        retrySuccess = results.count {
            it.assetId in initiallyFailed && it.outcome != Rgb565BackfillOutcome.WRITE_FAILED
        }
        val verification = verify(assets, results)
        val protectedAfter = protectedFingerprint(readAssets())
        check(protectedBefore == protectedAfter) {
            "DB, source image, JPEG, or settings fingerprint changed"
        }
        val report = Rgb565BackfillReport(
            readOnlyDatabase = true,
            assetCount = assets.size,
            validSlotSkipCount = results.count { it.outcome == Rgb565BackfillOutcome.SKIPPED_VALID },
            jpegConvertedCount = results.count { it.outcome == Rgb565BackfillOutcome.CONVERTED_JPEG },
            webpConvertedCount = results.count { it.outcome == Rgb565BackfillOutcome.CONVERTED_WEBP },
            sourceMissingCount = results.count { it.outcome == Rgb565BackfillOutcome.SOURCE_MISSING },
            decodeFailureCount = results.count { it.outcome == Rgb565BackfillOutcome.DECODE_FAILED },
            writeFailureCount = results.count { it.outcome == Rgb565BackfillOutcome.WRITE_FAILED },
            retrySuccessCount = retrySuccess,
            packCount = assets.map { packIndex(it.id) }.distinct().size,
            startedAt = startedAt,
            finishedAt = Instant.now().toString(),
            complete = results.none { it.outcome.isRetryable() } &&
                verification.eligibleCount == verification.validCount &&
                verification.validCount == verification.crcValidCount,
        )
        writeReport(report)
        return report
    }

    fun verifyExisting(): Rgb565BackfillVerification {
        val assets = readAssets()
        val synthetic = assets.map { asset ->
            val sources = sources(asset)
            val slot = packStore.readSlot(asset.id, sources)
            Rgb565BackfillAssetResult(
                asset.id,
                when {
                    sources.isEmpty() -> Rgb565BackfillOutcome.SOURCE_MISSING
                    slot != null && packStore.validatePayloadCrc(slot) ->
                        Rgb565BackfillOutcome.SKIPPED_VALID
                    else -> Rgb565BackfillOutcome.WRITE_FAILED
                },
            )
        }
        return verify(assets, synthetic)
    }

    fun verifyFeatReader(): Boolean {
        val asset = readAssets().firstOrNull { sources(it).isNotEmpty() } ?: return false
        val slot = packStore.readSlot(asset.id, sources(asset)) ?: return false
        val bitmap = packStore.readBitmap(slot)
        return try {
            bitmap.config == Bitmap.Config.RGB_565 &&
                bitmap.width == BACKFILL_RGB565_SIZE &&
                bitmap.height == BACKFILL_RGB565_SIZE
        } finally {
            bitmap.recycle()
        }
    }

    private fun processRound(assets: List<Rgb565BackfillAsset>): List<Rgb565BackfillAssetResult> {
        if (assets.isEmpty()) return emptyList()
        val groups = assets.groupBy { packIndex(it.id) }.toSortedMap()
        val parallelism = min(
            4,
            min(
                max(1, Runtime.getRuntime().availableProcessors() - 1),
                groups.size,
            ),
        )
        val executor = Executors.newFixedThreadPool(parallelism)
        return try {
            groups.map { (packIndex, packAssets) ->
                executor.submit(
                    Callable {
                        packObserver?.onPackStart(packIndex)
                        try {
                            packAssets
                                .sortedBy(Rgb565BackfillAsset::id)
                                .map(::processAsset)
                        } finally {
                            packObserver?.onPackFinished(packIndex)
                        }
                    },
                )
            }.flatMap { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun processAsset(asset: Rgb565BackfillAsset): Rgb565BackfillAssetResult {
        val sources = sources(asset)
        if (sources.isEmpty()) {
            return Rgb565BackfillAssetResult(asset.id, Rgb565BackfillOutcome.SOURCE_MISSING)
        }
        val current = try {
            packStore.readSlot(asset.id, sources)
        } catch (_: IOException) {
            return Rgb565BackfillAssetResult(asset.id, Rgb565BackfillOutcome.WRITE_FAILED)
        }
        if (current != null && packStore.validatePayloadCrc(current)) {
            return Rgb565BackfillAssetResult(asset.id, Rgb565BackfillOutcome.SKIPPED_VALID)
        }
        val decoded = sources.firstNotNullOfOrNull { source ->
            val payload = try {
                decodePayload(source.file)
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            payload?.let { source to it }
        } ?: return Rgb565BackfillAssetResult(
            asset.id,
            Rgb565BackfillOutcome.DECODE_FAILED,
        )
        val (source, payload) = decoded
        if (!source.file.isFile ||
            source.file.length() != source.length ||
            source.file.lastModified() != source.lastModified
        ) {
            return Rgb565BackfillAssetResult(asset.id, Rgb565BackfillOutcome.WRITE_FAILED)
        }
        return try {
            val slot = packStore.publish(asset.id, payload, source)
            if (!packStore.validatePayloadCrc(slot)) throw IOException("published CRC is invalid")
            Rgb565BackfillAssetResult(
                asset.id,
                if (source.kind == TargetRgb565SourceKind.PERSISTENT_JPEG) {
                    Rgb565BackfillOutcome.CONVERTED_JPEG
                } else {
                    Rgb565BackfillOutcome.CONVERTED_WEBP
                },
            )
        } catch (_: IOException) {
            Rgb565BackfillAssetResult(asset.id, Rgb565BackfillOutcome.WRITE_FAILED)
        }
    }

    private fun verify(
        assets: List<Rgb565BackfillAsset>,
        results: List<Rgb565BackfillAssetResult>,
    ): Rgb565BackfillVerification {
        val resultById = results.associateBy(Rgb565BackfillAssetResult::assetId)
        var eligible = 0
        var valid = 0
        var crcValid = 0
        assets.forEach { asset ->
            val outcome = resultById[asset.id]?.outcome
            if (outcome == Rgb565BackfillOutcome.SOURCE_MISSING) return@forEach
            val sources = sources(asset)
            if (sources.isEmpty()) return@forEach
            eligible += 1
            val slot = packStore.readSlot(asset.id, sources) ?: return@forEach
            valid += 1
            if (packStore.validatePayloadCrc(slot)) crcValid += 1
        }
        return Rgb565BackfillVerification(eligible, valid, crcValid)
    }

    private fun sources(asset: Rgb565BackfillAsset): List<Source> {
        val local = File(asset.localPath).absoluteFile.takeIf(File::isFile)
        val jpeg = File(
            context.filesDir,
            "media_grid_previews/v1/${asset.id}.jpg",
        ).absoluteFile
        val validJpeg = jpeg.takeIf {
            isValidJpeg(it) && (local == null || it.lastModified() >= local.lastModified())
        }
        return buildList {
            validJpeg?.let {
                add(Source(it, TargetRgb565SourceKind.PERSISTENT_JPEG, it.length(), it.lastModified()))
            }
            local?.let {
                add(Source(it, TargetRgb565SourceKind.LOCAL_WEBP, it.length(), it.lastModified()))
            }
        }
    }

    private fun isValidJpeg(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth == BACKFILL_RGB565_SIZE &&
            options.outHeight == BACKFILL_RGB565_SIZE &&
            options.outMimeType == "image/jpeg"
    }

    private fun decodePayload(source: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = inSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: return null
        return try {
            if (decoded.width == BACKFILL_RGB565_SIZE &&
                decoded.height == BACKFILL_RGB565_SIZE &&
                decoded.config == Bitmap.Config.RGB_565
            ) {
                copyPayload(decoded)
            } else {
                val output = Bitmap.createBitmap(
                    BACKFILL_RGB565_SIZE,
                    BACKFILL_RGB565_SIZE,
                    Bitmap.Config.RGB_565,
                )
                try {
                    val crop = min(decoded.width, decoded.height)
                    val left = (decoded.width - crop) / 2
                    val top = (decoded.height - crop) / 2
                    Canvas(output).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(
                            decoded,
                            Rect(left, top, left + crop, top + crop),
                            Rect(0, 0, BACKFILL_RGB565_SIZE, BACKFILL_RGB565_SIZE),
                            Paint(
                                Paint.ANTI_ALIAS_FLAG or
                                    Paint.FILTER_BITMAP_FLAG or
                                    Paint.DITHER_FLAG,
                            ),
                        )
                    }
                    copyPayload(output)
                } finally {
                    output.recycle()
                }
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun copyPayload(bitmap: Bitmap): ByteArray {
        check(bitmap.config == Bitmap.Config.RGB_565)
        val buffer = ByteBuffer.allocateDirect(BACKFILL_RGB565_PAYLOAD_SIZE)
            .order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        return ByteArray(BACKFILL_RGB565_PAYLOAD_SIZE).also(buffer::get)
    }

    private fun inSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (min(width / (sample * 2), height / (sample * 2)) >= BACKFILL_RGB565_SIZE) {
            sample *= 2
        }
        return sample
    }

    private fun readAssets(): List<Rgb565BackfillAsset> {
        check(databaseFile.isFile) { "production database is missing" }
        val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, flags).use { database ->
            check(database.isReadOnly) { "database connection is not read-only" }
            database.rawQuery(
                "SELECT id, localPath FROM assets WHERE id > 0 AND localPath IS NOT NULL ORDER BY id",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val localPath = cursor.getString(1)?.trim().orEmpty()
                        if (id > 0L && localPath.isNotEmpty()) {
                            add(Rgb565BackfillAsset(id, localPath))
                        }
                    }
                }
            }
        }
    }

    private fun protectedFingerprint(assets: List<Rgb565BackfillAsset>): String {
        val files = linkedSetOf<File>()
        files += databaseFile
        files += File(databaseFile.path + "-wal")
        assets.forEach { asset ->
            files += File(asset.localPath)
            files += File(context.filesDir, "media_grid_previews/v1/${asset.id}.jpg")
        }
        File(context.applicationInfo.dataDir, "shared_prefs")
            .listFiles()
            ?.filter(File::isFile)
            ?.forEach(files::add)
        val digest = MessageDigest.getInstance("SHA-256")
        files.asSequence()
            .map { file -> file.absoluteFile }
            .filter { file -> file.isFile }
            .distinctBy { file -> file.canonicalPath }
            .sortedBy { file -> file.canonicalPath }
            .forEach { file ->
                digest.update(file.canonicalPath.toByteArray())
                digest.update(ByteBuffer.allocate(16).putLong(file.length()).putLong(file.lastModified()).array())
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeReport(report: Rgb565BackfillReport) {
        reportDirectory.mkdirs()
        check(reportDirectory.isDirectory)
        val reportFile = File(reportDirectory, "latest-report.txt").canonicalFile
        check(reportFile.parentFile == reportDirectory.canonicalFile)
        val temporary = File(reportDirectory, ".latest-report.tmp").canonicalFile
        check(temporary.parentFile == reportDirectory.canonicalFile)
        FileOutputStream(temporary).use { output ->
            output.write(report.asText().toByteArray())
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                reportFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(
                temporary.toPath(),
                reportFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun packIndex(assetId: Long): Long = (assetId - 1L) / BACKFILL_RGB565_SLOT_COUNT

    private fun Rgb565BackfillOutcome.isRetryable(): Boolean =
        this == Rgb565BackfillOutcome.DECODE_FAILED ||
            this == Rgb565BackfillOutcome.WRITE_FAILED
}

internal enum class TargetRgb565SourceKind {
    LOCAL_WEBP,
    PERSISTENT_JPEG,
}

/**
 * Calls the pack implementation from the installed feat APK. The temp APK owns no
 * independent pack format implementation.
 */
internal class TargetRgb565PackStore(
    private val context: Context,
) {
    private val classLoader = context.classLoader
    private val storeClass = load("com.lyco256.llm.data.MediaGridRgb565PackStore")
    private val payloadClass = load("com.lyco256.llm.data.MediaGridRgb565Payload")
    private val sourceClass = load("com.lyco256.llm.data.MediaGridRgb565SourceSignature")
    private val sourceKindClass = load("com.lyco256.llm.data.MediaGridRgb565SourceKind")
    private val store = storeClass.getDeclaredConstructor(File::class.java).apply {
        isAccessible = true
    }.newInstance(context.filesDir)
    private val payloadConstructor = payloadClass.getDeclaredConstructor(ByteArray::class.java).apply {
        isAccessible = true
    }
    private val sourceConstructor = sourceClass.declaredConstructors.single().apply {
        isAccessible = true
    }
    private val publishMethod = storeClass.methods.single {
        it.name == "publish" && it.parameterTypes.size == 4
    }
    private val readSlotMethod = storeClass.methods.single {
        it.name == "readSlot" && it.parameterTypes.size == 2
    }
    private val validateMethod = storeClass.methods.single {
        it.name == "validatePayloadCrc" && it.parameterTypes.size == 1
    }
    private val readBitmapMethod = storeClass.methods.single {
        it.name == "readBitmap" && it.parameterTypes.size == 1
    }

    fun readSlot(assetId: Long, sources: Collection<Any>): Any? =
        invoke(readSlotMethod, store, assetId, sources)

    fun readSlot(assetId: Long, sources: List<Rgb565BackfillEngine.Source>): Any? =
        readSlot(assetId, sources.map(::sourceObject))

    fun publish(
        assetId: Long,
        payload: ByteArray,
        source: Rgb565BackfillEngine.Source,
    ): Any {
        val payloadObject = payloadConstructor.newInstance(payload)
        return requireNotNull(
            invoke(
                publishMethod,
                store,
                assetId,
                payloadObject,
                sourceObject(source),
                null,
            ),
        )
    }

    fun validatePayloadCrc(slot: Any): Boolean =
        invoke(validateMethod, store, slot) as Boolean

    fun readBitmap(slot: Any): Bitmap =
        invoke(readBitmapMethod, store, slot) as Bitmap

    private fun sourceObject(source: Rgb565BackfillEngine.Source): Any {
        @Suppress("UNCHECKED_CAST")
        val enumClass = sourceKindClass as Class<out Enum<*>>
        val kind = enumClass.enumConstants.single { it.name == source.kind.name }
        return sourceConstructor.newInstance(kind, source.length, source.lastModified)
    }

    private fun load(name: String): Class<*> = Class.forName(name, true, classLoader)

    private fun invoke(method: java.lang.reflect.Method, receiver: Any, vararg args: Any?): Any? =
        try {
            method.invoke(receiver, *args)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            if (cause is IOException) throw cause
            if (cause is RuntimeException) throw cause
            throw RuntimeException(cause)
        }
}

internal fun resolveProductionStorage(context: Context): Rgb565StorageSelection {
    val preferences = context.getSharedPreferences("post_storage_settings", Context.MODE_PRIVATE)
    val selectedId = preferences.getString("selected_id", "internal").orEmpty()
    return if (selectedId.startsWith("external:")) {
        val root = File(preferences.getString("selected_path", "").orEmpty()).absoluteFile
        check(root.isDirectory) { "selected external storage is unavailable" }
        Rgb565StorageSelection(
            databaseFile = File(root, "like_list_manager.db"),
            imagesDirectory = File(root, "images"),
        )
    } else {
        Rgb565StorageSelection(
            databaseFile = context.getDatabasePath("like_list_manager.db"),
            imagesDirectory = File(context.filesDir, "images"),
        )
    }
}
