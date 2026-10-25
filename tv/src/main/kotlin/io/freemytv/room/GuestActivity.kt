package io.freemytv.room

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.freemytv.RoomText

/**
 * The home screen's "Scan to send a meeting" tile: a QR a guest scans with the phone's camera (no app, no pairing). It
 * opens the TV's own page on the LAN, where the guest pastes one Zoom link; the TV then shows the "Meeting ready" card
 * and waits for OK. The code works once and a new one appears by itself after each use and every few minutes. The
 * button switches guest sending off (and on again).
 */
class GuestActivity : RoomActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        RoomService.start(this)
        setContent { RoomTheme { GuestScreen() } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuestScreen() {
    val state by RoomRuntime.state.collectAsState()
    val token by RoomRuntime.guest.collectAsState()
    var enabled by remember { mutableStateOf(RoomRuntime.guestEnabled()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val url = if (enabled && state.ip != null) RoomRuntime.guestUrl() else null
    val qr = remember(url) { url?.let { Qr.bitmap(it, 440) } }

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(RoomText.scanToSend(), color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                Text(RoomText.guestHow(), color = RoomColors.Ink, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        !enabled -> RoomText.guestOff()
                        state.ip == null -> RoomText.guestNoNetwork()
                        else -> RoomText.guestRenews()
                    },
                    color = RoomColors.Muted, fontSize = 18.sp,
                )
                Spacer(Modifier.height(28.dp))
                Button(onClick = {
                    enabled = !enabled
                    RoomRuntime.setGuestEnabled(enabled)
                }, modifier = Modifier.focusRequester(focus)) {
                    Text(if (enabled) RoomText.guestTurnOff() else RoomText.guestTurnOn())
                }
            }
            Spacer(Modifier.width(48.dp))
            Box(
                Modifier.size(400.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                // `token` is read so the picture is made again when a new one is issued.
                if (qr != null && token != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = RoomText.scanToSend(),
                        modifier = Modifier.fillMaxSize(),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text(if (enabled) RoomText.guestNoNetwork() else RoomText.guestOff(), color = Color(0xFF1C1C1E), fontSize = 18.sp)
                }
            }
        }
    }
}
