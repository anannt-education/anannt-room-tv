package io.freemytv.room

import android.os.Bundle
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.freemytv.RoomText

/**
 * Free My TV > Room Name: the name other people see in the Zoom meeting when the room joins (the phone does not send
 * its own). Empty goes back to "Anannt Room TV".
 */
class RoomNameActivity : RoomActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        setContent { RoomTheme { RoomNameScreen(onDone = { finish() }) } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RoomNameScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(RoomRuntime.roomName()) }
    fun save() {
        RoomRuntime.setRoomName(context, name)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text(RoomText.roomName(), color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(RoomText.roomNameHint(), color = RoomColors.Muted, fontSize = 18.sp)
            Spacer(Modifier.height(32.dp))
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = RoomColors.Ink, fontSize = 36.sp),
                cursorBrush = SolidColor(RoomColors.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().background(RoomColors.Panel, RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = { save() }) {
                Text(RoomText.save(), fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}
