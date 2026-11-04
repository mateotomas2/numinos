package io.github.mateotomas2.numinos

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class Palette(
    val paper: Long, val card: Long, val ink: Long, val soft: Long,
    val line: Long, val accent: Long, val night: Long,
)

/** The theme in use. [Store] sets it as soon as the app starts. */
object Look {
    var symbol = Symbol.YIN_YANG
    var dark = false
}

val PAPER: Int get() = Look.symbol.palette.paper.toInt()
val CARD: Int get() = Look.symbol.palette.card.toInt()
val INK: Int get() = Look.symbol.palette.ink.toInt()
val SOFT: Int get() = Look.symbol.palette.soft.toInt()
val LINE: Int get() = Look.symbol.palette.line.toInt()
val ACCENT: Int get() = Look.symbol.palette.accent.toInt()
val NIGHT: Int get() = Look.symbol.palette.night.toInt()

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

fun View.tappable(onClick: () -> Unit) {
    isClickable = true
    foreground = RippleDrawable(ColorStateList.valueOf(0x22000000), null, rounded(Color.WHITE, 12f * resources.displayMetrics.density))
    setOnClickListener { onClick() }
}

fun Context.label(text: String, sizeSp: Float = 14f, color: Int = INK, serif: Boolean = false, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit) =
    label(text, 13f, if (selected) PAPER else INK).apply {
        gravity = Gravity.CENTER
        minHeight = dp(34)
        setPadding(dp(13), dp(6), dp(13), dp(6))
        background = rounded(if (selected) INK else CARD, dp(17).toFloat(), dp(1), if (selected) INK else LINE)
        tappable(onClick)
    }

fun Context.iconButton(icon: Int, description: String, tint: Int = INK, onClick: () -> Unit) =
    ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(Color.TRANSPARENT, dp(22).toFloat())
        tappable(onClick)
    }

/** Dialog that can appear above other apps, from the service as well as from an activity. */
fun Context.dialogBuilder() = AlertDialog.Builder(ContextThemeWrapper(this, dialogTheme()))

private fun dialogTheme() =
    if (Look.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

/** Runs [action] for the back gesture on Android 13 and newer, in a window that isn't an activity. */
fun View.onBackGesture(action: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 33) {
        post {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { action() },
            )
        }
    }
}

fun AlertDialog.showOverlay() {
    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    show()
}

fun Context.dialogField(hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE): Pair<View, EditText> {
    val field = EditText(ContextThemeWrapper(this, dialogTheme())).apply {
        this.hint = hint
        inputType = type
    }
    val box = FrameLayout(this).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(field)
    }
    return box to field
}

/** The minutes the timer offers. 0 means no timer. */
val DURATIONS = listOf(0) + (1..10) + (15..60 step 5) + (75..120 step 15)

fun durationLabel(minutes: Int) = if (minutes == 0) "∞" else "$minutes min"

/** A native wheel over [values]. Its value is an index into them. */
fun Context.durationWheel(values: List<Int>, minutes: Int) = NumberPicker(ContextThemeWrapper(this, dialogTheme())).apply {
    minValue = 0
    maxValue = values.lastIndex
    displayedValues = values.map { if (it == 0) "∞  no timer" else durationLabel(it) }.toTypedArray()
    value = values.indexOf(minutes).coerceAtLeast(0)
    wrapSelectorWheel = false
    descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS // Keeps the keyboard away.
}

/** A symbol and the theme and book that go with it. */
enum class Symbol(val label: String, val title: String, val bookId: String?, private val light: Palette, private val dark: Palette) {
    YIN_YANG(
        "Yin-yang", "Tao", "tao",
        Palette(0xFFF6F1E7, 0xFFFFFDF8, 0xFF2A2620, 0xFF6F675A, 0xFFE3DBCB, 0xFF3F7D68, 0xFF15130F),
        Palette(0xFF181A18, 0xFF222522, 0xFFEDEBE3, 0xFFA5A79E, 0xFF363A35, 0xFF6FB79E, 0xFF0F110F),
    ),
    ENSO(
        "Enso", "Zen", null,
        Palette(0xFFF4F2EC, 0xFFFBFAF6, 0xFF1C1B19, 0xFF6B675F, 0xFFDEDAD0, 0xFFB8432B, 0xFF0F0E0D),
        Palette(0xFF1C1B19, 0xFF272522, 0xFFEFEBE2, 0xFFA39E93, 0xFF3A3733, 0xFFD9694F, 0xFF0F0E0D),
    ),
    OM(
        "Om", "Vedanta", "upanishads",
        Palette(0xFFFBF1E0, 0xFFFFF9EE, 0xFF3B2410, 0xFF7D6548, 0xFFEAD9BD, 0xFFB5620F, 0xFF1C1208),
        Palette(0xFF221608, 0xFF2E2010, 0xFFF6E7CF, 0xFFC2A985, 0xFF463420, 0xFFE8943A, 0xFF140C04),
    ),
    WHEEL(
        "Dharma wheel", "Dharma", "dhammapada",
        Palette(0xFFF8EFE2, 0xFFFFF9EF, 0xFF4A1B20, 0xFF7F5B52, 0xFFE8D6C4, 0xFF8F2F34, 0xFF140809),
        Palette(0xFF2A1316, 0xFF38191D, 0xFFF5E6C8, 0xFFC4AC8D, 0xFF4F2A2E, 0xFFE0A93B, 0xFF140809),
    ),
    CROSS(
        "Cross", "Christian", "bible",
        Palette(0xFFF3F5F9, 0xFFFFFFFF, 0xFF1E2A44, 0xFF5F6B82, 0xFFD8DEE9, 0xFF3A5FA8, 0xFF0E1421),
        Palette(0xFF141B2B, 0xFF1E2739, 0xFFE8EDF7, 0xFFA5B0C6, 0xFF333E55, 0xFF86A8E8, 0xFF0E1421),
    );

    val palette: Palette get() = if (Look.dark) dark else light

    companion object {
        fun of(id: String) = entries.firstOrNull { it.name == id } ?: YIN_YANG
    }
}

/** Draws a symbol in pale ink on a dark disc. */
class SymbolView(context: Context, symbol: Symbol) : View(context) {
    var symbol: Symbol = symbol
        set(value) {
            field = value
            contentDescription = value.label
            discColor = value.palette.ink.toInt()
            markColor = value.palette.paper.toInt()
            invalidate()
        }
    var discColor = symbol.palette.ink.toInt()
    var markColor = symbol.palette.paper.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    init {
        contentDescription = symbol.label
    }

    override fun onDraw(canvas: Canvas) {
        val c = min(width, height) / 2f
        paint.style = Paint.Style.FILL
        paint.color = discColor
        canvas.drawCircle(c, c, c, paint)
        val r = c * 0.74f
        paint.color = markColor
        when (symbol) {
            Symbol.YIN_YANG -> yinYang(canvas, c, r)
            Symbol.ENSO -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.2f
                paint.strokeCap = Paint.Cap.ROUND
                box.set(c - r * 0.92f, c - r * 0.92f, c + r * 0.92f, c + r * 0.92f)
                canvas.drawArc(box, -70f, 325f, false, paint)
            }
            Symbol.OM -> {
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = r * 1.75f
                val m = paint.fontMetrics
                canvas.drawText("ॐ", c, c - (m.ascent + m.descent) / 2, paint)
            }
            Symbol.WHEEL -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.13f
                canvas.drawCircle(c, c, r * 0.9f, paint)
                canvas.drawCircle(c, c, r * 0.2f, paint)
                for (i in 0 until 8) {
                    val a = Math.PI * i / 4
                    canvas.drawLine(
                        c + r * 0.2f * cos(a).toFloat(), c + r * 0.2f * sin(a).toFloat(),
                        c + r * 0.9f * cos(a).toFloat(), c + r * 0.9f * sin(a).toFloat(), paint,
                    )
                }
            }
            Symbol.CROSS -> {
                val w = r * 0.13f
                canvas.drawRoundRect(c - w, c - r, c + w, c + r, w / 2, w / 2, paint)
                canvas.drawRoundRect(c - r * 0.62f, c - r * 0.45f - w, c + r * 0.62f, c - r * 0.45f + w, w / 2, w / 2, paint)
            }
        }
    }

    private fun yinYang(canvas: Canvas, c: Float, r: Float) {
        canvas.drawCircle(c, c, r, paint)
        path.reset()
        box.set(c - r, c - r, c + r, c + r)
        path.arcTo(box, -90f, 180f)
        box.set(c - r / 2, c, c + r / 2, c + r)
        path.arcTo(box, 90f, 180f)
        box.set(c - r / 2, c - r, c + r / 2, c)
        path.arcTo(box, 90f, -180f)
        path.close()
        paint.color = discColor
        canvas.drawPath(path, paint)
        canvas.drawCircle(c, c - r / 2, r * 0.14f, paint)
        paint.color = markColor
        canvas.drawCircle(c, c + r / 2, r * 0.14f, paint)
    }
}
