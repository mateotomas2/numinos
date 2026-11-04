package io.github.mateotomas2.numinos

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/** Keeps the floating symbol on screen and hosts the panel and the meditation. */
class OverlayService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var store: Store
    private var bubble: SymbolView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: Panel? = null
    private var meditation: Meditation? = null
    private var readerOpen = false

    /** Android announces the Home and Recents gestures this way; the window closes for them. */
    private val homeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            panel?.close()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        store = Store.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating symbol", NotificationManager.IMPORTANCE_MIN),
        )
        val closeFilter = IntentFilter(ACTION_SYSTEM_CLOSE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(homeReceiver, closeFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(homeReceiver, closeFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(meditation?.let { meditationNotification(it) } ?: presenceNotification())
        when (intent?.action) {
            ACTION_HIDE -> {
                store.symbolVisible = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_END -> meditation?.finish(completed = false)
            ACTION_REFRESH -> refreshBubble()
            ACTION_READER_OPEN -> {
                readerOpen = true
                removeBubble()
            }
            ACTION_READER_CLOSED -> {
                readerOpen = false
                panel?.close()
            }
            ACTION_READER_BACK -> {
                readerOpen = false
                panel?.resumeFromReader()
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // If you left a permission screen some other way, the waiting window shouldn't keep the symbol hidden.
        if (intent?.action == null && !readerOpen) panel?.takeIf { it.isSuspended }?.close()
        if (meditation == null && panel == null && !readerOpen && !AltarActivity.isOpen) showBubble()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        store.applyLook()
        bubbleParams?.let {
            it.x = restingX()
            it.y = it.y.coerceIn(0, screenSize().y - it.height)
            bubble?.let { view -> windows.updateViewLayout(view, it) }
        }
        refreshBubble()
    }

    override fun onDestroy() {
        unregisterReceiver(homeReceiver)
        meditation?.release()
        panel?.dismissNow()
        removeBubble()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    // Floating symbol

    private fun bubbleSize() = dp(54)

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        val size = Point()
        @Suppress("DEPRECATION") windows.defaultDisplay.getRealSize(size)
        return size
    }

    private fun idleAlpha() = store.bubbleOpacity / 100f

    private fun restingX(): Int {
        val offset = dp(store.bubbleOffsetDp)
        return if (store.bubbleOnLeft) offset else screenSize().x - bubbleSize() - offset
    }

    /** Applies changed settings straight away, so the sliders work as a live preview. */
    private fun refreshBubble() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        view.animate().cancel()
        view.symbol = Symbol.of(store.symbolId)
        view.alpha = idleAlpha()
        params.x = restingX()
        windows.updateViewLayout(view, params)
    }

    private fun showBubble() {
        if (bubble != null) return
        val params = WindowManager.LayoutParams(
            bubbleSize(), bubbleSize(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = restingX()
            y = store.bubbleY.coerceIn(0, screenSize().y - bubbleSize())
        }
        val view = SymbolView(this, Symbol.of(store.symbolId)).apply {
            alpha = 0f
            scaleX = 0.4f
            scaleY = 0.4f
            elevation = dp(4).toFloat()
        }
        addBubbleTouch(view, params)
        windows.addView(view, params)
        bubble = view
        bubbleParams = params
        view.animate().alpha(idleAlpha()).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windows.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    view.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = originX + dx.toInt()
                        params.y = originY + dy.toInt()
                        windows.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) settle(view, params) else openPanel()
                MotionEvent.ACTION_CANCEL -> settle(view, params)
            }
            true
        }
    }

    private fun settle(view: View, params: WindowManager.LayoutParams) {
        view.alpha = idleAlpha()
        store.bubbleOnLeft = params.x + bubbleSize() / 2 < screenSize().x / 2
        store.bubbleY = params.y
        ValueAnimator.ofInt(params.x, restingX()).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (bubble === view) {
                    params.x = it.animatedValue as Int
                    windows.updateViewLayout(view, params)
                }
            }
        }.start()
    }

    // Panel

    private fun openPanel() {
        store.applyLook()
        val params = bubbleParams ?: return
        val originX = params.x + bubbleSize() / 2
        val originY = params.y + bubbleSize() / 2
        val view = bubble ?: return
        view.animate().rotationBy(180f).alpha(0f).setDuration(220).withEndAction { removeBubble() }.start()
        panel = Panel(this, windows, store, originX, originY, ::onPanelClosed, ::startMeditation).also { it.show() }
    }

    private fun onPanelClosed() {
        panel = null
        if (meditation == null && !readerOpen && !AltarActivity.isOpen) showBubble()
    }

    // Meditation

    private fun startMeditation(minutes: Int) {
        val session = Meditation(this, windows, store, minutes) {
            meditation = null
            startInForeground(presenceNotification())
            showBubble()
        }
        meditation = session
        startInForeground(meditationNotification(session))
        session.start()
    }

    // Notifications

    private fun baseNotification(text: String) = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )

    private fun action(label: String, name: String) = Notification.Action.Builder(
        null, label,
        PendingIntent.getService(this, name.hashCode(), Intent(this, OverlayService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun presenceNotification() = baseNotification("The symbol is on your screen")
        .addAction(action("Hide", ACTION_HIDE))
        .build()

    private fun meditationNotification(session: Meditation) = baseNotification("Meditating")
        .setUsesChronometer(true)
        .setChronometerCountDown(!session.untimed)
        .setWhen(
            System.currentTimeMillis() +
                ((if (session.untimed) session.startedAtElapsed else session.endsAtElapsed) - SystemClock.elapsedRealtime()),
        )
        .addAction(action("End", ACTION_END))
        .build()

    companion object {
        private const val CHANNEL = "presence"
        private const val NOTIFICATION = 1
        private const val ACTION_HIDE = "hide"
        private const val ACTION_END = "end"
        private const val ACTION_REFRESH = "refresh"
        private const val ACTION_READER_OPEN = "reader-open"
        private const val ACTION_READER_CLOSED = "reader-closed"
        private const val ACTION_READER_BACK = "reader-back"
        @Suppress("DEPRECATION")
        private const val ACTION_SYSTEM_CLOSE = Intent.ACTION_CLOSE_SYSTEM_DIALOGS

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun refresh(context: Context) {
            if (Store.get(context).symbolVisible && Settings.canDrawOverlays(context)) {
                context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH))
            }
        }

        /** The symbol steps aside while a book is open on its card. */
        fun setReaderOpen(context: Context, open: Boolean) {
            if (Store.get(context).symbolVisible && Settings.canDrawOverlays(context)) {
                val action = if (open) ACTION_READER_OPEN else ACTION_READER_CLOSED
                context.startForegroundService(Intent(context, OverlayService::class.java).setAction(action))
            }
        }

        /** Back from the reader returns to the floating window as you left it. */
        fun returnFromReader(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_READER_BACK))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

/** Radius a circular reveal needs to cover a [width] x [height] view from a point inside or outside it. */
fun revealRadius(x: Float, y: Float, width: Int, height: Int): Float = maxOf(
    hypot(x, y), hypot(width - x, y), hypot(x, height - y), hypot(width - x, height - y),
)
