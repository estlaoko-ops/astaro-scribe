package com.diarizer.sherpa

import android.app.Application
import android.util.Log

class DiarizerApp : Application() {
    private val TAG = "DiarizerApp"

    override fun onCreate() {
        super.onCreate()

        // Global crash handler for Java-level exceptions
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===")
            Log.e(TAG, "Exception: ${throwable.javaClass.name}: ${throwable.message}")

            // Try to write to log file
            try {
                val logDir = filesDir.resolve("logs")
                if (!logDir.exists()) logDir.mkdirs()
                val crashLogFile = java.io.File(logDir, "crash_${System.currentTimeMillis()}.log")
                crashLogFile.writeText(
                    """
                    |=== CRASH LOG ===
                    |Thread: ${thread.name}
                    |Exception: ${throwable.javaClass.name}: ${throwable.message}
                    |
                    |Stack trace:
                    |${throwable.stackTrace?.joinToString("\n") { "  at $it" }}
                    |
                    |Cause:
                    |${throwable.cause?.let { "${it.javaClass.name}: ${it.message}\n${it.stackTrace?.joinToString("\n") { "  at $it" }}" } ?: "none"}
                    |""".trimMargin()
                )
                Log.i(TAG, "Crash log written to ${crashLogFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }

            // Chain to default handler
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "DiarizerApp initialized with global crash handler")
    }
}
