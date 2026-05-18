package com.diarizer.sherpa

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Логирует всё в файл во внутреннем хранилище приложения.
 * Хранит логи последних 2 запусков.
 */
class FileLogger(private val context: Context) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val file: File

    data class SavedLog(
        val name: String,
        val path: String,
        val content: String,
        val date: String
    )

    init {
        // Clean old: keep only the last log file (ours will be new)
        cleanupOldLogs()

        val dir = getLogDir()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        file = File(dir, "diarizer_$timestamp.log")

        try {
            file.writeText(
                """
                |=== Diarizer Debug Log ===
                |Device: ${android.os.Build.MODEL}
                |Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
                |Started: ${dateFormat.format(Date())}
                |
                |""".trimMargin()
            )
            android.util.Log.i("FileLogger", "Log file: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("FileLogger", "Failed to init file logging", e)
        }
    }

    private fun getLogDir(): File {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Keep only the last 2 log files (the newest one is us, keep 1 previous)
     */
    private fun cleanupOldLogs() {
        try {
            val dir = getLogDir()
            val files = dir.listFiles()
                ?.filter { it.name.startsWith("diarizer_") && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            // Keep last 2 files (the newest will be this session's file)
            if (files.size > 2) {
                files.drop(2).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    /**
     * Get logs from previous runs (excluding current session)
     */
    fun getPreviousLogs(): List<SavedLog> {
        val result = mutableListOf<SavedLog>()
        try {
            val dir = getLogDir()
            val files = dir.listFiles()
                ?.filter { it.name.startsWith("diarizer_") && it.name.endsWith(".log") && it.absolutePath != file.absolutePath }
                ?.sortedByDescending { it.lastModified() }
                ?: return result

            // Also look for crash logs
            val crashFiles = dir.listFiles()
                ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }

            for (f in files.take(2)) {
                val dateStr = try {
                    val ts = f.name.removePrefix("diarizer_").removeSuffix(".log")
                    val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    val dt = fmt.parse(ts)
                    SimpleDateFormat("dd.MM HH:mm", Locale.US).format(dt!!)
                } catch (_: Exception) { "?" }

                result.add(SavedLog(
                    name = f.name,
                    path = f.absolutePath,
                    content = try { f.readText() } catch (_: Exception) { "Can't read log" },
                    date = dateStr
                ))
            }

            // Add crash logs
            for (f in (crashFiles ?: emptyList()).take(2)) {
                val dateStr = try {
                    val ts = f.name.removePrefix("crash_").removeSuffix(".log")
                    SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(ts.toLong()))
                } catch (_: Exception) { "?" }

                result.add(SavedLog(
                    name = "💥 ${f.name}",
                    path = f.absolutePath,
                    content = try { f.readText() } catch (_: Exception) { "Can't read log" },
                    date = "$dateStr (CRASH)"
                ))
            }
        } catch (_: Exception) {}
        return result
    }

    fun log(msg: String) {
        val line = "[${dateFormat.format(Date())}] $msg"
        android.util.Log.i("FileLogger", msg)
        try {
            file.appendText("$line\n")
        } catch (_: Exception) {}
    }

    fun logError(msg: String, e: Throwable? = null) {
        val line = "[${dateFormat.format(Date())}] [ERROR] $msg"
        android.util.Log.e("FileLogger", msg, e)
        try {
            file.appendText("$line\n")
            if (e != null) {
                file.appendText("${e.javaClass.name}: ${e.message}\n")
                e.stackTrace?.take(10)?.forEach { frame ->
                    file.appendText("  at $frame\n")
                }
            }
        } catch (_: Exception) {}
    }

    fun getPath(): String = file.absolutePath

    fun getContent(): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            "Can't read log file: ${e.message}"
        }
    }
}
