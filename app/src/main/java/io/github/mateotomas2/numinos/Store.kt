package io.github.mateotomas2.numinos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val SOURCE_MINE = "mine"
const val MINE_TITLE = "My sentences"

data class Book(val id: String, val title: String, val credit: String, val sentences: List<Sentence>)
data class Sentence(val key: String, val text: String, val source: String, val ref: String)
data class Video(val id: String, val title: String, val category: String, val lastUsed: Long)

/** Everything the app remembers. It all stays on the phone. */
class Store private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("numinos", Context.MODE_PRIVATE)

    init {
        Look.symbol = Symbol.of(symbolId)
    }

    val books: List<Book> by lazy {
        app.assets.list("books").orEmpty().sorted().map { file ->
            val json = JSONObject(app.assets.open("books/$file").bufferedReader().use { it.readText() })
            val id = json.getString("id")
            val title = json.getString("title")
            val items = json.getJSONArray("sentences")
            Book(id, title, json.getString("credit"), List(items.length()) { i ->
                val item = items.getJSONObject(i)
                val ref = item.getString("ref")
                Sentence("$id:$ref", item.getString("text"), title, ref)
            })
        }
    }

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
        if (pinnedKey == key) pinnedKey = null
        if (key in likedKeys()) toggleLiked(key)
    }

    fun allSentences(): List<Sentence> = mySentences() + books.flatMap { it.sentences }

    fun isSourceOn(id: String) = id !in prefs.getStringSet("sourcesOff", emptySet()).orEmpty()

    fun setSourceOn(id: String, on: Boolean) {
        val off = prefs.getStringSet("sourcesOff", emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(id) else off.add(id)
        prefs.edit().putStringSet("sourcesOff", off).apply()
    }

    fun randomSentence(excludeKey: String? = null): Sentence? {
        val on = (if (isSourceOn(SOURCE_MINE)) mySentences() else emptyList()) +
            books.filter { isSourceOn(it.id) }.flatMap { it.sentences }
        val pool = on.ifEmpty { allSentences() }
        return pool.filter { it.key != excludeKey }.ifEmpty { pool }.randomOrNull()
    }

    /** The fixed sentence if there is one, otherwise a new random one. */
    fun sentenceToShow(): Sentence? =
        pinnedKey?.let { key -> allSentences().firstOrNull { it.key == key } } ?: randomSentence()

    var pinnedKey: String?
        get() = prefs.getString("pinned", null)
        set(value) = prefs.edit().putString("pinned", value).apply()

    fun likedKeys(): Set<String> = prefs.getStringSet("liked", emptySet()).orEmpty()

    fun toggleLiked(key: String) {
        val liked = likedKeys().toMutableSet()
        if (!liked.add(key)) liked.remove(key)
        prefs.edit().putStringSet("liked", liked).apply()
    }

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

    fun categories(): List<String> = (listOf("Stretching", "Lymphatic", "Qigong") + videos().map { it.category }).distinct()

    /** A line to preview a theme with, from the book that goes with it. */
    fun sampleSentence(symbol: Symbol): Sentence? {
        val book = books.firstOrNull { it.id == symbol.bookId }
        return (book?.sentences ?: books.flatMap { it.sentences }).randomOrNull()
    }

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
        Look.symbol = Symbol.YIN_YANG
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
