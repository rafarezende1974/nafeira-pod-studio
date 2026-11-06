package br.com.rafarezende.podcaststudio.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** Medidor de nível segmentado (verde → amarelo → vermelho), no lugar do
 *  ProgressBar horizontal. Mesma escala 0..32767. */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var level = 0

    private val segmentCount = 28
    private val maxLevel = 32767

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val unlitColor = Color.parseColor("#262636")
    private val green = Color.parseColor("#35D07F")
    private val yellow = Color.parseColor("#E8B90F")
    private val red = Color.parseColor("#E9452B")

    fun setLevel(value: Int) {
        val newLevel = value.coerceIn(0, maxLevel)
        if (newLevel != level) {
            level = newLevel
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val gap = 2f * density
        val corner = 2f * density
        val segmentWidth = (width - gap * (segmentCount - 1)) / segmentCount.toFloat()
        val litCount = (level / maxLevel.toFloat() * segmentCount).roundToInt()
        var x = 0f
        for (i in 0 until segmentCount) {
            val fraction = if (segmentCount > 1) i / (segmentCount - 1).toFloat() else 0f
            paint.color = when {
                i >= litCount -> unlitColor
                fraction < 0.6f -> green
                fraction < 0.85f -> yellow
                else -> red
            }
            canvas.drawRoundRect(
                x, 0f, x + segmentWidth, height.toFloat(),
                corner, corner, paint
            )
            x += segmentWidth + gap
        }
    }
}
