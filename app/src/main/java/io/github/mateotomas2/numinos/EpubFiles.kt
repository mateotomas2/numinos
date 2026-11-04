package io.github.mateotomas2.numinos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Html
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class EpubInfo(val title: String?, val author: String?, val isProtected: Boolean)

data class FoundEpub(val path: String, val title: String, val author: String, val size: Long, val isProtected: Boolean)

/** Finds EPUB files on the phone and reads just enough of each to list it. Nothing leaves the phone. */
object EpubFiles {
    private const val MAX_XML_BYTES = 1_000_000
    private const val MAX_WALKED_FOLDERS = 5_000
    private const val MAX_DEPTH = 7

    /** Encryption these use only hides fonts, which doesn't stop a book being read. */
    private val FONT_ONLY = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun canSearch(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun search(context: Context, done: (List<FoundEpub>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val paths = runCatching { indexedPaths(app) }.getOrDefault(emptySet()) + runCatching { walkedPaths() }.getOrDefault(emptySet())
            val found = paths.mapNotNull { describe(File(it)) }.sortedBy { it.title.lowercase() }
            main.post { done(found) }
        }
    }

    /** Android's file index, which is fast but can miss files. */
    private fun indexedPaths(context: Context): Set<String> {
        val files = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(MediaStore.Files.FileColumns.DATA)
        val paths = mutableSetOf<String>()
        @Suppress("DEPRECATION")
        context.contentResolver.query(files, columns, "${MediaStore.Files.FileColumns.DATA} LIKE ?", arrayOf("%.epub"), null)?.use {
            while (it.moveToNext()) it.getString(0)?.let(paths::add)
        }
        return paths
    }

    /** A bounded walk of normal storage, for files the index hasn't noticed yet. */
    private fun walkedPaths(): Set<String> {
        val paths = mutableSetOf<String>()
        var folders = 0
        fun walk(folder: File, depth: Int) {
            if (depth > MAX_DEPTH || folders++ > MAX_WALKED_FOLDERS) return
            folder.listFiles()?.forEach { file ->
                when {
                    file.name.startsWith(".") -> Unit
                    file.isDirectory -> if (depth > 0 || file.name != "Android") walk(file, depth + 1)
                    file.name.endsWith(".epub", ignoreCase = true) -> paths.add(file.path)
                }
            }
        }
        @Suppress("DEPRECATION")
        walk(Environment.getExternalStorageDirectory(), 0)
        return paths
    }

    private fun describe(file: File): FoundEpub? {
        if (!file.isFile || !file.canRead()) return null
        val info = runCatching { readFile(file) }.getOrNull() ?: return null
        return FoundEpub(file.path, info.title ?: file.nameWithoutExtension, info.author.orEmpty(), file.length(), info.isProtected)
    }

    fun readFile(file: File): EpubInfo = ZipFile(file).use { zip ->
        val wanted = mutableMapOf<String, String>()
        val names = mutableListOf<String>()
        zip.entries().asSequence().take(20_000).forEach { entry ->
            names.add(entry.name)
            if (isWanted(entry.name)) zip.getInputStream(entry).use { wanted[entry.name] = readCapped(it) }
        }
        interpret(names, wanted)
    }

    fun readStream(stream: InputStream): EpubInfo = ZipInputStream(stream).use { zip ->
        val wanted = mutableMapOf<String, String>()
        val names = mutableListOf<String>()
        while (names.size < 20_000) {
            val entry = zip.nextEntry ?: break
            names.add(entry.name)
            if (isWanted(entry.name)) wanted[entry.name] = readCapped(zip)
        }
        interpret(names, wanted)
    }

    private fun isWanted(name: String) = name.endsWith(".opf", ignoreCase = true) || name == "META-INF/encryption.xml"

    /** Reads a limited amount, so a malformed or hostile file can't exhaust the phone's memory. */
    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < MAX_XML_BYTES) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }

    private fun interpret(names: List<String>, wanted: Map<String, String>): EpubInfo {
        val package_ = wanted.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }?.value
        val algorithms = Regex("""Algorithm="([^"]+)"""").findAll(wanted["META-INF/encryption.xml"].orEmpty()).map { it.groupValues[1] }
        return EpubInfo(
            title = package_?.let { element(it, "title") },
            author = package_?.let { element(it, "creator") },
            isProtected = "META-INF/rights.xml" in names || "META-INF/license.lcpl" in names || algorithms.any { it !in FONT_ONLY },
        )
    }

    private fun element(xml: String, name: String): String? =
        Regex("""<(?:\w+:)?$name\b[^>]*>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            ?.takeIf { it.isNotEmpty() }
}
