package io.freemytv.room

import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.freemytv.RoomText
import kotlinx.coroutines.delay

/**
 * Free My TV > Remote Control: what the optional Remote Control service is, whether it is on, and the commands that
 * turn it on and off. It is off until someone runs them: this app never turns it on by itself.
 */
class RemoteAccessActivity : RoomActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        setContent { RoomTheme { RemoteAccessScreen() } }
    }
}

/** True when our service is in the enabled list (it may take a moment to connect after that). */
private fun listed(c: android.content.Context): Boolean {
    val list = runCatching { Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services") }.getOrNull().orEmpty()
    return list.split(':').any { it.endsWith("io.freemytv.room.RoomControlService") }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RemoteAccessScreen() {
    val context = LocalContext.current
    val room by RoomRuntime.state.collectAsState()
    var on by remember { mutableStateOf(RoomControlService.connected) }
    LaunchedEffect(Unit) {
        while (true) {
            on = RoomControlService.connected || listed(context)
            delay(1000)
        }
    }
    val ip = room.ip ?: "<tv-ip>"

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text(RoomText.remoteControl(), color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (on) RoomText.remoteControlIsOn() else RoomText.remoteControlIsOff(),
                color = if (on) RoomColors.Gold else RoomColors.Muted, fontSize = 26.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(20.dp))
            Text(RoomText.remoteControlWhat(), color = RoomColors.Muted, fontSize = 20.sp)
            Spacer(Modifier.height(28.dp))
            Text(RoomText.remoteControlTurnOn(), color = RoomColors.Ink, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Command("scripts/remote-access.sh $ip on")
            Spacer(Modifier.height(24.dp))
            Text(RoomText.remoteControlTurnOff(), color = RoomColors.Ink, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Command("scripts/remote-access.sh $ip off")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Command(line: String) {
    Text(
        line,
        color = RoomColors.Ink, fontSize = 24.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().background(RoomColors.Panel, RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 16.dp),
    )
}
