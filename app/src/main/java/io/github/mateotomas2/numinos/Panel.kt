package io.github.mateotomas2.numinos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.text.InputType
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

private const val SOURCE_LIKED = "liked"

/** The floating window: one sentence, the videos, the meditation and the symbol, all at once. */
class Panel(
    serviceContext: Context,
    private val windows: WindowManager,
    private val store: Store,
    private val originX: Int,
    private val originY: Int,
    private val onClosed: () -> Unit,
    private val onMeditate: (Int) -> Unit,
) {
    private val context = ContextThemeWrapper(serviceContext, android.R.style.Theme_Material_Light_NoActionBar)
    private val pad = context.dp(16)
    private var sentence: Sentence? = store.sentenceToShow()
    private var category: String? = null
    private var librarySource = SOURCE_MINE
    private var closing = false
    private var breath: ValueAnimator? = null
    private var inLibrary = false
    private var showSentenceActions = false
    private val openDialogs = mutableListOf<android.app.AlertDialog>()

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) goBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PAPER, context.dp(26).toFloat())
        elevation = context.dp(14).toFloat()
        isClickable = true // Touches on the card must not reach the scrim and close it.
    }

    fun show() {
        root.setBackgroundColor(0x66000000)
        root.alpha = 0f
        root.setOnClickListener { close() }
        val width = min(context.resources.displayMetrics.widthPixels - context.dp(24), context.dp(380))
        root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        renderHome()
        root.onBackGesture { goBack() }
        windows.addView(
            root,
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ),
        )
        card.visibility = View.INVISIBLE
        root.animate().alpha(1f).setDuration(260).start()
        card.post { reveal(opening = true) }
    }

    private fun goBack() {
        if (inLibrary) {
            animateChange()
            renderHome()
        } else {
            close()
        }
    }

    private fun android.app.AlertDialog.showInPanel() {
        openDialogs.add(this)
        showOverlay()
    }

    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        openDialogs.forEach { runCatching { it.dismiss() } }
        root.animate().alpha(0f).setStartDelay(90).setDuration(240).start()
        reveal(opening = false) {
            dismissNow()
            then()
            onClosed()
        }
    }

    fun dismissNow() {
        closing = true
        breath?.cancel()
        openDialogs.forEach { runCatching { it.dismiss() } }
        runCatching { windows.removeView(root) }
    }

    /** The card grows out of (and back into) the point where the symbol sits. */
    private fun reveal(opening: Boolean, done: () -> Unit = {}) {
        if (!card.isAttachedToWindow) {
            done()
            return
        }
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        val x = (originX - location[0]).toFloat()
        val y = (originY - location[1]).toFloat()
        val radius = revealRadius(x, y, card.width, card.height)
        ViewAnimationUtils.createCircularReveal(
            card, x.toInt(), y.toInt(), if (opening) 0f else radius, if (opening) radius else 0f,
        ).apply {
            duration = if (opening) 460 else 320
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!opening) card.visibility = View.INVISIBLE
                    done()
                }
            })
        }.start()
        card.visibility = View.VISIBLE
    }

    private fun animateChange() {
        TransitionManager.beginDelayedTransition(card, AutoTransition().setDuration(240))
    }

    // Home: everything together

    private fun renderHome() {
        inLibrary = false
        breath?.cancel()
        card.removeAllViews()
        card.addView(sentenceSection())
        card.addView(divider())
        card.addView(videoSection())
        card.addView(divider())
        card.addView(meditationSection())
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, context.dp(1))
    }

    /** Tap to open the library, long-press to reveal the sentence's actions. */
    private fun sentenceSection(): View {
        val current = sentence
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + context.dp(2), pad + context.dp(4), pad, context.dp(12))
            addView(context.label(current?.text ?: "Add a sentence of your own, or turn on a book.", 21f, INK, serif = true).apply {
                setLineSpacing(0f, 1.18f)
                setPadding(0, 0, context.dp(26), 0)
                maxLines = 7
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val source = current?.let { listOf(it.source, it.ref).filter(String::isNotEmpty).joinToString(" · ") }.orEmpty()
            row.addView(context.label(source, 12f, SOFT), LinearLayout.LayoutParams(0, -2, 1f))
            if (showSentenceActions) addSentenceActions(row, current)
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            tappable {
                librarySource = sourceIdOf(sentence)
                renderLibraryAnimated()
            }
            setOnLongClickListener {
                showSentenceActions = !showSentenceActions
                refreshHome()
                true
            }
        }
        return FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2))
            addView(
                context.iconButton(R.drawable.ic_settings, "Settings", SOFT) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    close()
                },
                FrameLayout.LayoutParams(context.dp(40), context.dp(40), Gravity.TOP or Gravity.END).apply {
                    topMargin = context.dp(6)
                    marginEnd = context.dp(6)
                },
            )
        }
    }

    private fun addSentenceActions(row: LinearLayout, current: Sentence?) {
        val size = context.dp(42)
        val pinned = current != null && store.pinnedKey == current.key
        val liked = current != null && current.key in store.likedKeys()
        row.addView(context.iconButton(R.drawable.ic_another, "Another sentence") {
            store.pinnedKey = null
            sentence = store.randomSentence(sentence?.key)
            refreshHome()
        }, LinearLayout.LayoutParams(size, size))
        row.addView(context.iconButton(if (liked) R.drawable.ic_liked else R.drawable.ic_like, "Like", if (liked) ACCENT else INK) {
            sentence?.let { store.toggleLiked(it.key) }
            refreshHome()
        }, LinearLayout.LayoutParams(size, size))
        row.addView(context.iconButton(R.drawable.ic_pin, if (pinned) "Unfix sentence" else "Fix sentence", if (pinned) ACCENT else INK) {
            sentence?.let { store.pinnedKey = if (store.pinnedKey == it.key) null else it.key }
            refreshHome()
        }, LinearLayout.LayoutParams(size, size))
    }

    private fun refreshHome() {
        animateChange()
        renderHome()
    }

    private fun sourceIdOf(sentence: Sentence?): String {
        val key = sentence?.key ?: return SOURCE_MINE
        return key.substringBefore(':')
    }

    private fun horizontal(content: LinearLayout) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(content)
    }

    private fun videoSection(): View {
        val all = store.videos()
        val categories = store.categories()
        if (category !in categories) category = null
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(12))
            if (categories.size > 1 || (categories.size == 1 && all.any { it.category.isEmpty() })) {
                val chips = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(pad, 0, pad, context.dp(10))
                }
                fun add(name: String, value: String?) {
                    chips.addView(context.chip(name, category == value) {
                        category = value
                        refreshHome()
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(6) })
                }
                add("All", null)
                categories.forEach { add(it, it) }
                addView(horizontal(chips))
            }
            val tiles = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Otherwise the row lines up text baselines, and the Add tile sits lower than the thumbnails.
                isBaselineAligned = false
                setPadding(pad, 0, pad, 0)
            }
            all.filter { category == null || it.category == category }.forEach { tiles.addView(videoTile(it)) }
            tiles.addView(addVideoButton())
            addView(horizontal(tiles))
        }
    }

    private fun videoTile(video: Video): View {
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = video.title
        }
        YouTube.loadThumbnail(context, video.id, thumb)
        val shade = View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE0000000.toInt(), 0x00000000),
            )
        }
        val title = context.label(video.title.ifEmpty { "YouTube video" }, 11.5f, android.graphics.Color.WHITE).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(context.dp(8), 0, context.dp(8), context.dp(6))
        }
        if (video.title.isEmpty()) YouTube.fetchTitle(context, video.id) { title.text = it }
        return FrameLayout(context).apply {
            background = rounded(SOFT, context.dp(12).toFloat())
            clipToOutline = true
            addView(thumb, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, context.dp(56), Gravity.BOTTOM))
            addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            layoutParams = LinearLayout.LayoutParams(context.dp(138), context.dp(78)).apply { marginEnd = context.dp(10) }
            tappable {
                store.markVideoUsed(video.id)
                YouTube.open(context, video.id)
                close()
            }
            setOnLongClickListener {
                val dialog = context.dialogBuilder()
                    .setTitle(video.title.ifEmpty { "YouTube video" })
                    .setMessage("Remove this video from your practice list?")
                    .setPositiveButton("Remove") { _, _ ->
                        store.removeVideo(video.id)
                        refreshHome()
                    }
                    .setNegativeButton("Keep", null)
                    .create()
                dialog.showInPanel()
                true
            }
        }
    }

    private fun addVideoButton() = context.iconButton(R.drawable.ic_add, "Add a video", SOFT) { promptVideo() }.apply {
        background = rounded(CARD, context.dp(20).toFloat(), context.dp(1), LINE)
        layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun promptVideo() {
        val (box, field) = context.dialogField("YouTube link", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val dialog = context.dialogBuilder()
            .setTitle("Add a video")
            .setMessage("Paste a link here, or use Share in the YouTube or SmartTube app and choose NuminOS.")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val id = YouTube.videoId(field.text.toString())
                if (id == null) {
                    android.widget.Toast.makeText(context, "That isn't a YouTube video link", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    pickCategory(id)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.showInPanel()
    }

    private fun pickCategory(id: String) {
        chooseCategory(context, store, { it.showInPanel() }) { name ->
            store.addVideo(id, name)
            YouTube.fetchTitle(context, id) { refreshHome() }
            refreshHome()
        }
    }

    private fun meditationSection() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, context.dp(12), pad, context.dp(12))
        val ring = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(context.dp(3), ACCENT)
            }
        }
        val start = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(44)
            addView(ring, LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(10) })
            addView(context.label("Meditate", 16f, INK, serif = true, bold = true).apply { maxLines = 1 })
            setPadding(context.dp(4), 0, context.dp(10), 0)
            background = rounded(android.graphics.Color.TRANSPARENT, context.dp(12).toFloat())
            tappable {
                val minutes = store.minutes
                close { onMeditate(minutes) }
            }
        }
        addView(start, LinearLayout.LayoutParams(0, -2, 1f))
        breath = ValueAnimator.ofFloat(0.72f, 1.1f).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                ring.scaleX = scale
                ring.scaleY = scale
            }
            start()
        }
        val options = (listOf(5, 10, 20) + store.minutes).distinct().sorted()
        options.forEach { minutes ->
            addView(context.chip("$minutes", minutes == store.minutes) {
                store.minutes = minutes
                refreshHome()
            }.apply { minimumWidth = context.dp(38); setPadding(context.dp(8), 0, context.dp(8), 0) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(4) })
        }
        addView(context.chip("…", false) { promptMinutes() }.apply {
            contentDescription = "Other duration"
            minimumWidth = context.dp(34)
            setPadding(context.dp(6), 0, context.dp(6), 0)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(4) })
    }

    private fun promptMinutes() {
        val (box, field) = context.dialogField("Minutes", InputType.TYPE_CLASS_NUMBER)
        context.dialogBuilder()
            .setTitle("How many minutes?")
            .setView(box)
            .setPositiveButton("Set") { _, _ ->
                field.text.toString().toIntOrNull()?.takeIf { it in 1..600 }?.let {
                    store.minutes = it
                    refreshHome()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .showInPanel()
    }

    // Library: every sentence of one book

    private fun renderLibraryAnimated() {
        animateChange()
        renderLibrary()
    }

    private fun librarySentences(): List<Sentence> {
        val liked = store.likedKeys()
        return when (librarySource) {
            SOURCE_LIKED -> store.allSentences().filter { it.key in liked }
            SOURCE_MINE -> store.mySentences()
            else -> store.books.firstOrNull { it.id == librarySource }?.sentences.orEmpty()
        }
    }

    private fun renderLibrary() {
        inLibrary = true
        breath?.cancel()
        card.removeAllViews()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(6), context.dp(8), pad, 0)
            addView(context.iconButton(R.drawable.ic_back, "Back") {
                animateChange()
                renderHome()
            }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            addView(context.label("Library", 20f, INK, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (librarySource == SOURCE_MINE) {
                addView(context.iconButton(R.drawable.ic_add, "Add a sentence") { promptSentence() }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            }
        }
        card.addView(header)
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, context.dp(6), pad, context.dp(10))
        }
        fun addChip(name: String, id: String) = chips.addView(context.chip(name, librarySource == id) {
            librarySource = id
            renderLibraryAnimated()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(6) })
        addChip(MINE_TITLE, SOURCE_MINE)
        addChip("Liked", SOURCE_LIKED)
        store.books.forEach { addChip(it.title, it.id) }
        card.addView(horizontal(chips))
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
        }
        val items = librarySentences()
        if (items.isEmpty()) {
            rows.addView(context.label(
                if (librarySource == SOURCE_MINE) "Nothing here yet. Tap + to write a sentence of your own." else "Tap the heart on a sentence to keep it here.",
                14f, SOFT,
            ).apply { setPadding(context.dp(2), context.dp(12), context.dp(2), context.dp(24)) })
        }
        items.forEach { rows.addView(sentenceRow(it)) }
        val height = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
        card.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows)
        }, LinearLayout.LayoutParams(-1, height))
    }

    private fun sentenceRow(item: Sentence) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(12), context.dp(10), context.dp(4), context.dp(4))
        background = rounded(CARD, context.dp(14).toFloat(), context.dp(1), LINE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) }
        addView(context.label(item.text, 15f, INK, serif = true).apply {
            setLineSpacing(0f, 1.12f)
            setPadding(0, 0, context.dp(8), 0)
        })
        val pinned = store.pinnedKey == item.key
        val liked = item.key in store.likedKeys()
        val size = context.dp(40)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ref = if (librarySource == SOURCE_LIKED) listOf(item.source, item.ref).filter(String::isNotEmpty).joinToString(" · ") else item.ref
            addView(context.label(ref, 11.5f, SOFT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.iconButton(if (liked) R.drawable.ic_liked else R.drawable.ic_like, "Like", if (liked) ACCENT else INK) {
                store.toggleLiked(item.key)
                renderLibrary()
            }, LinearLayout.LayoutParams(size, size))
            addView(context.iconButton(R.drawable.ic_pin, if (pinned) "Unfix sentence" else "Fix sentence", if (pinned) ACCENT else INK) {
                store.pinnedKey = if (pinned) null else item.key
                renderLibrary()
            }, LinearLayout.LayoutParams(size, size))
        })
        tappable {
            sentence = item
            animateChange()
            renderHome()
        }
        if (item.key.startsWith("$SOURCE_MINE:")) {
            setOnLongClickListener {
                context.dialogBuilder()
                    .setMessage("Delete this sentence?")
                    .setPositiveButton("Delete") { _, _ ->
                        store.removeMine(item.key)
                        if (sentence?.key == item.key) sentence = store.sentenceToShow()
                        renderLibrary()
                    }
                    .setNegativeButton("Keep", null)
                    .create()
                    .showInPanel()
                true
            }
        }
    }

    private fun promptSentence() {
        val (box, field) = context.dialogField("A sentence to live with")
        context.dialogBuilder()
            .setTitle("Your sentence")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) {
                    sentence = store.addMine(text)
                    renderLibraryAnimated()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .showInPanel()
    }
}

/** Shared by the panel and the Share screen. */
fun chooseCategory(context: Context, store: Store, present: (android.app.AlertDialog) -> Unit, onChosen: (String) -> Unit) {
    fun askForName() {
        val (box, field) = context.dialogField("Category name (optional)")
        context.dialogBuilder()
            .setTitle("New category")
            .setView(box)
            .setPositiveButton("Add") { _, _ -> onChosen(field.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .create()
            .let(present)
    }

    val names = store.categories()
    if (names.isEmpty()) {
        askForName()
        return
    }
    context.dialogBuilder()
        .setTitle("Which category?")
        .setItems((names + "New category…").toTypedArray()) { _, which ->
            if (which < names.size) onChosen(names[which]) else askForName()
        }
        .create()
        .let(present)
}
