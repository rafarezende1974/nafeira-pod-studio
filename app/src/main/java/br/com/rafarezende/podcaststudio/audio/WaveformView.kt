package br.com.rafarezende.podcaststudio.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Onda ao vivo da gravação: barras verticais vermelhas com o histórico
 *  recente de amplitude. Quando parado, mostra uma linha baixa/plana. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val samples = ArrayDeque<Float>()
    private val maxSamples = 56

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9452B")
        style = Paint.Style.FILL
    }

    private val flatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A2A28")
        style = Paint.Style.FILL
    }

    /** Amplitude 0..32767 (mesma escala do MediaRecorder). */
    fun setAmplitude(amplitude: Int) {
        val normalized = amplitude.coerceIn(0, 32767) / 32767f
        samples.addLast(normalized)
        while (samples.size > maxSamples) samples.removeFirst()
        invalidate()
    }

    /** Volta à linha baixa/plana (quando parado). */
    fun reset() {
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (samples.isEmpty()) {
            // Linha baixa/plana no meio.
            val thickness = 3f * resources.displayMetrics.density
            canvas.drawRect(
                0f, h / 2f - thickness / 2f,
                w, h / 2f + thickness / 2f,
                flatPaint
            )
            return
        }
        val density = resources.displayMetrics.density
        val gap = 2f * density
        val barWidth = (w - gap * (maxSamples - 1)) / maxSamples
        val minHeight = 4f * density
        // Alinha à direita: a amostra mais recente fica na borda direita.
        val totalWidth = samples.size * barWidth + (samples.size - 1) * gap
        var x = w - totalWidth
        for (sample in samples) {
            val barHeight = (minHeight + sample * (h - minHeight)).coerceAtLeast(minHeight)
            val top = (h - barHeight) / 2f
            canvas.drawRect(x, top, x + barWidth, top + barHeight, barPaint)
            x += barWidth + gap
        }
    }
}
