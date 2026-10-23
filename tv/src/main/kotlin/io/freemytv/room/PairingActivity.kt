package io.freemytv.room

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.mutableLongStateOf
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
import io.anannt.roomtv.protocol.PairingQr
import kotlinx.coroutines.delay

/** Free My TV > Pair a Phone: the QR the phone scans, renewed every two minutes and after each pairing. */
class PairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        RoomService.start(this)
        setContent {
            RoomTheme {
                PairingScreen(onPairedPhones = { startActivity(Intent(this, PairedPhonesActivity::class.java)) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PairingScreen(onPairedPhones: () -> Unit) {
    val state by RoomRuntime.state.collectAsState()
    val openedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }

    // A fresh code when the screen opens, another when the one on screen was used or ran out.
    LaunchedEffect(Unit) {
        RoomRuntime.newPairToken()
        focus.requestFocus()
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            if (!RoomRuntime.tokenLive()) RoomRuntime.newPairToken()
        }
    }

    val identity = RoomRuntime.identity
    val token = state.token
    val ip = state.ip
    val qrText = remember(token, ip, state.port) {
        if (token == null || ip == null) null
        else PairingQr(tvId = identity.tvId, name = identity.name, ip = ip, port = state.port, pairToken = token.value).encode()
    }
    val qr = remember(qrText) { qrText?.let { Qr.bitmap(it, 440) } }
    val justPaired = state.lastPaired?.takeIf { state.lastPairedAt >= openedAt && now - state.lastPairedAt < 15_000 }
    val remaining = token?.remainingMs(now)?.div(1000) ?: 0

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("Pair a phone", color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                Step(1, "On the phone, open Anannt Room TV and tap Scan TV code.")
                Step(2, "Point it at the code on the right. Same Wi-Fi as the TV.")
                Step(3, "The phone says Connected to Room TV. Done.")
                Spacer(Modifier.height(28.dp))
                Text(identity.name, color = RoomColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        state.serving && ip != null -> "On this network at $ip, port ${state.port}"
                        state.error != null -> state.error!!
                        else -> "Starting…"
                    },
                    color = RoomColors.Muted, fontSize = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (justPaired != null) "Paired with ${justPaired.name}"
                    else if (token != null) "This code works once and for %d:%02d more".format(remaining / 60, remaining % 60)
                    else "",
                    color = if (justPaired != null) RoomColors.Gold else RoomColors.Muted, fontSize = 18.sp,
                )
                Spacer(Modifier.height(28.dp))
                Row {
                    Button(onClick = { RoomRuntime.newPairToken() }, modifier = Modifier.focusRequester(focus)) {
                        Text("New code")
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onPairedPhones) {
                        Text("Paired phones (${RoomRuntime.devices.all.collectAsState().value.size})")
                    }
                }
            }
            Spacer(Modifier.width(48.dp))
            Box(
                Modifier.size(400.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Pairing code",
                        modifier = Modifier.fillMaxSize(),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text("Waiting for the network…", color = Color(0xFF1C1C1E), fontSize = 18.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$n.", color = RoomColors.Blue, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
        Text(text, color = RoomColors.Ink, fontSize = 20.sp)
    }
}
