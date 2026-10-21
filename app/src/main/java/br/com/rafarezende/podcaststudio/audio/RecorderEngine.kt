package br.com.rafarezende.podcaststudio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motor de gravacao da V2: AudioRecord PCM -> mixagem digital
 * (voz + efeitos) -> encoder AAC -> .m4a via MediaMuxer.
 *
 * O MediaRecorder da V1 nao servia mais: ele so grava o microfone e nao
 * aceita misturar os efeitos da mesa de som na mesma faixa.
 */
class RecorderEngine(private val context: Context) {

    companion object {
        private const val TAG = "RecorderEngine"
        const val SAMPLE_RATE = 44100
        private const val FRAME_SAMPLES = 1024
        private const val BIT_RATE = 128000
        private const val MAX_INPUT_SIZE = 16384
    }

    /** Volume dos efeitos (0f..1f), lido a cada frame. */
    @Volatile
    var effectsVolume: Float = 0.8f

    private class ActiveEffect(val pcm: ShortArray) {
        @Volatile
        var pos: Int = 0
    }

    private val effectsLock = Any()
    private val activeEffects = ArrayList<ActiveEffect>()

    private val pauseLock = Object()

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var samplesQueued: Long = 0

    @Volatile
    private var peakHold: Int = 0

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var recordThread: Thread? = null

    private var monitorTrack: AudioTrack? = null

    // ---------------------------------------------------------------
    // API publica
    // ---------------------------------------------------------------

    /**
     * Prepara tudo e comeca a gravar. Em falha, lanca excecao (o servico
     * trata voltando pra IDLE) — nenhum recurso fica aberto nesse caso.
     */
    fun start(file: File, deviceId: Int) {
        if (running) throw IllegalStateException("Engine ja esta gravando.")
        cleanupPartial()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(if (minBuffer > 0) minBuffer else 4096, 4096) * 2

        val record = AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release()
            } catch (e: Exception) {
                // ignora
            }
            throw IllegalStateException("AudioRecord nao inicializou.")
        }

        if (deviceId >= 0 && Build.VERSION.SDK_INT >= 28) {
            try {
                val audioManager =
                    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == deviceId }
                if (device != null) {
                    record.setPreferredDevice(device)
                }
            } catch (e: Exception) {
                Log.w(TAG, "setPreferredDevice falhou, usando padrao", e)
            }
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)

        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val newMuxer: MediaMuxer
        try {
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newMuxer = MediaMuxer(
                file.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            newCodec.start()
        } catch (e: Exception) {
            try {
                newCodec.release()
            } catch (ignored: Exception) {
            }
            try {
                record.release()
            } catch (ignored: Exception) {
            }
            throw e
        }

        audioRecord = record
        codec = newCodec
        muxer = newMuxer
        trackIndex = -1
        muxerStarted = false
        samplesQueued = 0
        peakHold = 0
        synchronized(effectsLock) {
            activeEffects.clear()
        }
        synchronized(pauseLock) {
            paused = false
        }
        running = true

        try {
            record.startRecording()
        } catch (e: Exception) {
            running = false
            cleanupPartial()
            throw e
        }

        val thread = Thread({ recordLoop() }, "PodcastRecorder")
        recordThread = thread
        thread.start()
    }

    fun pause() {
        if (!running) return
        synchronized(pauseLock) {
            if (paused) return
            paused = true
        }
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord.stop na pausa falhou", e)
        }
    }

    fun resume() {
        if (!running) return
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord.startRecording na retomada falhou", e)
        }
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    fun isPaused(): Boolean = synchronized(pauseLock) { paused }

    /** Para tudo e espera a thread finalizar o arquivo (EOS + muxer stop). */
    fun stopAndJoin() {
        if (!running && recordThread == null) {
            cleanupPartial()
            return
        }
        // Acorda a thread se estiver pausada, pra ela enxergar running=false.
        synchronized(pauseLock) {
            running = false
            paused = false
            pauseLock.notifyAll()
        }
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // ignora
        }
        val thread = recordThread
        recordThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(5000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "join da thread interrompido", e)
            }
        }
        // Se a thread nao encerrou os recursos (travou?), limpa por fora.
        if (codec != null || muxer != null || audioRecord != null) {
            cleanupPartial()
        }
        releaseMonitor()
    }

    /** Dispara um efeito: ele entra na mixagem a partir do proximo frame. */
    fun triggerEffect(pcm: ShortArray) {
        if (!running) return
        synchronized(effectsLock) {
            activeEffects.add(ActiveEffect(pcm))
        }
        ensureMonitor()
    }

    fun stopEffects() {
        synchronized(effectsLock) {
            activeEffects.clear()
        }
    }

    fun isEffectActive(): Boolean = synchronized(effectsLock) { activeEffects.isNotEmpty() }

    /** Tempo efetivamente gravado (o tempo pausado nao conta). */
    fun elapsedMillis(): Long = samplesQueued * 1000L / SAMPLE_RATE

    /** Pico do microfone desde a ultima leitura (le-e-zera, pro medidor). */
    fun currentPeak(): Int {
        val p = peakHold
        peakHold = 0
        return p
    }

    // ---------------------------------------------------------------
    // Thread de gravacao
    // ---------------------------------------------------------------

    private fun recordLoop() {
        val micFrame = ShortArray(FRAME_SAMPLES)
        val mixed = ShortArray(FRAME_SAMPLES)
        val fxOnly = ShortArray(FRAME_SAMPLES)
        try {
            while (true) {
                synchronized(pauseLock) {
                    while (running && paused) {
                        try {
                            pauseLock.wait()
                        } catch (e: InterruptedException) {
                            // segue e reavalia as flags
                        }
                    }
                }
                if (!running) break

                val record = audioRecord ?: break
                val read = try {
                    record.read(micFrame, 0, FRAME_SAMPLES)
                } catch (e: Exception) {
                    Log.w(TAG, "audioRecord.read falhou", e)
                    -1
                }
                if (read <= 0) {
                    // Pausado (stop() no AudioRecord) ou erro transitorio:
                    // pequena espera pra nao girar a CPU e reavalia.
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        // ignora
                    }
                    continue
                }

                // Pico do microfone (so a voz) pro medidor.
                var peak = 0
                var i = 0
                while (i < read) {
                    val a = kotlin.math.abs(micFrame[i].toInt())
                    if (a > peak) peak = a
                    i++
                }
                if (peak > peakHold) peakHold = peak

                // Mixagem: voz + efeitos ativos (com ganho), com clamp.
                // Snapshot dos efeitos no inicio do frame: o lock NAO pode
                // ficar dentro do loop por amostra (44 mil vezes/segundo).
                val frameEffects: List<ActiveEffect> = synchronized(effectsLock) {
                    ArrayList(activeEffects)
                }
                val gain = effectsVolume.coerceIn(0f, 1f)
                var hasFx = false
                i = 0
                while (i < read) {
                    var fx = 0f
                    for (fxItem in frameEffects) {
                        if (fxItem.pos < fxItem.pcm.size) {
                            fx += fxItem.pcm[fxItem.pos].toFloat()
                            fxItem.pos++
                        }
                    }
                    val fxSample = fx * gain
                    fxOnly[i] = clampToShort(fxSample)
                    if (fxSample != 0f) hasFx = true
                    mixed[i] = clampToShort(micFrame[i].toFloat() + fxSample)
                    i++
                }
                if (frameEffects.isNotEmpty()) {
                    synchronized(effectsLock) {
                        val it = activeEffects.iterator()
                        while (it.hasNext()) {
                            val item = it.next()
                            if (item.pos >= item.pcm.size) {
                                it.remove()
                            }
                        }
                    }
                }

                // Monitor: so os efeitos (a voz NAO volta, pra nao dar microfonia).
                if (hasFx) {
                    writeMonitor(fxOnly, read)
                }

                if (!enqueueFrame(mixed, read)) {
                    Log.w(TAG, "fila do encoder cheia, frame descartado")
                }
                drainEncoder(endOfStream = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "thread de gravacao falhou", e)
        } finally {
            finishEncoding()
        }
    }

    private fun clampToShort(v: Float): Short {
        return when {
            v > 32767f -> 32767
            v < -32768f -> -32768
            else -> v.toInt().toShort()
        }
    }

    // ---------------------------------------------------------------
    // Encoder / Muxer
    // ---------------------------------------------------------------

    private fun enqueueFrame(frame: ShortArray, samples: Int): Boolean {
        val enc = codec ?: return false
        val index = try {
            enc.dequeueInputBuffer(10_000L)
        } catch (e: Exception) {
            Log.w(TAG, "dequeueInputBuffer falhou", e)
            return false
        }
        if (index < 0) return false
        val inputBuffer = try {
            enc.getInputBuffer(index)
        } catch (e: Exception) {
            null
        } ?: return false

        inputBuffer.clear()
        inputBuffer.order(ByteOrder.nativeOrder())
        val byteCount = samples * 2
        if (byteCount > inputBuffer.capacity()) return false
        inputBuffer.asShortBuffer().put(frame, 0, samples)
        inputBuffer.limit(byteCount)

        // PTS pelo total de samples ja enfileirados: o tempo pausado nao
        // conta, porque nenhum sample e enfileirado durante a pausa.
        val ptsUs = samplesQueued * 1_000_000L / SAMPLE_RATE
        try {
            enc.queueInputBuffer(index, 0, byteCount, ptsUs, 0)
        } catch (e: Exception) {
            Log.w(TAG, "queueInputBuffer falhou", e)
            return false
        }
        samplesQueued += samples
        return true
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = codec ?: return
        val info = MediaCodec.BufferInfo()
        var guard = 0
        while (guard < 1000) {
            guard++
            val outIndex = try {
                enc.dequeueOutputBuffer(info, if (endOfStream) 100_000L else 0L)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueOutputBuffer falhou", e)
                return
            }
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer
                    if (mux != null && !muxerStarted) {
                        try {
                            trackIndex = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted = true
                        } catch (e: Exception) {
                            Log.e(TAG, "muxer start falhou", e)
                        }
                    }
                }
                outIndex >= 0 -> {
                    val outBuffer = try {
                        enc.getOutputBuffer(outIndex)
                    } catch (e: Exception) {
                        null
                    }
                    val mux = muxer
                    if (outBuffer != null && mux != null && muxerStarted && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        try {
                            mux.writeSampleData(trackIndex, outBuffer, info)
                        } catch (e: Exception) {
                            Log.w(TAG, "writeSampleData falhou", e)
                        }
                    }
                    try {
                        enc.releaseOutputBuffer(outIndex, false)
                    } catch (e: Exception) {
                        // ignora
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                else -> {
                    // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED
                    if (!endOfStream) return
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Em EOS continuamos tentando ate o guard ou o flag
                        // de fim de stream aparecer.
                        continue
                    }
                    return
                }
            }
        }
    }

    /** EOS na entrada + drena ate o fim + libera codec/muxer/record. */
    private fun finishEncoding() {
        val enc = codec
        if (enc != null) {
            try {
                val index = enc.dequeueInputBuffer(100_000L)
                if (index >= 0) {
                    val ptsUs = samplesQueued * 1_000_000L / SAMPLE_RATE
                    enc.queueInputBuffer(
                        index, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "EOS falhou", e)
            }
            drainEncoder(endOfStream = true)
        }
        cleanupPartial()
    }

    private fun cleanupPartial() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // ignora
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // ignora
        }
        audioRecord = null

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
        codec = null

        if (muxerStarted) {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "muxer stop falhou", e)
            }
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            // ignora
        }
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }

    // ---------------------------------------------------------------
    // Monitor dos efeitos (AudioTrack sob demanda)
    // ---------------------------------------------------------------

    private fun ensureMonitor() {
        if (monitorTrack != null) return
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(if (minBuffer > 0) minBuffer else 4096, 4096) * 2
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val track = AudioTrack(
                attributes, format, bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                monitorTrack = track
            } else {
                track.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "monitor nao inicializou", e)
        }
    }

    private fun writeMonitor(frame: ShortArray, samples: Int) {
        val track = monitorTrack ?: return
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                track.write(frame, 0, samples, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                track.write(frame, 0, samples)
            }
        } catch (e: Exception) {
            Log.w(TAG, "monitor write falhou", e)
        }
    }

    private fun releaseMonitor() {
        val track = monitorTrack
        monitorTrack = null
        if (track != null) {
            try {
                track.stop()
            } catch (e: Exception) {
                // ignora
            }
            try {
                track.release()
            } catch (e: Exception) {
                // ignora
            }
        }
    }
}
