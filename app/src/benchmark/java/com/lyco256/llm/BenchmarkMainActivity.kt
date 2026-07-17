package com.lyco256.llm

import android.content.Intent
import android.os.Bundle
import com.lyco256.llm.data.MediaGridBenchmarkMetrics
import com.lyco256.llm.data.MediaGridBenchmarkMetricsWriter
import com.lyco256.llm.data.MediaGridBenchmarkSettings
import com.lyco256.llm.data.MediaGridFrameTimingCollector
import com.lyco256.llm.data.MediaGridFrameTimingSummary

/** Benchmark-only launcher. The production Activity has no measurement lifecycle. */
class BenchmarkMainActivity : MainActivity() {
    override val initialAppTab: AppTab = AppTab.Classified
    override val initialClassifiedDisplayMode: ClassifiedDisplayMode = ClassifiedDisplayMode.MediaGrid
    override val persistScreenState: Boolean = false

    private var frameTiming: MediaGridFrameTimingCollector? = null
    private var metrics: MediaGridBenchmarkMetrics? = null
    private var metricsWritten = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = MediaGridBenchmarkSettings.resolve(intent)
        if (intent.getBooleanExtra("com.lyco256.llm.benchmark.resetGenerated", false)) {
            BenchmarkSnapshotImporter.resetGeneratedResults(this)
        }
        metrics = MediaGridBenchmarkMetrics.forSettings(settings).also { it.reset() }
        frameTiming = MediaGridFrameTimingCollector().also { it.start() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("com.lyco256.llm.benchmark.flushMetrics", false)) {
            writeMetricsIfNeeded()
        }
    }

    override fun onStop() {
        writeMetricsIfNeeded()
        super.onStop()
    }

    private fun writeMetricsIfNeeded() {
        if (metricsWritten) return
        metricsWritten = true
        val settings = MediaGridBenchmarkSettings.resolve(intent)
        val frames = frameTiming?.stop() ?: MediaGridFrameTimingSummary.fromDurations(emptyList())
        MediaGridBenchmarkMetricsWriter.write(
            context = this,
            settings = settings,
            metrics = metrics ?: MediaGridBenchmarkMetrics.forSettings(settings),
            frames = frames,
        )
    }
}
