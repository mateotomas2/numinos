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

/** Mind is reading, Body is practice videos and Soul is meditating, each with minutes to reach a day. */
enum class Pillar(val label: String) { MIND("Mind"), BODY("Body"), SOUL("Soul") }

/** A day's minutes for each pillar, out of the minutes that complete it. */
class DayPractice(private val minutes: Map<Pillar, Int>, private val goals: Map<Pillar, Int>) {
    fun minutes(pillar: Pillar) = minutes[pillar] ?: 0

    fun goal(pillar: Pillar) = goals[pillar] ?: 1

    fun progress(pillar: Pillar) = (minutes(pillar).toFloat() / goal(pillar)).coerceIn(0f, 1f)

    fun done(pillar: Pillar) = minutes(pillar) >= goal(pillar)

    val complete get() = Pillar.entries.all(::done)

    companion object {
        val NONE = DayPractice(emptyMap(), emptyMap())
    }
}

/** The day starts at 4:00, so practice after midnight counts for the evening before. */
fun practiceDay(at: Long): LocalDate =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

private const val DAY_STARTS_AT_HOUR = 4L

/**
 * Draws the three pillars as arcs that fill with the day's minutes, Mind first from the top. A full ring sweeps 360 degrees and the
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
        val start = START_DEGREES + part * index + gap / 2
        paint.color = todoColor
        canvas.drawArc(box, start, part - gap, false, paint)
        val progress = day.progress(pillar)
        if (progress > 0f) {
            paint.color = doneColor
            canvas.drawArc(box, start, (part - gap) * progress, false, paint)
        }
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
