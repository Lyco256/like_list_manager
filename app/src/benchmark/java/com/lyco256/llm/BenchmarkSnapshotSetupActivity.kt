package com.lyco256.llm

import android.app.Activity
import android.util.Log
import android.os.Bundle
import java.io.File

/**
 * Explicit, unmeasured benchmark setup entry point. It runs before MainActivity
 * so the benchmark target never opens Room against an unprepared empty DB.
 */
class BenchmarkSnapshotSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            BenchmarkSnapshotImporter.prepareRequiredSnapshot(this)
            getExternalFilesDir("media-grid-validation")?.let { File(it, "setup-error.txt").delete() }
            Log.i("BenchmarkSnapshotSetup", "snapshot setup ready: ${getExternalFilesDir("media-grid-validation")}")
            setResult(RESULT_OK)
        } catch (error: Throwable) {
            val directory = getExternalFilesDir("media-grid-validation")
            directory?.mkdirs()
            File(directory, "setup-error.txt").writeText(
                error.stackTraceToString(),
                Charsets.UTF_8,
            )
            Log.e("BenchmarkSnapshotSetup", "snapshot setup failed: ${error.message}", error)
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
