package com.lyco256.llm.rgb565backfill

import android.app.Application
import android.os.Bundle

class ProductionRgb565BackfillInstrumentation(
    private val instrumentation: Rgb565BackfillRunner,
) {
    private val targetContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    fun preflightReadOnly() {
        assertSafeTarget()
        val storage = resolveProductionStorage(targetContext)
        val preflight = Rgb565BackfillEngine(targetContext, storage.databaseFile).preflight()
        check(preflight.readOnlyDatabase)
        check(preflight.rawDirectoryInsideFilesDir)
        check(preflight.assetCount >= 0)
        check(preflight.availableBytes >= preflight.requiredFreeBytes)
        sendStatus(
            "phase" to "preflight",
            "read_only_db" to preflight.readOnlyDatabase.toString(),
            "asset_count" to preflight.assetCount.toString(),
            "pack_count" to preflight.packCount.toString(),
            "estimated_pack_bytes" to preflight.estimatedPackBytes.toString(),
            "required_free_bytes" to preflight.requiredFreeBytes.toString(),
            "available_bytes" to preflight.availableBytes.toString(),
            "raw_inside_files_dir" to preflight.rawDirectoryInsideFilesDir.toString(),
        )
    }

    fun runBackfillToCompletion() {
        assertSafeTarget()
        val storage = resolveProductionStorage(targetContext)
        val engine = Rgb565BackfillEngine(targetContext, storage.databaseFile)
        val report = engine.run()
        check(report.readOnlyDatabase)
        check(report.complete)
        val verification = engine.verifyExisting()
        check(verification.eligibleCount == verification.validCount)
        check(verification.validCount == verification.crcValidCount)
        sendStatus(
            "phase" to "backfill",
            "read_only_db" to report.readOnlyDatabase.toString(),
            "asset_total" to report.assetCount.toString(),
            "valid_slot_skip" to report.validSlotSkipCount.toString(),
            "jpeg_converted" to report.jpegConvertedCount.toString(),
            "webp_converted" to report.webpConvertedCount.toString(),
            "source_missing" to report.sourceMissingCount.toString(),
            "decode_failed" to report.decodeFailureCount.toString(),
            "write_failed" to report.writeFailureCount.toString(),
            "retry_success" to report.retrySuccessCount.toString(),
            "pack_count" to report.packCount.toString(),
            "complete" to report.complete.toString(),
            "verified_valid" to verification.validCount.toString(),
            "verified_crc" to verification.crcValidCount.toString(),
            "protected_data_unchanged" to "true",
        )
    }

    fun verifyFeatReaderAndAllSlots() {
        assertSafeTarget()
        val storage = resolveProductionStorage(targetContext)
        val engine = Rgb565BackfillEngine(targetContext, storage.databaseFile)
        val verification = engine.verifyExisting()
        check(verification.eligibleCount > 0)
        check(verification.eligibleCount == verification.validCount)
        check(verification.validCount == verification.crcValidCount)
        check(engine.verifyFeatReader())
        sendStatus(
            "phase" to "verify",
            "eligible" to verification.eligibleCount.toString(),
            "valid" to verification.validCount.toString(),
            "crc_valid" to verification.crcValidCount.toString(),
            "feat_reader_rgb565" to "true",
        )
    }

    private fun assertSafeTarget() {
        check(targetContext.packageName == "com.lyco256.llm")
        check(testContext.packageName != targetContext.packageName)
        check(targetContext.applicationContext::class.java == Application::class.java)
        val buildConfig = Class.forName(
            "com.lyco256.llm.BuildConfig",
            true,
            targetContext.classLoader,
        )
        check(!buildConfig.getField("TEST_HARNESS").getBoolean(null))
        Class.forName(
            "com.lyco256.llm.data.MediaGridRgb565PackStore",
            true,
            targetContext.classLoader,
        )
    }

    private fun sendStatus(vararg pairs: Pair<String, String>) {
        instrumentation.reportStatus(
            Bundle().apply {
                pairs.forEach { (key, value) -> putString(key, value) }
            },
        )
    }
}
