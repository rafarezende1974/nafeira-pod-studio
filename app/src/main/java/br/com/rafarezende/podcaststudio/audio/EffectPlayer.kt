package br.com.rafarezende.podcaststudio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Preview de efeitos FORA da gravacao: toca um PCM uma vez via AudioTrack.
 * Tocar de novo para a reproducao anterior.
 */
class EffectPlayer {

    companion object {
        private const val TAG = "EffectPlayer"
    }

    @Volatile
    var volume: Float = 0.8f

    @Volatile
    private var playing = false

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun play(pcm: ShortArray) {
        stop()
        val myTrack = try {
            createTrack()
        } catch (e: Exception) {
            Log.w(TAG, "falha ao criar AudioTrack", e)
            return
        }
        synchronized(this) {
            track = myTrack
            playing = true
        }
        val t = Thread {
            try {
                myTrack.setVolume(volume.coerceIn(0f, 1f))
                myTrack.play()
                var offset = 0
                while (playing && offset < pcm.size) {
                    val chunk = minOf(2048, pcm.size - offset)
                    val written = myTrack.write(pcm, offset, chunk)
                    if (written <= 0) break
                    offset += written
                }
            } catch (e: Exception) {
                Log.w(TAG, "preview falhou", e)
            } finally {
                synchronized(this@EffectPlayer) {
                    if (track === myTrack) {
                        track = null
                        playing = false
                    }
                }
                try {
                    myTrack.stop()
                } catch (e: Exception) {
                    // ignora
                }
                try {
                    myTrack.release()
                } catch (e: Exception) {
                    // ignora
                }
            }
        }
        synchronized(this) {
            thread = t
        }
        t.start()
    }

    fun stop() {
        val old: AudioTrack?
        synchronized(this) {
            playing = false
            old = track
            track = null
        }
        if (old != null) {
            try {
                old.pause()
            } catch (e: Exception) {
                // ignora
            }
            try {
                old.flush()
            } catch (e: Exception) {
                // ignora
            }
            try {
                old.stop()
            } catch (e: Exception) {
                // ignora
            }
            try {
                old.release()
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    fun isPlaying(): Boolean = playing

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SfxSynth.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, 4096 * 2)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SfxSynth.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }
}
