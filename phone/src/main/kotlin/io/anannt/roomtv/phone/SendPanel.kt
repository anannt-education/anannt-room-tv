package io.anannt.roomtv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import io.anannt.roomtv.protocol.YouTubeParser
import io.anannt.roomtv.protocol.YtParse
import io.anannt.roomtv.protocol.ZoomJoin
import io.anannt.roomtv.protocol.ZoomMeeting
import io.anannt.roomtv.protocol.ZoomPhase
import io.anannt.roomtv.protocol.ZoomState
import io.anannt.roomtv.protocol.ZoomParser
import kotlinx.coroutines.launch

/**
 * The paste box and the share sheet's screen are the same thing: text in, a Zoom meeting or a YouTube video or playlist
 * found, one tap to send. A Zoom meeting is joined from the TV's card; a YouTube link goes to the TV as a request that
 * needs the admin PIN here and OK on the TV (see [YouTubeSend]).
 */
@Composable
fun SendPanel(app: PhoneApp, initialText: String = "", onSent: () -> Unit = {}) {
    var text by remember { mutableStateOf(initialText) }
    var name by remember { mutableStateOf(app.store.joinName) }
    var countdown by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val meeting: ZoomMeeting? = remember(text) { ZoomParser.parse(text) }
    // A Zoom invite wins when the text holds both; YouTube is looked for only when there is no meeting.
    val yt: YtParse = remember(text, meeting) { if (meeting == null) YouTubeParser.check(text) else YtParse.NotYouTube }
    val zoomState by app.connection.zoomState.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Send a Zoom meeting or a YouTube link to the TV", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; status = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Zoom link, invite text or YouTube link") },
                minLines = 3,
                maxLines = 8,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.getText()?.text?.let { text = it; status = null }
                }) { Text("Paste") }
                if (text.isNotEmpty()) OutlinedButton(onClick = { text = ""; status = null }) { Text("Clear") }
            }
            if (text.isNotBlank() && yt is YtParse.Rejected) {
                Text(yt.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else if (yt is YtParse.Found) {
                YouTubeSend(app, yt.ref, onSent)
            } else {
                if (text.isNotBlank()) {
                    if (meeting != null) {
                        Text("Meeting ID ${ZoomParser.formatId(meeting.id)}" + if (meeting.webinar) " (webinar)" else "", style = MaterialTheme.typography.bodyLarge)
                        val pass = if (meeting.pwd.isNotEmpty() || meeting.passcode.isNotEmpty()) "Passcode found" else "No passcode in this text"
                        Text(pass, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No Zoom meeting ID found in this text.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name in the meeting (optional)") },
                    placeholder = { Text("The TV uses its room name") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = countdown, onCheckedChange = { countdown = it })
                    Text("Join by itself after 10 seconds", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    enabled = meeting != null && !sending,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val m = meeting ?: return@Button
                        val join = ZoomJoin(m.id, m.pwd, name, m.passcode, if (countdown) 10 else 0).validated()
                        if (join == null) {
                            status = "That meeting can't be sent (check the ID and passcode)."
                            return@Button
                        }
                        app.store.joinName = name
                        sending = true
                        status = "Sending…"
                        scope.launch {
                            when (val r = app.connection.sendZoomJoin(join)) {
                                TvConnection.SendResult.Sent -> {
                                    status = "On the TV now. Press OK there to join."
                                    onSent()
                                }
                                is TvConnection.SendResult.Failed -> status = r.reason
                            }
                            sending = false
                        }
                    },
                ) { Text("Send to Room TV") }
            }
            if (yt !is YtParse.Found && status != null) Text(status!!, style = MaterialTheme.typography.bodyMedium)
            if (yt !is YtParse.Found) zoomState?.let { Text(zoomStateText(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** What the TV reported about the meeting, in words. "joined" means Zoom is in front, not that the host let the room in. */
fun zoomStateText(s: ZoomState): String = when (s.parsed()) {
    ZoomPhase.JOINING -> "Joining… Zoom is opening on the TV."
    ZoomPhase.JOINED -> "Zoom is open on the TV."
    ZoomPhase.UNCONFIRMED -> "The TV handed the meeting to Zoom. It can't see the screen, so it can't confirm."
    ZoomPhase.FALLBACK -> "Zoom didn't take the link. The meeting ID and passcode are on the TV to type."
    ZoomPhase.FAILED -> "The TV couldn't open Zoom."
    ZoomPhase.CANCELLED -> "Cancelled on the TV."
    ZoomPhase.ENDED -> "The meeting was ended."
    null -> "The TV reported: ${s.phase}"
}
