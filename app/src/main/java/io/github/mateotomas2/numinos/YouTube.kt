package io.github.mateotomas2.numinos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object YouTube {
    /** In order of preference. The last entry lets Android use any app that can open the link. */
    private val PLAYERS = listOf(
        "org.smarttube.stable",
        "org.smarttube.beta",
        "app.smarttube.fdroid",
        "com.google.android.youtube",
        null,
    )
    private val idPattern = Regex(
        """(?:youtu\.be/|youtube\.com/(?:watch\?(?:[^\s]*&)?v=|shorts/|embed/|live/))([A-Za-z0-9_-]{11})""",
    )
    private val workers = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun videoId(text: String): String? = idPattern.find(text)?.groupValues?.get(1)

    fun open(context: Context, id: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (app in PLAYERS) {
            try {
                context.startActivity(Intent(intent).setPackage(app))
                return true
            } catch (_: ActivityNotFoundException) {
                // Not installed: try the next one.
            }
        }
        Toast.makeText(context, "No app can open this video", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** Looks up and stores a video's title; [done] runs on the main thread only if it succeeds. */
    fun fetchTitle(context: Context, id: String, done: (String) -> Unit = {}) {
        val store = Store.get(context)
        workers.execute {
            try {
                val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                val title = JSONObject(String(get("https://www.youtube.com/oembed?format=json&url=$watch"))).getString("title")
                store.setVideoTitle(id, title)
                main.post { done(title) }
            } catch (_: Exception) {
                // Offline or unavailable: the tile keeps its placeholder and tries again next time.
            }
        }
    }

    /** Fills [view] with the video's thumbnail, keeping a copy so it also shows offline. */
    fun loadThumbnail(context: Context, id: String, view: ImageView) {
        view.tag = id
        val file = File(File(context.cacheDir, "thumbs").apply { mkdirs() }, "$id.jpg")
        workers.execute {
            val bitmap: Bitmap? = try {
                if (!file.exists()) file.writeBytes(get("https://img.youtube.com/vi/$id/mqdefault.jpg"))
                BitmapFactory.decodeFile(file.path)
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) main.post { if (view.tag == id) view.setImageBitmap(bitmap) }
        }
    }
}
