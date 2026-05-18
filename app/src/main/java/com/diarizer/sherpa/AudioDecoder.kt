package com.diarizer.sherpa

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {
    private val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_FLOAT = 4

    /**
     * Decodes any audio file to 16kHz mono FloatArray for sherpa-onnx.
     * Uses streaming approach to avoid OutOfMemoryError on long audio.
     * Returns DecodedAudio or null on failure.
     */
    fun decodeToAudio(context: Context, uri: Uri): Pipeline.DecodedAudio? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
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
            Log.i(TAG, "Source: $mime, ${srcSampleRate}Hz, ${srcChannels}ch")

            // Decode with MediaCodec
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            // Accumulate float samples directly — no intermediate ByteArray copies
            var floatAccumulator = mutableListOf<Float>()
            var isDone = false
            var outputPcmEncoding = ENCODING_PCM_16BIT
            var outputChannels = srcChannels
            var outputSampleRate = srcSampleRate
            var totalRawBytes = 0L

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
                        } catch (e: Exception) {
                            ENCODING_PCM_16BIT
                        }
                        outputChannels = try {
                            outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } catch (e: Exception) {
                            srcChannels
                        }
                        outputSampleRate = try {
                            outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } catch (e: Exception) {
                            srcSampleRate
                        }
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
                            totalRawBytes += bufferInfo.size

                            // Convert this chunk to mono float samples immediately
                            chunkToFloatMono(chunk, outputPcmEncoding, outputChannels, floatAccumulator)
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

            if (floatAccumulator.isEmpty()) return null

            Log.i(TAG, "Decoded $totalRawBytes raw bytes → ${floatAccumulator.size} float samples")

            // Convert to array
            val floatSamples = floatAccumulator.toFloatArray()
            floatAccumulator.clear() // free memory

            // Resample to 16kHz if needed
            val finalSamples = if (outputSampleRate != TARGET_SAMPLE_RATE) {
                resample(floatSamples, outputSampleRate, TARGET_SAMPLE_RATE)
            } else {
                floatSamples
            }

            Log.i(TAG, "Final: ${finalSamples.size} samples, ${TARGET_SAMPLE_RATE}Hz")

            Pipeline.DecodedAudio(finalSamples, TARGET_SAMPLE_RATE)

        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
            null
        }
    }

    /** Convert a raw PCM chunk to mono float samples and append to accumulator. */
    private fun chunkToFloatMono(chunk: ByteArray, encoding: Int, channels: Int, accumulator: MutableList<Float>) {
        when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val floatBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val srcSamples = chunk.size / 4 / channels
                for (i in 0 until srcSamples) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += floatBuf.get(i * channels + ch)
                    }
                    accumulator.add(sum / channels)
                }
            }
            else -> {
                val byteBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                val srcSamples = chunk.size / 2 / channels
                for (i in 0 until srcSamples) {
                    var sum = 0
                    for (ch in 0 until channels) {
                        sum += byteBuf.getShort((i * channels + ch) * 2).toInt()
                    }
                    val avg = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    accumulator.add(avg.toFloat() / 32768f)
                }
            }
        }
    }

    /** Simple linear resampling. Operates on FloatArray directly. */
    private fun resample(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstSize = (src.size / ratio).toInt()
        val dst = FloatArray(dstSize)
        for (i in 0 until dstSize) {
            val srcIdx = (i * ratio).toInt()
            if (srcIdx < src.size) {
                dst[i] = src[srcIdx]
            }
        }
        Log.i(TAG, "Resampled: $srcRate -> $dstRate, ${src.size} -> ${dst.size}")
        return dst
    }
}
