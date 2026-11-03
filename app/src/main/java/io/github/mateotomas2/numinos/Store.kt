package io.github.mateotomas2.numinos

import android.content.Context
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val SOURCE_MINE = "mine"
const val SOURCE_JAR = "jar"
const val MINE_TITLE = "My sentences"
const val DARK_MODE_SYSTEM = "system"
const val DARK_MODE_LIGHT = "light"
const val DARK_MODE_DARK = "dark"

data class Book(
    val id: String,
    val title: String,
    val credit: String,
    val shelf: Int,
    val url: String,
    val sha256: String,
    val size: Long,
    val marks: List<Sentence>,
)

/** A line to show: from a book when [bookId] is set, otherwise written by you. [locator] is its place in the book. */
data class Sentence(
    val key: String,
    val text: String,
    val source: String,
    val ref: String,
    val bookId: String? = null,
    val locator: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("text", text).put("source", source).put("ref", ref)
        .put("bookId", bookId).put("locator", locator)

    companion object {
        fun fromJson(json: JSONObject) = Sentence(
            json.getString("key"), json.getString("text"), json.getString("source"), json.getString("ref"),
            json.optString("bookId").ifEmpty { null }, json.optString("locator").ifEmpty { null },
        )
    }
}

data class Video(val id: String, val title: String, val category: String, val lastUsed: Long)

/** Everything the app remembers. It all stays on the phone. */
class Store private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("numinos", Context.MODE_PRIVATE)

    init {
        applyLook()
    }

    /** Works out the theme and light or dark mode in use. Call again when the system setting may have changed. */
    fun applyLook() {
        Look.symbol = Symbol.of(symbolId)
        Look.dark = when (darkMode) {
            DARK_MODE_LIGHT -> false
            DARK_MODE_DARK -> true
            else -> app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** Every book available to add. The catalog ships with the app, and the texts are downloaded. */
    val books: List<Book> by lazy {
        val items = JSONObject(app.assets.open("catalog.json").bufferedReader().use { it.readText() }).getJSONArray("books")
        List(items.length()) { i ->
            val json = items.getJSONObject(i)
            val id = json.getString("id")
            val title = json.getString("title")
            val marks = json.getJSONArray("marks")
            Book(
                id, title, json.getString("credit"), json.getInt("shelf"), json.getString("url"),
                json.getString("sha256"), json.getLong("size"),
                List(marks.length()) { m ->
                    val mark = marks.getJSONObject(m)
                    val highlight = mark.getString("highlight")
                    val href = mark.getString("href")
                    val fragment = mark.getString("fragment")
                    val locator = JSONObject()
                        .put("href", href)
                        .put("type", "application/xhtml+xml")
                        .put("locations", JSONObject().put("fragments", JSONArray().put(fragment)))
                        .put("text", JSONObject().put("highlight", highlight))
                    Sentence("$id:m:$href#$fragment:${highlight.hashCode()}", mark.getString("text"), title, mark.getString("ref"), id, locator.toString())
                },
            )
        }.sortedBy { it.shelf }
    }

    fun book(id: String?): Book? = books.firstOrNull { it.id == id }

    fun bookFile(book: Book) = File(File(app.filesDir, "books"), "${book.id}.epub")

    fun isDownloaded(book: Book) = bookFile(book).exists()

    private fun shelfIds(): Set<String> = prefs.getStringSet("shelf", null) ?: startingShelf(Symbol.of(symbolId))

    private fun startingShelf(symbol: Symbol): Set<String> =
        symbol.bookId?.let { setOf(it) } ?: books.map { it.id }.toSet()

    fun shelfBooks(): List<Book> = shelfIds().let { ids -> books.filter { it.id in ids } }

    fun isOnShelf(book: Book) = book.id in shelfIds()

    fun addToShelf(book: Book) = prefs.edit().putStringSet("shelf", shelfIds() + book.id).apply()

    /** Your liked passages stay in the jar, and your place in the book is kept in case you add it back. */
    fun removeFromShelf(book: Book) {
        prefs.edit().putStringSet("shelf", shelfIds() - book.id).apply()
        bookFile(book).delete()
    }

    /** Where you stopped reading, in Readium's own format. */
    fun place(bookId: String): String? = prefs.getString("place_$bookId", null)

    fun setPlace(bookId: String, locator: String) = prefs.edit().putString("place_$bookId", locator).apply()

    var readerFontScale: Double
        get() = prefs.getFloat("readerFontScale", 1f).toDouble()
        set(value) = prefs.edit().putFloat("readerFontScale", value.toFloat()).apply()

    // Sentences

    @Synchronized
    fun mySentences(): List<Sentence> {
        val items = JSONArray(prefs.getString("mine", "[]"))
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            Sentence("$SOURCE_MINE:${item.getString("id")}", item.getString("text"), MINE_TITLE, "")
        }
    }

    @Synchronized
    fun addMine(text: String): Sentence {
        val id = UUID.randomUUID().toString()
        val items = JSONArray(prefs.getString("mine", "[]"))
        items.put(JSONObject().put("id", id).put("text", text.trim()))
        prefs.edit().putString("mine", items.toString()).apply()
        return Sentence("$SOURCE_MINE:$id", text.trim(), MINE_TITLE, "")
    }

    @Synchronized
    fun removeMine(key: String) {
        val kept = JSONArray()
        val items = JSONArray(prefs.getString("mine", "[]"))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if ("$SOURCE_MINE:${item.getString("id")}" != key) kept.put(item)
        }
        prefs.edit().putString("mine", kept.toString()).apply()
        if (pinned()?.key == key) setPinned(null)
    }

    fun isSourceOn(id: String) = id !in prefs.getStringSet("sourcesOff", emptySet()).orEmpty()

    fun setSourceOn(id: String, on: Boolean) {
        val off = prefs.getStringSet("sourcesOff", emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(id) else off.add(id)
        prefs.edit().putStringSet("sourcesOff", off).apply()
    }

    private fun allMarks() = books.flatMap { it.marks }

    fun randomSentence(excludeKey: String? = null): Sentence? {
        val hidden = hiddenKeys()
        val chosen = (if (isSourceOn(SOURCE_MINE)) mySentences() else emptyList()) +
            (if (isSourceOn(SOURCE_JAR)) liked() else emptyList()) +
            shelfBooks().filter { isSourceOn(it.id) }.flatMap { it.marks }
        val pool = chosen.filter { it.key !in hidden }.distinctBy { it.key }
        return pool.filter { it.key != excludeKey }.ifEmpty { pool }.randomOrNull()
    }

    /** The fixed sentence if there is one, otherwise a new random one. */
    fun sentenceToShow(): Sentence? = pinned() ?: randomSentence()

    fun pinned(): Sentence? = prefs.getString("pinnedItem", null)?.let { Sentence.fromJson(JSONObject(it)) }

    fun setPinned(sentence: Sentence?) =
        prefs.edit().putString("pinnedItem", sentence?.toJson()?.toString()).apply()

    fun liked(): List<Sentence> {
        val items = JSONArray(prefs.getString("jar", "[]"))
        return List(items.length()) { Sentence.fromJson(items.getJSONObject(it)) }
    }

    fun isLiked(key: String) = liked().any { it.key == key }

    @Synchronized
    fun toggleLiked(sentence: Sentence) {
        val kept = liked().filter { it.key != sentence.key }
        val updated = if (kept.size == liked().size) listOf(sentence) + kept else kept
        val items = JSONArray()
        updated.forEach { items.put(it.toJson()) }
        prefs.edit().putString("jar", items.toString()).apply()
    }

    fun hiddenKeys(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty()

    /** "Show less": this passage stays in its book but stops coming up at random. */
    fun hide(key: String) = prefs.edit().putStringSet("hidden", hiddenKeys() + key).apply()

    fun restoreHidden() = prefs.edit().remove("hidden").apply()

    // Videos

    @Synchronized
    fun videos(): List<Video> {
        val items = JSONArray(prefs.getString("videos", "[]"))
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            Video(item.getString("id"), item.getString("title"), item.getString("category"), item.getLong("lastUsed"))
        }.sortedByDescending { it.lastUsed }
    }

    private fun saveVideos(videos: List<Video>) {
        val items = JSONArray()
        videos.forEach {
            items.put(JSONObject().put("id", it.id).put("title", it.title).put("category", it.category).put("lastUsed", it.lastUsed))
        }
        prefs.edit().putString("videos", items.toString()).apply()
    }

    @Synchronized
    fun addVideo(id: String, category: String) {
        val old = videos().firstOrNull { it.id == id }
        saveVideos(videos().filter { it.id != id } + Video(id, old?.title.orEmpty(), category, System.currentTimeMillis()))
    }

    @Synchronized
    fun setVideoTitle(id: String, title: String) = saveVideos(videos().map { if (it.id == id) it.copy(title = title) else it })

    @Synchronized
    fun markVideoUsed(id: String) =
        saveVideos(videos().map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it })

    @Synchronized
    fun removeVideo(id: String) = saveVideos(videos().filter { it.id != id })

    fun categories(): List<String> = videos().map { it.category }.filter(String::isNotEmpty).distinct()

    /** A line to preview a theme with, from the book that goes with it. */
    fun sampleSentence(symbol: Symbol): Sentence? = (book(symbol.bookId)?.marks ?: allMarks()).randomOrNull()

    /** First-run default: the shelf starts with the theme's book, or with all of them for a theme without one. */
    fun useBookOf(symbol: Symbol) {
        prefs.edit().putStringSet("shelf", startingShelf(symbol)).apply()
    }

    /** Erases everything the user added or chose. */
    @Synchronized
    fun reset() {
        prefs.edit().clear().commit()
        File(app.cacheDir, "thumbs").deleteRecursively()
        File(app.filesDir, "books").deleteRecursively()
        applyLook()
    }

    // Small settings

    var symbolId: String
        get() = prefs.getString("symbol", Symbol.YIN_YANG.name) ?: Symbol.YIN_YANG.name
        set(value) {
            prefs.edit().putString("symbol", value).apply()
            Look.symbol = Symbol.of(value)
        }

    var minutes: Int
        get() = prefs.getInt("minutes", 10)
        set(value) = prefs.edit().putInt("minutes", value).apply()

    var bubbleOnLeft: Boolean
        get() = prefs.getBoolean("bubbleOnLeft", false)
        set(value) = prefs.edit().putBoolean("bubbleOnLeft", value).apply()

    var bubbleY: Int
        get() = prefs.getInt("bubbleY", 400)
        set(value) = prefs.edit().putInt("bubbleY", value).apply()

    var darkMode: String
        get() = prefs.getString("darkMode", DARK_MODE_SYSTEM) ?: DARK_MODE_SYSTEM
        set(value) {
            prefs.edit().putString("darkMode", value).apply()
            applyLook()
        }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    var bubbleOpacity: Int
        get() = prefs.getInt("bubbleOpacity", 88)
        set(value) = prefs.edit().putInt("bubbleOpacity", value).apply()

    /** Negative tucks the symbol behind the screen edge; positive moves it away from the edge. */
    var bubbleOffsetDp: Int
        get() = prefs.getInt("bubbleOffsetDp", -18)
        set(value) = prefs.edit().putInt("bubbleOffsetDp", value).apply()

    var symbolVisible: Boolean
        get() = prefs.getBoolean("symbolVisible", true)
        set(value) = prefs.edit().putBoolean("symbolVisible", value).apply()

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context).also { instance = it }
        }
    }
}
