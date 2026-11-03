package io.github.mateotomas2.numinos

import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
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
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import kotlin.math.min

private const val MARKED_TEXT =
    "The thirty spokes unite in the one nave; but it is on the empty space (for the axle), that the use of the wheel depends."

/**
 * A test of Readium's EPUB reader inside a transparent screen that looks like the floating window.
 * Readium's reader needs an activity, so this is what lets a book stay on a card over your other apps.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderTestActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: Store
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var mark: Locator? = null
    private var returning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The book is opened again from scratch, so Android must not try to restore the reader itself.
        super.onCreate(null)
        store = Store.get(this)
        store.applyLook()
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
            addView(LinearLayout(this@ReaderTestActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(8), dp(2))
                addView(iconButton(R.drawable.ic_back, "Back") { goBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(label("Reader test", 19f, INK, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_mark, "Go to the marked passage", SOFT) {
                    val target = mark
                    if (target != null) navigator?.go(target, animated = true)
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
            })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(width, (metrics.heightPixels * 0.86f).toInt(), Gravity.CENTER))
        }
    }

    private suspend fun open(containerId: Int) {
        val file = File(cacheDir, "reader-test.epub")
        assets.open("sample/reader-test.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(contentResolver, http)
        val asset = retriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { return fail("retrieve", it.message) }
        val opener = PublicationOpener(DefaultPublicationParser(this, http, retriever, null))
        val opened = opener.open(asset, allowUserInteraction = false).getOrElse { return fail("open", it.message) }
        publication = opened
        val markedChapter = opened.readingOrder.getOrNull(2)
        mark = markedChapter?.let(opened::locatorFromLink)?.copy(text = Locator.Text(highlight = MARKED_TEXT))

        val preferences = EpubPreferences(
            scroll = true,
            theme = if (Look.dark) Theme.DARK else Theme.LIGHT,
            backgroundColor = ReadiumColor(PAPER),
            textColor = ReadiumColor(INK),
        )
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(opened).createFragmentFactory(
            initialLocator = null,
            initialPreferences = preferences,
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
        mark?.let { reader.applyDecorations(listOf(Decoration("mark", it, Decoration.Style.Underline(ACCENT))), "marks") }
    }

    private fun fail(step: String, message: String?) {
        Toast.makeText(this, "Couldn't $step the book: $message", Toast.LENGTH_LONG).show()
        goBack()
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
                val words = navigator?.currentSelection()?.locator?.text?.highlight?.trim().orEmpty()
                if (words.isNotEmpty()) {
                    val sentence = Sentence("sample:${words.hashCode()}", words, "Reader test", "", "sample")
                    if (id == MENU_LIKE) {
                        if (!store.isLiked(sentence.key)) store.toggleLiked(sentence)
                        Toast.makeText(this@ReaderTestActivity, "Added to the jar", Toast.LENGTH_SHORT).show()
                    } else {
                        store.setPinned(sentence)
                        Toast.makeText(this@ReaderTestActivity, "Fixed on top", Toast.LENGTH_SHORT).show()
                    }
                }
                navigator?.clearSelection()
                mode.finish()
            }
            return true
        }
    }

    override fun onStart() {
        super.onStart()
        OverlayService.setReaderOpen(this, true)
    }

    override fun onStop() {
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

    private companion object {
        const val MENU_LIKE = 1001
        const val MENU_FIX = 1002
    }
}
