package com.lyco256.llm.macrobenchmark

import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MediaGridPerformanceMacrobenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    private val modes = listOf("FRAME_ONLY", "PRIORITY_ONLY", "CACHED_UI", "ENCODER_ONLY", "FULL")
    private val scenarios = listOf("fast_round_trip", "slow_drag", "settle_after_scroll", "single_fling")
    private val pinchScenarios = listOf("pinch_4_5_4", "pinch_8_9_8", "pinch_return")
    private val validationRoot = "/sdcard/Android/data/com.lyco256.llm.test.benchmark/files/media-grid-validation"
    private val traceNames = listOf(
        "MediaGridViewportCapture", "MediaGridViewportPublish", "MediaGridPrioritySelect",
        "MediaGridCacheLookup", "MediaGridSourceRead", "MediaGridSourceDecode",
        "MediaGridThumbnailResize", "MediaGridThumbnailEncode", "MediaGridThumbnailWrite",
        "MediaGridReadyPublish", "MediaGridCellImageApply", "MediaGridMorphPlan",
        "MediaGridMorphFirstDraw", "MediaGridMorphHandoff",
    )

    @Test
    fun allModesAndScenarios() {
        modes.forEach { mode -> scenarios.forEach { scenario -> measure(mode, scenario) } }
    }

    @Test
    fun twoPointerColumnMorphs() {
        modes.forEach { mode -> pinchScenarios.forEach { scenario ->
            assertSnapshotPrepared()
            benchmarkRule.measureRepeated(
                    packageName = TARGET,
                    metrics = metrics(),
                    compilationMode = CompilationMode.Partial(),
                    startupMode = StartupMode.COLD,
                    iterations = 5,
                    setupBlock = { },
                ) {
                    startActivityAndWait(intentFor(mode, scenario, resetGenerated = true))
                    device.waitForIdle()
                when (scenario) {
                        "pinch_4_5_4" -> { pinch(device, 1.35f); pinch(device, 0.75f) }
                        "pinch_8_9_8" -> {
                            repeat(4) { pinch(device, 1.35f) }
                            pinch(device, 1.35f); pinch(device, 0.75f)
                        }
                        "pinch_return" -> {
                            pinch(device, 1.35f); pinch(device, 0.75f)
                            repeat(4) { pinch(device, 1.35f) }
                            pinch(device, 1.35f); pinch(device, 0.75f)
                            repeat(4) { pinch(device, 0.75f) }
                        }
                    }
                    device.waitForIdle()
                    startActivityAndWait(intentFor(mode, scenario, resetGenerated = false, flushMetrics = true))
                }
            }
        }
    }

    private fun measure(mode: String, scenario: String) {
        assertSnapshotPrepared()
        benchmarkRule.measureRepeated(
            packageName = TARGET,
            metrics = metrics(),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { },
        ) {
            startActivityAndWait(intentFor(mode, scenario, resetGenerated = mode == "ENCODER_ONLY" || mode == "FULL"))
            device.waitForIdle()
            when (scenario) {
                "fast_round_trip" -> fastRoundTrip(device)
                "slow_drag" -> slowDrag(device)
                "settle_after_scroll" -> { fastRoundTrip(device); device.waitForIdle(); SystemClock.sleep(350) }
                "single_fling" -> { singleFling(device); device.waitForIdle() }
            }
            startActivityAndWait(intentFor(mode, scenario, resetGenerated = false, flushMetrics = true))
        }
    }

    private fun metrics() = listOf(FrameTimingMetric())

    private fun assertSnapshotPrepared() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val marker = device.executeShellCommand("cat $validationRoot/snapshot.json")
        val error = device.executeShellCommand("cat $validationRoot/setup-error.txt")
        val requiredCounts = listOf("activeClips", "activeMediaAssets", "taggedMediaClips", "localMediaAssets", "persistentPreviews")
        val countsReady = requiredCounts.all { field ->
            Regex("\\\"$field\\\":(\\d+)").find(marker)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true
        }
        if (!marker.contains("\"ready\":true") || !countsReady || !marker.contains("like_list_manager_benchmark.db")) {
            throw IllegalStateException("Benchmark snapshot is not prepared: root=$validationRoot marker=$marker error=$error")
        }
    }

    private fun intentFor(mode: String, scenario: String, resetGenerated: Boolean, flushMetrics: Boolean = false): Intent = Intent(Intent.ACTION_MAIN)
        .setClassName(TARGET, ACTIVITY)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra("com.lyco256.llm.benchmark.mode", mode)
        .putExtra("com.lyco256.llm.benchmark.scenario", scenario)
        .putExtra("com.lyco256.llm.benchmark.resetGenerated", resetGenerated)
        .putExtra("com.lyco256.llm.benchmark.flushMetrics", flushMetrics)

    private fun fastRoundTrip(device: UiDevice) {
        val x = device.displayWidth / 2
        device.swipe(x, (device.displayHeight * 0.78f).toInt(), x, (device.displayHeight * 0.22f).toInt(), 8)
        device.swipe(x, (device.displayHeight * 0.22f).toInt(), x, (device.displayHeight * 0.78f).toInt(), 8)
    }

    private fun slowDrag(device: UiDevice) {
        val x = device.displayWidth / 2
        repeat(3) {
            device.swipe(x, (device.displayHeight * 0.72f).toInt(), x, (device.displayHeight * 0.28f).toInt(), 700)
            device.swipe(x, (device.displayHeight * 0.28f).toInt(), x, (device.displayHeight * 0.72f).toInt(), 700)
        }
    }

    private fun singleFling(device: UiDevice) {
        val x = device.displayWidth / 2
        device.swipe(x, (device.displayHeight * 0.78f).toInt(), x, (device.displayHeight * 0.22f).toInt(), 8)
    }

    private fun pinch(device: UiDevice, scale: Float) {
        val center = Point(device.displayWidth / 2, (device.displayHeight * 0.52f).toInt())
        val base = (device.displayWidth * 0.14f).toFloat()
        val end = base * scale
        inject(MotionEvent.ACTION_DOWN, center, -base)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), center, -base)
        repeat(4) { step ->
            val progress = (step + 1) / 4f
            inject(MotionEvent.ACTION_MOVE, center, -(base + (end - base) * progress))
        }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), center, -end)
        inject(MotionEvent.ACTION_UP, center, -end)
        device.waitForIdle()
    }

    private fun inject(action: Int, center: Point, xOffset: Float, yOffset: Float = 0f) {
        val properties = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
        properties.forEachIndexed { index, property -> property.id = index; property.toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
        coords[0].x = center.x + xOffset; coords[0].y = center.y + yOffset; coords[0].pressure = 1f; coords[0].size = 1f
        coords[1].x = center.x - xOffset; coords[1].y = center.y - yOffset; coords[1].pressure = 1f; coords[1].size = 1f
        val maskedAction = action and MotionEvent.ACTION_MASK
        val pointerCount = if (maskedAction == MotionEvent.ACTION_DOWN || maskedAction == MotionEvent.ACTION_UP) 1 else 2
        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action,
            pointerCount, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try { InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
            SystemClock.sleep(32)
    }

    private companion object {
        const val TARGET = "com.lyco256.llm.test.benchmark"
        const val ACTIVITY = "com.lyco256.llm.BenchmarkMainActivity"
    }
}
