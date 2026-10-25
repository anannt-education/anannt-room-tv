package io.anannt.roomtv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anannt.roomtv.protocol.Ack
import io.anannt.roomtv.protocol.Commands
import io.anannt.roomtv.protocol.NavKey
import io.anannt.roomtv.protocol.NavResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The remote: D-pad, OK, Back, Home, volume, "Open Zoom" and "End meeting / return home". Built for one hand: the big
 * controls are at the bottom, within a thumb's reach. Holding an arrow or a volume key repeats it. Every press is a
 * signed `nav.key` over the same link as the Zoom push, and the screen says plainly when the TV can't do something.
 */
@Composable
fun RemoteScreen(app: PhoneApp, onBack: () -> Unit) {
    val link by app.connection.link.collectAsState()
    val remoteControl by app.connection.remoteControl.collectAsState()
    val zoomState by app.connection.zoomState.collectAsState()
    var hint by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val connection = app.connection
    val live = link is Link.On

    // Keep the screen on while the remote is open: a remote that sleeps in your hand is no remote.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { connection.ensureConnected() }
    LaunchedEffect(Unit) {
        connection.acks.collect { a -> if (a.command in REMOTE_COMMANDS) hint = navHint(a) }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(7_000)
            hint = null
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back to setup") }
        }
        ConnectionBadge(link, onRetry = { connection.connect() })
        if (live && !remoteControl) {
            Text(
                "Back, Home and arrows work on the TV's home screen. Over Zoom they need Remote Control turned on at the TV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        zoomState?.let { Text(zoomStateText(it), style = MaterialTheme.typography.bodySmall) }
        Text(hint ?: " ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.height(44.dp))

        DPad(enabled = live) { connection.sendKey(it) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((label, key) in listOf("Back" to NavKey.BACK, "Home" to NavKey.HOME)) {
                RemoteKey(
                    label = label, description = label, enabled = live, repeat = false,
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).height(60.dp),
                ) { connection.sendKey(key) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteKey("Vol −", "Volume down", live, true, RoundedCornerShape(28.dp), Modifier.weight(1f).height(60.dp)) { connection.sendKey(NavKey.VOLUME_DOWN) }
            RemoteKey("Mute", "Mute", live, false, RoundedCornerShape(28.dp), Modifier.weight(1f).height(60.dp)) { connection.sendKey(NavKey.MUTE) }
            RemoteKey("Vol +", "Volume up", live, true, RoundedCornerShape(28.dp), Modifier.weight(1f).height(60.dp)) { connection.sendKey(NavKey.VOLUME_UP) }
        }

        OutlinedButton(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); connection.sendOpenZoom() },
            enabled = live,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) { Text("Open Zoom", fontSize = 18.sp) }
        Button(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); confirmEnd = true },
            enabled = live,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) { Text("End meeting / return home", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End the meeting on the TV?") },
            text = { Text("Zoom is closed on the TV and the home screen comes back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    connection.sendReturnHome(endMeeting = true)
                }) { Text("End meeting") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep it") } },
        )
    }
}

private val REMOTE_COMMANDS = setOf(Commands.NAV_KEY, Commands.NAV_RETURN_HOME, Commands.ZOOM_OPEN)

/** What the TV's answer means to a person. Null when it worked and there is nothing to say. */
fun navHint(a: Ack): String? = when {
    a.detail == NavResult.UNCONFIRMED -> "Sent. This TV can't see which app is in front, so it can't confirm."
    a.ok -> null
    else -> when (a.detail) {
        NavResult.NEEDS_REMOTE_CONTROL -> "Another app is open on the TV. To use Back, Home and arrows over it, turn on Remote Control at the TV (Free My TV, Remote Control)."
        NavResult.NEEDS_ANDROID_13 -> "This TV's Android is older than 13, so arrows can't reach other apps. Back and Home still can."
        NavResult.VOLUME_FIXED -> "This TV's volume is set by its sound system. Use that remote for volume."
        NavResult.BLOCKED -> "Android didn't let the TV bring that app forward. Allow \"display over other apps\" for Free My TV, or turn on Remote Control."
        NavResult.NOT_INSTALLED -> "Zoom isn't installed on the TV."
        NavResult.UNKNOWN_KEY -> "The TV doesn't know that key. Update the TV app."
        else -> "The TV couldn't do that (${a.detail})."
    }
}

@Composable
private fun ConnectionBadge(link: Link, onRetry: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(link) {
        while (link is Link.Retrying) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val (color, title, detail) = when (link) {
        is Link.On -> Triple(Color(0xFF2E9E5B), "Connected to ${link.tvName}", null)
        Link.Connecting -> Triple(Color(0xFFE0A100), "Connecting…", null)
        is Link.Retrying -> {
            val s = ((link.nextTryAt - now) / 1000).coerceAtLeast(0)
            Triple(Color(0xFFE0A100), "Reconnecting (try ${link.attempt}), next in $s s", link.reason)
        }
        is Link.Failed -> Triple(Color(0xFFD64545), "Not connected", link.reason)
        Link.Off -> Triple(Color(0xFF8A8F98), "Not connected", null)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (link is Link.Retrying || link is Link.Failed || link == Link.Off) TextButton(onClick = onRetry) { Text("Try now") }
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DPad(enabled: Boolean, send: (NavKey) -> Boolean) {
    val size = 86.dp
    Box(Modifier.size(size * 3 + 12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        RemoteKey("\u25B2", "Up", enabled, true, CircleShape, Modifier.align(Alignment.TopCenter).size(size)) { send(NavKey.UP) }
        RemoteKey("\u25BC", "Down", enabled, true, CircleShape, Modifier.align(Alignment.BottomCenter).size(size)) { send(NavKey.DOWN) }
        RemoteKey("\u25C0", "Left", enabled, true, CircleShape, Modifier.align(Alignment.CenterStart).size(size)) { send(NavKey.LEFT) }
        RemoteKey("\u25B6", "Right", enabled, true, CircleShape, Modifier.align(Alignment.CenterEnd).size(size)) { send(NavKey.RIGHT) }
        RemoteKey("OK", "OK", enabled, false, CircleShape, Modifier.align(Alignment.Center).size(size), primary = true) { send(NavKey.OK) }
    }
}

/**
 * One remote key. A press sends at once with a tick of haptic feedback; with [repeat] holding it sends again every
 * 140 ms after 450 ms. Also an accessibility button with a description, for TalkBack.
 */
@Composable
private fun RemoteKey(
    label: String,
    description: String,
    enabled: Boolean,
    repeat: Boolean,
    shape: Shape,
    modifier: Modifier,
    primary: Boolean = false,
    onPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    val base = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val ink = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (down) base.copy(alpha = 0.6f) else base)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick(label = description) { if (enabled) press(); enabled }
            }
            .pointerInput(enabled, repeat) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    down = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    press()
                    val held = if (repeat) scope.launch {
                        delay(450)
                        while (true) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            press()
                            delay(140)
                        }
                    } else null
                    tryAwaitRelease()
                    held?.cancel()
                    down = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}
