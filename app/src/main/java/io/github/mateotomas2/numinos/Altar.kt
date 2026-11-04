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

enum class AltarKind(val label: String, val art: Int? = null) {
    SYMBOL("Symbol"), CANDLE("Candle", R.drawable.altar_candle), INCENSE("Incense", R.drawable.altar_incense),
    FLOWER("Lotus", R.drawable.altar_flower), LEAF("Leaf", R.drawable.altar_leaf), STONE("Stones", R.drawable.altar_stone),
    BOWL("Singing bowl", R.drawable.altar_bowl), GONG("Gong", R.drawable.altar_gong), TEXT("Text"), TIMER("Timer"), IMAGE("Your image");

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

enum class AltarBackground(val label: String, val image: Int) {
    NIGHT("Night", R.drawable.altar_bg_night),
    DAWN("Dawn", R.drawable.altar_bg_dawn),
    STONE("Stone", R.drawable.altar_bg_stone),
    WOOD("Wood", R.drawable.altar_bg_wood);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.name == id } ?: NIGHT
    }
}

val Symbol.medallion: Int
    get() = when (this) {
        Symbol.YIN_YANG -> R.drawable.altar_symbol_yin_yang
        Symbol.ENSO -> R.drawable.altar_symbol_enso
        Symbol.OM -> R.drawable.altar_symbol_om
        Symbol.WHEEL -> R.drawable.altar_symbol_wheel
        Symbol.CROSS -> R.drawable.altar_symbol_cross
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
        AltarItem(AltarKind.SYMBOL, 0.5f, 0.27f, 1.7f),
        AltarItem(left, 0.22f, 0.6f, 1.15f),
        AltarItem(right, 0.78f, 0.6f, 1.15f),
        AltarItem(AltarKind.BOWL, 0.5f, 0.7f, 1.35f),
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
    init {
        clipChildren = false // The candle's glow reaches past its own square.
    }

    private val base = (min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.3f).toInt()

    /** What every timer on the altar shows. */
    var timerText = ""
        set(value) {
            field = value
            shown.forEach { (item, view) -> if (item.kind == AltarKind.TIMER) (view as android.widget.TextView).text = value }
        }

    fun setBackdrop(backdrop: AltarBackground) {
        setBackgroundResource(backdrop.image)
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

    /** The size an element has on the altar before it's resized. */
    val elementSize get() = base

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
        AltarKind.SYMBOL -> ImageView(context).apply {
            setImageResource(symbol.medallion)
            contentDescription = symbol.label
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
        else -> ElementView(context, item.kind)
    }
}

/** A built-in element: its painted image, with a living flame on the candle and smoke on the incense. */
class ElementView(context: Context, private val kind: AltarKind, private val alive: Boolean = true) : View(context) {
    private val image = kind.art?.let { context.getDrawable(it) }?.apply { setBounds(0, 0, ART, ART) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val glow = RadialGradient(256f, 112f, 190f, 0x88FFB454.toInt(), 0x00FFB454, Shader.TileMode.CLAMP)
    private val outerFlame = RadialGradient(256f, 134f, 62f, 0xFFFFD27A.toInt(), 0xFFF0781E.toInt(), Shader.TileMode.CLAMP)

    init {
        contentDescription = kind.label
    }

    override fun onDraw(canvas: Canvas) {
        val unit = min(width, height).toFloat() / ART
        canvas.save()
        canvas.scale(unit, unit)
        image?.draw(canvas)
        val time = SystemClock.uptimeMillis().toFloat()
        val moving = alive && (kind == AltarKind.CANDLE || kind == AltarKind.INCENSE)
        if (kind == AltarKind.CANDLE) flame(canvas, if (alive) time else 0f)
        if (kind == AltarKind.INCENSE) smoke(canvas, if (alive) time else 0f)
        canvas.restore()
        if (moving) postInvalidateDelayed(40)
    }

    private fun flame(canvas: Canvas, time: Float) {
        val sway = sin(time / 170f) * 7f + sin(time / 67f) * 3f
        val lick = sin(time / 110f) * 9f
        paint.style = Paint.Style.FILL
        if (alive) {
            paint.shader = glow
            paint.alpha = 215 + (sin(time / 130f) * 40f).toInt()
            canvas.drawCircle(256f, 112f, 190f, paint)
            paint.alpha = 255
        }
        paint.shader = outerFlame
        teardrop(canvas, 256f + sway, 58f + lick, 30f)
        paint.shader = null
        paint.color = 0xFFFFF4C4.toInt()
        teardrop(canvas, 256f + sway * 0.6f, 104f + lick * 0.6f, 15f)
        paint.color = 0x663C6EE0
        canvas.drawCircle(256f, 158f, 8f, paint)
    }

    private fun teardrop(canvas: Canvas, tipX: Float, tipY: Float, half: Float) {
        path.reset()
        path.moveTo(tipX, tipY)
        path.cubicTo(256f + half * 0.5f, 104f, 256f + half * 1.5f, 132f, 256f, 168f)
        path.cubicTo(256f - half * 1.5f, 132f, 256f - half * 0.5f, 104f, tipX, tipY)
        canvas.drawPath(path, paint)
    }

    private fun smoke(canvas: Canvas, time: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        repeat(3) { strand ->
            paint.color = 0xFFEDE8DE.toInt()
            paint.alpha = 34 - strand * 6
            paint.strokeWidth = 7f + strand * 6f
            path.reset()
            path.moveTo(254f, 150f)
            var y = 150f
            while (y > 6f) {
                y -= 10f
                val rise = 150f - y
                path.lineTo(254f + sin(rise * 0.03f + time / (720f + strand * 260f) + strand * 1.7f) * rise * 0.26f, y)
            }
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF7A3A.toInt()
        paint.alpha = 190 + (sin(time / 300f) * 60f).toInt()
        canvas.drawCircle(254f, 150f, 7f, paint)
        paint.alpha = 255
    }

    private companion object {
        const val ART = 512
    }
}
