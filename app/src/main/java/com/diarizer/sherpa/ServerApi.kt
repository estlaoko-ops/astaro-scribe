package com.diarizer.sherpa

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object ServerApi {

    data class Segment(
        val speaker: String,
        val start: Double,
        val end: Double,
        val text: String,
    )

    data class JobStatus(
        val status: String,       // "processing" | "done" | "error"
        val step: String,
        val progress: Float,
        val segments: List<Segment>?,
        val fullText: String?,
        val errorMessage: String?,
        val segsDone: Int = 0,
        val segsTotal: Int = 0,
        val phaseElapsedSec: Int = 0,
        val segErrors: List<String> = emptyList(),
        val hasDiarization: Boolean = true,
    )

    private val baseUrl: String get() =
        BuildConfig.WHISPER_SERVER_URL
            .removeSuffix("/transcribe")
            .removeSuffix("/")

    private val auth: String get() = BuildConfig.WHISPER_AUTH

    suspend fun submitJob(context: Context, uri: Uri, mode: String = "diarize"): String = withContext(Dispatchers.IO) {
        val submitUrl = "$baseUrl/pipeline/submit?mode=$mode"

        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: "audio/mpeg"
        val extension = when {
            mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
            mimeType.contains("wav") -> "wav"
            mimeType.contains("ogg") -> "ogg"
            else -> "mp3"
        }

        val fileSize = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

        val boundary = "----AstaroBoundary${System.currentTimeMillis()}"
        val conn = URL(submitUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("Authorization", auth)
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000  // 5 min for upload

        if (fileSize > 0) {
            conn.setFixedLengthStreamingMode(
                "--$boundary\r\n".length +
                "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.$extension\"\r\n".length +
                "Content-Type: $mimeType\r\n\r\n".length +
                fileSize +
                "\r\n--$boundary--\r\n".length
            )
        } else {
            conn.setChunkedStreamingMode(65536)
        }

        conn.outputStream.use { os: OutputStream ->
            val header = "--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"audio.$extension\"\r\nContent-Type: $mimeType\r\n\r\n"
            os.write(header.toByteArray())
            cr.openInputStream(uri)?.use { input ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    os.write(buf, 0, n)
                }
            }
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        val body = (if (code == 200) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()

        if (code != 200) throw RuntimeException("Submit failed ($code): $body")

        JSONObject(body).getString("job_id")
    }

    suspend fun pollStatus(jobId: String): JobStatus = withContext(Dispatchers.IO) {
        val statusUrl = "$baseUrl/pipeline/status/$jobId"
        val conn = URL(statusUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", auth)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val code = conn.responseCode
        val body = (if (code == 200) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()

        if (code == 404) return@withContext JobStatus(
            status = "not_found", step = "", progress = 0f,
            segments = null, fullText = null, errorMessage = "Задача не найдена на сервере",
        )
        if (code != 200) throw RuntimeException("Status check failed ($code): $body")

        val json = JSONObject(body)
        val status = json.getString("status")
        val step = json.optString("step", "")
        val progress = json.optDouble("progress", 0.0).toFloat()
        val segsDone = json.optInt("seg_done", 0)
        val segsTotal = json.optInt("seg_total", 0)
        val phaseElapsed = json.optInt("phase_elapsed_sec", 0)
        val hasDiarization = json.optBoolean("has_diarization", true)
        val segErrors = buildList {
            val arr = json.optJSONArray("seg_errors") ?: return@buildList
            repeat(arr.length()) { add(arr.getString(it)) }
        }

        when (status) {
            "done" -> {
                val segsArr = json.getJSONArray("segments")
                val segments = (0 until segsArr.length()).map { i ->
                    val s = segsArr.getJSONObject(i)
                    Segment(
                        speaker = s.getString("speaker"),
                        start = s.getDouble("start"),
                        end = s.getDouble("end"),
                        text = s.getString("text"),
                    )
                }
                JobStatus(
                    status = "done", step = step, progress = progress,
                    segments = segments, fullText = json.optString("full_text", ""),
                    errorMessage = null,
                    segsDone = segsDone, segsTotal = segsTotal,
                    phaseElapsedSec = phaseElapsed, segErrors = segErrors,
                    hasDiarization = hasDiarization,
                )
            }
            "error" -> JobStatus(
                status = "error", step = step, progress = progress,
                segments = null, fullText = null,
                errorMessage = json.optString("message", "Неизвестная ошибка"),
                segsDone = segsDone, segsTotal = segsTotal,
                phaseElapsedSec = phaseElapsed, segErrors = segErrors,
            )
            else -> JobStatus(
                status = status, step = step, progress = progress,
                segments = null, fullText = null, errorMessage = null,
                segsDone = segsDone, segsTotal = segsTotal,
                phaseElapsedSec = phaseElapsed, segErrors = segErrors,
            )
        }
    }

    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        val conn = URL("$baseUrl/pipeline/cancel/$jobId").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", auth)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        runCatching { conn.responseCode }
        conn.disconnect()
    }

    fun formatSpeakerText(segments: List<Segment>): String {
        if (segments.isEmpty()) return ""

        // Normalize speaker IDs to human-friendly names
        val speakerMap = mutableMapOf<String, String>()
        var speakerCount = 1
        val sb = StringBuilder()
        var lastSpeaker: String? = null

        for (seg in segments) {
            val name = speakerMap.getOrPut(seg.speaker) { "Спикер $speakerCount".also { speakerCount++ } }
            if (name != lastSpeaker) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("$name:\n")
                lastSpeaker = name
            }
            sb.append(seg.text.trim())
            sb.append(" ")
        }
        return sb.toString().trimEnd()
    }
}
