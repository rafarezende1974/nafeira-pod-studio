package br.com.rafarezende.podcaststudio.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodifica um arquivo de audio (mp3/wav/m4a/ogg...) pra PCM 44100 Hz,
 * mono, 16 bits — o formato interno da mesa de efeitos.
 * Retorna null em qualquer falha. NUNCA chame na thread principal.
 */
object SfxDecoder {

    private const val TAG = "SfxDecoder"
    const val TARGET_RATE = 44100
    private const val MAX_OUTPUT_SAMPLES = TARGET_RATE * 90 // teto de 90 s
    private const val TIMEOUT_US = 10_000L

    fun decode(file: File): ShortArray? {
        if (!file.exists() || file.length() == 0L) return null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                Log.w(TAG, "sem faixa de audio em ${file.name}")
                return null
            }
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var srcRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                TARGET_RATE
            }
            var srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            if (srcChannels < 1) srcChannels = 1
            if (srcChannels > 2) srcChannels = 2

            val mono = ArrayList<Float>(TARGET_RATE * 10)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            val maxGuard = 200_000

            while (!outputDone && guard < maxGuard) {
                guard++

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        } else {
                            inBuf.clear()
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, size,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceIn(1, 2)
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.nativeOrder())
                            val shorts = outBuf.asShortBuffer()
                            val totalShorts = info.size / 2
                            val frames = totalShorts / srcChannels
                            var f = 0
                            while (f < frames && mono.size < MAX_OUTPUT_SAMPLES) {
                                var sum = 0f
                                var ch = 0
                                while (ch < srcChannels) {
                                    val idx = f * srcChannels + ch
                                    if (idx < shorts.limit()) {
                                        sum += shorts.get(idx).toFloat()
                                    }
                                    ch++
                                }
                                mono.add(sum / srcChannels)
                                f++
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (mono.size >= MAX_OUTPUT_SAMPLES) {
                            outputDone = true
                        }
                    }
                    // INFO_TRY_AGAIN_LATER etc.: segue o loop (com o guard).
                }
            }

            if (mono.isEmpty()) return null
            val monoArray = FloatArray(mono.size) { mono[it] }
            return resampleToTarget(monoArray, srcRate)
        } catch (e: Exception) {
            Log.w(TAG, "falha ao decodificar ${file.name}", e)
            return null
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // ignora
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // ignora
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    /** Reamostragem linear simples pra 44100 Hz. */
    private fun resampleToTarget(input: FloatArray, srcRate: Int): ShortArray {
        if (srcRate == TARGET_RATE || srcRate <= 0) {
            return ShortArray(input.size) { i ->
                input[i].coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
        val outLen = (input.size.toDouble() * TARGET_RATE / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / TARGET_RATE
        for (i in 0 until outLen) {
            val pos = i * step
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx]
            val b = if (idx + 1 < input.size) input[idx + 1] else a
            val v = a + (b - a) * frac
            out[i] = v.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return out
    }
}
