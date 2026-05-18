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
    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_FLOAT = 4

    /**
     * Decodes any audio file to 16kHz mono FloatArray for sherpa-onnx.
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
            val decodedChunks = mutableListOf<ByteArray>()
            var totalDecoded = 0
            var isDone = false
            var outputPcmEncoding = ENCODING_PCM_16BIT
            var outputChannels = srcChannels
            var outputSampleRate = srcSampleRate

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
                            decodedChunks.add(chunk)
                            totalDecoded += bufferInfo.size
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

            Log.i(TAG, "Decoded $totalDecoded bytes, encoding=$outputPcmEncoding")
            if (totalDecoded == 0) return null

            // Concatenate all chunks
            val rawPcm = ByteArray(totalDecoded)
            var offset = 0
            for (chunk in decodedChunks) {
                System.arraycopy(chunk, 0, rawPcm, offset, chunk.size)
                offset += chunk.size
            }

            // Convert to 16-bit mono shorts
            val shorts = convertTo16BitMono(rawPcm, outputPcmEncoding, outputChannels)

            // Resample to 16kHz if needed
            val resampled = if (outputSampleRate != TARGET_SAMPLE_RATE) {
                resample(shorts, outputSampleRate, TARGET_SAMPLE_RATE)
            } else {
                shorts
            }

            Log.i(TAG, "Final: ${resampled.size} samples, ${TARGET_SAMPLE_RATE}Hz, 16-bit mono")

            // Convert to FloatArray [-1..1] for sherpa-onnx
            val floatSamples = FloatArray(resampled.size) { i ->
                resampled[i].toFloat() / 32768f
            }

            Log.i(TAG, "Декодировано: ${floatSamples.size} семплов, ${TARGET_SAMPLE_RATE}Гц")
            Pipeline.DecodedAudio(floatSamples, TARGET_SAMPLE_RATE)

        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
            null
        }
    }

    private fun convertTo16BitMono(rawPcm: ByteArray, encoding: Int, channels: Int): ShortArray {
        return when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val srcSamples = rawPcm.size / 4 / channels
                val floatBuf = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val mono = ShortArray(srcSamples)
                for (i in 0 until srcSamples) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += floatBuf.get(i * channels + ch)
                    }
                    val avg = sum / channels
                    mono[i] = (avg.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                mono
            }
            else -> {
                val srcSamples = rawPcm.size / 2 / channels
                val byteBuf = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)
                val mono = ShortArray(srcSamples)
                for (i in 0 until srcSamples) {
                    var sum = 0
                    for (ch in 0 until channels) {
                        sum += byteBuf.getShort((i * channels + ch) * 2).toInt()
                    }
                    mono[i] = (sum / channels).toShort()
                }
                mono
            }
        }
    }

    private fun resample(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstSize = (src.size / ratio).toInt()
        val dst = ShortArray(dstSize)
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
