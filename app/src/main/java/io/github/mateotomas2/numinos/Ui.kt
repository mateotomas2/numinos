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
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val PAPER = 0xFFF6F1E7.toInt()
const val CARD = 0xFFFFFDF8.toInt()
const val INK = 0xFF2A2620.toInt()
const val SOFT = 0xFF6F675A.toInt()
const val LINE = 0xFFE3DBCB.toInt()
const val ACCENT = 0xFF3F7D68.toInt()
const val NIGHT = 0xFF15130F.toInt()

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
    label(text, 13f, if (selected) Color.WHITE else INK).apply {
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
fun Context.dialogBuilder() = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert))

fun AlertDialog.showOverlay() {
    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    show()
}

fun Context.dialogField(hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE): Pair<View, EditText> {
    val field = EditText(ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)).apply {
        this.hint = hint
        inputType = type
    }
    val box = FrameLayout(this).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(field)
    }
    return box to field
}

enum class Symbol(val label: String) {
    YIN_YANG("Yin-yang"), ENSO("Enso"), OM("Om"), WHEEL("Dharma wheel"), CROSS("Cross");

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
            invalidate()
        }
    var discColor = INK
    var markColor = PAPER
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
