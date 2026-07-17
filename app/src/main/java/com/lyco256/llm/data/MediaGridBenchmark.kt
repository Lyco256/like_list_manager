package com.lyco256.llm.data

import android.content.Intent
import android.os.Trace
import android.os.Environment
import com.lyco256.llm.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import android.content.Context
import java.io.File

enum class MediaGridBenchmarkMode { FRAME_ONLY, PRIORITY_ONLY, CACHED_UI, ENCODER_ONLY, FULL }

data class MediaGridBenchmarkSettings(
    val mode: MediaGridBenchmarkMode = MediaGridBenchmarkMode.FULL,
    val enabled: Boolean = false,
    val scenario: String = "default",
) {
    val viewportEnabled: Boolean get() = mode != MediaGridBenchmarkMode.FRAME_ONLY
    val priorityEnabled: Boolean get() = mode != MediaGridBenchmarkMode.FRAME_ONLY
    val cacheUiEnabled: Boolean get() = mode == MediaGridBenchmarkMode.CACHED_UI || mode == MediaGridBenchmarkMode.FULL
    val encoderEnabled: Boolean get() = mode == MediaGridBenchmarkMode.ENCODER_ONLY || mode == MediaGridBenchmarkMode.FULL
    val uiImageEnabled: Boolean get() = cacheUiEnabled
    val networkAllowed: Boolean get() = !enabled

    companion object {
        const val EXTRA_MODE = "com.lyco256.llm.benchmark.mode"
        const val EXTRA_SCENARIO = "com.lyco256.llm.benchmark.scenario"

        fun resolve(intent: Intent?): MediaGridBenchmarkSettings {
            if (BuildConfig.BUILD_TYPE != "benchmark") return MediaGridBenchmarkSettings()
            val mode = intent?.getStringExtra(EXTRA_MODE)?.let { raw ->
                runCatching { MediaGridBenchmarkMode.valueOf(raw.uppercase()) }.getOrNull()
            } ?: MediaGridBenchmarkMode.FULL
            val scenario = intent?.getStringExtra(EXTRA_SCENARIO)?.takeUnless { it.isNullOrBlank() } ?: "default"
            return MediaGridBenchmarkSettings(mode, enabled = true, scenario = scenario)
        }
    }
}

data class MediaGridBenchmarkCounters(
    val viewportCapture: Long = 0,
    val viewportPublish: Long = 0,
    val prioritySelect: Long = 0,
    val cacheLookup: Long = 0,
    val sourceRead: Long = 0,
    val sourceDecode: Long = 0,
    val resize: Long = 0,
    val encode: Long = 0,
    val write: Long = 0,
    val readyPublish: Long = 0,
    val cellImageApply: Long = 0,
    val renderModel: Long = 0,
    val morphPlan: Long = 0,
    val morphFirstDraw: Long = 0,
    val morphHandoff: Long = 0,
    val network: Long = 0,
) {
    fun asMap(): Map<String, Long> = mapOf(
        "viewportCapture" to viewportCapture, "viewportPublish" to viewportPublish,
        "prioritySelect" to prioritySelect, "cacheLookup" to cacheLookup,
        "sourceRead" to sourceRead, "sourceDecode" to sourceDecode, "resize" to resize,
        "encode" to encode, "write" to write, "readyPublish" to readyPublish,
        "cellImageApply" to cellImageApply, "renderModel" to renderModel,
        "morphPlan" to morphPlan, "morphFirstDraw" to morphFirstDraw,
        "morphHandoff" to morphHandoff, "network" to network,
    )
}

interface MediaGridBenchmarkMetrics {
    fun count(name: String, amount: Long = 1)
    fun <T> trace(name: String, block: () -> T): T
    fun addDuration(name: String, nanos: Long)
    fun snapshot(): Map<String, Long>
    fun counterSnapshot(): Map<String, Long>
    fun traceSnapshot(): Map<String, Long>
    fun reset()

    companion object {
        fun forSettings(settings: MediaGridBenchmarkSettings): MediaGridBenchmarkMetrics =
            if (settings.enabled) ActiveMediaGridBenchmarkMetrics() else NoopMediaGridBenchmarkMetrics
    }
}

private object NoopMediaGridBenchmarkMetrics : MediaGridBenchmarkMetrics {
    override fun count(name: String, amount: Long) = Unit
    override fun <T> trace(name: String, block: () -> T): T = block()
    override fun addDuration(name: String, nanos: Long) = Unit
    override fun snapshot(): Map<String, Long> = emptyMap()
    override fun counterSnapshot(): Map<String, Long> = emptyMap()
    override fun traceSnapshot(): Map<String, Long> = emptyMap()
    override fun reset() = Unit
}

private class ActiveMediaGridBenchmarkMetrics : MediaGridBenchmarkMetrics {
    private val values = MediaGridBenchmarkCounters::class.java.declaredFields
        .filter { it.type == Long::class.javaPrimitiveType }
        .associate { it.name to AtomicLong() }
    private val durations = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    override fun count(name: String, amount: Long) { values[name]?.addAndGet(amount) }

    override fun <T> trace(name: String, block: () -> T): T {
        val started = System.nanoTime()
        Trace.beginSection(name)
        return try { block() } finally {
            Trace.endSection()
            addDuration(name, System.nanoTime() - started)
        }
    }

    override fun addDuration(name: String, nanos: Long) { durations.computeIfAbsent(name) { AtomicLong() }.addAndGet(nanos) }
    override fun snapshot(): Map<String, Long> = values.mapValues { it.value.get() } + durations.mapValues { it.value.get() / 1_000_000L }
    override fun counterSnapshot(): Map<String, Long> = values.mapValues { it.value.get() }
    override fun traceSnapshot(): Map<String, Long> = durations.mapValues { it.value.get() / 1_000_000L }
    override fun reset() { values.values.forEach { it.set(0) }; durations.clear() }
}

data class MediaGridFrameTimingSummary(
    val p50Ms: Double,
    val p90Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val jankFrames: Int,
    val frameCount: Int,
) {
    companion object {
        fun fromDurations(values: List<Long>): MediaGridFrameTimingSummary {
            if (values.isEmpty()) return MediaGridFrameTimingSummary(0.0, 0.0, 0.0, 0.0, 0, 0)
            val sorted = values.sorted()
            fun percentile(percent: Double): Double = sorted[((sorted.size - 1) * percent).toInt()].toDouble() / 1_000_000.0
            return MediaGridFrameTimingSummary(
                p50Ms = percentile(0.50), p90Ms = percentile(0.90), p95Ms = percentile(0.95), p99Ms = percentile(0.99),
                jankFrames = sorted.count { it > 16_666_667L }, frameCount = sorted.size,
            )
        }
    }
}

class MediaGridFrameTimingCollector {
    private val durations = ArrayList<Long>()
    private var lastFrameNanos = 0L
    private var running = false
    private val callback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) durations += (frameTimeNanos - lastFrameNanos).coerceAtLeast(0L)
            lastFrameNanos = frameTimeNanos
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() { if (!running) { running = true; android.view.Choreographer.getInstance().postFrameCallback(callback) } }
    fun stop(): MediaGridFrameTimingSummary {
        running = false
        android.view.Choreographer.getInstance().removeFrameCallback(callback)
        return MediaGridFrameTimingSummary.fromDurations(durations.toList())
    }
}

object MediaGridBenchmarkMetricsWriter {
    fun write(context: Context, settings: MediaGridBenchmarkSettings, metrics: MediaGridBenchmarkMetrics, frames: MediaGridFrameTimingSummary) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        val root = context.getExternalFilesDirs("media-grid-metrics")
            .filterNotNull()
            .firstOrNull(Environment::isExternalStorageRemovable)
            ?: error("Removable SD storage is required for benchmark metrics")
        root.mkdirs()
        val safeScenario = settings.scenario.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(root, "${settings.mode.name}-$safeScenario-${System.nanoTime()}.json")
        fun jsonMap(values: Map<String, Long>) = values.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        val json = """{"mode":"${settings.mode.name}","scenario":"$safeScenario","frame":{"p50Ms":${frames.p50Ms},"p90Ms":${frames.p90Ms},"p95Ms":${frames.p95Ms},"p99Ms":${frames.p99Ms},"jank":${frames.jankFrames},"frames":${frames.frameCount}},"counters":{${jsonMap(metrics.counterSnapshot())}},"traceMs":{${jsonMap(metrics.traceSnapshot())}}}"""
        file.writeText(json)
    }
}
