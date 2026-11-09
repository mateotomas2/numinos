package io.github.mateotomas2.numinos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads a book with Readium on a card that looks like the floating window.
 * Readium's reader needs an activity, so this is a transparent screen over your other apps.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: Store
    private lateinit var book: Book
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var marks: List<Locator> = emptyList()
    private var nextMark = -1
    private var returning = false
    private var readingSince = 0L
    private var lastTouch = 0L
    private var readSeconds = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // The book is opened again from scratch, so Android must not try to restore the reader itself.
        super.onCreate(null)
        store = Store.get(this)
        store.applyLook()
        val found = store.book(intent.getStringExtra(EXTRA_BOOK))
        if (found == null || !store.isDownloaded(found)) {
            goBack()
            return
        }
        book = found
        val container = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(cardAround(container))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        scope.launch { open(container.id) }
    }

    private fun goBack() {
        returning = true
        OverlayService.returnFromReader(this)
        finish()
    }

    private fun cardAround(content: View): View {
        val metrics = resources.displayMetrics
        val width = min(metrics.widthPixels - dp(24), dp(560))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PAPER, dp(26).toFloat())
            elevation = dp(14).toFloat()
            clipToOutline = true
            isClickable = true // Touches on the card must not reach the scrim and close it.
            addView(label(book.title, 13f, SOFT, serif = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(16), dp(10), dp(16), dp(4))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls(), LinearLayout.LayoutParams(-1, -2))
        }
        return FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(width, (metrics.heightPixels * 0.86f).toInt(), Gravity.CENTER))
        }
    }

    /** All the reader's buttons sit at the bottom, within reach of one hand. */
    private fun controls() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(6))
        background = rounded(CARD, 0f)
        elevation = dp(4).toFloat()
        addView(iconButton(R.drawable.ic_back, "Back") { goBack() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(View(this@ReaderActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(textButton("A−", "Smaller text", -0.1), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(textButton("A+", "Larger text", 0.1), LinearLayout.LayoutParams(dp(48), dp(48)))
        if (book.marks.isNotEmpty()) {
            addView(iconButton(R.drawable.ic_mark, "Next marked passage", SOFT) { jumpToNextMark() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addView(iconButton(R.drawable.ic_list, "Contents", SOFT) { showContents() }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun textButton(name: String, description: String, change: Double) = label(name, 15f, INK, serif = true).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        tappable {
            store.readerFontScale = ((store.readerFontScale + change).coerceIn(0.7, 2.2) * 10).roundToInt() / 10.0
            navigator?.submitPreferences(preferences())
        }
    }

    private fun preferences() = EpubPreferences(
        scroll = true,
        fontSize = store.readerFontScale,
        theme = if (Look.dark) Theme.DARK else Theme.LIGHT,
        backgroundColor = ReadiumColor(PAPER),
        textColor = ReadiumColor(INK),
    )

    private fun locator(json: String?): Locator? = json?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

    private suspend fun open(containerId: Int) {
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(contentResolver, http)
        val source = book.path?.takeIf { it.startsWith("content:") }?.let { Uri.parse(it).toAbsoluteUrl() }
            ?: store.bookFile(book).toUrl(isDirectory = false)
        val asset = retriever.retrieve(source).getOrElse { return fail(it.message) }
        val opener = PublicationOpener(DefaultPublicationParser(this, http, retriever, null))
        val opened = opener.open(asset, allowUserInteraction = false).getOrElse { return fail(it.message) }
        publication = opened
        marks = book.marks.mapNotNull { locator(it.locator) }

        val start = locator(intent.getStringExtra(EXTRA_LOCATOR)) ?: locator(store.place(book.id))
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(opened).createFragmentFactory(
            initialLocator = start,
            initialPreferences = preferences(),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit
            },
            configuration = EpubNavigatorFragment.Configuration {
                selectionActionModeCallback = selectionMenu
            },
        )
        supportFragmentManager.beginTransaction()
            .add(containerId, EpubNavigatorFragment::class.java, Bundle(), "reader")
            .commitNow()
        val reader = supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
        navigator = reader
        reader.applyDecorations(
            marks.mapIndexed { index, mark -> Decoration("mark$index", mark, Decoration.Style.Underline(ACCENT)) },
            "marks",
        )
    }

    private fun fail(message: String?) {
        Toast.makeText(this, "Couldn't open this book: $message", Toast.LENGTH_LONG).show()
        goBack()
    }

    /** Follows the pencil marks, starting from the current chapter and looping at the end of the book. */
    private fun jumpToNextMark() {
        val reader = navigator ?: return
        val opened = publication ?: return
        if (marks.isEmpty()) return
        if (nextMark < 0) {
            val chapters = opened.readingOrder.map { it.href.toString() }
            val here = chapters.indexOf(reader.currentLocator.value.href.toString())
            val ordered = marks.indices.sortedBy { chapters.indexOf(marks[it].href.toString()) }
            marks = ordered.map { marks[it] }
            nextMark = marks.indexOfFirst { chapters.indexOf(it.href.toString()) >= here }.coerceAtLeast(0)
        }
        reader.go(marks[nextMark], animated = true)
        nextMark = (nextMark + 1) % marks.size
    }

    private fun showContents() {
        val opened = publication ?: return
        val entries = mutableListOf<Link>()
        fun collect(links: List<Link>) {
            links.forEach {
                entries.add(it)
                collect(it.children)
            }
        }
        collect(opened.tableOfContents.ifEmpty { opened.readingOrder })
        dialogBuilder()
            .setTitle(book.title)
            .setItems(entries.map { it.title ?: it.href.toString() }.toTypedArray()) { _, which ->
                opened.locatorFromLink(entries[which])?.let { navigator?.go(it) }
                nextMark = -1
            }
            .show()
    }

    /** Long-pressing selects the exact words, then this menu acts on them. */
    private val selectionMenu = object : BaseActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_LIKE, Menu.NONE, "Like")
            menu.add(Menu.NONE, MENU_FIX, Menu.NONE, "Fix")
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val id = item.itemId
            if (id != MENU_LIKE && id != MENU_FIX) return false
            scope.launch {
                navigator?.currentSelection()?.locator?.let { place ->
                    val words = place.text.highlight?.trim().orEmpty()
                    if (words.isNotEmpty()) keep(id, words, place)
                }
                navigator?.clearSelection()
                mode.finish()
            }
            return true
        }
    }

    private fun keep(menuId: Int, words: String, place: Locator) {
        val chapter = navigator?.currentLocator?.value?.title ?: place.title.orEmpty()
        val sentence = Sentence(
            "${book.id}:s:${place.href}:${words.hashCode()}", words, book.title, chapter, book.id, place.toJSON().toString(),
        )
        if (menuId == MENU_LIKE) {
            if (!store.isLiked(sentence.key)) store.toggleLiked(sentence)
            Toast.makeText(this, "Added to the jar", Toast.LENGTH_SHORT).show()
        } else {
            store.setPinned(sentence)
            Toast.makeText(this, "Fixed on top", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        OverlayService.setReaderOpen(this, true)
    }

    override fun onResume() {
        super.onResume()
        readingSince = System.currentTimeMillis()
        lastTouch = readingSince
        readSeconds = 0
    }

    /** Time counts up to [IDLE_MS] after each touch or key, so a book left open doesn't count. */
    private fun noteReading() {
        val now = System.currentTimeMillis()
        readSeconds += minOf(now - lastTouch, IDLE_MS) / 1000
        lastTouch = now
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        noteReading()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteReading()
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        noteReading()
        if (readSeconds > 0) store.recordPractice(Pillar.MIND, readingSince, readSeconds)
        readSeconds = 0
        super.onPause()
    }

    override fun onStop() {
        navigator?.let { store.setPlace(book.id, it.currentLocator.value.toJSON().toString()) }
        super.onStop()
        // Home or tapping outside closes everything, while Back has already asked to return.
        if (!returning) OverlayService.setReaderOpen(this, false)
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        scope.cancel()
        publication?.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_BOOK = "book"
        private const val IDLE_MS = 3 * 60 * 1000L
        private const val EXTRA_LOCATOR = "locator"
        private const val MENU_LIKE = 1001
        private const val MENU_FIX = 1002

        fun intent(context: Context, bookId: String, locator: String?) = Intent(context, ReaderActivity::class.java)
            .putExtra(EXTRA_BOOK, bookId)
            .putExtra(EXTRA_LOCATOR, locator)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
