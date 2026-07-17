package com.lyco256.llm.macrobenchmark

import android.os.Bundle
import android.os.Environment
import androidx.test.runner.AndroidJUnitRunner
import org.json.JSONObject
import java.io.File

class PersistentBenchmarkRunner : AndroidJUnitRunner() {
    private val outputDirectory: File
        get() = targetContext.getExternalFilesDirs(OUTPUT_DIRECTORY)
            .filterNotNull()
            .firstOrNull(Environment::isExternalStorageRemovable)
            ?: error("Removable SD storage is required for macrobenchmark output")

    override fun onCreate(arguments: Bundle?) {
        val output = outputDirectory.apply { mkdirs() }
        val runnerArguments = Bundle(arguments ?: Bundle()).apply {
            putString("additionalTestOutputDir", File(output, "androidx-test-output").absolutePath)
        }
        super.onCreate(runnerArguments)
    }

    override fun onStart() {
        outputDirectory.mkdirs()
        File(outputDirectory, COMPLETION_FILE).delete()
        writeAtomically(
            File(outputDirectory, STATUS_FILE),
            JSONObject()
                .put("state", "running")
                .put("startedAtEpochMs", System.currentTimeMillis())
                .toString(),
        )
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        runCatching {
            outputDirectory.mkdirs()
            val result = JSONObject()
                .put("state", "complete")
                .put("resultCode", resultCode)
                .put("finishedAtEpochMs", System.currentTimeMillis())
            results?.keySet()?.sorted()?.forEach { key ->
                result.put(key, results.get(key)?.toString())
            }
            writeAtomically(File(outputDirectory, COMPLETION_FILE), result.toString())
            File(outputDirectory, STATUS_FILE).delete()
        }
        super.finish(resultCode, results)
    }

    private fun writeAtomically(target: File, value: String) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(target)) { "Could not publish ${target.name}" }
    }

    companion object {
        const val OUTPUT_DIRECTORY = "media-grid-run"
        const val STATUS_FILE = "running.json"
        const val COMPLETION_FILE = "completion.json"
    }
}
