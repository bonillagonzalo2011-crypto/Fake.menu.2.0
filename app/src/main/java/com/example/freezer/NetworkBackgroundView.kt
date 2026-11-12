package com.example.freezer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Fondo negro con bolas blancas unidas por palitos, animado y con brillo. */
class NetworkBackgroundView(context: Context) : View(context) {

    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float, val ph: Float)

    private val pts = mutableListOf<P>()
    private val d = resources.displayMetrics.density

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(18f, 0f, 0f, Color.WHITE)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * d
        setShadowLayer(8f, 0f, 0f, Color.WHITE)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // necesario para el brillo
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pts.clear()
        val n = ((w * h) / (14000f * d * d)).toInt().coerceIn(20, 70)
        repeat(n) {
            pts.add(
                P(
                    Random.nextFloat() * w, Random.nextFloat() * h,
                    (Random.nextFloat() - 0.5f) * 1.4f * d, (Random.nextFloat() - 0.5f) * 1.4f * d,
                    (2f + Random.nextFloat() * 2f) * d, Random.nextFloat() * 6.28f
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis() / 400f
        val maxD = 130f * d

        for (p in pts) {
            p.x += p.vx
            p.y += p.vy
            if (p.x < 0 || p.x > width) p.vx = -p.vx
            if (p.y < 0 || p.y > height) p.vy = -p.vy
        }

        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val a = pts[i]
            val b = pts[j]
            val dist = hypot(a.x - b.x, a.y - b.y)
            if (dist < maxD) {
                line.color = Color.argb(((1 - dist / maxD) * 0.7f * 255).toInt(), 255, 255, 255)
                canvas.drawLine(a.x, a.y, b.x, b.y, line)
            }
        }

        for (p in pts) {
            canvas.drawCircle(p.x, p.y, p.r * (1f + 0.25f * sin(t + p.ph)), dot)
        }

        postInvalidateOnAnimation()
    }
}
