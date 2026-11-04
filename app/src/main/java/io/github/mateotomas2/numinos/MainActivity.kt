package io.github.mateotomas2.numinos

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.transition.Fade
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** First-run welcome, then settings. Daily use happens in the floating window. */
class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var store: Store
    private var welcomeStep = 0
    private var breath: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        store.applyLook()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                content.setPadding(dp(22), insets.systemWindowInsetTop + dp(22), dp(22), insets.systemWindowInsetBottom + dp(22))
                insets
            }
        }
        setContentView(scroll)
        paintWindow()
    }

    override fun onResume() {
        super.onResume()
        store.applyLook()
        paintWindow()
        render()
        if (store.onboarded && canFloat() && store.symbolVisible) OverlayService.start(this)
    }

    override fun onDestroy() {
        breath?.cancel()
        super.onDestroy()
    }

    private fun canFloat() = Settings.canDrawOverlays(this)

    private fun notificationsAllowed() =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Suppress("DEPRECATION")
    private fun paintWindow() {
        scroll.setBackgroundColor(PAPER)
        window.setBackgroundDrawable(ColorDrawable(PAPER))
        window.statusBarColor = PAPER
        window.navigationBarColor = PAPER
        window.decorView.systemUiVisibility = if (Look.dark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun render() {
        breath?.cancel()
        content.removeAllViews()
        when {
            store.onboarded -> renderSettings()
            welcomeStep == 0 -> renderChooseTheme()
            else -> renderPermissions()
        }
    }

    private fun chooseTheme(symbol: Symbol) {
        val from = PAPER
        store.symbolId = symbol.name
        paintWindow()
        scroll.setBackgroundColor(from)
        ObjectAnimator.ofArgb(scroll, "backgroundColor", from, PAPER).setDuration(450).start()
        TransitionManager.beginDelayedTransition(content, Fade().setDuration(300))
        render()
        if (store.onboarded) OverlayService.refresh(this)
    }

    private fun chooseDarkMode(mode: String) {
        store.darkMode = mode
        paintWindow()
        TransitionManager.beginDelayedTransition(content, Fade().setDuration(300))
        render()
        OverlayService.refresh(this)
    }

    // Welcome, step 1: the theme

    private fun renderChooseTheme() {
        content.gravity = Gravity.CENTER_HORIZONTAL
        val symbol = Look.symbol
        content.addView(label("Choose what stays with you", 27f, INK, serif = true).apply { gravity = Gravity.CENTER })
        addBreathingSymbol(symbol)
        val line = store.sampleSentence(symbol)
        content.addView(label(line?.text.orEmpty(), 18f, INK, serif = true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            minLines = 4
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(label(line?.let { "${it.source} · ${it.ref}" }.orEmpty(), 12.5f, SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        })
        content.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        content.addView(themeRow(), LinearLayout.LayoutParams(-1, -2))
        content.addView(primaryButton("Continue", true) {
            welcomeStep = 1
            TransitionManager.beginDelayedTransition(content, Fade().setDuration(300))
            render()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
    }

    private fun addBreathingSymbol(symbol: Symbol) {
        val mark = SymbolView(this, symbol)
        content.addView(FrameLayout(this).apply {
            addView(mark, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, dp(190)))
        breath = ValueAnimator.ofFloat(0.9f, 1.1f).apply {
            duration = 3600
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
    }

    private fun themeRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        Symbol.entries.forEach { symbol ->
            val selected = symbol == Look.symbol
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                contentDescription = symbol.title
                addView(SymbolView(this@MainActivity, symbol).apply {
                    alpha = if (selected) 1f else 0.62f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(if (selected) 52 else 44), dp(if (selected) 52 else 44)))
                addView(label(symbol.title, 12f, if (selected) INK else SOFT, bold = selected).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(6), 0, 0)
                })
                tappable { if (!selected) chooseTheme(symbol) }
            }, LinearLayout.LayoutParams(0, dp(92), 1f))
        }
    }

    private fun primaryButton(text: String, enabled: Boolean, action: () -> Unit) =
        label(text, 16f, PAPER, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            background = rounded(INK, dp(16).toFloat())
            alpha = if (enabled) 1f else 0.4f
            if (enabled) tappable(action)
        }

    // Welcome, step 2: the permissions

    private fun renderPermissions() {
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(FrameLayout(this).apply {
            addView(SymbolView(this@MainActivity, Look.symbol), FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, dp(112)))
        content.addView(label("Let it float", 27f, INK, serif = true).apply { gravity = Gravity.CENTER })
        content.addView(body("Your symbol needs Android's permission to stay above your other apps.").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        })
        addSetupSteps()
        content.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        content.addView(primaryButton("Begin", canFloat()) {
            store.useBookOf(Look.symbol)
            store.onboarded = true
            store.symbolVisible = true
            BookLoader.fetchShelf(this)
            OverlayService.start(this)
            finish()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
    }

    private fun addSetupSteps() {
        step("Show over other apps", "Required. If Android calls this a restricted setting, open NuminOS in App info, tap the ⋮ menu and choose Allow restricted settings.", canFloat()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            step("Notifications", "Lets you hide the symbol or end a meditation from the notification.", notificationsAllowed()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        step("Keep it running", "Stops the phone from closing the symbol to save battery. Choose No restrictions or Unrestricted for NuminOS.", batteryExempt()) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // Settings

    private fun renderSettings() {
        content.gravity = Gravity.TOP
        content.addView(label("Settings", 30f, INK, serif = true))

        heading("Theme")
        content.addView(themeRow(), LinearLayout.LayoutParams(-1, -2))

        heading("Light or dark")
        content.addView(LinearLayout(this).apply {
            listOf("System" to DARK_MODE_SYSTEM, "Light" to DARK_MODE_LIGHT, "Dark" to DARK_MODE_DARK).forEach { (name, mode) ->
                addView(chip(name, store.darkMode == mode) { chooseDarkMode(mode) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
        })

        heading("Floating symbol")
        toggle("Show on screen", store.symbolVisible, canFloat()) { on ->
            store.symbolVisible = on
            if (on) OverlayService.start(this) else OverlayService.stop(this)
        }
        toggle("Hide when an app is full screen", store.bubbleHidesInFullScreen) {
            store.bubbleHidesInFullScreen = it
            OverlayService.refresh(this)
        }
        slider("Size", store.bubbleSizeDp, 8, 64, { "$it dp" }) {
            store.bubbleSizeDp = it
            OverlayService.refresh(this)
        }
        slider("Opacity", store.bubbleOpacity, 20, 100, { "$it%" }) {
            store.bubbleOpacity = it
            OverlayService.refresh(this)
        }
        slider("Offset from the edge", store.bubbleOffsetDp, -27, 24, ::offsetLabel) {
            store.bubbleOffsetDp = it
            OverlayService.refresh(this)
        }

        heading("During a sit")
        toggle("Let the screen sleep", store.sitScreenSleeps) { store.sitScreenSleeps = it }

        heading("Permissions")
        addSetupSteps()

        heading("Random sentences come from")
        toggle(MINE_TITLE, store.isSourceOn(SOURCE_MINE)) { store.setSourceOn(SOURCE_MINE, it) }
        toggle("Liked passages", store.isSourceOn(SOURCE_JAR)) { store.setSourceOn(SOURCE_JAR, it) }
        store.shelfBooks().filter { it.marks.isNotEmpty() }.forEach { book ->
            toggle("${book.title} (${book.marks.size} marked passages)", store.isSourceOn(book.id)) { store.setSourceOn(book.id, it) }
        }
        val hidden = store.hiddenKeys().size
        if (hidden > 0) {
            content.addView(label("Show $hidden hidden passages again", 14f, ACCENT, bold = true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                tappable {
                    store.restoreHidden()
                    render()
                }
            })
        }

        heading("Add a video")
        content.addView(body("In the YouTube or SmartTube app, tap Share on a video and choose NuminOS. You can also tap + in the floating window and paste a link. Videos open in SmartTube if it's installed, otherwise in YouTube."))

        heading("Translations")
        store.books.forEach { content.addView(body("${it.title}: ${it.credit}")) }

        heading("Start over")
        content.addView(label("Delete everything", 15f, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = rounded(CARD, dp(14).toFloat(), dp(1), LINE)
            tappable(::confirmReset)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun offsetLabel(value: Int) = when {
        value < 0 -> "$value dp, tucked behind the edge"
        value == 0 -> "Touching the edge"
        else -> "+$value dp, away from the edge"
    }

    private fun confirmReset() {
        dialogBuilder()
            .setTitle("Delete everything?")
            .setMessage("This erases your sentences, videos, likes and settings, then returns to the first screen. It can't be undone.")
            .setPositiveButton("Delete everything") { _, _ ->
                OverlayService.stop(this)
                store.reset()
                welcomeStep = 0
                paintWindow()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun heading(text: String) {
        content.addView(label(text.uppercase(), 12f, SOFT, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(24), 0, dp(8))
        })
    }

    private fun body(text: String): TextView = label(text, 14f, SOFT).apply {
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(6), 0, 0)
    }

    private fun step(title: String, detail: String, done: Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, dp(14).toFloat(), dp(1), LINE)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 15f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(if (done) "Done" else "Set up", 13f, if (done) ACCENT else INK, bold = true))
            })
            if (!done) addView(body(detail))
        }
        if (!done) row.tappable(action)
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun toggle(title: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        @Suppress("DEPRECATION")
        val switch = Switch(this).apply {
            text = title
            isChecked = value
            isEnabled = enabled
            minHeight = dp(48)
            setTextColor(INK)
            thumbTintList = ColorStateList(states, intArrayOf(ACCENT, SOFT))
            trackTintList = ColorStateList(states, intArrayOf(ACCENT, LINE))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        content.addView(switch, LinearLayout.LayoutParams(-1, -2))
    }

    private fun slider(title: String, value: Int, min: Int, max: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val shown = label(format(value), 13f, SOFT)
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(label(title, 14f, INK), LinearLayout.LayoutParams(0, -2, 1f))
            addView(shown)
        })
        content.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        shown.text = format(progress + min)
                        onChange(progress + min)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(40)))
    }
}
