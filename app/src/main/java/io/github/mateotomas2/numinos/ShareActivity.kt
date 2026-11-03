package io.github.mateotomas2.numinos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives what you share from other apps: a YouTube video, or text to keep as a sentence. */
class ShareActivity : Activity() {
    private var openDialogs = 0

    /** Shows a dialog and closes this invisible screen once the last one is gone. */
    private fun present(dialog: AlertDialog) {
        openDialogs++
        dialog.setOnDismissListener { if (--openDialogs == 0) finish() }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store.get(this)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val videoId = YouTube.videoId(text)
        when {
            videoId != null -> chooseCategory(this, store, ::present) { category ->
                store.addVideo(videoId, category)
                YouTube.fetchTitle(applicationContext, videoId)
                Toast.makeText(applicationContext, "Added to $category", Toast.LENGTH_SHORT).show()
            }
            text.isNotEmpty() -> present(
                dialogBuilder()
                    .setTitle("Keep this as a sentence?")
                    .setMessage(text)
                    .setPositiveButton("Keep") { _, _ -> store.addMine(text) }
                    .setNegativeButton("Cancel", null)
                    .create(),
            )
            else -> finish()
        }
    }
}
