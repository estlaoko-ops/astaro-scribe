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
            // Accumulate float samples using growing FloatArray (no boxing!)
            var floatBuffer = FloatArray(65536)
            var floatSize = 0
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

                            // Estimate max samples this chunk will produce and grow buffer
                            val maxNewSamples = chunk.size / 2 // worst case: 16-bit mono
                            if (floatSize + maxNewSamples > floatBuffer.size) {
                                val newSize = maxOf(floatBuffer.size * 3 / 2, floatSize + maxNewSamples + 4096)
                                floatBuffer = floatBuffer.copyOf(newSize)
                            }

                            // Convert this chunk to mono float samples immediately
                            floatSize = chunkToFloatMono(chunk, outputPcmEncoding, outputChannels, floatBuffer, floatSize)
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

            if (floatSize == 0) return null

            Log.i(TAG, "Decoded $totalRawBytes raw bytes → $floatSize float samples")

            // Trim buffer to actual size and free temporary references
            val floatSamples = floatBuffer.copyOf(floatSize)

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

    /** Convert a raw PCM chunk to mono float samples and append to buffer. Returns new size. */
    private fun chunkToFloatMono(chunk: ByteArray, encoding: Int, channels: Int, buffer: FloatArray, offset: Int): Int {
        var pos = offset
        // Ensure buffer has room - caller is responsible for growing
        when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val floatBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val srcSamples = chunk.size / 4 / channels
                var bufPos = 0
                for (i in 0 until srcSamples) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += floatBuf.get(bufPos++)
                    }
                    buffer[pos++] = sum / channels
                }
            }
            else -> {
                val byteBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                val srcSamples = chunk.size / 2 / channels
                var bufPos = 0
                for (i in 0 until srcSamples) {
                    var sum = 0
                    for (ch in 0 until channels) {
                        sum += byteBuf.getShort(bufPos).toInt()
                        bufPos += 2
                    }
                    val avg = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    buffer[pos++] = avg.toFloat() / 32768f
                }
            }
        }
        return pos
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
