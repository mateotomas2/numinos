package io.github.mateotomas2.numinos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** One sit: a bell, a breathing symbol and a countdown, then a closing bell. */
class Meditation(
    private val context: Context,
    private val windows: WindowManager,
    symbol: Symbol,
    minutes: Int,
    private val onDone: () -> Unit,
) {
    val endsAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "numinos:meditation")
    private val mark = SymbolView(context, symbol).apply {
        discColor = NIGHT
        markColor = 0xFFD9D2C3.toInt()
    }
    private val countdown = context.label("", 44f, 0xFFF6F1E7.toInt(), serif = true).apply { gravity = Gravity.CENTER }
    private val hint = context.label("Tap to end", 14f, 0xFF8F887B.toInt()).apply {
        gravity = Gravity.CENTER
        alpha = 0f
    }
    private var breath: ValueAnimator? = null
    private var root: View? = null
    private var finished = false
    private var endArmed = false

    private val tick = object : Runnable {
        override fun run() {
            val left = endsAtElapsed - SystemClock.elapsedRealtime()
            if (left <= 0) {
                finish(completed = true)
                return
            }
            val seconds = (left + 999) / 1000
            countdown.text = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
            handler.postDelayed(this, 250)
        }
    }

    fun start() {
        wakeLock.acquire(endsAtElapsed - SystemClock.elapsedRealtime() + 30_000)
        ring()
        val view = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onTap()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(NIGHT)
            alpha = 0f
            setOnClickListener { onTap() }
        }
        view.onBackGesture { onTap() }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(mark, LinearLayout.LayoutParams(context.dp(150), context.dp(150)))
            addView(countdown, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(44) })
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12) })
        }
        view.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        windows.addView(
            view,
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ),
        )
        root = view
        view.animate().alpha(1f).setDuration(900).start()
        breath = ValueAnimator.ofFloat(0.82f, 1.12f).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                mark.scaleX = scale
                mark.scaleY = scale
            }
            start()
        }
        tick.run()
    }

    private fun onTap() {
        if (finished) {
            close()
        } else if (endArmed) {
            finish(completed = false)
        } else {
            // The first tap only reveals the option, so a stray touch can't end the sit.
            endArmed = true
            hint.animate().alpha(1f).setDuration(250).start()
            handler.postDelayed({
                endArmed = false
                hint.animate().alpha(0f).setDuration(600).start()
            }, 4000)
        }
    }

    fun finish(completed: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (!completed) {
            close()
            return
        }
        ring()
        countdown.text = "0:00"
        hint.text = "Complete"
        hint.animate().alpha(1f).setDuration(600).start()
        handler.postDelayed({ close() }, 7000)
    }

    private fun close() {
        handler.removeCallbacksAndMessages(null)
        val view = root
        if (view == null) {
            release()
            onDone()
            return
        }
        view.animate().alpha(0f).setDuration(700).withEndAction {
            release()
            onDone()
        }.start()
    }

    fun release() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        breath?.cancel()
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun ring() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        MediaPlayer.create(context, R.raw.bell, attributes, audio.generateAudioSessionId())?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}
