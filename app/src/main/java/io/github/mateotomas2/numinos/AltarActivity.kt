package io.github.mateotomas2.numinos

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/** Arranges the altar: drag from the tray to add, drag to move, two fingers to rotate and resize, drag to the trash to remove. */
class AltarActivity : Activity() {
    private lateinit var store: Store
    private lateinit var altar: AltarView
    private lateinit var trash: ImageView
    private lateinit var tray: LinearLayout
    private lateinit var root: FrameLayout

    private var active: AltarItem? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f
    private var travelled = 0f
    private var twoFingers = false
    private var dropX = 0.5f
    private var dropY = 0.45f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        store.applyLook()
        altar = AltarView(this, Look.symbol)
        altar.setBackdrop(store.altarBackdrop)
        altar.timerText = "${store.minutes}:00"
        store.altar().forEach { altar.add(it) }
        altar.setOnTouchListener { _, event -> onAltarTouch(event) }

        trash = iconButton(R.drawable.ic_delete, "Remove", PALE_INK) {}.apply {
            background = rounded(0x99000000.toInt(), dp(28).toFloat())
            isClickable = false
            visibility = View.INVISIBLE
        }
        tray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TRAY)
            addView(shelf(ADDABLE.map { kind -> widget(kind) }))
            addView(shelf(AltarBackground.entries.map { backdrop -> backdropTile(backdrop) }))
        }
        val done = chip("Done", true) { finish() }
        root = FrameLayout(this).apply {
            addView(altar, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(trash, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(12) })
                addView(tray, LinearLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(done, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            setOnApplyWindowInsetsListener { _, insets ->
                val edges = screenEdges(insets)
                (done.layoutParams as FrameLayout.LayoutParams).apply {
                    topMargin = edges.top + dp(14)
                    marginEnd = edges.right + dp(14)
                }
                done.requestLayout()
                tray.setPadding(edges.left, dp(6), edges.right, edges.bottom + dp(6))
                insets
            }
        }
        setContentView(root)
        window.useWholeScreen()
    }

    override fun onStart() {
        super.onStart()
        isOpen = true
    }

    override fun onResume() {
        super.onResume()
        window.decorView.hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        store.saveAltar(altar.items())
    }

    override fun onStop() {
        super.onStop()
        isOpen = false
        OverlayService.refresh(this)
    }

    private fun shelf(tiles: List<View>) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            tiles.forEach { addView(it) }
        })
    }

    /** What an element looks like in the tray and under your finger. */
    private fun picture(kind: AltarKind): View = when (kind) {
        AltarKind.SYMBOL -> ImageView(this).apply { setImageResource(Look.symbol.medallion) }
        AltarKind.TEXT -> label("Aa", 30f, PALE_INK, serif = true).apply { gravity = Gravity.CENTER }
        AltarKind.TIMER -> label(altar.timerText, 20f, PALE_INK, serif = true).apply { gravity = Gravity.CENTER }
        AltarKind.IMAGE -> ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(PALE_INK)
            scaleType = ImageView.ScaleType.CENTER
            background = rounded(0x22FFFFFF, dp(14).toFloat(), dp(1), 0x55FFFFFF)
        }
        else -> ElementView(this, kind, alive = false)
    }

    /** Drag it up onto the altar, or tap to place it in the middle. Sideways scrolls the tray. */
    private fun widget(kind: AltarKind) = FrameLayout(this).apply {
        contentDescription = "Add ${kind.label}"
        layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(4) }
        setPadding(dp(5), dp(5), dp(5), dp(5))
        addView(picture(kind), FrameLayout.LayoutParams(-1, -1))
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var ghost: View? = null
        var tapping = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    tapping = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val up = downY - event.rawY
                    val across = abs(event.rawX - downX)
                    if (up > slop || across > slop) tapping = false
                    if (ghost == null && up > slop && up > across) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        ghost = picture(kind).also {
                            root.addView(it, FrameLayout.LayoutParams(altar.elementSize, altar.elementSize))
                        }
                    }
                    ghost?.let { moveGhost(it, event.rawX, event.rawY) }
                }
                MotionEvent.ACTION_UP -> {
                    val dropped = ghost
                    ghost = null
                    if (dropped != null) {
                        val x = dropped.translationX + dropped.width / 2f
                        val y = dropped.translationY + dropped.height / 2f
                        root.removeView(dropped)
                        if (event.rawY < trayTop()) addKind(kind, x / altar.width, y / altar.height)
                    } else if (tapping) {
                        addKind(kind, 0.5f + Random.nextFloat() * 0.2f - 0.1f, 0.42f + Random.nextFloat() * 0.2f - 0.1f)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    ghost?.let(root::removeView)
                    ghost = null
                }
            }
            true
        }
    }

    /** The element rides above the finger, so you can see where it's going. */
    private fun moveGhost(ghost: View, x: Float, y: Float) {
        ghost.translationX = x - altar.elementSize / 2f
        ghost.translationY = y - altar.elementSize - dp(24)
        ghost.alpha = if (y < trayTop()) 1f else 0.5f
    }

    private fun trayTop() = IntArray(2).also(tray::getLocationOnScreen)[1]

    private fun backdropTile(backdrop: AltarBackground) = ImageView(this).apply {
        setImageResource(backdrop.image)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "${backdrop.label} background"
        clipToOutline = true
        background = rounded(Color.BLACK, dp(8).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(62), dp(40)).apply { marginEnd = dp(8) }
        tappable { chooseBackdrop(backdrop) }
    }

    private fun chooseBackdrop(backdrop: AltarBackground) {
        store.altarBackdrop = backdrop
        altar.setBackdrop(backdrop)
    }

    private fun addKind(kind: AltarKind, x: Float, y: Float) {
        dropX = x
        dropY = y
        when (kind) {
            AltarKind.TEXT -> askForText()
            AltarKind.IMAGE -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png"),
                PICK_IMAGE,
            )
            else -> place(kind)
        }
    }

    /** Text and images land where they were dropped, once they've been chosen. */
    private fun place(kind: AltarKind, content: String = "") {
        val item = AltarItem(kind, dropX, dropY, content = content)
        if (altar.add(item) != null) store.saveAltar(altar.items())
    }

    private fun askForText() {
        val sentences = (store.mySentences() + store.liked()).distinctBy { it.key }
        val options = buildList {
            add("Write your own" to { writeText("") { place(AltarKind.TEXT, it) } })
            if (sentences.isNotEmpty()) add("One of my sentences" to { chooseSentence(sentences.map { it.text }) })
            store.randomSentence()?.let { sentence -> add("A passage from the books" to { place(AltarKind.TEXT, sentence.text) }) }
        }
        dialogBuilder()
            .setTitle("Add text")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun chooseSentence(texts: List<String>) {
        dialogBuilder()
            .setTitle("Choose a sentence")
            .setItems(texts.toTypedArray()) { _, which -> place(AltarKind.TEXT, texts[which]) }
            .show()
    }

    private fun writeText(current: String, onDone: (String) -> Unit) {
        val (box, field) = dialogField("Your words", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        field.setText(current)
        dialogBuilder()
            .setTitle("Your words")
            .setView(box)
            .setPositiveButton("Keep") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Activity's own result callback, which needs no extra library.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || uri == null) return
        val name = importAltarImage(this, uri)
        if (name != null) place(AltarKind.IMAGE, name) else Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
    }

    // Moving things

    private fun onAltarTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = altar.itemAt(event.x, event.y)
                active?.let(altar::raise)
                lastX = event.x
                lastY = event.y
                travelled = 0f
                twoFingers = false
                trash.visibility = if (active != null) View.VISIBLE else View.INVISIBLE
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                twoFingers = true
                lastSpan = span(event)
                lastAngle = angle(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Carry on dragging with whichever finger stayed down.
                val stays = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(stays)
                lastY = event.getY(stays)
            }
            MotionEvent.ACTION_MOVE -> active?.let { item ->
                if (event.pointerCount >= 2) {
                    val nextSpan = span(event)
                    val nextAngle = angle(event)
                    if (lastSpan > 0f) item.scale = (item.scale * nextSpan / lastSpan).coerceIn(0.3f, 5f)
                    item.rotation += nextAngle - lastAngle
                    lastSpan = nextSpan
                    lastAngle = nextAngle
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    travelled += abs(dx) + abs(dy)
                    item.x = (item.x + dx / altar.width).coerceIn(0f, 1f)
                    item.y = (item.y + dy / altar.height).coerceIn(0f, 1f)
                    lastX = event.x
                    lastY = event.y
                    trash.alpha = if (overTrash(event)) 1f else 0.6f
                }
                altar.place(item)
            }
            MotionEvent.ACTION_UP -> {
                active?.let { item ->
                    when {
                        overTrash(event) && !twoFingers -> altar.remove(item)
                        !twoFingers && travelled < ViewConfiguration.get(this).scaledTouchSlop -> onItemTapped(item)
                    }
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> endTouch()
        }
        return true
    }

    private fun endTouch() {
        active = null
        trash.visibility = View.INVISIBLE
        store.saveAltar(altar.items())
    }

    private fun onItemTapped(item: AltarItem) {
        if (item.kind == AltarKind.TEXT) {
            writeText(item.content) { text ->
                item.content = text
                (altar.viewOf(item) as? android.widget.TextView)?.text = text
                store.saveAltar(altar.items())
            }
        } else {
            altar.strike(item)
        }
    }

    private fun overTrash(event: MotionEvent): Boolean {
        val spot = IntArray(2).also(trash::getLocationOnScreen)
        val margin = dp(16)
        return event.rawX >= spot[0] - margin && event.rawX <= spot[0] + trash.width + margin &&
            event.rawY >= spot[1] - margin && event.rawY <= spot[1] + trash.height + margin
    }

    private fun span(event: MotionEvent) = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    private fun angle(event: MotionEvent) =
        Math.toDegrees(atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())).toFloat()

    companion object {
        /** While this is open, the floating symbol stays out of its way. */
        var isOpen = false

        private const val PICK_IMAGE = 1
        private const val PALE_INK = 0xFFF6F1E7.toInt()
        private const val TRAY = 0xF0141210.toInt()
        private val ADDABLE = listOf(
            AltarKind.CANDLE, AltarKind.INCENSE, AltarKind.BOWL, AltarKind.GONG, AltarKind.FLOWER, AltarKind.LEAF,
            AltarKind.STONE, AltarKind.SYMBOL, AltarKind.TEXT, AltarKind.TIMER, AltarKind.IMAGE,
        )
    }
}
