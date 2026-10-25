package io.anannt.roomtv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.anannt.roomtv.protocol.Ack
import io.anannt.roomtv.protocol.PinGuard
import io.anannt.roomtv.protocol.WhitelistItem
import io.anannt.roomtv.protocol.WhitelistOutcome
import io.anannt.roomtv.protocol.WhitelistResult
import io.anannt.roomtv.protocol.YtKind
import io.anannt.roomtv.protocol.YtRef
import io.anannt.roomtv.protocol.YtResult
import kotlinx.coroutines.launch

/** The admin PIN box: digits only, hidden, at most 8. The PIN lives in this field only; it is cleared after every send and never saved. */
@Composable
fun PinField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c in '0'..'9' }.take(PinGuard.MAX_DIGITS)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text("Admin PIN (set on the TV)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

/**
 * A YouTube video or playlist found in what was shared or pasted: with the admin PIN, ask the TV to approve it. The TV
 * then shows "Approve this video?" and OK there decides; the result comes back here. Nothing is added by this phone alone.
 */
@Composable
fun YouTubeSend(app: PhoneApp, ref: YtRef, onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var status by remember(ref) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playlist = ref.parsedKind == YtKind.PLAYLIST

    LaunchedEffect(ref) {
        app.connection.whitelistResults.collect { r ->
            if (r.kind == ref.kind && r.id == ref.id) {
                status = resultText(r)
                if (r.parsed() == WhitelistOutcome.APPROVED) onDone()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (playlist) "YouTube playlist found" else "YouTube video found", style = MaterialTheme.typography.titleSmall)
        Text(ref.id, style = MaterialTheme.typography.bodySmall)
        Text(
            "The TV will ask whether to approve it. Press OK on the TV to approve, Back to reject.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PinField(pin, { pin = it })
        Button(
            enabled = PinGuard.isValid(pin) && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val sent = pin
                pin = ""
                busy = true
                status = "Asking the TV…"
                scope.launch {
                    status = ytAckText(app.connection.sendWhitelistAdd(ref, sent))
                    busy = false
                }
            },
        ) { Text(if (playlist) "Ask the TV to approve this playlist" else "Ask the TV to approve this video") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** What the TV said to a YouTube or list command, in words a person can act on. */
fun ytAckText(a: Ack): String = when (a.detail) {
    YtResult.SHOWN -> "On the TV now. Press OK there to approve, or Back to reject."
    YtResult.ALREADY_APPROVED -> "That is already on the approved list."
    YtResult.REMOVED -> "Removed from the approved list."
    YtResult.NOT_ON_LIST -> "That was not on the list."
    YtResult.PLAYING -> "Playing on the TV."
    YtResult.NOT_APPROVED -> "That is not on the approved list, so the TV will not play it."
    YtResult.WRONG_PIN -> if (a.triesLeft > 0) "Wrong PIN. ${a.triesLeft} tries left before a short lockout." else "Wrong PIN."
    YtResult.LOCKED -> "Too many wrong PINs. The TV will check one again in ${formatWait(a.waitMs)}."
    YtResult.PIN_NOT_SET -> "No admin PIN is set yet. On the TV: Videos, then Admin PIN."
    YtResult.BAD_ITEM, YtResult.BAD_REQUEST -> "The TV did not accept that link."
    YtResult.NEEDS_V2 -> "The TV runs an older Room TV app. Update it to use YouTube."
    YtResult.COULD_NOT_SHOW -> "The TV could not show the question. Check that it is on, and that Room TV may open over other apps (docs/test-m4.md)."
    YtResult.FAILED -> "The TV could not do that."
    "no-tv" -> "This phone is not paired with a TV yet."
    "no-link" -> "Could not reach the TV."
    "no-answer" -> "The TV did not answer."
    else -> "The TV said: ${a.detail}"
}

fun resultText(r: WhitelistResult): String = when (r.parsed()) {
    WhitelistOutcome.APPROVED -> "Approved on the TV." + if (r.title.isNotEmpty()) " ${r.title}" else ""
    WhitelistOutcome.REJECTED -> "Rejected on the TV."
    WhitelistOutcome.CANCELLED -> "The question on the TV was dropped before anyone answered."
    null -> "The TV reported: ${r.outcome}"
}

fun formatWait(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(1)
    return if (s < 60) "$s s" else "${(s + 59) / 60} min"
}

/** The approved list on the TV: play an item, or remove it (admin PIN). Adding is done by sharing a YouTube link to this app. */
@Composable
fun VideosScreen(app: PhoneApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown by app.connection.whitelist.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<WhitelistItem?>(null) }
    var pin by remember { mutableStateOf("") }

    suspend fun load() {
        loading = true
        val body = app.connection.refreshWhitelist()
        status = if (body == null) {
            if (app.connection.tvProtocol.value < io.anannt.roomtv.protocol.YOUTUBE_PROTOCOL_VERSION) ytAckText(Ack("", false, YtResult.NEEDS_V2))
            else "Could not get the list from the TV."
        } else null
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) { app.connection.whitelistResults.collect { load() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = { scope.launch { load() } }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
        }
        Text("Approved videos", style = MaterialTheme.typography.headlineSmall)
        val body = shown
        if (body != null && !body.pinSet) {
            Text("No admin PIN is set yet. On the TV: Videos, then Admin PIN. Until then nothing can be added or removed from a phone.", style = MaterialTheme.typography.bodyMedium)
        }
        if (body != null && body.locked > 0) {
            Text("Wrong PINs were tried. The TV will check one again in ${formatWait(body.locked)}.", style = MaterialTheme.typography.bodyMedium)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (body != null && body.items.isEmpty()) {
            Text("Nothing is approved yet. Share a YouTube link to Room TV, or paste one on the main screen.", style = MaterialTheme.typography.bodyMedium)
        }
        for (item in body?.items.orEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title.ifEmpty { item.id }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (item.ref.parsedKind == YtKind.PLAYLIST) "Playlist" else "Video") + if (item.addedBy.isNotEmpty()) " · added by ${item.addedBy}" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { status = ytAckText(app.connection.sendYtPlay(item.ref)) } }) { Text("Play on the TV") }
                        OutlinedButton(onClick = { pin = ""; removing = item }) { Text("Remove") }
                    }
                }
            }
        }
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null; pin = "" },
            title = { Text("Remove from the list?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title.ifEmpty { item.id })
                    PinField(pin, { pin = it })
                }
            },
            confirmButton = {
                TextButton(enabled = PinGuard.isValid(pin), onClick = {
                    val sent = pin
                    val target = item
                    pin = ""
                    removing = null
                    scope.launch {
                        val ack = app.connection.sendWhitelistRemove(target.ref, sent)
                        load()
                        status = ytAckText(ack)
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null; pin = "" }) { Text("Cancel") } },
        )
    }
}
