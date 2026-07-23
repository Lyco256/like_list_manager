package com.lyco256.llm.rgb565backfill

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.io.PrintWriter
import java.io.StringWriter

class Rgb565BackfillRunner : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle) {
        this.arguments = arguments
        super.onCreate(arguments)
        start()
    }

    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(
        classLoader,
        Application::class.java.name,
        context,
    )

    override fun onStart() {
        try {
            val selector = arguments.getString("class").orEmpty()
            val method = selector.substringAfter("#", missingDelimiterValue = "")
            check(selector.substringBefore("#") == ProductionRgb565BackfillInstrumentation::class.java.name)
            check(method in setOf(
                "preflightReadOnly",
                "runBackfillToCompletion",
                "verifyFeatReaderAndAllSlots",
            ))
            val command = ProductionRgb565BackfillInstrumentation(this)
            command.javaClass.getMethod(method).invoke(command)
            finish(
                Activity.RESULT_OK,
                Bundle().apply { putString("stream", "\nOK (1 test)\n") },
            )
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            val trace = StringWriter().also { writer ->
                cause.printStackTrace(PrintWriter(writer))
            }.toString()
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("shortMsg", cause.message ?: cause.javaClass.name)
                    putString("stream", "\nFAILED (1 test)\n$trace")
                },
            )
        }
    }

    fun reportStatus(values: Bundle) {
        sendStatus(0, values)
    }
}
