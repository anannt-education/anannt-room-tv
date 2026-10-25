package io.freemytv.room

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.anannt.roomtv.protocol.JoinWatch
import io.anannt.roomtv.protocol.ZoomParser
import io.anannt.roomtv.protocol.ZoomPhase
import io.freemytv.RoomText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The "Meeting ready" card: what a phone sent with zoom.join, big enough to read from the sofa. OK joins, Cancel (or
 * Back) drops it. The meeting lives in [RoomRuntime.pending], in memory, not in the intent: the passcode is never
 * written to an extra or a log.
 *
 * After OK the card does not vanish: it shows "Joining Zoom…" until Zoom is in front, or for 20 s ([JoinWatch]), and the
 * phone is told what happened (`zoom.state`: joined, unconfirmed, fallback, failed, cancelled).
 */
class ZoomReadyActivity : RoomActivity() {
    /** True while another app (Zoom, once it opens) covers the card. */
    @Volatile private var covered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        RoomService.start(this)
        setContent {
            RoomTheme {
                ZoomReadyScreen(onDone = { finish() }, covered = { covered })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        covered = false
    }

    override fun onStop() {
        covered = true
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) RoomRuntime.clearPending()
        super.onDestroy()
    }
}

/** What the card shows: waiting for OK, or Zoom's own app is the only way and the person types the numbers. */
private sealed class CardState {
    object Ready : CardState()
    /** The link was handed to Zoom; waiting up to 20 s for Zoom to be in front. */
    object Joining : CardState()
    data class Manual(val route: ZoomLauncher.Route, val pkg: String?) : CardState()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ZoomReadyScreen(onDone: () -> Unit, covered: () -> Boolean = { false }) {
    val meeting by RoomRuntime.pending.collectAsState()
    val m = meeting
    if (m == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val context = LocalContext.current
    val launcher = remember { ZoomLauncher(context) }
    var card by remember(m.at) { mutableStateOf<CardState>(CardState.Ready) }
    var left by remember(m.at) { mutableIntStateOf(m.join.countdownSec) }
    var counting by remember(m.at) { mutableStateOf(m.join.countdownSec > 0) }
    val focus = remember { FocusRequester() }

    fun join() {
        counting = false
        val route = launcher.join(m.join)
        when (route) {
            ZoomLauncher.Route.DEEP_LINK, ZoomLauncher.Route.WEB_LINK -> {
                card = CardState.Joining
                RoomRuntime.reportZoom(ZoomPhase.JOINING, route.log)
            }
            ZoomLauncher.Route.FAILED -> {
                card = CardState.Manual(route, launcher.zoomPackage())
                RoomRuntime.reportZoom(ZoomPhase.FAILED, route.log)
            }
            else -> {
                card = CardState.Manual(route, launcher.zoomPackage())
                RoomRuntime.reportZoom(ZoomPhase.FALLBACK, route.log)
            }
        }
    }

    LaunchedEffect(m.at, card) { runCatching { focus.requestFocus() } }
    // The optional countdown (off unless the phone asked for it): ticks once a second, joins at zero.
    LaunchedEffect(m.at, counting) {
        while (counting && left > 0) {
            delay(1000)
            left -= 1
        }
        if (counting && left == 0) join()
    }

    // Joining: look every half second for Zoom in front. Stays on the card for 20 s at most.
    LaunchedEffect(card, m.at) {
        if (card !is CardState.Joining) return@LaunchedEffect
        val watch = JoinWatch(System.currentTimeMillis())
        while (true) {
            delay(500)
            val (front, canSee) = withContext(Dispatchers.IO) { RoomRuntime.probe.current() to RoomRuntime.probe.canSee }
            val phase = watch.update(System.currentTimeMillis(), front, canSee, covered(), launcher::isZoomPackage) ?: continue
            when (phase) {
                ZoomPhase.FALLBACK -> {
                    // Zoom never came forward: the numbers go on screen to type (the third route).
                    card = CardState.Manual(ZoomLauncher.Route.APP_ONLY, launcher.zoomPackage())
                    RoomRuntime.reportZoom(phase, "timeout")
                }
                else -> {
                    RoomRuntime.reportZoom(phase, if (phase == ZoomPhase.UNCONFIRMED) "cannot-see-front-app" else "")
                    onDone()
                }
            }
            break
        }
    }

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(RoomColors.Panel, RoundedCornerShape(24.dp)).padding(horizontal = 64.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val manual = card as? CardState.Manual
            val joining = card is CardState.Joining
            Text(
                if (joining) RoomText.joiningZoom() else if (manual == null) RoomText.meetingReady() else if (manual.route == ZoomLauncher.Route.NOT_INSTALLED) RoomText.zoomMissing() else RoomText.enterInZoom(),
                color = RoomColors.Ink, fontSize = if (manual == null) 48.sp else 30.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(RoomText.sentBy(m.from), color = RoomColors.Muted, fontSize = 22.sp)
            Spacer(Modifier.height(28.dp))
            Text(RoomText.meetingId(), color = RoomColors.Muted, fontSize = 22.sp)
            Text(ZoomParser.formatId(m.join.id), color = RoomColors.Ink, fontSize = 84.sp, fontWeight = FontWeight.Bold)
            // The passcode a person can type. The link token (m.join.pwd) is long and not meant to be read, so it stays off screen.
            if (m.join.passcode.isNotEmpty() && manual != null) {
                Spacer(Modifier.height(12.dp))
                Text(RoomText.passcode(), color = RoomColors.Muted, fontSize = 22.sp)
                Text(m.join.passcode, color = RoomColors.Gold, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    joining -> {}
                    manual == null -> Button(onClick = { join() }, modifier = Modifier.focusRequester(focus)) {
                        Text(RoomText.pressOkToJoin(), fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    manual.pkg != null -> Button(onClick = { launcher.openApp(manual.pkg) }, modifier = Modifier.focusRequester(focus)) {
                        Text(RoomText.openZoom(), fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
                if (!joining && (manual == null || manual.pkg != null)) Spacer(Modifier.width(24.dp))
                Button(
                    onClick = { counting = false; onDone() },
                    modifier = if (joining || (manual != null && manual.pkg == null)) Modifier.focusRequester(focus) else Modifier,
                ) {
                    Text(RoomText.cancel(), fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
            if (counting && manual == null && !joining) {
                Spacer(Modifier.height(16.dp))
                Text(RoomText.joiningIn(left), color = RoomColors.Gold, fontSize = 22.sp)
            }
        }
    }
}
