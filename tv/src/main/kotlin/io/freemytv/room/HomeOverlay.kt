package io.freemytv.room

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.anannt.roomtv.protocol.NextSessionRules
import io.anannt.roomtv.protocol.SessionDay
import io.freemytv.RoomText
import java.lang.ref.WeakReference
import java.util.TimeZone

/**
 * The "Next session: Maths at 15:30" line on the home screen. A plain TextView laid over the home screen's own canvas
 * along its bottom edge (it never takes focus or a key), filled from [HomeCardFile] every few seconds while the home
 * screen is on show and hidden once the start time has passed. Called by name from HomeActivity (Java), in the :home
 * process, so it reads the file the server writes in the main process.
 */
object HomeOverlay {
    private const val POLL_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private var host: WeakReference<Activity>? = null
    private var line: TextView? = null
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    @JvmStatic
    fun attach(a: Activity) {
        if (host?.get() !== a || line?.parent == null) {
            val t = TextView(a).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xE6FFFFFF.toInt())
                setShadowLayer(6f, 0f, 2f, 0xB3000000.toInt())
                maxLines = 1
                isFocusable = false
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.GONE
            }
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            lp.bottomMargin = (10 * a.resources.displayMetrics.density).toInt()
            a.addContentView(t, lp)
            host = WeakReference(a)
            line = t
        }
        handler.removeCallbacks(poll)
        poll.run()
    }

    @JvmStatic
    fun detach(a: Activity) {
        if (host?.get() === a) handler.removeCallbacks(poll)
    }

    private fun refresh() {
        val a = host?.get() ?: return
        val t = line ?: return
        val card = HomeCardFile.read(a)
        val now = System.currentTimeMillis()
        if (card == null || card.nextTitle.isEmpty() || card.nextStartMs <= now) {
            t.visibility = View.GONE
            return
        }
        val day = when (NextSessionRules.dayOf(card.nextStartMs, now, TimeZone.getDefault())) {
            SessionDay.TODAY -> 0
            SessionDay.TOMORROW -> 1
            SessionDay.LATER -> 2
        }
        t.text = RoomText.nextSession(card.nextTitle, card.nextStartMs, day)
        t.visibility = View.VISIBLE
    }
}
