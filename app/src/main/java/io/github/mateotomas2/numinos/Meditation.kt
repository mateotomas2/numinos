package io.github.mateotomas2.numinos

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** One sit in front of the altar: a bell, the time, then a closing bell. With 0 minutes it counts up until ended. */
class Meditation(
    private val context: Context,
    private val windows: WindowManager,
    private val store: Store,
    minutes: Int,
    private val onDone: () -> Unit,
) {
    val untimed = minutes == 0
    val startedAtElapsed = SystemClock.elapsedRealtime()
    val endsAtElapsed = startedAtElapsed + (if (untimed) UNTIMED_LIMIT_MINUTES else minutes) * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "numinos:meditation")
    private val altar = AltarView(context, Symbol.of(store.symbolId)).apply {
        setBackdrop(store.altarBackdrop)
        store.altar().forEach { item ->
            val shown = add(item)
            if (item.kind.sound != null) shown?.setOnClickListener { strike(item) }
        }
    }
    private val countdown = context.label("", 30f, 0xFFF6F1E7.toInt(), serif = true).apply {
        gravity = Gravity.CENTER
        alpha = 0f
    }
    private val hint = context.label("Tap to end", 14f, 0xFFD9D2C3.toInt()).apply {
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
            showTime(if (untimed) elapsedClock() else clock((left + 999) / 1000))
            handler.postDelayed(this, 250)
        }
    }

    fun start() {
        wakeLock.acquire(endsAtElapsed - SystemClock.elapsedRealtime() + 30_000)
        context.playRing(R.raw.bell)
        val view = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onTap()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            alpha = 0f
            setOnClickListener { onTap() }
        }
        view.onBackGesture { onTap() }
        view.addView(altar, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(countdown)
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(6) })
        }
        view.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dp(48)
        })
        val keepAwake = if (store.sitScreenSleeps) 0 else WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        windows.addView(view, wholeScreenParams(keepAwake).apply { if (!store.sitScreenSleeps) screenBrightness = DIM_BRIGHTNESS })
        view.hideSystemBars()
        root = view
        view.animate().alpha(1f).setDuration(900).start()
        breath = ValueAnimator.ofFloat(0.95f, 1.05f).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                altar.breathe(it.animatedValue as Float)
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
            // The first tap only reveals the time and the option, so a stray touch can't end the sit.
            endArmed = true
            countdown.animate().alpha(1f).setDuration(250).start()
            hint.animate().alpha(1f).setDuration(250).start()
            handler.postDelayed({
                endArmed = false
                countdown.animate().alpha(0f).setDuration(600).start()
                hint.animate().alpha(0f).setDuration(600).start()
            }, 4000)
        }
    }

    fun finish(completed: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (!completed && !untimed) {
            close()
            return
        }
        context.playRing(R.raw.bell)
        showTime(if (untimed) elapsedClock() else "0:00")
        hint.text = "Complete"
        countdown.animate().alpha(1f).setDuration(600).start()
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

    private fun elapsedClock() = clock((SystemClock.elapsedRealtime() - startedAtElapsed) / 1000)

    private fun clock(seconds: Long) = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

    private fun showTime(time: String) {
        countdown.text = time
        altar.timerText = time
    }

    private companion object {
        /** A forgotten sit with no timer ends by itself, so it can't hold the wake lock for good. */
        const val UNTIMED_LIMIT_MINUTES = 120
        const val DIM_BRIGHTNESS = 0.08f
    }
}
