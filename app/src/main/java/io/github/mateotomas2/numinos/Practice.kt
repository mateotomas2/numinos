package io.github.mateotomas2.numinos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

/** Mind is reading, Body is a practice video and Soul is meditating. */
enum class Pillar(val label: String) { MIND("Mind"), BODY("Body"), SOUL("Soul") }

class DayPractice(val mind: Boolean, val body: Boolean, val soul: Boolean) {
    val complete get() = mind && body && soul

    fun done(pillar: Pillar) = when (pillar) {
        Pillar.MIND -> mind
        Pillar.BODY -> body
        Pillar.SOUL -> soul
    }

    companion object {
        val NONE = DayPractice(mind = false, body = false, soul = false)
    }
}

/** The day starts at 4:00, so practice after midnight counts for the evening before. */
fun practiceDay(at: Long): LocalDate =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

private const val DAY_STARTS_AT_HOUR = 4L

/**
 * Draws the three pillars as arcs, Mind first from the top. A full ring sweeps 360 degrees and the
 * floating symbol's half ring sweeps 180 or -180, so it always faces into the screen.
 */
fun drawPillars(
    canvas: Canvas, paint: Paint, box: RectF, day: DayPractice,
    sweep: Float, stroke: Float, doneColor: Int, todoColor: Int,
) {
    val gap = if (sweep > 0) GAP_DEGREES else -GAP_DEGREES
    val part = sweep / Pillar.entries.size
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.strokeCap = Paint.Cap.ROUND
    paint.shader = null
    Pillar.entries.forEachIndexed { index, pillar ->
        paint.color = if (day.done(pillar)) doneColor else todoColor
        canvas.drawArc(box, START_DEGREES + part * index + gap / 2, part - gap, false, paint)
    }
}

private const val START_DEGREES = 270f
private const val GAP_DEGREES = 14f

/** The day as a small ring of three arcs, with a dot in the middle once all three are done. */
class PillarsView(context: Context, var day: DayPractice) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        contentDescription = Pillar.entries.joinToString { "${it.label} ${if (day.done(it)) "done" else "not yet"}" }
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.13f
        val inset = stroke / 2 + size * 0.04f
        box.set((width - size) / 2 + inset, (height - size) / 2 + inset, (width + size) / 2 - inset, (height + size) / 2 - inset)
        drawPillars(canvas, paint, box, day, 360f, stroke, ACCENT, LINE)
        if (day.complete) {
            paint.style = Paint.Style.FILL
            paint.color = ACCENT
            canvas.drawCircle(width / 2f, height / 2f, size * 0.14f, paint)
        }
    }
}
