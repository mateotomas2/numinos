package io.github.mateotomas2.numinos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.random.Random

/** A book standing on the shelf, with its title running up the spine. */
@SuppressLint("ViewConstructor")
class SpineView(context: Context, private val title: String, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        contentDescription = title
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = context.dp(5).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = color
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, corner, corner, paint)
        // Light and shadow make the spine look rounded.
        paint.color = 0x24FFFFFF
        canvas.drawRect(w * 0.14f, corner, w * 0.3f, h - corner, paint)
        paint.color = 0x22000000
        canvas.drawRect(w * 0.82f, corner, w, h - corner, paint)
        paint.color = 0x99FFE9B0.toInt()
        for (y in listOf(0.07f, 0.1f, 0.9f, 0.93f)) canvas.drawRect(0f, h * y, w, h * y + context.dp(2), paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.SERIF
        paint.textSize = context.dp(16).toFloat()
        paint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.translate(w / 2, h / 2)
        canvas.rotate(-90f)
        val metrics = paint.fontMetrics
        val fitted = TextUtils.ellipsize(title, TextPaint(paint), h * 0.74f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(fitted, 0f, -(metrics.ascent + metrics.descent) / 2, paint)
        canvas.restore()
    }
}

/** A worn notebook holding the sentences you wrote. */
class NotebookView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        contentDescription = MINE_TITLE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = context.dp(6).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = 0xFF8A6B45.toInt()
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, corner, corner, paint)
        paint.color = 0xFFF3E7CE.toInt()
        box.set(w * 0.22f, h * 0.24f, w * 0.9f, h * 0.56f)
        canvas.drawRoundRect(box, corner / 2, corner / 2, paint)
        paint.color = 0xFF8A6B45.toInt()
        paint.strokeWidth = context.dp(1).toFloat()
        for (y in listOf(0.34f, 0.42f, 0.5f)) canvas.drawLine(w * 0.3f, h * y, w * 0.82f, h * y, paint)
        // The stitched edge.
        paint.color = 0xFFF3E7CE.toInt()
        val stitches = 7
        for (i in 0 until stitches) {
            val y = h * (i + 0.7f) / (stitches + 0.4f)
            canvas.drawLine(w * 0.1f, y, w * 0.1f, y + h * 0.05f, paint)
        }
    }
}

/** A glass jar that fills with paper slips as you like passages. */
class JarView(context: Context, private val slips: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        contentDescription = "Liked passages"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = context.dp(12).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = SOFT
        box.set(w * 0.2f, 0f, w * 0.8f, h * 0.12f)
        canvas.drawRoundRect(box, corner / 3, corner / 3, paint)
        paint.color = (LINE and 0x00FFFFFF) or 0x66000000
        box.set(0f, h * 0.14f, w, h)
        canvas.drawRoundRect(box, corner, corner, paint)
        val random = Random(7)
        val colors = intArrayOf(0xFFF3E7CE.toInt(), 0xFFE8D5B5.toInt(), 0xFFFFFAF0.toInt(), ACCENT)
        val slipW = w * 0.3f
        val slipH = h * 0.1f
        for (i in 0 until slips.coerceAtMost(MAX_SLIPS)) {
            val row = i / 3
            val x = w * (0.26f + (i % 3) * 0.24f) + random.nextFloat() * w * 0.06f
            val y = h * (0.9f - row * 0.13f) - random.nextFloat() * h * 0.03f
            canvas.save()
            canvas.rotate(random.nextFloat() * 70f - 35f, x, y)
            paint.color = colors[i % colors.size]
            box.set(x - slipW / 2, y - slipH / 2, x + slipW / 2, y + slipH / 2)
            canvas.drawRoundRect(box, corner / 6, corner / 6, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2).toFloat()
        paint.color = SOFT
        box.set(paint.strokeWidth, h * 0.14f, w - paint.strokeWidth, h - paint.strokeWidth)
        canvas.drawRoundRect(box, corner, corner, paint)
    }

    private companion object {
        const val MAX_SLIPS = 15
    }
}
