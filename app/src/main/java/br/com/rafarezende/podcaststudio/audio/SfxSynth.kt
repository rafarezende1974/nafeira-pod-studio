package br.com.rafarezende.podcaststudio.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import java.util.Random

/**
 * Sons de fabrica gerados PROCEDURALMENTE (PCM 44100 Hz, mono, 16 bits).
 * Nenhum arquivo binario: cada som e sintetizado por codigo, com seed fixa
 * (deterministico) e cache em memoria.
 */
object SfxSynth {

    const val SAMPLE_RATE = 44100

    enum class BuiltinId { APPLAUSE, LAUGH, BELL, DING, STING, HORN, WHOOSH, JINGLE }

    private val cache = HashMap<BuiltinId, ShortArray>()

    @Synchronized
    fun get(id: BuiltinId): ShortArray {
        val existing = cache[id]
        if (existing != null) return existing
        val pcm = when (id) {
            BuiltinId.APPLAUSE -> applause()
            BuiltinId.LAUGH -> laugh()
            BuiltinId.BELL -> bell()
            BuiltinId.DING -> ding()
            BuiltinId.STING -> sting()
            BuiltinId.HORN -> horn()
            BuiltinId.WHOOSH -> whoosh()
            BuiltinId.JINGLE -> jingle()
        }
        cache[id] = pcm
        return pcm
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun buffer(seconds: Double): FloatArray =
        FloatArray((seconds * SAMPLE_RATE).toInt().coerceAtLeast(1))

    /** Normaliza o pico pra ~0,9 e converte pra ShortArray (soft safety). */
    private fun finish(buf: FloatArray): ShortArray {
        var peak = 0f
        for (v in buf) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        val scale = if (peak > 0f) (0.9f * 32767f) / peak else 0f
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            var s = buf[i] * scale
            if (s > 32767f) s = 32767f
            if (s < -32768f) s = -32768f
            out[i] = s.toInt().toShort()
        }
        return out
    }

    /** Soma um toque FM (sino) com decaimento exponencial no buffer. */
    private fun addFmBell(
        buf: FloatArray,
        startSec: Double,
        carrier: Double,
        modRatio: Double,
        indexStart: Double,
        decay: Double,
        amp: Double
    ) {
        val start = (startSec * SAMPLE_RATE).toInt()
        val maxI = buf.size - start
        if (maxI <= 0) return
        val modFreq = carrier * modRatio
        var i = 0
        while (i < maxI) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-decay * t)
            if (env < 0.001) break
            val beta = indexStart * env
            val v = sin(2.0 * PI * carrier * t + beta * sin(2.0 * PI * modFreq * t))
            buf[start + i] += (v * env * amp).toFloat()
            i++
        }
    }

    /** Soma uma nota "pluck" (seno + harmonico, decaimento exp). */
    private fun addPluck(
        buf: FloatArray,
        startSec: Double,
        freq: Double,
        decay: Double,
        amp: Double,
        durSec: Double
    ) {
        val start = (startSec * SAMPLE_RATE).toInt()
        val len = (durSec * SAMPLE_RATE).toInt()
        val maxI = minOf(len, buf.size - start)
        if (maxI <= 0) return
        var i = 0
        while (i < maxI) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-decay * t)
            val v = sin(2.0 * PI * freq * t) + 0.4 * sin(2.0 * PI * freq * 2.0 * t) +
                0.15 * sin(2.0 * PI * freq * 3.0 * t)
            buf[start + i] += (v * env * amp).toFloat()
            i++
        }
    }

    // ---------------------------------------------------------------
    // Sons
    // ---------------------------------------------------------------

    /** Aplausos ~3s: muitas palmas (rajadas de ruido com decaimento rapido). */
    private fun applause(): ShortArray {
        val dur = 3.0
        val buf = buffer(dur)
        val rnd = Random(20261020L)
        // Leito baixo de ruido da "plateia".
        for (i in buf.indices) {
            buf[i] += ((rnd.nextDouble() * 2.0 - 1.0) * 0.03).toFloat()
        }
        val claps = 170
        for (c in 0 until claps) {
            // Densidade maior no comeco.
            val u = rnd.nextDouble()
            val startSec = dur * u * u * 0.92
            val start = (startSec * SAMPLE_RATE).toInt()
            val len = (0.025 + rnd.nextDouble() * 0.04) * SAMPLE_RATE
            val amp = 0.35 + rnd.nextDouble() * 0.5
            var j = 0
            while (j < len && start + j < buf.size) {
                val t = j.toDouble() / SAMPLE_RATE
                val env = exp(-t * 90.0)
                val noise = rnd.nextDouble() * 2.0 - 1.0
                buf[start + j] += (noise * env * amp).toFloat()
                j++
            }
        }
        // Fade-in 80 ms e fade-out nos ultimos 800 ms.
        val fadeIn = (0.08 * SAMPLE_RATE).toInt()
        val fadeOut = (0.8 * SAMPLE_RATE).toInt()
        for (i in buf.indices) {
            var g = 1f
            if (i < fadeIn) g = i.toFloat() / fadeIn
            val fromEnd = buf.size - i
            if (fromEnd < fadeOut) {
                val gOut = fromEnd.toFloat() / fadeOut
                if (gOut < g) g = gOut
            }
            buf[i] *= g
        }
        return finish(buf)
    }

    /** Risadas ~2,2s: 6 rajadas "ha" com fundamental descendo. */
    private fun laugh(): ShortArray {
        val dur = 2.2
        val buf = buffer(dur)
        val rnd = Random(777L)
        val starts = doubleArrayOf(0.0, 0.33, 0.64, 0.93, 1.20, 1.46)
        for (b in starts.indices) {
            val startSec = starts[b]
            val burstDur = 0.24
            val start = (startSec * SAMPLE_RATE).toInt()
            val len = (burstDur * SAMPLE_RATE).toInt()
            val f0 = 330.0 - b * 18.0
            val f1 = 205.0 - b * 8.0
            var phase = 0.0
            var j = 0
            while (j < len && start + j < buf.size) {
                val frac = j.toDouble() / len
                val freq = f0 + (f1 - f0) * frac
                phase += 2.0 * PI * freq / SAMPLE_RATE
                val attack = minOf(1.0, j / (0.02 * SAMPLE_RATE))
                val env = attack * (1.0 - frac * 0.55)
                var v = sin(phase) + 0.5 * sin(2.0 * phase) + 0.22 * sin(3.0 * phase)
                v += (rnd.nextDouble() * 2.0 - 1.0) * 0.06
                buf[start + j] += (v * env * 0.55).toFloat()
                j++
            }
        }
        return finish(buf)
    }

    /** Campainha ~2s: 3 toques FM agudos. */
    private fun bell(): ShortArray {
        val buf = buffer(2.0)
        addFmBell(buf, 0.0, 2000.0, 2.7, 7.0, 5.5, 0.8)
        addFmBell(buf, 0.6, 2050.0, 2.7, 7.0, 5.5, 0.8)
        addFmBell(buf, 1.2, 1950.0, 2.7, 7.0, 5.0, 0.85)
        return finish(buf)
    }

    /** Ding ~1,5s: um toque FM suave e longo. */
    private fun ding(): ShortArray {
        val buf = buffer(1.5)
        addFmBell(buf, 0.0, 1046.5, 2.76, 4.0, 2.4, 0.8)
        // Harmonico suave por cima.
        var i = 0
        while (i < buf.size) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-3.2 * t)
            buf[i] += (sin(2.0 * PI * 2093.0 * t) * env * 0.18).toFloat()
            i++
        }
        return finish(buf)
    }

    /** "Ba dum tss" ~1,6s: dois tons graves + prato. */
    private fun sting(): ShortArray {
        val buf = buffer(1.6)
        val rnd = Random(99L)
        // Tom 1 em 0s (180 -> 70 Hz), tom 2 em 0,18s (150 -> 60 Hz).
        addTom(buf, 0.0, 180.0, 70.0, 0.13)
        addTom(buf, 0.18, 150.0, 60.0, 0.13)
        // Prato em 0,36s: ruido high-pass (diferenca) com decaimento longo.
        val start = (0.36 * SAMPLE_RATE).toInt()
        var prev = 0.0
        var i = start
        while (i < buf.size) {
            val t = (i - start).toDouble() / SAMPLE_RATE
            val env = exp(-3.0 * t)
            val noise = rnd.nextDouble() * 2.0 - 1.0
            val hp = noise - prev
            prev = noise
            buf[i] += (hp * env * 0.5).toFloat()
            i++
        }
        return finish(buf)
    }

    private fun addTom(buf: FloatArray, startSec: Double, f0: Double, f1: Double, durSec: Double) {
        val start = (startSec * SAMPLE_RATE).toInt()
        val len = (durSec * SAMPLE_RATE).toInt()
        var phase = 0.0
        var j = 0
        while (j < len && start + j < buf.size) {
            val frac = j.toDouble() / len
            val freq = f0 + (f1 - f0) * frac
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = 1.0 - frac * 0.4
            buf[start + j] += (sin(phase) * env * 0.9).toFloat()
            j++
        }
    }

    /** Buzina ~0,9s: duas fundamentais com harmonicos e leve saturacao. */
    private fun horn(): ShortArray {
        val dur = 0.9
        val buf = buffer(dur)
        val freqs = doubleArrayOf(392.0, 493.88)
        for (i in buf.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = minOf(1.0, t / 0.03)
            val release = minOf(1.0, (dur - t) / 0.15)
            val env = minOf(attack, release)
            var v = 0.0
            for (f in freqs) {
                v += sin(2.0 * PI * f * t) * 0.5
                v += sin(2.0 * PI * f * 2.0 * t) * 0.25
                v += sin(2.0 * PI * f * 3.0 * t) * 0.12
            }
            // Saturacao leve.
            v = Math.tanh(v * 1.2)
            buf[i] = (v * env * 0.7).toFloat()
        }
        return finish(buf)
    }

    /** Transicao/whoosh ~1,2s: ruido filtrado com varredura de frequencia. */
    private fun whoosh(): ShortArray {
        val dur = 1.2
        val buf = buffer(dur)
        val rnd = Random(4242L)
        var y = 0.0
        for (i in buf.indices) {
            val frac = i.toDouble() / buf.size
            // Frequencia sobe e desce (sino).
            val fc = 300.0 + 3200.0 * sin(PI * frac)
            val alpha = 1.0 - exp(-2.0 * PI * fc / SAMPLE_RATE)
            val x = rnd.nextDouble() * 2.0 - 1.0
            y += alpha * (x - y)
            val env = Math.pow(sin(PI * frac), 1.5)
            buf[i] = (y * env * 1.4).toFloat()
        }
        return finish(buf)
    }

    /** Vinheta ~2s: arpejo Do5-Mi5-Sol5-Do6 + acorde final. */
    private fun jingle(): ShortArray {
        val buf = buffer(2.0)
        val c5 = 523.25
        val e5 = 659.25
        val g5 = 783.99
        val c6 = 1046.5
        addPluck(buf, 0.0, c5, 5.0, 0.55, 0.6)
        addPluck(buf, 0.15, e5, 5.0, 0.55, 0.6)
        addPluck(buf, 0.30, g5, 5.0, 0.55, 0.6)
        addPluck(buf, 0.45, c6, 3.2, 0.6, 1.5)
        // Acorde final sustentado junto da ultima nota.
        addPluck(buf, 0.45, c5, 2.6, 0.30, 1.5)
        addPluck(buf, 0.45, e5, 2.6, 0.30, 1.5)
        addPluck(buf, 0.45, g5, 2.6, 0.30, 1.5)
        return finish(buf)
    }
}
