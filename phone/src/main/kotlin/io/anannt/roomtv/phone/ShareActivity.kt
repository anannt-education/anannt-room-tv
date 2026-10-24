package io.anannt.roomtv.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * "Send to Room TV" in the share sheet: a Zoom invite or link shared from Mail, WhatsApp, Calendar or Chrome lands
 * here, the meeting is read out of it and one tap sends it. It closes by itself a moment after the TV has it.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = PhoneApp.get(this)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() else ""
        app.connection.ensureConnected()
        setContent {
            PhoneTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val tv by app.store.tv.collectAsState()
                    var closing by remember { mutableStateOf(false) }
                    LaunchedEffect(closing) {
                        if (closing) {
                            delay(1500)
                            finish()
                        }
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Send to Room TV", style = MaterialTheme.typography.headlineSmall)
                        if (tv == null) {
                            Text("This phone isn't paired with a TV yet. Open Anannt Room TV, scan the code on the TV, then share again.")
                        } else {
                            SendPanel(app, initialText = shared, onSent = { closing = true })
                        }
                        OutlinedButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
