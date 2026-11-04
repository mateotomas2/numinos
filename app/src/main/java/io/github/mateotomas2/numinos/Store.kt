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
    val chapters: List<String>,
    val marks: List<Sentence>,
)

data class Passage(val label: String, val text: String, val commentary: Boolean)

/** A line to show: from a book when [bookId] is set, otherwise written by you. */
data class Sentence(
    val key: String,
    val text: String,
    val source: String,
    val ref: String,
    val bookId: String? = null,
    val chapter: Int = -1,
    val passage: Int = -1,
    val start: Int = -1,
    val end: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("text", text).put("source", source).put("ref", ref)
        .put("bookId", bookId).put("chapter", chapter).put("passage", passage)
        .put("start", start).put("end", end)

    companion object {
        fun fromJson(json: JSONObject) = Sentence(
            json.getString("key"), json.getString("text"), json.getString("source"), json.getString("ref"),
            json.optString("bookId").ifEmpty { null }, json.optInt("chapter", -1), json.optInt("passage", -1),
            json.optInt("start", -1), json.optInt("end", -1),
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

    val books: List<Book> by lazy {
        app.assets.list("books").orEmpty().map { folder ->
            val json = JSONObject(readAsset("books/$folder/book.json"))
            val id = json.getString("id")
            val title = json.getString("title")
            val chapters = json.getJSONArray("chapters")
            val marks = json.getJSONArray("marks")
            Book(
                id, title, json.getString("credit"), json.getInt("shelf"),
                List(chapters.length()) { chapters.getString(it) },
                List(marks.length()) { i ->
                    val mark = marks.getJSONObject(i)
                    val chapter = mark.getInt("chapter")
                    val passage = mark.getInt("passage")
                    val start = mark.getInt("start")
                    Sentence(
                        "$id:m:$chapter:$passage:$start", mark.getString("text"), title, mark.getString("ref"),
                        id, chapter, passage, start, mark.getInt("end"),
                    )
                },
            )
        }.sortedBy { it.shelf }
    }

    private fun readAsset(path: String) = app.assets.open(path).bufferedReader().use { it.readText() }

    fun book(id: String?): Book? = books.firstOrNull { it.id == id }

    /** One chapter's text, read only when it's opened because the books are large. */
    fun chapter(bookId: String, index: Int): List<Passage> {
        val items = JSONArray(readAsset("books/$bookId/c$index.json"))
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            Passage(item.getString("l"), item.getString("t"), item.optInt("c") == 1)
        }
    }

    /** A whole verse or paragraph as a sentence, for liking or fixing it from the reader. */
    fun passageSentence(book: Book, chapter: Int, index: Int, passage: Passage): Sentence {
        val title = book.chapters[chapter]
        val ref = when {
            passage.label.isEmpty() -> title
            book.id == "bible" -> "$title:${passage.label}"
            passage.label.first().isDigit() -> "$title, verse ${passage.label}"
            else -> "$title, ${passage.label}"
        }
        return Sentence("${book.id}:p:$chapter:$index", passage.text, book.title, ref, book.id, chapter, index)
    }

    fun place(bookId: String): Pair<Int, Int> {
        val parts = prefs.getString("place_$bookId", null)?.split(":") ?: return 0 to 0
        return parts[0].toInt() to parts[1].toInt()
    }

    fun setPlace(bookId: String, chapter: Int, passage: Int) =
        prefs.edit().putString("place_$bookId", "$chapter:$passage").apply()

    var readerTextSp: Int
        get() = prefs.getInt("readerTextSp", 18)
        set(value) = prefs.edit().putInt("readerTextSp", value).apply()

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
            books.filter { isSourceOn(it.id) }.flatMap { it.marks }
        val pool = chosen.filter { it.key !in hidden }.distinctBy { it.key }.ifEmpty { allMarks() }
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
        val videos = List(items.length()) { i ->
            val item = items.getJSONObject(i)
            Video(item.getString("id"), item.getString("title"), item.getString("category"), item.getLong("lastUsed"))
        }
        // Lists saved by older versions have no order of their own until they're next saved.
        return if (prefs.getBoolean("videosOrdered", false)) videos else videos.sortedByDescending { it.lastUsed }
    }

    private fun saveVideos(videos: List<Video>) {
        val items = JSONArray()
        videos.forEach {
            items.put(JSONObject().put("id", it.id).put("title", it.title).put("category", it.category).put("lastUsed", it.lastUsed))
        }
        prefs.edit().putString("videos", items.toString()).putBoolean("videosOrdered", true).apply()
    }

    @Synchronized
    fun addVideo(id: String, category: String) {
        val old = videos().firstOrNull { it.id == id }
        saveVideos(listOf(Video(id, old?.title.orEmpty(), category, System.currentTimeMillis())) + videos().filter { it.id != id })
        videoCategory = category.ifEmpty { null }
        videoScroll = 0
    }

    @Synchronized
    fun setVideoTitle(id: String, title: String) = saveVideos(videos().map { if (it.id == id) it.copy(title = title) else it })

    @Synchronized
    fun setVideoCategory(id: String, category: String) = saveVideos(videos().map { if (it.id == id) it.copy(category = category) else it })

    /** Puts the videos in [order] into the places they already hold, so a filtered list leaves the others where they are. */
    @Synchronized
    fun arrangeVideos(order: List<String>) {
        val all = videos()
        val byId = all.associateBy { it.id }
        val next = order.iterator()
        saveVideos(all.map { if (it.id in order) byId.getValue(next.next()) else it })
    }

    @Synchronized
    fun removeVideo(id: String) = saveVideos(videos().filter { it.id != id })

    fun categories(): List<String> = videos().map { it.category }.filter(String::isNotEmpty).distinct()

    /** A line to preview a theme with, from the book that goes with it. */
    fun sampleSentence(symbol: Symbol): Sentence? = (book(symbol.bookId)?.marks ?: allMarks()).randomOrNull()

    /** First-run default: random sentences come from the theme's book and your own. */
    fun useBookOf(symbol: Symbol) {
        val bookId = symbol.bookId ?: return
        books.forEach { setSourceOn(it.id, it.id == bookId) }
    }

    /** Erases everything the user added or chose. */
    @Synchronized
    fun reset() {
        prefs.edit().clear().commit()
        File(app.cacheDir, "thumbs").deleteRecursively()
        File(app.filesDir, "altar").deleteRecursively()
        applyLook()
    }

    // Small settings

    var symbolId: String
        get() = prefs.getString("symbol", Symbol.YIN_YANG.name) ?: Symbol.YIN_YANG.name
        set(value) {
            prefs.edit().putString("symbol", value).apply()
            Look.symbol = Symbol.of(value)
        }

    /** The altar you arranged, or the theme's ready-made one until you do. */
    fun altar(): List<AltarItem> {
        val saved = prefs.getString("altar", null) ?: return starterAltar(Look.symbol)
        val items = JSONArray(saved)
        return List(items.length()) { AltarItem.fromJson(items.getJSONObject(it)) }
    }

    fun saveAltar(items: List<AltarItem>) {
        val saved = JSONArray()
        items.forEach { saved.put(it.toJson()) }
        prefs.edit().putString("altar", saved.toString()).apply()
    }

    var altarBackdrop: AltarBackground
        get() = AltarBackground.of(prefs.getString("altarBackdrop", null))
        set(value) = prefs.edit().putString("altarBackdrop", value.name).apply()

    var sitScreenSleeps: Boolean
        get() = prefs.getBoolean("sitScreenSleeps", false)
        set(value) = prefs.edit().putBoolean("sitScreenSleeps", value).apply()

    var minutes: Int
        get() = prefs.getInt("minutes", 10)
        set(value) = prefs.edit().putInt("minutes", value).apply()

    /** The category last chosen in the floating window; null shows them all. */
    var videoCategory: String?
        get() = prefs.getString("videoCategory", null)
        set(value) = prefs.edit().putString("videoCategory", value).apply()

    var videoScroll: Int
        get() = prefs.getInt("videoScroll", 0)
        set(value) = prefs.edit().putInt("videoScroll", value).apply()

    var chipScroll: Int
        get() = prefs.getInt("chipScroll", 0)
        set(value) = prefs.edit().putInt("chipScroll", value).apply()

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
