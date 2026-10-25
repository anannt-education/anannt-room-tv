package io.freemytv.room

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.anannt.roomtv.protocol.PinGuard
import io.freemytv.RoomText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Videos > Admin PIN, on the TV itself. First time: choose a PIN of 4 to 8 digits (typed twice, with the arrows and OK,
 * or the remote's number keys). Later: Reset PIN (press twice) forgets it and the lockout, then a new one is chosen.
 * The reset is the way out of a forgotten PIN and needs the TV's remote in hand. Digits are shown as dots and never logged.
 */
class AdminPinActivity : RoomActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        setContent { RoomTheme { AdminPinScreen(onDone = { finish() }) } }
    }
}

private enum class PinStep { MENU, ENTER, CONFIRM }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AdminPinScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val guard = remember { AdminPin.get(context) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (guard.isSet) PinStep.MENU else PinStep.ENTER) }
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }
    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            delay(6000)
            confirmReset = false
        }
    }

    fun digit(d: Char) {
        if (step != PinStep.MENU && entry.length < PinGuard.MAX_DIGITS) entry += d
    }

    fun submit() {
        if (!PinGuard.isValid(entry)) return
        when (step) {
            PinStep.ENTER -> {
                first = entry
                entry = ""
                message = ""
                step = PinStep.CONFIRM
            }
            PinStep.CONFIRM -> {
                if (entry == first) {
                    val pin = entry
                    // PBKDF2 takes a moment on a slow TV: not on the main thread.
                    scope.launch {
                        withContext(Dispatchers.Default) { guard.set(pin) }
                        onDone()
                    }
                } else {
                    entry = ""
                    first = ""
                    message = RoomText.pinsDiffer()
                    step = PinStep.ENTER
                }
            }
            PinStep.MENU -> {}
        }
    }

    Box(
        Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 40.dp).onKeyEvent { ev ->
            val code = ev.key.nativeKeyCode
            val d = when (code) {
                in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_0)
                in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                else -> null
            }
            if (d != null && ev.type == KeyEventType.KeyDown) {
                digit(d)
                true
            } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.background(RoomColors.Panel, RoundedCornerShape(24.dp)).padding(horizontal = 64.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (step == PinStep.MENU) {
                Text(RoomText.adminPin(), color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Text(RoomText.adminPinIsSet(), color = RoomColors.Muted, fontSize = 22.sp, modifier = Modifier.width(720.dp))
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = {
                        if (confirmReset) {
                            guard.clear()
                            confirmReset = false
                            step = PinStep.ENTER
                        } else {
                            confirmReset = true
                        }
                    },
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text(if (confirmReset) RoomText.pressAgainToReset() else RoomText.resetPin(), fontSize = 24.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            } else {
                Text(if (step == PinStep.ENTER) RoomText.setAdminPin() else RoomText.confirmPin(), color = RoomColors.Ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(if (message.isNotEmpty()) message else RoomText.adminPinHint(), color = if (message.isNotEmpty()) RoomColors.Gold else RoomColors.Muted, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (entry.isEmpty()) "_ _ _ _" else entry.map { "●" }.joinToString(" "),
                    color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                PinPad(focus = focus, onDigit = { digit(it) }, onDelete = { entry = entry.dropLast(1) }, onOk = { submit() })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinPad(focus: FocusRequester, onDigit: (Char) -> Unit, onDelete: () -> Unit, onOk: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for ((r, row) in rows.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((c, ch) in row.withIndex()) {
                    Button(onClick = { onDigit(ch) }, modifier = (if (r == 0 && c == 0) Modifier.focusRequester(focus) else Modifier).width(120.dp)) {
                        Text(ch.toString(), fontSize = 28.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDelete, modifier = Modifier.width(120.dp)) { Text(RoomText.delete(), fontSize = 20.sp) }
            Button(onClick = { onDigit('0') }, modifier = Modifier.width(120.dp)) { Text("0", fontSize = 28.sp) }
            Button(onClick = onOk, modifier = Modifier.width(120.dp)) { Text(RoomText.save(), fontSize = 20.sp) }
        }
    }
}
