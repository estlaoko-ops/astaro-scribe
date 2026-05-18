package com.diarizer.sherpa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object ModelDownloader {
    private const val TAG = "ModelDownloader"

    data class ModelInfo(
        val name: String,
        val url: String,
        val filename: String,
        val sizeMB: Int,
        val isArchive: Boolean = false,      // tar.bz2, нужно распаковать
        val archiveInnerFile: String = ""     // какой файл внутри нам нужен
    )

    val MODELS = listOf(
        // Whisper Small INT8 — ASR
        ModelInfo(
            name = "Whisper encoder",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
            filename = "small-encoder.int8.onnx",
            sizeMB = 107
        ),
        ModelInfo(
            name = "Whisper decoder",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
            filename = "small-decoder.int8.onnx",
            sizeMB = 250
        ),
        ModelInfo(
            name = "Whisper tokens",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
            filename = "small-tokens.txt",
            sizeMB = 1
        ),
        // Reverb diarization v1 — альтернатива pyannote (более точная)
        ModelInfo(
            name = "Diarization seg",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2",
            filename = "model-reverb.int8.onnx",
            sizeMB = 3,
            isArchive = true,
            archiveInnerFile = "sherpa-onnx-reverb-diarization-v1/model.int8.onnx"
        ),
        // 3D-Speaker embedding — для speaker diarization
        ModelInfo(
            name = "3D-Speaker emb",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            filename = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            sizeMB = 27
        )
    )

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelsDir(context), model.filename)
        return file.exists() && file.length() > 1024
    }

    fun areAllModelsDownloaded(context: Context): Boolean {
        for (model in MODELS) {
            val file = File(getModelsDir(context), model.filename)
            if (!file.exists() || file.length() <= 1024) return false
        }
        return true
    }

    fun getMissingModels(context: Context): List<ModelInfo> {
        return MODELS.filter { !isModelDownloaded(context, it) }
    }

    suspend fun downloadModels(
        context: Context,
        onProgress: (String, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDir(context)

        for (model in MODELS) {
            if (isModelDownloaded(context, model)) {
                Log.i(TAG, "${model.name} already downloaded")
                continue
            }
            if (model.isArchive) {
                downloadAndExtractArchive(model, modelsDir, onProgress)
            } else {
                val outputFile = File(modelsDir, model.filename)
                downloadFile(model.url, outputFile, model.name, onProgress)
            }
        }
    }

    private fun downloadAndExtractArchive(
        model: ModelInfo,
        modelsDir: File,
        onProgress: (String, Int) -> Unit
    ) {
        val archiveFile = File(modelsDir, "${model.filename}.tar.bz2")
        try {
            downloadFile(model.url, archiveFile, model.name, onProgress)
            onProgress("Распаковываю ${model.name}...", 95)

            // Extract via tar command
            val pb = ProcessBuilder(
                "tar", "-xjf", archiveFile.absolutePath,
                "-C", modelsDir.absolutePath,
                model.archiveInnerFile
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.inputStream.bufferedReader().readText()
                Log.e(TAG, "tar extraction failed: $err")
                throw RuntimeException("tar exit code $exitCode: $err")
            }

            archiveFile.delete()
            Log.i(TAG, "Extracted ${model.name}: ${model.archiveInnerFile}")

            // Move extracted file to root of modelsDir
            val extractedFile = File(modelsDir, model.archiveInnerFile)
            val targetFile = File(modelsDir, model.filename)
            if (extractedFile.exists() && extractedFile.absolutePath != targetFile.absolutePath) {
                extractedFile.renameTo(targetFile)
                extractedFile.parentFile?.deleteRecursively()
                Log.i(TAG, "Moved ${model.name} to ${targetFile.name}")
            }

            onProgress("${model.name}: готово", 100)
        } catch (e: Exception) {
            if (archiveFile.exists()) archiveFile.delete()
            throw e
        }
    }

    private fun downloadFile(
        url: String,
        outputFile: File,
        name: String,
        onProgress: (String, Int) -> Unit
    ) {
        Log.i(TAG, "Downloading $name from $url")
        onProgress("Скачиваю $name...", 0)

        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 120000

        connection.instanceFollowRedirects = true
        connection.connect()

        val totalBytes = connection.contentLengthLong
        val inputStream = connection.getInputStream()
        val outputStream = FileOutputStream(outputFile)

        val buffer = ByteArray(65536)
        var bytesRead: Int
        var totalRead: Long = 0
        var lastProgressTime = System.currentTimeMillis()
        var lastMb = -1L

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalRead += bytesRead

            val now = System.currentTimeMillis()
            val currentMb = totalRead / (1024 * 1024)

            if (now - lastProgressTime >= 200 && currentMb != lastMb) {
                lastProgressTime = now
                lastMb = currentMb

                if (totalBytes > 0) {
                    val pct = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 99)
                    if (pct % 5 == 0 || currentMb != lastMb) {
                        onProgress("Скачиваю $name... ${modelSizeStr(totalRead)} ($pct%)", pct)
                    }
                } else {
                    onProgress("Скачиваю $name... ${currentMb}MB", 0)
                }
            }
        }

        outputStream.close()
        inputStream.close()
        connection.disconnect()

        val finalSize = outputFile.length()
        Log.i(TAG, "Downloaded $name: ${finalSize} bytes")
        onProgress("$name: готово (${modelSizeStr(finalSize)})", 100)
    }

    private fun modelSizeStr(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 -> "%.0f МБ".format(bytes.toDouble() / (1024 * 1024))
            bytes > 1024 -> "%.0f КБ".format(bytes.toDouble() / 1024)
            else -> "$bytes Б"
        }
    }
}
