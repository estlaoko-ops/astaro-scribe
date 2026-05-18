package com.diarizer.sherpa

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Decodes any audio file to 16kHz mono PCM, writing directly to a temp file
 * during decoding to avoid holding the entire audio in memory.
 */
object AudioDecoder {
    private val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_FLOAT = 4
    /** Files over this many samples are written to temp file (now: always for large audio) */
    private const val FILE_BACKED_THRESHOLD = 1_000_000
    /** Max chunk to resample at once: 1 second of audio at 48kHz */
    private const val RESAMPLE_CHUNK = 48_000

    /**
     * Decodes any audio file to 16kHz mono.
     * - Small audio (<1M samples): returned as in-memory FloatArray
     * - Large audio: written to temp file during decoding, no large in-memory buffers
     */
    fun decodeToAudio(context: Context, uri: Uri): Pipeline.DecodedAudio? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                mime@ val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (e: Exception) { -1L }
            Log.i(TAG, "Source: $mime, ${srcSampleRate}Hz, ${srcChannels}ch, ${durationUs / 1000}ms")

            // Decode with MediaCodec
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()

            // Prepare temp file for streaming write
            val cacheDir = File(context.cacheDir, "decoded_audio")
            cacheDir.mkdirs()
            val tempFile = File.createTempFile("pcm_", ".raw", cacheDir)
            val fileOut = FileOutputStream(tempFile)
            val floatBuf = ByteBuffer.allocate(65536)  // 16K floats
            floatBuf.order(ByteOrder.LITTLE_ENDIAN)

            var outputPcmEncoding = ENCODING_PCM_16BIT
            var outputChannels = srcChannels
            var outputSampleRate = srcSampleRate
            var totalFileSamples = 0L
            var isDone = false

            while (!isDone) {
                // Feed input
                val inputIdx = codec.dequeueInputBuffer(10000L)
                if (inputIdx >= 0) {
                    val sampleSize = extractor.readSampleData(codec.getInputBuffer(inputIdx)!!, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Drain output
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        outputPcmEncoding = try {
                            outFormat.getInteger("pcm-encoding")
                        } catch (e: Exception) { ENCODING_PCM_16BIT }
                        outputChannels = try {
                            outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } catch (e: Exception) { srcChannels }
                        outputSampleRate = try {
                            outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } catch (e: Exception) { srcSampleRate }
                        val encodingStr = when (outputPcmEncoding) {
                            ENCODING_PCM_16BIT -> "PCM_16BIT"
                            ENCODING_PCM_FLOAT -> "PCM_FLOAT"
                            else -> "PCM_${outputPcmEncoding}"
                        }
                        Log.i(TAG, "Output: $encodingStr, ${outputSampleRate}Hz, ${outputChannels}ch")
                    }
                    outIdx >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isDone = true
                        }
                        if (bufferInfo.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            buf.get(chunk)

                            // Convert chunk to mono float samples and write to file
                            val monoFloats = chunkToMonoFloat(chunk, outputPcmEncoding, outputChannels)
                            val resampled = if (outputSampleRate != TARGET_SAMPLE_RATE) {
                                resampleChunk(monoFloats, outputSampleRate, TARGET_SAMPLE_RATE)
                            } else monoFloats
                            writeFloatsToFile(fileOut, floatBuf, resampled)
                            totalFileSamples += resampled.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (isDone) break
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
            fileOut.close()

            if (totalFileSamples == 0L) {
                tempFile.delete()
                return null
            }

            Log.i(TAG, "Decoded → tempFile: $totalFileSamples samples (${tempFile.length() / (1024*1024)} MB) at ${TARGET_SAMPLE_RATE}Hz")

            // For small audio, read back into memory for convenience
            if (totalFileSamples <= FILE_BACKED_THRESHOLD) {
                val mem = readAllFloats(tempFile, totalFileSamples.toInt())
                tempFile.delete()
                Pipeline.DecodedAudio(sampleRate = TARGET_SAMPLE_RATE, samples = mem)
            } else {
                Pipeline.DecodedAudio(
                    sampleRate = TARGET_SAMPLE_RATE,
                    tempFile = tempFile,
                    numSamples = totalFileSamples.toInt()
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
            null
        }
    }

    /** Convert a raw PCM chunk to mono float array (not yet resampled). */
    private fun chunkToMonoFloat(chunk: ByteArray, encoding: Int, channels: Int): FloatArray {
        return when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val fb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val srcSamples = chunk.size / 4 / channels
                val out = FloatArray(srcSamples)
                var fi = 0
                for (i in 0 until srcSamples) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += if (fi < fb.limit()) fb.get(fi) else 0f
                        fi++
                    }
                    out[i] = sum / channels
                }
                out
            }
            else -> {
                val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                val srcSamples = chunk.size / 2 / channels
                val out = FloatArray(srcSamples)
                var bi = 0
                for (i in 0 until srcSamples) {
                    var sum = 0
                    for (ch in 0 until channels) {
                        sum += if (bi < bb.limit() * 2) bb.getShort(bi).toInt() else 0
                        bi += 2
                    }
                    val avg = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    out[i] = avg.toFloat() / 32768f
                }
                out
            }
        }
    }

    /** Linear resample a chunk in-place. Ratio computed from source/dest rates. */
    private fun resampleChunk(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstSize = (src.size / ratio).toInt().coerceAtLeast(1)
        val dst = FloatArray(dstSize)
        for (i in 0 until dstSize) {
            val srcIdx = (i * ratio).toInt()
            dst[i] = if (srcIdx < src.size) src[srcIdx] else 0f
        }
        return dst
    }

    /** Write float array to file via reusable ByteBuffer. */
    private fun writeFloatsToFile(out: FileOutputStream, floatBuf: ByteBuffer, floats: FloatArray) {
        val fb = floatBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        fb.clear()
        if (fb.capacity() < floats.size * 4) {
            // Rarely happens — allocate one-off for oversized chunk
            val bigBuf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bigBuf.asFloatBuffer().put(floats)
            out.write(bigBuf.array(), 0, floats.size * 4)
            return
        }
        fb.limit(floats.size * 4)
        fb.asFloatBuffer().put(floats)
        out.write(fb.array(), 0, floats.size * 4)
    }

    /** Read all floats back from a temp file (for small audio). */
    private fun readAllFloats(file: File, count: Int): FloatArray {
        val bytes = ByteArray(count * 4)
        java.io.RandomAccessFile(file, "r").use { raf ->
            var total = 0
            while (total < bytes.size) {
                val r = raf.read(bytes, total, bytes.size - total)
                if (r < 0) break
                total += r
            }
        }
        val result = FloatArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result)
        return result
    }
}
