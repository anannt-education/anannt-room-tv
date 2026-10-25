package io.anannt.roomtv.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.anannt.roomtv.protocol.NextSession
import io.anannt.roomtv.protocol.SessionResult
import io.anannt.roomtv.protocol.TodayMeetings
import io.anannt.roomtv.protocol.ZoomEntry
import io.anannt.roomtv.protocol.ZoomJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.TimeZone

/**
 * Today's Zoom meetings from the phone's calendar. The calendar is read only when this screen is open and the person
 * has allowed it; denying it leaves the rest of the app working. One tap sends a meeting to the TV (the same signed
 * zoom.join as the paste box: the TV still waits for OK). The next meeting can be shown on the TV's home screen.
 */
@Composable
fun TodayScreen(app: PhoneApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by app.connection.link.collectAsState()
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    var entries by remember { mutableStateOf<List<ZoomEntry>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val status = remember { mutableStateOf(mapOf<Long, String>()) }
    var tvStatus by remember { mutableStateOf<String?>(null) }
    var hideTitle by remember { mutableStateOf(app.store.hideSessionTitle) }
    var auto by remember { mutableStateOf(app.store.autoPushNext) }

    LaunchedEffect(granted, reload) {
        if (!granted) return@LaunchedEffect
        error = null
        try {
            entries = withContext(Dispatchers.IO) { TodayMeetings.load(PhoneCalendar(context.contentResolver), System.currentTimeMillis(), TimeZone.getDefault()) }
        } catch (e: SecurityException) {
            granted = false   // taken away in Settings while the screen was open
        } catch (e: Exception) {
            error = "The calendar could not be read."
        }
    }

    val now = System.currentTimeMillis()
    val next = entries?.let { TodayMeetings.next(it, now) }
    fun timeText(ms: Long) = DateFormat.getTimeFormat(context).format(Date(ms))
    fun session(e: ZoomEntry) = NextSession(if (hideTitle) NextSession.DEFAULT_TITLE else e.event.title.ifBlank { NextSession.DEFAULT_TITLE }, e.event.startMs).cleaned()

    fun explain(detail: String, ok: Boolean): String = when {
        ok && detail == SessionResult.SHOWN -> "Shown on the TV's home screen."
        ok && detail == SessionResult.CLEARED -> "Taken off the TV's home screen."
        detail == SessionResult.PASSED -> "That meeting has already started."
        detail == SessionResult.TOO_FAR -> "That meeting is too far away to show."
        detail == SessionResult.NEEDS_V3 -> "This TV needs the latest Room TV update to show sessions."
        detail == "no-link" || detail == "no-tv" -> "Not connected to the TV."
        detail == "no-answer" -> "The TV didn't answer."
        else -> "The TV didn't take it ($detail)."
    }

    // Keeps the TV in step while the person has asked for that and the app is open.
    LaunchedEffect(next?.event?.startMs, next?.event?.title, hideTitle, auto, link is Link.On, entries != null) {
        if (!auto || link !is Link.On || entries == null) return@LaunchedEffect
        val ack = if (next != null) app.connection.sendNextSession(session(next)) else app.connection.sendClearSession()
        tvStatus = explain(ack.detail, ack.ok)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Today's meetings", style = MaterialTheme.typography.headlineMedium)
        if (!granted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Show today's Zoom meetings here?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "To list them, Room TV reads the events in this phone's calendar. It only reads: it never adds, changes or deletes an " +
                            "event. Nothing leaves the phone except a meeting you tap Send on (or the next session's title and time, if you choose to show it on the TV).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (asked) {
                        Text(
                            "Calendar access is off. The rest of the app works without it. You can allow it in the phone's settings.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open the phone's settings") }
                    }
                    Button(onClick = { ask.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.fillMaxWidth()) { Text("Allow calendar access") }
                }
            }
        } else {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = entries
            if (list == null) {
                if (error == null) Text("Reading the calendar…")
            } else if (list.isEmpty()) {
                Text("No Zoom meetings in today's calendar.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Events count when their title, location or notes hold a Zoom link or invite. All-day, declined and cancelled events are left out.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("On the TV's home screen", style = MaterialTheme.typography.titleMedium)
                        if (next == null) {
                            Text("No more meetings today.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val shown = session(next)
                            Text("The TV would show: Next session: ${shown.title} at ${timeText(shown.startMs)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hideTitle, onCheckedChange = { hideTitle = it; app.store.hideSessionTitle = it })
                            Text("Hide the title (the TV is on show to the room)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = auto, onCheckedChange = { auto = it; app.store.autoPushNext = it })
                            Text("Keep the TV up to date while this app is open", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = next != null && link is Link.On, onClick = {
                                val n = next ?: return@Button
                                scope.launch { app.connection.sendNextSession(session(n)).let { tvStatus = explain(it.detail, it.ok) } }
                            }) { Text("Show on the TV") }
                            OutlinedButton(enabled = link is Link.On, onClick = {
                                scope.launch { app.connection.sendClearSession().let { tvStatus = explain(it.detail, it.ok) } }
                            }) { Text("Clear") }
                        }
                        tvStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                for (entry in list) {
                    val e = entry.event
                    val key = e.startMs xor e.id
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(e.title.ifBlank { "Meeting" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${timeText(e.startMs)} to ${timeText(e.endMs)}" + if (entry.ended) "  (over)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("Meeting ID ${io.anannt.roomtv.protocol.ZoomParser.formatId(entry.meeting.id)}", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = link is Link.On, modifier = Modifier.fillMaxWidth(), onClick = {
                                val m = entry.meeting
                                val join = ZoomJoin(m.id, m.pwd, app.store.joinName, m.passcode, 0).validated()
                                if (join == null) {
                                    status.value = status.value + (key to "That meeting can't be sent (check the ID and passcode).")
                                    return@Button
                                }
                                status.value = status.value + (key to "Sending…")
                                scope.launch {
                                    val text = when (val r = app.connection.sendZoomJoin(join)) {
                                        TvConnection.SendResult.Sent -> "On the TV now. Press OK there to join."
                                        is TvConnection.SendResult.Failed -> r.reason
                                    }
                                    status.value = status.value + (key to text)
                                }
                            }) { Text("Send to Room TV") }
                            status.value[key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
            }
            if (link !is Link.On) Text("Not connected to the TV yet. Sending works once it says Connected.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
