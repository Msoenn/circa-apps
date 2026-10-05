package org.circa.watchlink

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Minimal round-screen UI: big state, name, last message, details, Start/Stop.
 * Start/Stop also set the persisted "user wants it running" flag that BootReceiver uses for autostart.
 * While this activity is in the foreground the service is asked to add the health FGS type if it is missing
 * (e.g. after a boot start that only got connectedDevice).
 */
class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var name: TextView
    private lateinit var last: TextView
    private lateinit var detail: TextView
    private lateinit var button: Button
    private val ui = Handler(Looper.getMainLooper())
    private var autostart = false

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun text(sp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        // 384 px at density 192 = 320 dp; keep content inside the circle's central square.
        col.setPadding(dp(44f), dp(36f), dp(44f), dp(30f))
        val title = text(13f, 0xFF9E9E9E.toInt(), false)
        title.text = "WatchLink"
        state = text(26f, Color.WHITE, true)
        name = text(14f, 0xFF80CBC4.toInt(), false)
        last = text(13f, 0xFFE0E0E0.toInt(), false)
        last.maxLines = 2
        detail = text(12f, 0xFFBDBDBD.toInt(), false)
        detail.maxLines = 3
        button = Button(this)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        button.isAllCaps = false
        button.setOnClickListener { toggle() }
        col.addView(title)
        col.addView(state)
        col.addView(name)
        col.addView(last)
        col.addView(detail)
        val bp = LinearLayout.LayoutParams(dp(150f), LinearLayout.LayoutParams.WRAP_CONTENT)
        bp.topMargin = dp(6f)
        bp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(button, bp)
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        autostart = intent.getBooleanExtra("autostart", false)
        Log.i(TAG, "UI create autostart=$autostart")
        button.post {
            val xy = IntArray(2)
            button.getLocationOnScreen(xy)
            Log.i(TAG, "UI button center=" + (xy[0] + button.width / 2) + "," + (xy[1] + button.height / 2))
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.getBooleanExtra("autostart", false)) {
            autostart = true
            maybeAutostart()
        }
    }

    private fun missing(): List<String> {
        val m = ArrayList<String>()
        for (p in PERMS) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) m.add(p)
        return m
    }

    override fun onResume() {
        super.onResume()
        refresh.run()
        maybeAutostart()
        if (WatchLinkService.running) {
            startService(Intent(this, WatchLinkService::class.java).setAction(WatchLinkService.ACTION_ENSURE_HEALTH))
        }
    }

    private fun maybeAutostart() {
        if (autostart && !WatchLinkService.running) {
            autostart = false
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun toggle() {
        if (WatchLinkService.running) {
            Log.i(TAG, "UI stop pressed")
            WatchLinkService.setWantRunning(this, false)
            startService(Intent(this, WatchLinkService::class.java).setAction(WatchLinkService.ACTION_STOP))
        } else {
            Log.i(TAG, "UI start pressed")
            start()
        }
    }

    private fun start() {
        WatchLinkService.setWantRunning(this, true)
        val m = missing()
        if (m.isNotEmpty()) {
            Log.i(TAG, "UI requesting permissions $m")
            requestPermissions(m.toTypedArray(), 1)
            return
        }
        Log.i(TAG, "UI starting service (activity in foreground)")
        startForegroundService(
            Intent(this, WatchLinkService::class.java).setAction(WatchLinkService.ACTION_START)
                .putExtra(WatchLinkService.EXTRA_ORIGIN, "activity")
        )
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        val m = missing()
        Log.i(TAG, "UI permission result, still missing $m")
        val btOk = !m.contains("android.permission.BLUETOOTH_CONNECT") && !m.contains("android.permission.BLUETOOTH_ADVERTISE")
        if (btOk) {
            startForegroundService(
                Intent(this, WatchLinkService::class.java).setAction(WatchLinkService.ACTION_START)
                    .putExtra(WatchLinkService.EXTRA_ORIGIN, "activity")
            )
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val on = WatchLinkService.running
            state.text = if (on) WatchLinkService.uiState.uppercase(Locale.getDefault()) else "STOPPED"
            name.text = WatchLinkService.uiName
            last.text = if (on) WatchLinkService.uiLast else ""
            detail.text = if (on) WatchLinkService.uiDetail else ""
            button.text = if (on) "Stop" else "Start"
            ui.postDelayed(this, 1000)
        }
    }

    companion object {
        const val TAG = "WatchLink"
        val PERMS = arrayOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.ACTIVITY_RECOGNITION",
        )
    }
}
