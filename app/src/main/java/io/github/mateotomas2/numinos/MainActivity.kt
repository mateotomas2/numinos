package io.github.mateotomas2.numinos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** The only regular screen: permissions and choices. Daily use happens in the floating window. */
class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private val store by lazy { Store.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(PAPER)
            addView(content)
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                content.setPadding(dp(22), insets.systemWindowInsetTop + dp(22), dp(22), insets.systemWindowInsetBottom + dp(22))
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (canFloat() && store.symbolVisible) OverlayService.start(this)
    }

    private fun canFloat() = Settings.canDrawOverlays(this)

    private fun notificationsAllowed() =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun render() {
        content.removeAllViews()
        content.addView(label("NuminOS", 30f, INK, serif = true))
        content.addView(body("A symbol that floats above your apps. Tap it for a sentence, your practice videos and a meditation."))

        heading("Set up")
        step("Show over other apps", "Required for the floating symbol. If Android calls this a restricted setting, open NuminOS in App info, tap the ⋮ menu and choose Allow restricted settings.", canFloat()) {
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

        heading("Floating symbol")
        toggle("Show on screen", store.symbolVisible, canFloat()) { on ->
            store.symbolVisible = on
            if (on) OverlayService.start(this) else OverlayService.stop(this)
        }

        heading("Random sentences come from")
        toggle(MINE_TITLE, store.isSourceOn(SOURCE_MINE)) { store.setSourceOn(SOURCE_MINE, it) }
        store.books.forEach { book ->
            toggle("${book.title} (${book.sentences.size})", store.isSourceOn(book.id)) { store.setSourceOn(book.id, it) }
        }

        heading("Add a video")
        content.addView(body("In the YouTube app, tap Share on a video and choose NuminOS. You can also tap Add in the floating window and paste a link."))

        heading("Translations")
        store.books.forEach { content.addView(body("${it.title}: ${it.credit}")) }
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
        @Suppress("DEPRECATION")
        val switch = Switch(this).apply {
            text = title
            isChecked = value
            isEnabled = enabled
            minHeight = dp(48)
            setTextColor(INK)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        content.addView(switch, LinearLayout.LayoutParams(-1, -2))
    }
}
