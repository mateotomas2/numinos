package io.github.mateotomas2.numinos

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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

/** Arranges the altar: tap the tray to add, drag to move, two fingers to rotate and resize, drag to the trash to remove. */
class AltarActivity : Activity() {
    private lateinit var store: Store
    private lateinit var altar: AltarView
    private lateinit var trash: ImageView

    private var active: AltarItem? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f
    private var travelled = 0f
    private var twoFingers = false

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
        val tray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER)
            addView(row(AltarBackground.entries.map { backdrop ->
                backdrop.label to { chooseBackdrop(backdrop) }
            }))
            addView(row(ADDABLE.map { kind -> kind.label to { addKind(kind) } }))
        }
        val done = chip("Done", true) { finish() }
        val root = FrameLayout(this).apply {
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

    private fun row(entries: List<Pair<String, () -> Unit>>) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            entries.forEach { (name, action) ->
                addView(chip(name, false, action), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
        })
    }

    private fun chooseBackdrop(backdrop: AltarBackground) {
        store.altarBackdrop = backdrop
        altar.setBackdrop(backdrop)
    }

    private fun addKind(kind: AltarKind) {
        when (kind) {
            AltarKind.TEXT -> askForText()
            AltarKind.IMAGE -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png"),
                PICK_IMAGE,
            )
            else -> place(kind)
        }
    }

    /** New things land near the middle, a little apart so they don't hide each other. */
    private fun place(kind: AltarKind, content: String = "") {
        val item = AltarItem(kind, 0.5f + Random.nextFloat() * 0.2f - 0.1f, 0.42f + Random.nextFloat() * 0.2f - 0.1f, content = content)
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
        private val ADDABLE = listOf(
            AltarKind.CANDLE, AltarKind.INCENSE, AltarKind.BOWL, AltarKind.GONG, AltarKind.FLOWER, AltarKind.LEAF,
            AltarKind.STONE, AltarKind.SYMBOL, AltarKind.TEXT, AltarKind.TIMER, AltarKind.IMAGE,
        )
    }
}
