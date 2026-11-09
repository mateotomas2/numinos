package io.github.mateotomas2.numinos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
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
import android.widget.Toast
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val BACK_REPEAT_MS = 200L
private const val BACK_ANNOUNCE_MS = 700L

private enum class Page { HOME, SHELF, BOOKSELLER, NOTEBOOK, JAR, PRACTICE }

private const val HOLD_START = "Let go to start, slide sideways to cancel"

private const val MOVED_FILE = "This book's file was moved or deleted. Remove it and add it again from its new place."

private val OWN_BOOK_COLORS = intArrayOf(
    0xFF6B4E71.toInt(), 0xFF2F6F73.toInt(), 0xFF8A5A2B.toInt(), 0xFF4F6D3A.toInt(), 0xFF7A3E48.toInt(), 0xFF3F5277.toInt(),
)

private val SPINE_COLORS = mapOf(
    "tao" to 0xFF3F7D68.toInt(),
    "upanishads" to 0xFFB5620F.toInt(),
    "dhammapada" to 0xFF8F2F34.toInt(),
    "bible" to 0xFF3A5FA8.toInt(),
)

/** The floating window: the sentence, videos and meditation, with the library behind the sentence. */
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
    private var category: String? = store.videoCategory
    private var videoScroll = store.videoScroll
    private var chipScroll = store.chipScroll
    private var arranging: String? = null
    private var closing = false
    private var breath: ValueAnimator? = null
    private val openDialogs = mutableListOf<AlertDialog>()

    private var page = Page.HOME
    private val backStack = ArrayDeque<Page>()
    private var sentenceHost: FrameLayout? = null

    private var pill: LinearLayout? = null
    private var touchX = 0f
    private var touchY = 0f
    private var swallowTouch = false
    private var suspended = false
    val isSuspended get() = suspended
    private var foundBooks: List<FoundEpub>? = null
    private var searching = false
    private var lastBackAt = -BACK_ANNOUNCE_MS
    private val windowParams = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) goBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchX = event.rawX
                touchY = event.rawY
                val shown = pill
                if (shown != null && !isInside(shown, event)) {
                    dismissPill()
                    swallowTouch = true
                    return true
                }
            }
            if (swallowTouch) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    swallowTouch = false
                }
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PAPER, context.dp(26).toFloat())
        elevation = context.dp(14).toFloat()
        clipToOutline = true
        isClickable = true // Touches on the card must not reach the scrim and close it.
    }

    fun show() {
        root.setBackgroundColor(0x66000000)
        root.alpha = 0f
        root.setOnClickListener { close() }
        val width = min(context.resources.displayMetrics.widthPixels - context.dp(24), context.dp(380))
        root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        render()
        BookLoader.listener = { if (page == Page.SHELF || page == Page.BOOKSELLER) render() }
        root.onBackGesture { goBack() }
        windows.addView(root, windowParams)
        card.visibility = View.INVISIBLE
        root.animate().alpha(1f).setDuration(260).start()
        card.post { reveal(opening = true) }
    }

    /** Steps aside for the reader, keeping this window's page and sentence for when you come back. */
    fun suspendForReader() {
        suspended = true
        dismissPill()
        root.animate().cancel()
        root.animate().alpha(0f).setStartDelay(0).setDuration(140).withEndAction {
            if (suspended) runCatching { windows.removeViewImmediate(root) }
        }.start()
    }

    fun resumeFromReader() {
        if (!suspended || closing) return
        suspended = false
        root.animate().cancel()
        if (!root.isAttachedToWindow) {
            windows.addView(root, windowParams)
            root.onBackGesture { goBack() }
        }
        // The window is redrawn so the tilted book stands straight and anything kept while reading shows up.
        store.pinned()?.let { sentence = it }
        foundBooks = null
        render()
        root.animate().alpha(1f).setStartDelay(0).setDuration(220).start()
    }

    /** True just after a back press, when a system "close" announcement is that same press and not Home. */
    fun backJustHandled() = SystemClock.uptimeMillis() - lastBackAt < BACK_ANNOUNCE_MS

    private fun goBack() {
        // Some phones deliver one press both as a key and as a gesture, which would skip a page.
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < BACK_REPEAT_MS) return
        lastBackAt = now
        when {
            pill != null -> dismissPill()
            arranging != null -> arrange(null)
            backStack.isNotEmpty() -> {
                page = backStack.removeLast()
                morph()
            }
            else -> close()
        }
    }

    private fun navigate(target: Page) {
        arranging = null
        backStack.addLast(page)
        page = target
        morph()
    }

    private fun returnHome() {
        backStack.clear()
        page = Page.HOME
        morph()
    }

    private fun AlertDialog.showInPanel() {
        openDialogs.add(this)
        showOverlay()
    }

    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        dismissPill()
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
        BookLoader.listener = null
        store.videoCategory = category
        store.videoScroll = videoScroll
        store.chipScroll = chipScroll
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

    private fun isInside(view: View, event: MotionEvent): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return event.rawX >= location[0] && event.rawX <= location[0] + view.width &&
            event.rawY >= location[1] && event.rawY <= location[1] + view.height
    }

    // Moving between pages

    /** The card smoothly changes size while the new page fades in. Only the card is animated, so long chapters stay fast. */
    private fun morph() {
        dismissPill()
        TransitionManager.beginDelayedTransition(root, ChangeBounds().setDuration(300).addTarget(card))
        render()
        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i)
            child.alpha = 0f
            child.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun render() {
        breath?.cancel()
        sentenceHost = null
        card.removeAllViews()
        val tall = page == Page.NOTEBOOK || page == Page.JAR || page == Page.BOOKSELLER
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (tall) (context.resources.displayMetrics.heightPixels * 0.86f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        when (page) {
            Page.HOME -> renderHome()
            Page.SHELF -> renderShelf()
            Page.BOOKSELLER -> renderBookseller()
            Page.NOTEBOOK -> renderNotebook()
            Page.JAR -> renderJar()
            Page.PRACTICE -> renderPractice()
        }
    }

    private fun refreshHomeVideos() {
        if (page != Page.HOME) return
        TransitionManager.beginDelayedTransition(root, ChangeBounds().setDuration(240).addTarget(card))
        render()
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, context.dp(1))
    }

    /** Opens at [startX], once the content has a size to scroll over, and reports where it's moved to. */
    private fun horizontal(content: LinearLayout, startX: Int = 0, onScrolled: (Int) -> Unit = {}) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(content)
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                removeOnLayoutChangeListener(this)
                scrollTo(startX, 0)
                setOnScrollChangeListener { _, x, _, _, _ -> onScrolled(x) }
                onScrolled(scrollX)
            }
        })
    }

    private fun sourceLine(item: Sentence?) =
        item?.let { listOf(it.source, it.ref).filter(String::isNotEmpty).joinToString(" · ") }.orEmpty()

    private fun header(title: String, subtitle: String? = null, actions: LinearLayout.() -> Unit = {}) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(6), context.dp(8), context.dp(8), context.dp(4))
            addView(context.iconButton(R.drawable.ic_back, "Back") { goBack() }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (subtitle != null) addView(context.label(subtitle, 11.5f, SOFT).apply { maxLines = 1 })
                addView(context.label(title, 19f, INK, serif = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions()
        }

    // Home: everything together

    private fun renderHome() {
        val host = FrameLayout(context)
        sentenceHost = host
        fillSentence(host)
        card.addView(host, LinearLayout.LayoutParams(-1, -2))
        card.addView(divider())
        card.addView(videoSection())
        card.addView(divider())
        card.addView(meditationSection())
        card.addView(pillarsLine())
    }

    /** Tap to browse the shelf, tap the title for the book, long-press for the floating actions. */
    private fun fillSentence(host: FrameLayout) {
        host.removeAllViews()
        val current = sentence
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + context.dp(2), pad + context.dp(4), pad, context.dp(6))
            addView(context.label(current?.text ?: "Add a sentence of your own, or turn on a book.", 21f, INK, serif = true).apply {
                setLineSpacing(0f, 1.18f)
                setPadding(0, 0, context.dp(26), 0)
                maxLines = 10
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(context.label(sourceLine(current).let { if (it.isEmpty()) "" else "$it  ›" }, 12.5f, SOFT).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = context.dp(40)
                setPadding(0, 0, context.dp(8), 0)
                if (current != null) tappable { openSource(current) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(2) })
            tappable { navigate(Page.SHELF) }
            setOnLongClickListener {
                sentence?.let { showPill(it) }
                true
            }
        }
        host.addView(body, FrameLayout.LayoutParams(-1, -2))
        host.addView(
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

    private fun openSource(item: Sentence) {
        val owner = store.book(item.bookId)
        when {
            owner == null -> navigate(Page.NOTEBOOK)
            store.isDownloaded(owner) -> openBook(owner, item.locator)
            owner.isImported -> Toast.makeText(context, MOVED_FILE, Toast.LENGTH_LONG).show()
            store.isOnShelf(owner) -> tellDownloadState(owner)
            else -> navigate(Page.BOOKSELLER)
        }
    }

    private fun openBook(item: Book, locator: String?) {
        context.startActivity(ReaderActivity.intent(context, item.id, locator))
        suspendForReader()
    }

    private fun tellDownloadState(item: Book) {
        val percent = BookLoader.progressOf(item.id)
        val message = if (percent == null) {
            BookLoader.fetch(context, item)
            "Downloading ${item.title}…"
        } else {
            "Downloading ${item.title}: $percent%"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Swaps the sentence without touching the rest of the window, except to make room for a longer one. */
    private fun changeSentence(item: Sentence?) {
        sentence = item
        val host = sentenceHost ?: return
        val change = TransitionSet()
            .addTransition(ChangeBounds().addTarget(card))
            .addTransition(Fade())
            .setDuration(240)
        TransitionManager.beginDelayedTransition(root, change)
        fillSentence(host)
    }

    // The floating actions

    private fun showPill(item: Sentence) = showPill { fillPill(it, item) }

    private fun showPill(fill: (LinearLayout) -> Unit) {
        dismissPill()
        val shown = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(2))
            background = rounded(CARD, context.dp(26).toFloat(), context.dp(1), LINE)
            elevation = context.dp(18).toFloat()
        }
        fill(shown)
        shown.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        val margin = context.dp(8)
        val x = (touchX - origin[0] - shown.measuredWidth / 2).toInt().coerceIn(margin, (root.width - shown.measuredWidth - margin).coerceAtLeast(margin))
        val above = (touchY - origin[1]).toInt() - shown.measuredHeight - context.dp(20)
        val y = if (above >= margin) above else (touchY - origin[1]).toInt() + context.dp(20)
        root.addView(shown, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = x
            topMargin = y
        })
        pill = shown
        shown.alpha = 0f
        shown.scaleX = 0.8f
        shown.scaleY = 0.8f
        shown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    /** Every action closes the pill, in every place it appears. */
    private fun LinearLayout.addAction(icon: Int, description: String, tint: Int = INK, action: () -> Unit) {
        addView(context.iconButton(icon, description, tint) {
            dismissPill()
            action()
        }, LinearLayout.LayoutParams(context.dp(46), context.dp(46)))
    }

    private fun fillPill(shown: LinearLayout, item: Sentence) {
        fun add(icon: Int, description: String, tint: Int = INK, action: () -> Unit) = shown.addAction(icon, description, tint, action)
        if (page == Page.HOME) {
            add(R.drawable.ic_another, "Another sentence") {
                store.setPinned(null)
                changeSentence(store.randomSentence(sentence?.key))
            }
        }
        val liked = store.isLiked(item.key)
        add(if (liked) R.drawable.ic_liked else R.drawable.ic_like, if (liked) "Remove from jar" else "Like", if (liked) ACCENT else INK) {
            store.toggleLiked(item)
            if (page == Page.JAR) render()
        }
        val pinned = store.pinned()?.key == item.key
        add(R.drawable.ic_pin, if (pinned) "Unfix sentence" else "Fix sentence", if (pinned) ACCENT else INK) {
            if (pinned) {
                store.setPinned(null)
            } else {
                store.setPinned(item)
                sentence = item
            }
            if (page != Page.HOME) Toast.makeText(context, if (pinned) "Unfixed" else "Fixed on top", Toast.LENGTH_SHORT).show()
        }
        if (item.bookId != null) {
            add(R.drawable.ic_less, "Show less") {
                store.hide(item.key)
                if (store.pinned()?.key == item.key) store.setPinned(null)
                if (page == Page.HOME) {
                    changeSentence(store.randomSentence(item.key))
                } else if (sentence?.key == item.key) {
                    sentence = store.randomSentence(item.key)
                }
                Toast.makeText(context, "It won't come up at random. Settings can bring it back.", Toast.LENGTH_SHORT).show()
            }
        } else {
            add(R.drawable.ic_edit, "Edit") { editMine(item) }
            add(R.drawable.ic_delete, "Delete") {
                context.dialogBuilder()
                    .setMessage("Delete this sentence?")
                    .setPositiveButton("Delete") { _, _ ->
                        store.removeMine(item.key)
                        if (sentence?.key == item.key) sentence = store.sentenceToShow()
                        render()
                    }
                    .setNegativeButton("Keep", null)
                    .create()
                    .showInPanel()
            }
        }
    }

    private fun editMine(item: Sentence) {
        val (box, field) = context.dialogField("A sentence to live with")
        field.setText(item.text)
        field.setSelection(item.text.length)
        context.dialogBuilder()
            .setTitle("Edit sentence")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) {
                    val edited = store.editMine(item.key, text)
                    if (sentence?.key == item.key) sentence = edited
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .showInPanel()
    }

    private fun dismissPill() {
        val shown = pill ?: return
        pill = null
        shown.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(120).withEndAction { root.removeView(shown) }.start()
    }

    // Videos

    private fun videoSection(): View {
        val all = store.videos()
        val categories = store.categories()
        if (category !in categories) category = null
        val shown = all.filter { category == null || it.category == category }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(12))
            if (arranging != null) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad, 0, pad, context.dp(10))
                    addView(context.label("Drag the outlined video", 13f, SOFT), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(context.chip("Done", true) { arrange(null) })
                })
            } else if (categories.size > 1 || (categories.size == 1 && all.any { it.category.isEmpty() })) {
                val chips = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(pad, 0, pad, context.dp(10))
                }
                fun add(name: String, value: String?) {
                    chips.addView(context.chip(name, category == value) {
                        category = value
                        videoScroll = 0
                        refreshHomeVideos()
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(6) })
                }
                add("All", null)
                categories.forEach { add(it, it) }
                addView(horizontal(chips, chipScroll) { chipScroll = it })
            }
            val tiles = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Otherwise the row lines up text baselines rather than the tiles themselves.
                isBaselineAligned = false
                setPadding(pad, 0, pad, 0)
            }
            shown.forEach { tiles.addView(videoTile(it, shown.size)) }
            if (arranging == null) tiles.addView(addVideoButton())
            val scroller = horizontal(tiles, videoScroll) { videoScroll = it }
            addView(scroller)
            for (i in 0 until tiles.childCount) {
                val tile = tiles.getChildAt(i)
                if (tile.tag == arranging) dragAlong(tile, tiles, scroller)
            }
        }
    }

    private fun videoTile(video: Video, neighbours: Int): View {
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = video.title
        }
        YouTube.loadThumbnail(context, video.id, thumb)
        val shade = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE0000000.toInt(), 0x00000000))
        }
        val title = context.label(video.title.ifEmpty { "YouTube video" }, 11.5f, Color.WHITE).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
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
            tag = video.id
            if (arranging == null) {
                tappable {
                    if (YouTube.open(context, video.id)) {
                        val now = System.currentTimeMillis()
                        store.recordPractice(Pillar.BODY, now, 0)
                        store.videoOpenedAt = now
                        store.videoFullScreen = false
                    }
                    close()
                }
                setOnLongClickListener {
                    showPill { fillVideoPill(it, video, neighbours) }
                    true
                }
            } else if (arranging == video.id) {
                addView(View(context).apply {
                    background = rounded(Color.TRANSPARENT, context.dp(12).toFloat(), context.dp(3), ACCENT)
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                alpha = 0.55f
                tappable { arrange(video.id) }
            }
        }
    }

    private fun fillVideoPill(shown: LinearLayout, video: Video, neighbours: Int) {
        if (neighbours > 1) shown.addAction(R.drawable.ic_rearrange, "Rearrange") { arrange(video.id) }
        shown.addAction(R.drawable.ic_category, "Change category") {
            chooseCategory(context, store, { it.showInPanel() }) { name ->
                store.setVideoCategory(video.id, name)
                refreshHomeVideos()
            }
        }
        shown.addAction(R.drawable.ic_delete, "Remove") {
            context.dialogBuilder()
                .setTitle(video.title.ifEmpty { "YouTube video" })
                .setMessage("Remove this video from your practice list?")
                .setPositiveButton("Remove") { _, _ ->
                    store.removeVideo(video.id)
                    refreshHomeVideos()
                }
                .setNegativeButton("Keep", null)
                .create()
                .showInPanel()
        }
    }

    /** Lifts one video out of the row to be dragged, or puts the row back to normal when given null. */
    private fun arrange(id: String?) {
        arranging = id
        refreshHomeVideos()
    }

    /** The tile follows the finger, the row scrolls when it nears an edge, and letting go saves the order. */
    private fun dragAlong(tile: View, row: LinearLayout, scroller: HorizontalScrollView) {
        val edge = context.dp(48)
        var fingerX = 0f

        fun place(move: Float) {
            tile.translationX = (tile.translationX + move).coerceIn(-tile.left.toFloat(), (row.width - tile.right).toFloat())
            // Children keep their old positions until the next layout, so wait for it before comparing them.
            if (row.isLayoutRequested) return
            val center = tile.left + tile.translationX + tile.width / 2
            val index = row.indexOfChild(tile)
            val neighbour = row.getChildAt(if (tile.translationX > 0) index + 1 else index - 1) ?: return
            if (tile.translationX > 0 == center > neighbour.left + neighbour.width / 2) {
                val step = (neighbour.left - tile.left).toFloat()
                row.removeView(neighbour)
                row.addView(neighbour, index)
                tile.translationX -= step
                neighbour.translationX = step
                neighbour.animate().translationX(0f).setDuration(160).start()
            }
        }

        val autoScroll = object : Runnable {
            override fun run() {
                val at = IntArray(2)
                scroller.getLocationOnScreen(at)
                val speed = when {
                    fingerX < at[0] + edge -> -context.dp(6)
                    fingerX > at[0] + scroller.width - edge -> context.dp(6)
                    else -> 0
                }
                val before = scroller.scrollX
                scroller.scrollBy(speed, 0)
                if (scroller.scrollX != before) place((scroller.scrollX - before).toFloat())
                scroller.postOnAnimation(this)
            }
        }

        tile.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerX = event.rawX
                    scroller.requestDisallowInterceptTouchEvent(true)
                    scroller.postOnAnimation(autoScroll)
                }
                MotionEvent.ACTION_MOVE -> {
                    place(event.rawX - fingerX)
                    fingerX = event.rawX
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scroller.removeCallbacks(autoScroll)
                    tile.animate().translationX(0f).setDuration(160).start()
                    store.arrangeVideos(List(row.childCount) { row.getChildAt(it).tag as String })
                }
            }
            true
        }
    }

    private fun addVideoButton() = context.iconButton(R.drawable.ic_add, "Add a video", SOFT) { promptVideo() }.apply {
        background = rounded(CARD, context.dp(20).toFloat(), context.dp(1), LINE)
        layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun promptVideo() {
        val (box, field) = context.dialogField("YouTube link", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        context.dialogBuilder()
            .setTitle("Add a video")
            .setMessage("Paste a link here, or use Share in the YouTube or SmartTube app and choose NuminOS.")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val id = YouTube.videoId(field.text.toString())
                if (id == null) {
                    Toast.makeText(context, "That isn't a YouTube video link", Toast.LENGTH_SHORT).show()
                } else {
                    pickCategory(id)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .showInPanel()
    }

    private fun pickCategory(id: String) {
        chooseCategory(context, store, { it.showInPanel() }) { name ->
            store.addVideo(id, name)
            category = store.videoCategory
            videoScroll = 0
            YouTube.fetchTitle(context, id) { refreshHomeVideos() }
            refreshHomeVideos()
        }
    }

    // Meditation

    private fun meditationSection() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, context.dp(12), pad, context.dp(12))
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
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
            background = rounded(Color.TRANSPARENT, context.dp(12).toFloat())
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
        addView(context.chip("Altar", false) {
            AltarActivity.isOpen = true // Keeps the symbol from flashing up as this window closes.
            context.startActivity(Intent(context, AltarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            close()
        })
        addView(durationChip(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(4) })
    }

    // Practice

    /** Today's Mind, Body and Soul under Meditate. Tap it for the month. */
    private fun pillarsLine() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val today = store.practice(practiceDay(System.currentTimeMillis()))
        setPadding(pad + context.dp(4), 0, pad, context.dp(12))
        addView(PillarsView(context, today), LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(10) })
        Pillar.entries.forEachIndexed { index, pillar ->
            if (index > 0) addView(context.label("·", 13f, SOFT).apply { setPadding(context.dp(6), 0, context.dp(6), 0) })
            addView(context.label(pillar.label, 13f, if (today.done(pillar)) ACCENT else SOFT, bold = today.done(pillar)))
        }
        contentDescription = "Today: " + Pillar.entries.joinToString { "${it.label} ${if (today.done(it)) "done" else "not yet"}" }
        tappable { navigate(Page.PRACTICE) }
    }

    /** This month, one ring a day, and how many days reached each pillar. */
    private fun renderPractice() {
        val today = practiceDay(System.currentTimeMillis())
        val month = YearMonth.from(today)
        val days = store.practiceDays(month.atDay(1), month.atEndOfMonth())
        val complete = days.values.count { it.complete }
        card.addView(header(month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.year, "Practice"))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, context.dp(4), pad, context.dp(20))
        }
        val now = days[today] ?: store.practice(today)
        body.addView(context.label("Today  " + Pillar.entries.joinToString(" · ") { "${it.label} ${now.minutes(it)}/${now.goal(it)}" } + " min", 13.5f, INK))
        body.addView(context.label(if (complete == 1) "1 complete day this month" else "$complete complete days this month", 15f, INK, serif = true).apply {
            setPadding(0, context.dp(10), 0, 0)
        })
        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also { grid.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val names = row()
        repeat(7) { i ->
            names.addView(context.label(firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.ENGLISH), 11f, SOFT).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        var week = row()
        repeat((month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7) {
            week.addView(View(context), LinearLayout.LayoutParams(0, context.dp(40), 1f))
        }
        for (date in 1..month.lengthOfMonth()) {
            val day = month.atDay(date)
            if (day.dayOfWeek == firstDay && date > 1) week = row()
            val cell = FrameLayout(context)
            if (day <= today) cell.addView(PillarsView(context, days[day] ?: DayPractice.NONE), FrameLayout.LayoutParams(context.dp(32), context.dp(32), Gravity.CENTER))
            cell.addView(context.label("$date", 9.5f, if (day == today) INK else SOFT, bold = day == today).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            week.addView(cell, LinearLayout.LayoutParams(0, context.dp(40), 1f))
        }
        while (week.childCount < 7) week.addView(View(context), LinearLayout.LayoutParams(0, context.dp(40), 1f))
        body.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        val counts = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            Pillar.entries.forEach { pillar ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val reached = days.values.count { it.done(pillar) }
                    addView(context.label("$reached", 20f, INK, serif = true))
                    addView(context.label("${pillar.label} ${if (reached == 1) "day" else "days"}", 12f, SOFT))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        body.addView(counts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(14) })
        card.addView(body)
    }

    /** Tap to choose on the wheel. Hold to set it with one drag and start on letting go. */
    private fun durationChip() = context.chip(durationLabel(store.minutes), false) { promptDuration() }.apply {
        contentDescription = if (store.minutes == 0) "No timer. Change duration" else "${store.minutes} minutes. Change duration"
        minimumWidth = context.dp(58)
        var whileHeld: ((MotionEvent) -> Unit)? = null
        setOnLongClickListener {
            if (!isPressed) return@setOnLongClickListener false // Accessibility services long-click with no finger to follow.
            isPressed = false
            parent.requestDisallowInterceptTouchEvent(true)
            whileHeld = holdToStart(this) { whileHeld = null }
            true
        }
        setOnTouchListener { _, event ->
            whileHeld?.let { it(event); true } ?: false
        }
    }

    private fun durationChoices() = (DURATIONS + store.minutes).distinct().sorted()

    private fun promptDuration() {
        val values = durationChoices()
        val wheel = context.durationWheel(values, store.minutes)
        val box = FrameLayout(context).apply {
            addView(wheel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        context.dialogBuilder()
            .setTitle("How long?")
            .setView(box)
            .setPositiveButton("Start") { _, _ ->
                store.minutes = values[wheel.value]
                val minutes = store.minutes
                close { onMeditate(minutes) }
            }
            .setOnCancelListener {
                store.minutes = values[wheel.value]
                refreshHomeVideos()
            }
            .create()
            .showInPanel()
    }

    /** Shows the wheel above the finger and returns what handles the rest of the touch. */
    private fun holdToStart(chip: View, onEnded: () -> Unit): (MotionEvent) -> Unit {
        val values = durationChoices()
        val wheel = context.durationWheel(values, store.minutes)
        val first = wheel.value
        val hint = context.label(HOLD_START, 12f, SOFT).apply { gravity = Gravity.CENTER }
        val floating = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, context.dp(20).toFloat())
            elevation = context.dp(24).toFloat()
            setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(12))
            addView(wheel)
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4) })
        }
        val rootTop = IntArray(2).also { root.getLocationOnScreen(it) }[1]
        root.addView(floating, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (touchY.toInt() - rootTop - context.dp(290)).coerceAtLeast(context.dp(24))
        })
        var cancelling = false
        return { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val next = (first + ((touchY - event.rawY) / context.dp(12)).roundToInt()).coerceIn(values.indices)
                    if (next != wheel.value) {
                        wheel.value = next
                        chip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    cancelling = abs(event.rawX - touchX) > context.dp(72)
                    floating.alpha = if (cancelling) 0.45f else 1f
                    hint.text = if (cancelling) "Let go to cancel" else HOLD_START
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onEnded()
                    root.removeView(floating)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !cancelling) {
                        store.minutes = values[wheel.value]
                        val minutes = store.minutes
                        close { onMeditate(minutes) }
                    }
                }
            }
        }
    }

    // The bookshelf

    private fun renderShelf() {
        BookLoader.fetchShelf(context)
        card.addView(header("Library"))
        val shelf = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, context.dp(10), pad, context.dp(22))
        }
        val widths = listOf(58, 62, 52, 70)
        val heights = listOf(190, 172, 182, 204)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            isBaselineAligned = false
            minimumHeight = context.dp(204)
            store.shelfBooks().forEach { item ->
                val spine = SpineView(context, item.title, spineColor(item))
                spine.pivotX = 0f
                // A book still downloading stands faded on the shelf.
                if (!store.isDownloaded(item)) spine.alpha = 0.45f
                spine.setOnClickListener {
                    if (store.isDownloaded(item)) {
                        tiltOut(spine) { openBook(item, null) }
                    } else if (item.isImported) {
                        Toast.makeText(context, MOVED_FILE, Toast.LENGTH_LONG).show()
                    } else {
                        tellDownloadState(item)
                    }
                }
                spine.setOnLongClickListener {
                    confirmRemove(item)
                    true
                }
                addView(spine, LinearLayout.LayoutParams(context.dp(widths[item.shelf % widths.size]), context.dp(heights[item.shelf % heights.size])).apply {
                    marginEnd = context.dp(4)
                })
            }
            addView(context.iconButton(R.drawable.ic_add, "Add a book", SOFT) { navigate(Page.BOOKSELLER) }.apply {
                background = rounded(CARD, context.dp(5).toFloat(), context.dp(1), LINE)
            }, LinearLayout.LayoutParams(context.dp(46), context.dp(128)).apply { marginStart = context.dp(4) })
        }
        shelf.addView(horizontal(row), LinearLayout.LayoutParams(-1, -2))
        shelf.addView(plank())
        val keepsakes = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            isBaselineAligned = false
            setPadding(0, context.dp(26), 0, 0)
            val notebook = NotebookView(context).apply {
                rotation = -4f
                setOnClickListener { tiltOut(this) { navigate(Page.NOTEBOOK) } }
            }
            addView(notebook, LinearLayout.LayoutParams(context.dp(92), context.dp(118)).apply { marginEnd = context.dp(46) })
            val jar = JarView(context, store.liked().size).apply {
                setOnClickListener { tiltOut(this) { navigate(Page.JAR) } }
            }
            addView(jar, LinearLayout.LayoutParams(context.dp(94), context.dp(118)))
        }
        shelf.addView(keepsakes, LinearLayout.LayoutParams(-1, -2))
        shelf.addView(plank())
        shelf.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(8), 0, 0)
            addView(context.label(MINE_TITLE, 12.5f, SOFT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(context.dp(120), -2).apply { marginEnd = context.dp(20) })
            addView(context.label("Liked", 12.5f, SOFT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(context.dp(120), -2))
        })
        card.addView(shelf)
    }

    private fun confirmRemove(item: Book) {
        context.dialogBuilder()
            .setTitle("Remove ${item.title}?")
            .setMessage(
                if (item.isImported) {
                    "It leaves your shelf, but your file stays on your phone. Your liked passages stay in the jar."
                } else {
                    "It leaves your shelf and stops giving random sentences. Your liked passages stay in the jar."
                },
            )
            .setPositiveButton("Remove") { _, _ ->
                store.removeFromShelf(item)
                if (store.pinned()?.bookId == item.id) store.setPinned(null)
                if (sentence?.bookId == item.id && store.liked().none { it.key == sentence?.key }) sentence = store.sentenceToShow()
                render()
            }
            .setNegativeButton("Keep", null)
            .create()
            .showInPanel()
    }

    // The bookseller's table: books you can add

    private fun renderBookseller() {
        card.addView(header("Add a book"))
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, context.dp(6), pad, pad)
        }
        store.books.forEach { item ->
            val onShelf = store.isOnShelf(item)
            val percent = BookLoader.progressOf(item.id)
            val status = when {
                percent != null -> "Downloading $percent%"
                onShelf && BookLoader.hasFailed(item.id) -> "Download failed, tap to retry"
                onShelf && store.isDownloaded(item) -> "On your shelf"
                onShelf -> "Waiting to download"
                else -> "Add · ${sizeLabel(item.size)}"
            }
            val canTap = !onShelf || BookLoader.hasFailed(item.id)
            rows.addView(bookRow(spineColor(item), item.title, item.credit, status, canTap) {
                store.addToShelf(item)
                BookLoader.fetch(context, item)
                render()
            })
        }
        rows.addView(context.label("ON YOUR PHONE", 12f, SOFT, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(context.dp(2), context.dp(14), 0, context.dp(8))
        })
        addPhoneBooks(rows)
        card.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun addPhoneBooks(rows: LinearLayout) {
        if (!EpubFiles.canSearch(context)) {
            rows.addView(context.label("NuminOS can list the EPUB files on your phone so you can add them. It only opens EPUBs, and it doesn't upload anything.", 14f, SOFT).apply {
                setLineSpacing(0f, 1.16f)
            })
            rows.addView(actionButton("Continue") {
                context.startActivity(BookImportActivity.intent(context, BookImportActivity.MODE_ACCESS))
                suspendForReader()
            })
        } else {
            val found = foundBooks
            when {
                found == null -> {
                    rows.addView(context.label("Looking for EPUB files…", 14f, SOFT))
                    searchPhone()
                }
                found.isEmpty() -> rows.addView(context.label("No EPUB files found. Try Choose a file if yours is in another app.", 14f, SOFT))
                else -> found.forEach { rows.addView(phoneBookRow(it)) }
            }
            if (found != null) {
                rows.addView(actionButton("Look again") {
                    foundBooks = null
                    render()
                })
            }
        }
        rows.addView(actionButton("Choose a file") {
            context.startActivity(BookImportActivity.intent(context, BookImportActivity.MODE_PICK))
            suspendForReader()
        })
    }

    private fun searchPhone() {
        if (searching) return
        searching = true
        EpubFiles.search(context) { results ->
            searching = false
            foundBooks = results
            if (page == Page.BOOKSELLER && !closing) render()
        }
    }

    private fun phoneBookRow(found: FoundEpub): View {
        val added = store.isImported(found.path)
        val status = when {
            found.isProtected -> "Copy-protected"
            added -> "On your shelf"
            else -> "Add · ${sizeLabel(found.size)}"
        }
        val folder = java.io.File(found.path).parentFile?.name.orEmpty()
        val detail = listOf(found.author, folder).filter(String::isNotEmpty).joinToString(" · ")
        return bookRow(colorFor(found.title), found.title, detail, status, !added) {
            if (found.isProtected) {
                Toast.makeText(context, BookImportActivity.COPY_PROTECTED, Toast.LENGTH_LONG).show()
            } else {
                store.addImported(found.path, found.title, found.author)
                render()
            }
        }
    }

    private fun bookRow(color: Int, title: String, detail: String, status: String, canTap: Boolean, onTap: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            background = rounded(CARD, context.dp(12).toFloat(), context.dp(1), LINE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) }
            addView(View(context).apply {
                background = rounded(color, context.dp(3).toFloat())
            }, LinearLayout.LayoutParams(context.dp(14), context.dp(52)).apply { marginEnd = context.dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(context.label(title, 16f, INK, serif = true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (detail.isNotEmpty()) {
                    addView(context.label(detail, 11.5f, SOFT).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                addView(context.label(status, 12.5f, if (canTap) ACCENT else SOFT, bold = canTap).apply { setPadding(0, context.dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (canTap) tappable(onTap)
        }

    private fun actionButton(text: String, onTap: () -> Unit) = context.label(text, 14f, ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = context.dp(46)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) }
        tappable(onTap)
    }

    private fun spineColor(item: Book) = SPINE_COLORS[item.id] ?: colorFor(item.title)

    /** Your own books get a colour from their title, the same one every time. */
    private fun colorFor(title: String) = OWN_BOOK_COLORS[Math.floorMod(title.hashCode(), OWN_BOOK_COLORS.size)]

    private fun sizeLabel(bytes: Long) =
        if (bytes >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0) else "${bytes / 1000} KB"

    private fun plank() = View(context).apply {
        background = rounded(SOFT, context.dp(3).toFloat())
        elevation = context.dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, context.dp(7))
    }

    private fun tiltOut(view: View, then: () -> Unit) {
        view.animate().translationY(-context.dp(14).toFloat()).rotation(view.rotation - 5f).setDuration(190)
            .withEndAction(then).start()
    }

    // The notebook and the jar

    private fun renderNotebook() {
        card.addView(header(MINE_TITLE) {
            addView(context.iconButton(R.drawable.ic_add, "Add a sentence") { promptSentence() }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
        })
        keepsakeList(store.mySentences(), "Nothing here yet. Tap + to write a sentence of your own.", tilted = false)
    }

    private fun renderJar() {
        card.addView(header("Liked"))
        keepsakeList(store.liked(), "The jar is empty. Long-press a passage and tap the heart to keep it here.", tilted = true)
    }

    private fun keepsakeList(items: List<Sentence>, emptyText: String, tilted: Boolean) {
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, context.dp(6), pad, pad)
            clipChildren = false
            clipToPadding = false
        }
        if (items.isEmpty()) {
            rows.addView(context.label(emptyText, 14f, SOFT).apply { setPadding(context.dp(2), context.dp(12), context.dp(2), context.dp(24)) })
        }
        items.forEachIndexed { index, item ->
            rows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(14), context.dp(12), context.dp(if (tilted) 6 else 14), context.dp(if (tilted) 4 else 12))
                background = rounded(CARD, context.dp(if (tilted) 3 else 12).toFloat(), context.dp(1), LINE)
                if (tilted) rotation = if (index % 2 == 0) -0.9f else 0.9f
                addView(context.label(item.text, 16f, INK, serif = true).apply { setLineSpacing(0f, 1.16f) })
                val source = if (item.bookId == null) "" else sourceLine(item)
                if (tilted) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(context.label(source, 11.5f, SOFT), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(context.iconButton(R.drawable.ic_delete, "Remove from jar", SOFT) {
                            store.toggleLiked(item)
                            render()
                        }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
                    })
                } else if (source.isNotEmpty()) {
                    addView(context.label(source, 11.5f, SOFT).apply { setPadding(0, context.dp(6), 0, 0) })
                }
                tappable {
                    sentence = item
                    returnHome()
                }
                setOnLongClickListener {
                    showPill(item)
                    true
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) })
        }
        card.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
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
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .showInPanel()
    }
}

/** Shared by the panel and the Share screen. */
fun chooseCategory(context: Context, store: Store, present: (AlertDialog) -> Unit, onChosen: (String) -> Unit) {
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
