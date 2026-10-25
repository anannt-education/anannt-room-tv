package io.freemytv.room

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import io.anannt.roomtv.protocol.YtKind
import io.anannt.roomtv.protocol.YtRef
import io.freemytv.RoomText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays one approved video or playlist in the app, with android-youtube-player (YouTube's IFrame player in a WebView).
 * It checks the list again before anything starts, whoever asked. What it does about the rest of YouTube:
 *   - controls off and rel=0: no YouTube controls to wander into, and the end-of-video suggestions are limited to the
 *     same channel (rel=0 cannot hide them completely; the player is cued again at the end, which replaces them with
 *     the first frame);
 *   - every key is taken here and none reaches the WebView, so the D-pad cannot reach a link in the player;
 *   - the WebView is told never to leave the page it was given.
 * OK plays or pauses, Left and Right skip 10 s, Up and Down are previous and next (playlists), Back stops. If YouTube
 * refuses to play it in an embed, OK opens the official app on that item (see [YouTubeApp]).
 */
class VideoPlayerActivity : RoomActivity() {
    private var playerView: YouTubePlayerView? = null
    private var player: YouTubePlayer? = null
    private lateinit var status: TextView
    private lateinit var ref: YtRef
    private var seconds = 0f
    private var playing = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideStatus = Runnable { if (!failed) status.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wanted = YtRef(intent.getStringExtra(EXTRA_KIND).orEmpty(), intent.getStringExtra(EXTRA_ID).orEmpty()).validated()
        if (wanted == null) {
            finish()
            return
        }
        ref = wanted

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        lifecycleScope.launch {
            val allowed = withContext(Dispatchers.IO) { runCatching { RoomRuntime.videos.isAllowed(ref) }.getOrDefault(false) }
            if (!allowed) {
                Log.w(TAG, "room: player refused, not on the list (${ref.kind})")
                finish()
                return@launch
            }
            startPlayer(root)
        }
    }

    private fun startPlayer(root: FrameLayout) {
        val view = YouTubePlayerView(this)
        view.enableAutomaticInitialization = false
        view.isFocusable = false
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        lifecycle.addObserver(view)
        playerView = view
        keepOnThisPage(view)

        val playlist = ref.parsedKind == YtKind.PLAYLIST
        val options = IFramePlayerOptions.Builder()
            .controls(0)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(0)
            .fullscreen(0)
            .modestBranding(1)
            .autoplay(1)
            .apply { if (playlist) listType("playlist").list(ref.id) }
            .build()

        view.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                showHint()
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> playing = true
                    PlayerConstants.PlayerState.PAUSED -> playing = false
                    PlayerConstants.PlayerState.ENDED -> {
                        playing = false
                        // A single video: back to its first frame, which also drops the end-of-video suggestions.
                        if (!playlist) youTubePlayer.cueVideo(ref.id, 0f)
                        showHint(persistent = true)
                    }
                    else -> {}
                }
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                seconds = second
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                Log.w(TAG, "room: player error $error")
                failed = true
                status.text = RoomText.cannotPlayHere() + "\n" + RoomText.openInYoutubeApp()
                status.visibility = View.VISIBLE
            }
        }, true, options, if (playlist) null else ref.id)
    }

    /** The player page is ours. Anything that tries to load another page in the main frame is dropped. */
    private fun keepOnThisPage(v: View) {
        val web = findWebView(v) ?: return
        web.isFocusable = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = request?.isForMainFrame == true
        }
    }

    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun showHint(persistent: Boolean = false) {
        if (failed) return
        status.text = RoomText.playerKeys()
        status.visibility = View.VISIBLE
        handler.removeCallbacks(hideStatus)
        if (!persistent) handler.postDelayed(hideStatus, 6000)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        // The system's own volume keys keep working.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e)
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val p = player
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (e.repeatCount == 0) {
                    if (failed) openYoutubeApp() else if (playing) p?.pause() else p?.play()
                    if (!failed) showHint()
                }
            KeyEvent.KEYCODE_MEDIA_PLAY -> p?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p?.pause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p?.seekTo((seconds - SKIP_SECONDS).coerceAtLeast(0f))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p?.seekTo(seconds + SKIP_SECONDS)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (e.repeatCount == 0) p?.previousVideo()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> if (e.repeatCount == 0) p?.nextVideo()
            else -> {}
        }
        return true
    }

    // Back is handled in dispatchKeyEvent; this covers the system's Back (the Remote Control service).
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finish()
    }

    /** The list is checked once more, then the official YouTube app is opened on the item. */
    private fun openYoutubeApp() {
        lifecycleScope.launch {
            val allowed = withContext(Dispatchers.IO) { runCatching { RoomRuntime.videos.isAllowed(ref) }.getOrDefault(false) }
            if (!allowed) {
                finish()
                return@launch
            }
            if (YouTubeApp.open(this@VideoPlayerActivity, ref)) {
                finish()
            } else {
                status.text = RoomText.youtubeMissing()
                status.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FreeMyTV"
        private const val SKIP_SECONDS = 10f
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
    }
}
