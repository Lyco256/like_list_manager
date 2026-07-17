package com.lyco256.llm.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    private val validationRoot = "/sdcard/Android/data/com.lyco256.llm.test.benchmark/files/media-grid-validation"

    @Test
    fun coldStartup() {
        assertSnapshotPrepared()
        benchmarkRule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { },
        ) {
            startActivityAndWait(Intent().setClassName(TARGET, ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    @Test
    fun warmStartup() {
        assertSnapshotPrepared()
        benchmarkRule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = { },
        ) {
            startActivityAndWait(Intent().setClassName(TARGET, ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    private companion object {
        const val TARGET = "com.lyco256.llm.test.benchmark"
        const val ACTIVITY = "com.lyco256.llm.MainActivity"
    }

    private fun assertSnapshotPrepared() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val marker = device.executeShellCommand("cat $validationRoot/snapshot.json")
        val error = device.executeShellCommand("cat $validationRoot/setup-error.txt")
        val requiredCounts = listOf("activeClips", "activeMediaAssets", "taggedMediaClips", "localMediaAssets", "cachedJpegs")
        val countsReady = requiredCounts.all { field ->
            Regex("\\\"$field\\\":(\\d+)").find(marker)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true
        }
        if (!marker.contains("\"ready\":true") || !countsReady || !marker.contains("like_list_manager_benchmark.db")) {
            throw IllegalStateException("Benchmark snapshot is not prepared: root=$validationRoot marker=$marker error=$error")
        }
    }
}
