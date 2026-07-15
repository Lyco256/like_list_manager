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
    private var validationRoot = "/sdcard/Android/data/com.lyco256.llm.test.benchmark/files/media-grid-validation"

    @Test
    fun coldStartup() {
        prepareSnapshot()
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
        prepareSnapshot()
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
        const val SETUP_ACTIVITY = "com.lyco256.llm.BenchmarkSnapshotSetupActivity"
    }

    private fun prepareSnapshot() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("am force-stop $TARGET")
        val result = device.executeShellCommand("am start -W -n $TARGET/$SETUP_ACTIVITY")
        validationRoot = resolveValidationRoot(device)
        val marker = device.executeShellCommand("cat $validationRoot/snapshot.json")
        val error = device.executeShellCommand("cat $validationRoot/setup-error.txt")
        if (!result.contains("Status: ok") || !marker.contains("\"ready\":true")) {
            throw IllegalStateException("Benchmark snapshot setup failed: am=$result root=$validationRoot marker=$marker error=$error")
        }
    }

    private fun resolveValidationRoot(device: UiDevice): String {
        val roots = buildList {
            add("/sdcard")
            add("/storage/emulated/0")
            device.executeShellCommand("ls -d /storage/*").lineSequence()
                .map(String::trim)
                .map { if (it == "/storage/emulated") "/storage/emulated/0" else it }
                .filter { it.startsWith("/storage/") && it != "/storage/self" }
                .forEach(::add)
        }.distinct()
        return roots.firstOrNull { root ->
            val validationPath = "$root/Android/data/$TARGET/files/media-grid-validation"
            val marker = device.executeShellCommand("cat $validationPath/snapshot.json")
            val error = device.executeShellCommand("cat $validationPath/setup-error.txt")
            marker.contains("\"ready\":true") || error.isNotBlank()
        }?.let { "$it/Android/data/$TARGET/files/media-grid-validation" }
            ?: "/sdcard/Android/data/$TARGET/files/media-grid-validation"
    }
}
