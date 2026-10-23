package io.freemytv.room

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Free My TV > Paired Phones: every phone that can send commands, each with Revoke (press twice). */
class PairedPhonesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        setContent { RoomTheme { PairedPhonesScreen() } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PairedPhonesScreen() {
    val devices by RoomRuntime.devices.all.collectAsState()
    var confirming by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirming) {
        if (confirming != null) {
            delay(6000)
            confirming = null
        }
    }
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text("Paired phones", color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (devices.isEmpty()) "No phone is paired yet. Free My TV › Pair a Phone shows the code to scan."
                else "A revoked phone has to scan a new code to send anything again.",
                color = RoomColors.Muted, fontSize = 18.sp,
            )
            Spacer(Modifier.height(24.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(devices, key = { it.deviceId }) { d ->
                    Row(
                        Modifier.fillMaxWidth().background(RoomColors.Panel, RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = RoomColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Paired ${dates.format(Date(d.pairedAt))}  ·  Last seen ${dates.format(Date(d.lastSeen))}",
                                color = RoomColors.Muted, fontSize = 16.sp,
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        Button(onClick = {
                            if (confirming == d.deviceId) {
                                RoomRuntime.devices.remove(d.deviceId)
                                confirming = null
                            } else {
                                confirming = d.deviceId
                            }
                        }) {
                            Text(if (confirming == d.deviceId) "Press again to revoke" else "Revoke")
                        }
                    }
                }
            }
        }
    }
}
