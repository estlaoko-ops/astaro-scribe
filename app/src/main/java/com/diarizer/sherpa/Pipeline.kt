package com.diarizer.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlin.math.sqrt

class Pipeline(private val context: Context) {
    private val TAG = "Pipeline"

    data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

    private var recognizer: OfflineRecognizer? = null
    private var diarizer: OfflineSpeakerDiarization? = null
    var diarizationAvailable: Boolean = true; private set
    var diarizationError: String = ""; private set
    private var fileLogger: FileLogger? = null
    var clusterThreshold: Float = 0.45f
        private set
    
    init {
        val prefs = context.getSharedPreferences("transcriber_prefs", Context.MODE_PRIVATE)
        clusterThreshold = prefs.getFloat("cluster_threshold", 0.45f)
    }

    companion object {
        private const val CHUNK_SIZE = 400_000
        private const val FRAME_MS   = 30
        private const val SILENCE_THR = 0.015f
        private const val MIN_PAUSE_MS = 350f
        private const val MIN_SEG_MS   = 600f
    }

    fun setFileLogger(logger: FileLogger?) { fileLogger = logger }
    private fun log(msg: String) { Log.i(TAG, msg); fileLogger?.log(msg) }
    private fun logError(msg: String, e: Throwable? = null) { Log.e(TAG, msg, e); fileLogger?.logError(msg, e) }

    fun loadModels(): Boolean {
        val dir = ModelDownloader.getModelsDir(context)
        val ep = java.io.File(dir, "small-encoder.int8.onnx").absolutePath
        val dp = java.io.File(dir, "small-decoder.int8.onnx").absolutePath
        val tp = java.io.File(dir, "small-tokens.txt").absolutePath
        if (!java.io.File(ep).exists() || !java.io.File(dp).exists() || !java.io.File(tp).exists()) return false
        return try {
            recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(ep, dp, "", "transcribe"), tokens = tp)
            ))
            log("Whisper Small INT8 загружен"); true
        } catch (e: Exception) { logError("Ошибка загрузки Whisper", e); false }
    }

    // ===== ASR — с обрезкой тишины и диагностикой =====

    fun runAsr(audio: DecodedAudio, onProgress: (String) -> Unit, onLog: (String) -> Unit = {}): String? {
        val r = recognizer ?: run { onLog("[ОШИБКА] ASR не инициализирован"); return null }
        val samples = audio.samples; val sr = audio.sampleRate
        if (samples.isEmpty()) { onLog("[ОШИБКА] Пустое аудио"); return null }

        // Диагностика: статистика аудиосигнала
        val totalSec = samples.size.toFloat() / sr
        val rmsFirst = rmsLevel(samples, 0, minOf(sr, samples.size))
        val rmsLast = rmsLevel(samples, maxOf(0, samples.size - sr), samples.size)
        onLog("[ДИАГН] Длительность: ${"%.1f".format(totalSec)}с, семплов: ${samples.size}")
        onLog("[ДИАГН] RMS начало: ${"%.3f".format(rmsFirst)}, RMS конец: ${"%.3f".format(rmsLast)}")

        // Обрезка тишины перед ASR
        onProgress("Обрезаю тишину...")
        val trimmed = trimSilence(samples, sr)
        val trimmedSec = trimmed.size.toFloat() / sr
        val trimmedOff = totalSec - trimmedSec
        if (trimmedOff > 0.5f) {
            onLog("[VAD] Тишина обрезана: ${"%.1f".format(totalSec)}с => ${"%.1f".format(trimmedSec)}с (-${"%.1f".format(trimmedOff)}с)")
        } else {
            onLog("[VAD] Тишины почти нет: ${"%.1f".format(totalSec)}с (обрезано ${"%.1f".format(trimmedOff)}с)")
        }

        return try {
            val startMs = System.currentTimeMillis()
            if (trimmed.size <= CHUNK_SIZE) {
                onProgress("Распознаю...")
                val text = transcribe(r, trimmed, sr)
                val durSec = (System.currentTimeMillis() - startMs) / 1000
                onLog("[ВРЕМЯ] Готово за ${durSec / 60}:${"%02d".format(durSec % 60)}")
                text
            } else {
                val chunks = chunkIndices(trimmed.size)
                val chunkSec = CHUNK_SIZE.toFloat() / sr
                onLog("[INFO] Чанков: ${chunks.size} по ${"%.1f".format(chunkSec)}с")
                val sb = StringBuilder()
                for (i in chunks.indices) {
                    val (start, end) = chunks[i]
                    val text = transcribe(r, trimmed.sliceArray(start until end), sr)
                    if (text.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(text)
                    }
                    // ETA после завершения чанка: среднее время на чанк x всего чанков
                    val elapsed = System.currentTimeMillis() - startMs
                    val perChunk = elapsed / (i + 1)
                    val etaTotal = perChunk * chunks.size / 1000
                    val etaStr = "${etaTotal / 60}:${"%02d".format(etaTotal % 60)}"
                    onProgress("Часть ${i + 1}/${chunks.size} (ETA $etaStr)")
                }
                val totalDur = (System.currentTimeMillis() - startMs) / 1000
                onLog("[ВРЕМЯ] Всего: ${totalDur / 60}:${"%02d".format(totalDur % 60)} на ${chunks.size} чанков")
                sb.toString().trim()
            }
        } catch (e: Exception) { logError("Ошибка ASR", e); onLog("[ОШИБКА] ${e.message}"); null }
    }

    private fun transcribe(r: OfflineRecognizer, s: FloatArray, sr: Int): String {
        val st = r.createStream(); st.acceptWaveform(s, sr); r.decode(st)
        val t = r.getResult(st)?.text?.trim() ?: ""; st.release(); return t
    }

    private fun chunkIndices(total: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < total) {
            val end = minOf(start + CHUNK_SIZE, total)
            result.add(Pair(start, end))
            start += CHUNK_SIZE
        }
        return result
    }

    /** RMS energy of a range of samples */
    private fun rmsLevel(s: FloatArray, from: Int, to: Int): Float {
        if (from >= to || from >= s.size) return 0f
        val end = minOf(to, s.size)
        var sum = 0f
        for (i in from until end) sum += s[i] * s[i]
        return sqrt(sum / (end - from))
    }

    /** Trim leading and trailing silence. Returns original if no silence found. */
    private fun trimSilence(samples: FloatArray, sr: Int): FloatArray {
        val frameSize = (sr * FRAME_MS / 1000).coerceAtLeast(1)
        val nFrames = samples.size / frameSize
        if (nFrames < 4) return samples

        val energy = FloatArray(nFrames) { i ->
            val st = i * frameSize; val en = minOf(st + frameSize, samples.size)
            var sum = 0f; for (j in st until en) sum += samples[j] * samples[j]
            sqrt(sum / (en - st))
        }

        var first = -1; var last = -1
        for (i in energy.indices) {
            if (energy[i] > SILENCE_THR) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return samples // all silence

        val margin = 7 // ~200ms at 30ms frames
        val sf = (first - margin).coerceAtLeast(0)
        val ef = (last + margin + 1).coerceAtMost(nFrames)
        val ss = sf * frameSize
        val es = minOf(ef * frameSize, samples.size)
        if (ss == 0 && es == samples.size) return samples
        return samples.sliceArray(ss until es)
    }

    // ===== PAUSE-BASED DIARIZATION =====

    fun runDiarization(audio: DecodedAudio, text: String, onProgress: (String) -> Unit, onLog: (String) -> Unit = {}): String {
        if (text.isEmpty()) return text
        onProgress("Детектирую паузы..."); onLog("[INFO] Анализирую паузы...")

        val rawSegs = detectSegments(audio.samples, audio.sampleRate)
        onLog("[INFO] ${rawSegs.size} сегментов")
        if (rawSegs.size < 2) return text

        val speakerIds = assignSpeakers(rawSegs)
        val uniqueSpk = speakerIds.distinct()
        if (uniqueSpk.size <= 1) return text

        // Merge consecutive same-speaker segments
        val (mergedSegs, mergedSpeakerIds) = mergeSameSpeaker(rawSegs, speakerIds)
        onLog("[INFO] После склейки: ${mergedSegs.size} реплик")

        onProgress("Размечаю текст...")

        val words = text.split(" ").filter { it.isNotEmpty() }
        val totalDur = mergedSegs.sumOf { (it.second - it.first).toDouble() }.coerceAtLeast(1.0)

        data class SegInfo(val startSec: Float, val endSec: Float, val speaker: Int, var wordCount: Int)
        val segInfos = mergedSegs.mapIndexed { i, (s, e) -> SegInfo(s, e, mergedSpeakerIds[i], 0) }

        var wi = 0
        for (i in segInfos.indices) {
            val seg = segInfos[i]
            if (i == segInfos.lastIndex) {
                seg.wordCount = words.size - wi
            } else {
                val ratio = (seg.endSec - seg.startSec) / totalDur
                seg.wordCount = (ratio * words.size).toInt()
                    .coerceIn(1, words.size - wi - (segInfos.size - i - 1))
                    .coerceAtMost(words.size - wi)
            }
            wi += seg.wordCount
        }

        val sb = StringBuilder()
        wi = 0
        for ((s, e, spk, wc) in segInfos) {
            if (wc <= 0 || wi >= words.size) continue
            val segmentWords = words.subList(wi, (wi + wc).coerceAtMost(words.size))
            val timeStr = "%.0fс".format(s)
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[$timeStr] Спикер ${spk + 1}\n")
            sb.append(segmentWords.joinToString(" "))
            wi += wc
        }
        if (wi < words.size) { sb.append("\n\n"); sb.append(words.subList(wi, words.size).joinToString(" ")) }

        onLog("[INFO] ${uniqueSpk.size} спикера, ${mergedSegs.size} реплик")
        return sb.toString()
    }

    /** Merge consecutive same-speaker segments into one. */
    private fun mergeSameSpeaker(segs: List<Pair<Float, Float>>, spkIds: List<Int>): Pair<List<Pair<Float, Float>>, List<Int>> {
        val mergedSegs = mutableListOf<Pair<Float, Float>>()
        val mergedIds = mutableListOf<Int>()
        var i = 0
        while (i < segs.size) {
            var start = segs[i].first; var end = segs[i].second; val spk = spkIds[i]
            i++
            while (i < segs.size && spkIds[i] == spk) { end = segs[i].second; i++ }
            mergedSegs.add(Pair(start, end)); mergedIds.add(spk)
        }
        return Pair(mergedSegs, mergedIds)
    }

    private fun detectSegments(samples: FloatArray, sr: Int): List<Pair<Float, Float>> {
        val fs = (sr * FRAME_MS / 1000).coerceAtLeast(1)
        val nf = samples.size / fs; if (nf < 2) return emptyList()
        val energy = FloatArray(nf) { i ->
            val st = i * fs; val en = minOf(st + fs, samples.size)
            var sum = 0f; for (j in st until en) sum += samples[j] * samples[j]
            sqrt(sum / (en - st))
        }
        val vad = BooleanArray(nf) { energy[it] > SILENCE_THR }
        for (i in 1 until nf - 1) { if (!vad[i] && vad[i-1] && vad[i+1]) vad[i] = true }
        val segs = mutableListOf<Pair<Int, Int>>(); var p = 0
        while (p < nf) {
            while (p < nf && !vad[p]) p++; if (p >= nf) break
            val ss = p; while (p < nf && vad[p]) p++
            if ((p - ss) * FRAME_MS >= MIN_SEG_MS) segs.add(Pair(ss, p))
        }
        return segs.map { Pair(it.first * FRAME_MS / 1000f, it.second * FRAME_MS / 1000f) }
    }

    private fun assignSpeakers(segments: List<Pair<Float, Float>>): List<Int> {
        val ids = mutableListOf<Int>(); var spk = 0
        for (i in segments.indices) {
            if (i > 0) {
                val gapMs = (segments[i].first - segments[i-1].second) * 1000f
                if (gapMs >= MIN_PAUSE_MS) spk = (spk + 1) % 2
            }
            ids.add(spk)
        }
        return ids
    }

    // ===== SPEAKER EMBEDDING DIARIZATION (beta) =====

    fun isDiarizationLoaded(): Boolean = diarizer != null

    /** Run pure ML speaker diarization (Pyannote + 3D-Speaker + clustering).
     *  Returns [SpeakerTimeline] with speaker count and time segments. */
    data class SpeakerTimeline(
        val speakerCount: Int,
        val segments: List<SpeakerSegment>
    )
    data class SpeakerSegment(
        val speakerId: Int,
        val startSec: Float,
        val endSec: Float
    )
    data class TranscribedSegment(
        val speakerId: Int,
        val startSec: Float,
        val endSec: Float,
        val text: String,
        val audioFile: String
    )

    fun runSpeakerTimeline(
        audio: DecodedAudio,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit = {}
    ): SpeakerTimeline? {
        val d = diarizer ?: run { onLog("[ОШИБКА] Диаризация не инициализирована"); return null }
        return try {
            onProgress("Извлекаю эмбеддинги...")
            val totalSec = audio.samples.size.toFloat() / audio.sampleRate
            onLog("[ДИАР.] Обрабатываю аудио: ${audio.samples.size} семплов, ${"%.1f".format(totalSec)}с")

            // Normalize audio volume
            val maxAbs = audio.samples.maxOf { kotlin.math.abs(it) }
            val normalized = if (maxAbs > 0f && maxAbs < 0.5f) {
                val scale = 0.95f / maxAbs
                onLog("[ДИАР.] Нормализация: пик был ${"%.4f".format(maxAbs)}, усиление в ${"%.1f".format(scale)}x")
                FloatArray(audio.samples.size) { audio.samples[it] * scale }
            } else {
                onLog("[ДИАР.] Громкость норм (пик=${"%.4f".format(maxAbs)}), без нормализации")
                audio.samples
            }

            // Pass the entire audio to the model at once (no chunking)
            val sr = audio.sampleRate
            onLog("[ДИАР.] Передаю всё аудио целиком (${normalized.size} семплов, ${"%.1f".format(normalized.size.toFloat()/sr)}с)")
            
            val rawResult = d.process(normalized)
            onLog("[ДИАР.]   -> ${rawResult.size} сегментов")
            
            val rawSegments = rawResult.map { Triple(it.start, it.end, it.speaker) }

            if (rawSegments.isEmpty()) {
                onLog("[ДИАР.] Нет сегментов")
                return SpeakerTimeline(0, emptyList())
            }

            val speakers = rawSegments.map { it.third }.distinct().sorted()
            val speakerMap = speakers.mapIndexed { i, spk -> spk to i }.toMap()

            val segments = rawSegments.map { (s, e, spk) ->
                SpeakerSegment(
                    speakerId = speakerMap[spk] ?: 0,
                    startSec = s,
                    endSec = e
                )
            }
            val uniqueCount = segments.map { seg -> seg.speakerId }.distinct().size
            onLog("[ДИАР.] Найдено спикеров: $uniqueCount, всего сегментов: ${segments.size}")

            SpeakerTimeline(speakerCount = uniqueCount, segments = segments)
        } catch (e: Exception) { logError("Ошибка ML-диаризации", e); onLog("[ОШИБКА] ${e.message}"); null }
    }

    fun loadDiarizationModels(): Boolean {
        val dir = ModelDownloader.getModelsDir(context)
        val segModel = java.io.File(dir, "model-reverb.int8.onnx")
        val embModel = java.io.File(dir, "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")
        if (!segModel.exists() || !embModel.exists()) return false
        return try {
            val segConfig = OfflineSpeakerSegmentationPyannoteModelConfig(segModel.absolutePath)
            val segModelConfig = OfflineSpeakerSegmentationModelConfig(segConfig, 4, false, "cpu")
            val embExtractorConfig = SpeakerEmbeddingExtractorConfig(embModel.absolutePath, 4, false, "cpu")
            val clusterConfig = FastClusteringConfig(-1, clusterThreshold)  // numClusters=-1 (auto)
            val diarConfig = OfflineSpeakerDiarizationConfig(
                segModelConfig, embExtractorConfig, clusterConfig,
                0.2f, 0.3f  // minDurationOn=0.2, minDurationOff=0.3 (shorter gaps = more segments)
            )
            diarizer = OfflineSpeakerDiarization(null, diarConfig)
            log("Speaker diarization (beta) загружен"); true
        } catch (e: Exception) { logError("Ошибка загрузки диаризации", e); false }
    }

    fun setClusterThreshold(threshold: Float): Boolean {
        if (threshold <= 0f || threshold >= 1f) return false
        clusterThreshold = threshold
        // Save to SharedPreferences for persistence
        val prefs = context.getSharedPreferences("transcriber_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("cluster_threshold", threshold).apply()
        // Recreate diarizer with new threshold
        val dir = ModelDownloader.getModelsDir(context)
        val segModel = java.io.File(dir, "model-reverb.int8.onnx")
        val embModel = java.io.File(dir, "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")
        if (!segModel.exists() || !embModel.exists()) return false
        return try {
            diarizer?.release()
            val segConfig = OfflineSpeakerSegmentationPyannoteModelConfig(segModel.absolutePath)
            val segModelConfig = OfflineSpeakerSegmentationModelConfig(segConfig, 4, false, "cpu")
            val embExtractorConfig = SpeakerEmbeddingExtractorConfig(embModel.absolutePath, 4, false, "cpu")
            val clusterConfig = FastClusteringConfig(-1, threshold)
            val diarConfig = OfflineSpeakerDiarizationConfig(
                segModelConfig, embExtractorConfig, clusterConfig,
                0.2f, 0.3f
            )
            diarizer = OfflineSpeakerDiarization(null, diarConfig)
            log("Порог кластеризации изменён на $threshold")
            true
        } catch (e: Exception) { logError("Ошибка при смене порога", e); false }
    }

    fun runOfflineDiarization(
        audio: DecodedAudio,
        text: String,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit = {}
    ): String? {
        val d = diarizer ?: run { onLog("[ОШИБКА] Диаризация не инициализирована"); return null }
        if (text.isEmpty()) { onLog("[ДИАР.] Пустой текст, диаризация не нужна"); return text }
        return try {
            onProgress("Извлекаю эмбеддинги...")
            val segments = d.process(audio.samples)
            onLog("[ДИАР.] ${segments.size} сегментов от ML-диаризации")

            if (segments.size < 2) return text

            // Group segments by speaker
            val speakerCounts = mutableMapOf<Int, MutableList<Float>>()
            for (seg in segments) {
                speakerCounts.getOrPut(seg.speaker) { mutableListOf() }
                speakerCounts[seg.speaker]!!.add(seg.start)
            }
            val uniqueSpeakers = speakerCounts.keys.sorted()
            onLog("[ДИАР.] Найдено спикеров: ${uniqueSpeakers.size}")

            // Map speaker IDs to 0..N-1
            val speakerMap = uniqueSpeakers.mapIndexed { i, spk -> spk to i }.toMap()

            // Assign words to segments proportional to segment durations
            val words = text.split(" ").filter { it.isNotEmpty() }
            if (words.isEmpty()) return text

            val totalDuration = segments.sumOf { (it.end - it.start).toDouble() }.coerceAtLeast(1.0)

            val sb = StringBuilder()
            var wordIdx = 0
            for (seg in segments) {
                if (wordIdx >= words.size) break
                val segDuration = (seg.end - seg.start).coerceAtLeast(0.1f)
                val wordCount = ((segDuration / totalDuration.toFloat()) * words.size).toInt()
                    .coerceIn(1, words.size - wordIdx - (segments.size - 1))

                val segWords = words.subList(wordIdx, (wordIdx + wordCount).coerceAtMost(words.size))
                val spkName = speakerMap[seg.speaker]?.let { "Спикер ${it + 1}" } ?: "Спикер ?"

                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("[%.0fс] %s\n%s".format(seg.start, spkName, segWords.joinToString(" ")))
                wordIdx += wordCount
            }
            if (wordIdx < words.size) {
                sb.append("\n\n")
                sb.append(words.subList(wordIdx, words.size).joinToString(" "))
            }

            onLog("[ДИАР.] ML-диаризация завершена (${uniqueSpeakers.size} спикеров)")
            sb.toString()
        } catch (e: Exception) { logError("Ошибка ML-диаризации", e); onLog("[ОШИБКА] ${e.message}"); null }
    }

    fun release() {
        log("Освобождаю ресурсы...")
        try { recognizer?.release() } catch (_: Exception) {}
        try { diarizer?.release() } catch (_: Exception) {}
        log("Готово")
    }

    // ===== SEGMENT TRANSCRIPTION (slice audio → transcribe each) =====

    fun transcribeSegments(
        audio: DecodedAudio,
        segments: List<SpeakerSegment>,
        cacheDir: java.io.File,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit = {}
    ): List<TranscribedSegment> {
        val r = recognizer ?: run {
            onLog("[ОШИБКА] ASR не инициализирован для сегментов")
            return emptyList()
        }
        val sampleRate = audio.sampleRate
        val samples = audio.samples
        val result = mutableListOf<TranscribedSegment>()

        for ((i, seg) in segments.withIndex()) {
            onProgress("Сегмент ${i + 1}/${segments.size} (Спикер ${seg.speakerId + 1})...")
            val startSample = (seg.startSec * sampleRate).toInt().coerceIn(0, samples.size)
            val endSample = (seg.endSec * sampleRate).toInt().coerceIn(startSample, samples.size)
            val durSec = (endSample - startSample).toFloat() / sampleRate

            if (durSec < 0.5f) {
                onLog("[СЕГМЕНТ ${i + 1}] ⏭ Слишком короткий (${"%.1f".format(durSec)}с), пропускаю")
                result.add(TranscribedSegment(seg.speakerId, seg.startSec, seg.endSec, "", ""))
                continue
            }

            val segmentSamples = samples.sliceArray(startSample until endSample)
            val text = transcribe(r, segmentSamples, sampleRate)

            // Save as WAV
            val wavFile = java.io.File(cacheDir,
                "seg_${i}_spk${seg.speakerId}_${seg.startSec.toInt()}-${seg.endSec.toInt()}.wav")
            saveAsWav(segmentSamples, sampleRate, wavFile)

            result.add(TranscribedSegment(seg.speakerId, seg.startSec, seg.endSec, text, wavFile.absolutePath))
            onLog("[СЕГМЕНТ ${i + 1}] Спикер ${seg.speakerId + 1} [${
                "%.1f".format(seg.startSec)}—${"%.1f".format(seg.endSec)}с]: \"${text.take(80).replace("\n", " ")}\"")
        }
        return result
    }

    private fun saveAsWav(samples: FloatArray, sampleRate: Int, file: java.io.File) {
        val pcmData = ShortArray(samples.size) {
            (samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val dataSize = pcmData.size * 2
        val fileSize = 44 + dataSize

        file.outputStream().use { os ->
            os.write("RIFF".toByteArray())
            os.write(intToLeBytes(fileSize - 8))
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray())
            os.write(intToLeBytes(16))
            os.write(shortToLeBytes(1))   // PCM
            os.write(shortToLeBytes(1))   // mono
            os.write(intToLeBytes(sampleRate))
            os.write(intToLeBytes(sampleRate * 2)) // byte rate
            os.write(shortToLeBytes(2))   // block align
            os.write(shortToLeBytes(16))  // bits per sample
            os.write("data".toByteArray())
            os.write(intToLeBytes(dataSize))
            val buf = ByteArray(pcmData.size * 2)
            for (j in pcmData.indices) {
                buf[j * 2] = (pcmData[j].toInt() and 0xFF).toByte()
                buf[j * 2 + 1] = (pcmData[j].toInt() shr 8 and 0xFF).toByte()
            }
            os.write(buf)
        }
    }

    private fun intToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()
    )
    private fun shortToLeBytes(v: Short): ByteArray = byteArrayOf(
        (v.toInt() and 0xFF).toByte(), (v.toInt() shr 8 and 0xFF).toByte()
    )
}
