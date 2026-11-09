package io.github.mateotomas2.numinos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Opens a video in SmartTube or YouTube and waits invisibly behind it. If the player's Back comes
 * back here, the time in between is the practice. If it doesn't, the video stays at 0 minutes,
 * which shows the measure didn't work that time.
 */
class VideoActivity : Activity() {
    private var startedAt = 0L
    private var left = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latest?.get()?.takeIf { it !== this }?.finish() // An older video waiting behind can no longer be measured.
        latest = WeakReference(this)
        if (savedInstanceState != null) {
            startedAt = savedInstanceState.getLong(STATE_STARTED)
            left = savedInstanceState.getBoolean(STATE_LEFT)
            return
        }
        startedAt = System.currentTimeMillis()
        if (!YouTube.open(this, intent.getStringExtra(EXTRA_VIDEO).orEmpty())) {
            finish()
            return
        }
        Store.get(this).recordPractice(Pillar.BODY, startedAt, 0)
        OverlayService.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_STARTED, startedAt)
        outState.putBoolean(STATE_LEFT, left)
    }

    override fun onStop() {
        super.onStop()
        left = true
    }

    override fun onResume() {
        super.onResume()
        if (!left) return
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        if (seconds in 1..MAX_SECONDS) Store.get(this).setPracticeSeconds(Pillar.BODY, startedAt, seconds)
        finish()
    }

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val STATE_STARTED = "startedAt"
        private const val STATE_LEFT = "left"

        /** Longer than this, it's more likely we were found again much later than a real session. */
        private const val MAX_SECONDS = 3 * 60 * 60L
        private var latest: WeakReference<VideoActivity>? = null

        fun open(context: Context, videoId: String) {
            context.startActivity(
                Intent(context, VideoActivity::class.java)
                    .putExtra(EXTRA_VIDEO, videoId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
