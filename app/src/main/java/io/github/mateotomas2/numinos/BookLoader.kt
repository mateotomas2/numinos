package io.github.mateotomas2.numinos

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Downloads a book's EPUB from GitHub, keeping it only if it matches the fingerprint in the catalog. */
object BookLoader {
    private val workers = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val progress = mutableMapOf<String, Int>()
    private val failed = mutableSetOf<String>()

    /** Called on the main thread whenever a download moves forward, finishes or fails. */
    var listener: (() -> Unit)? = null

    fun progressOf(id: String): Int? = progress[id]

    fun hasFailed(id: String) = id in failed

    fun fetchShelf(context: Context) {
        val store = Store.get(context)
        store.shelfBooks().forEach { if (!store.isDownloaded(it) && it.id !in failed) fetch(context, it) }
    }

    fun fetch(context: Context, book: Book) {
        val store = Store.get(context)
        if (store.isDownloaded(book) || book.id in progress) return
        failed.remove(book.id)
        progress[book.id] = 0
        notifyListener()
        workers.execute {
            val ok = runCatching { download(store.bookFile(book), book) }.getOrDefault(false)
            main.post {
                progress.remove(book.id)
                if (!ok) failed.add(book.id)
                notifyListener()
            }
        }
    }

    private fun notifyListener() {
        listener?.invoke()
    }

    private fun download(target: File, book: Book): Boolean {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(book.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        try {
            var received = 0L
            var shown = 0
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > book.size) return false
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (received * 100 / book.size).toInt()
                        if (percent >= shown + 5) {
                            shown = percent
                            main.post {
                                if (book.id in progress) progress[book.id] = percent
                                notifyListener()
                            }
                        }
                    }
                }
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            return fingerprint == book.sha256 && partial.renameTo(target)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }
}
