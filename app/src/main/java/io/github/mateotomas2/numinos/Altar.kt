package io.github.mateotomas2.numinos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class AltarKind(val label: String) {
    SYMBOL("Symbol"), CANDLE("Candle"), INCENSE("Incense"), FLOWER("Flower"), LEAF("Leaf"), STONE("Stone"),
    BOWL("Bowl"), GONG("Gong"), TEXT("Text"), TIMER("Timer"), IMAGE("Image");

    val sound: Int?
        get() = when (this) {
            BOWL -> R.raw.bell
            GONG -> R.raw.gong
            else -> null
        }
}

/** One thing on the altar. [x] and [y] are its center as a fraction of the screen, and [content] is its text or image file. */
class AltarItem(
    val kind: AltarKind,
    var x: Float,
    var y: Float,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var content: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("x", x.toDouble()).put("y", y.toDouble())
        .put("scale", scale.toDouble()).put("rotation", rotation.toDouble()).put("content", content)

    companion object {
        fun fromJson(json: JSONObject) = AltarItem(
            AltarKind.valueOf(json.getString("kind")),
            json.getDouble("x").toFloat(), json.getDouble("y").toFloat(),
            json.getDouble("scale").toFloat(), json.getDouble("rotation").toFloat(),
            json.optString("content"),
        )
    }
}

enum class AltarBackground(val label: String, val top: Int, val bottom: Int) {
    NIGHT("Night", 0xFF0F0E0D.toInt(), 0xFF1E1B18.toInt()),
    DAWN("Dawn", 0xFF241B3A.toInt(), 0xFF9A5536.toInt()),
    STONE("Stone", 0xFF2C2E31.toInt(), 0xFF4A4C4F.toInt()),
    WOOD("Wood", 0xFF2B1A10.toInt(), 0xFF4E2F1C.toInt());

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.name == id } ?: NIGHT
    }
}

/** The ready-made altar a theme opens with. It has no timer, so a sit shows no numbers until you tap. */
fun starterAltar(symbol: Symbol): List<AltarItem> {
    val (left, right) = when (symbol) {
        Symbol.YIN_YANG -> AltarKind.STONE to AltarKind.LEAF
        Symbol.ENSO -> AltarKind.STONE to AltarKind.INCENSE
        Symbol.OM -> AltarKind.FLOWER to AltarKind.CANDLE
        Symbol.WHEEL -> AltarKind.FLOWER to AltarKind.INCENSE
        Symbol.CROSS -> AltarKind.CANDLE to AltarKind.FLOWER
    }
    return listOf(
        AltarItem(AltarKind.SYMBOL, 0.5f, 0.3f, 1.5f),
        AltarItem(left, 0.24f, 0.6f),
        AltarItem(right, 0.76f, 0.6f),
        AltarItem(AltarKind.BOWL, 0.5f, 0.68f),
    )
}

/** Plays a sound over any other that's still ringing, so struck bowls layer like real ones. */
fun Context.playRing(sound: Int) {
    val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    val audio = getSystemService(AudioManager::class.java)
    MediaPlayer.create(this, sound, attributes, audio.generateAudioSessionId())?.apply {
        setOnCompletionListener { it.release() }
        start()
    }
}

fun altarImageFile(context: Context, name: String) = File(File(context.filesDir, "altar"), name)

/** Copies a PNG into the app, shrunk if it's huge, and returns its file name, or null if it can't be read. */
fun importAltarImage(context: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIZE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val name = "${UUID.randomUUID()}.png"
    val file = altarImageFile(context, name)
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    name
}.getOrNull()

private const val MAX_IMAGE_SIZE = 1024
private const val PALE = 0xFFF6F1E7.toInt()

/** The altar on screen: the background and its items, each placed by its fractions of this view's size. */
class AltarView(context: Context, private val symbol: Symbol) : FrameLayout(context) {
    private val shown = linkedMapOf<AltarItem, View>()
    private val base = (min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.3f).toInt()

    /** What every timer on the altar shows. */
    var timerText = ""
        set(value) {
            field = value
            shown.forEach { (item, view) -> if (item.kind == AltarKind.TIMER) (view as android.widget.TextView).text = value }
        }

    fun setBackdrop(backdrop: AltarBackground) {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(backdrop.top, backdrop.bottom))
    }

    fun add(item: AltarItem): View? {
        val view = viewFor(item) ?: return null
        shown[item] = view
        val size = if (item.kind == AltarKind.TEXT || item.kind == AltarKind.TIMER) -2 else base
        addView(view, LayoutParams(size, size, Gravity.TOP or Gravity.START))
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(item) }
        return view
    }

    fun remove(item: AltarItem) {
        shown.remove(item)?.let { removeView(it) }
    }

    fun viewOf(item: AltarItem) = shown[item]

    fun items() = shown.keys.toList()

    fun place(item: AltarItem, breath: Float = 1f) {
        val view = shown[item] ?: return
        view.translationX = item.x * width - view.width / 2f
        view.translationY = item.y * height - view.height / 2f
        view.rotation = item.rotation
        view.scaleX = item.scale * breath
        view.scaleY = item.scale * breath
    }

    fun raise(item: AltarItem) {
        val view = shown.remove(item) ?: return
        shown[item] = view
        view.bringToFront()
    }

    /** The symbols breathe. */
    fun breathe(amount: Float) {
        shown.keys.filter { it.kind == AltarKind.SYMBOL }.forEach { place(it, amount) }
    }

    /** The topmost item under a point, allowing for its rotation and size. */
    fun itemAt(x: Float, y: Float): AltarItem? = shown.entries.toList().asReversed().firstOrNull { (item, view) ->
        val radians = Math.toRadians(-item.rotation.toDouble())
        val dx = x - (item.x * width)
        val dy = y - (item.y * height)
        val localX = (dx * cos(radians) - dy * sin(radians)) / item.scale
        val localY = (dx * sin(radians) + dy * cos(radians)) / item.scale
        kotlin.math.abs(localX) <= view.width / 2f && kotlin.math.abs(localY) <= view.height / 2f
    }?.key

    /** Rings a bowl or gong and shakes it a little. */
    fun strike(item: AltarItem) {
        val sound = item.kind.sound ?: return
        context.playRing(sound)
        val view = shown[item] ?: return
        view.animate().rotation(item.rotation + 4f).setDuration(70).withEndAction {
            view.animate().rotation(item.rotation).setDuration(220).start()
        }.start()
    }

    private fun viewFor(item: AltarItem): View? = when (item.kind) {
        AltarKind.SYMBOL -> SymbolView(context, symbol).apply {
            discColor = symbol.palette.night.toInt()
            markColor = 0xFFD9D2C3.toInt()
        }
        AltarKind.TEXT -> context.label(item.content, 22f, PALE, serif = true).apply {
            gravity = Gravity.CENTER
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            setShadowLayer(8f, 0f, 1f, Color.BLACK)
        }
        AltarKind.TIMER -> context.label(timerText, 44f, PALE, serif = true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 1f, Color.BLACK)
        }
        AltarKind.IMAGE -> BitmapFactory.decodeFile(altarImageFile(context, item.content).path)?.let { bitmap ->
            ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }
        else -> ShapeView(context, item.kind)
    }
}

/** The built-in elements, drawn on a 100 by 100 grid. The candle and incense keep moving. */
private class ShapeView(context: Context, private val kind: AltarKind) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val glow = RadialGradient(50f, 30f, 40f, 0xAAFFB84D.toInt(), 0x00FFB84D, Shader.TileMode.CLAMP)

    init {
        contentDescription = kind.label
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
    }

    private fun line(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.color = color
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val unit = min(width, height) / 100f
        canvas.save()
        canvas.scale(unit, unit)
        val time = SystemClock.uptimeMillis().toFloat()
        when (kind) {
            AltarKind.CANDLE -> candle(canvas, time)
            AltarKind.INCENSE -> incense(canvas, time)
            AltarKind.FLOWER -> flower(canvas)
            AltarKind.LEAF -> leaf(canvas)
            AltarKind.STONE -> stones(canvas)
            AltarKind.BOWL -> bowl(canvas)
            AltarKind.GONG -> gong(canvas)
            else -> Unit
        }
        canvas.restore()
        if (kind == AltarKind.CANDLE || kind == AltarKind.INCENSE) postInvalidateDelayed(50)
    }

    private fun candle(canvas: Canvas, time: Float) {
        val sway = sin(time / 170f) * 1.6f + sin(time / 67f) * 0.7f
        val lick = sin(time / 110f) * 2f
        paint.style = Paint.Style.FILL
        paint.shader = glow
        paint.alpha = 200 + (sin(time / 130f) * 40f).toInt()
        canvas.drawCircle(50f, 30f, 40f, paint)
        paint.alpha = 255
        fill(0xFFF3E6C8.toInt())
        canvas.drawRoundRect(37f, 50f, 63f, 98f, 3f, 3f, paint)
        line(0xFF3A2C20.toInt(), 1.4f)
        canvas.drawLine(50f, 50f, 50f, 43f, paint)
        flame(canvas, 0xFFFFA62E.toInt(), 50f + sway, 12f + lick, 9f)
        flame(canvas, 0xFFFFF0B0.toInt(), 50f + sway * 0.6f, 24f + lick, 5f)
    }

    private fun flame(canvas: Canvas, color: Int, tipX: Float, tipY: Float, half: Float) {
        fill(color)
        path.reset()
        path.moveTo(tipX, tipY)
        path.quadTo(50f + half * 1.3f, 32f, 50f, 45f)
        path.quadTo(50f - half * 1.3f, 32f, tipX, tipY)
        canvas.drawPath(path, paint)
    }

    private fun incense(canvas: Canvas, time: Float) {
        repeat(2) { strand ->
            line(0x66F6F1E7, 1.5f)
            path.reset()
            path.moveTo(50f, 42f)
            var y = 42f
            while (y > 2f) {
                y -= 3f
                val rise = 42f - y
                path.lineTo(50f + sin(rise * 0.14f + time / (620f + strand * 240f) + strand * 2f) * rise * 0.28f, y)
            }
            canvas.drawPath(path, paint)
        }
        line(0xFF8A5A3A.toInt(), 2f)
        canvas.drawLine(50f, 90f, 50f, 44f, paint)
        fill(0xFFFF7A3A.toInt())
        paint.alpha = 190 + (sin(time / 300f) * 60f).toInt()
        canvas.drawCircle(50f, 43f, 2.2f, paint)
        paint.alpha = 255
        fill(0xFF6B4A32.toInt())
        canvas.drawOval(24f, 86f, 76f, 98f, paint)
    }

    private fun flower(canvas: Canvas) {
        fill(0xFFE9A7B8.toInt())
        repeat(8) {
            canvas.save()
            canvas.rotate(it * 45f, 50f, 50f)
            canvas.drawOval(41f, 8f, 59f, 50f, paint)
            canvas.restore()
        }
        fill(0xFFF2C14E.toInt())
        canvas.drawCircle(50f, 50f, 10f, paint)
    }

    private fun leaf(canvas: Canvas) {
        fill(0xFF5F8F5B.toInt())
        path.reset()
        path.moveTo(50f, 4f)
        path.cubicTo(88f, 30f, 80f, 76f, 50f, 96f)
        path.cubicTo(20f, 76f, 12f, 30f, 50f, 4f)
        canvas.drawPath(path, paint)
        line(0xFF3E6340.toInt(), 1.8f)
        canvas.drawLine(50f, 12f, 50f, 92f, paint)
    }

    private fun stones(canvas: Canvas) {
        fill(0xFF7C7F82.toInt())
        canvas.drawOval(12f, 62f, 88f, 96f, paint)
        fill(0xFF999C9E.toInt())
        canvas.drawOval(25f, 36f, 75f, 66f, paint)
        fill(0xFFB5B7B8.toInt())
        canvas.drawOval(36f, 16f, 64f, 40f, paint)
    }

    private fun bowl(canvas: Canvas) {
        fill(0xFFA9782F.toInt())
        path.reset()
        path.moveTo(10f, 42f)
        path.cubicTo(12f, 80f, 32f, 88f, 50f, 88f)
        path.cubicTo(68f, 88f, 88f, 80f, 90f, 42f)
        path.close()
        canvas.drawPath(path, paint)
        fill(0xFFC99A4B.toInt())
        canvas.drawOval(10f, 34f, 90f, 50f, paint)
        fill(0xFF5E411A.toInt())
        canvas.drawOval(17f, 37f, 83f, 48f, paint)
    }

    private fun gong(canvas: Canvas) {
        line(0xFF5A3A22.toInt(), 4f)
        canvas.drawLine(10f, 8f, 90f, 8f, paint)
        canvas.drawLine(14f, 8f, 14f, 98f, paint)
        canvas.drawLine(86f, 8f, 86f, 98f, paint)
        line(0xFF3A2C20.toInt(), 1.2f)
        canvas.drawLine(40f, 8f, 44f, 24f, paint)
        canvas.drawLine(60f, 8f, 56f, 24f, paint)
        fill(0xFFB8863B.toInt())
        canvas.drawCircle(50f, 56f, 32f, paint)
        line(0xFF8A6226.toInt(), 1.5f)
        canvas.drawCircle(50f, 56f, 26f, paint)
        canvas.drawCircle(50f, 56f, 18f, paint)
        fill(0xFFD3A44F.toInt())
        canvas.drawCircle(50f, 56f, 9f, paint)
    }
}
