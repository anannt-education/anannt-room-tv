package io.anannt.roomtv.phone

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One of everything the screens share, for the life of the process. */
class PhoneApp private constructor(c: Context) {
    val store = PhoneStore(c)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val connection = TvConnection(store, http)
    val pairClient = PairClient(store, http)
    val discovery = Discovery(c)

    companion object {
        @Volatile private var instance: PhoneApp? = null
        fun get(c: Context): PhoneApp = instance ?: synchronized(this) {
            instance ?: PhoneApp(c.applicationContext).also { instance = it }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = PhoneApp.get(this)
        setContent { PhoneTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { RoomApp(app) } } }
    }
}

private enum class Screen { Home, Scan, Remote, Videos }

@Composable
fun RoomApp(app: PhoneApp) {
    var screen by remember { mutableStateOf(Screen.Home) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tv by app.store.tv.collectAsState()

    // Connect whenever there is a TV to connect to (and again when its address changes).
    LaunchedEffect(tv?.tvId, tv?.ip, tv?.port) {
        if (tv != null) app.connection.connect() else app.connection.disconnect()
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            app = app,
            message = message,
            onScan = { screen = Screen.Scan },
            onRemote = { screen = Screen.Remote },
            onVideos = { screen = Screen.Videos },
            onMessage = { message = it },
        )
        Screen.Remote -> {
            BackHandler { screen = Screen.Home }
            RemoteScreen(app, onBack = { screen = Screen.Home })
        }
        Screen.Videos -> {
            BackHandler { screen = Screen.Home }
            VideosScreen(app, onBack = { screen = Screen.Home })
        }
        Screen.Scan -> {
            BackHandler { screen = Screen.Home }
            ScanScreen(
                onCode = { qr ->
                    screen = Screen.Home
                    message = "Pairing with ${qr.name}…"
                    scope.launch {
                        app.pairClient.pair(qr)
                            .onSuccess { message = "Paired with ${it.tvName}" }
                            .onFailure { message = "Pairing failed: ${it.message}" }
                    }
                },
                onCancel = { screen = Screen.Home },
            )
        }
    }
}

@Composable
private fun HomeScreen(app: PhoneApp, message: String?, onScan: () -> Unit, onRemote: () -> Unit, onVideos: () -> Unit, onMessage: (String?) -> Unit) {
    val tv by app.store.tv.collectAsState()
    val link by app.connection.link.collectAsState()
    val found by app.discovery.found.collectAsState()
    var searching by remember { mutableStateOf(false) }
    DisposableEffect(searching) {
        if (searching) app.discovery.start()
        onDispose { app.discovery.stop() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Anannt Room TV", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val current = tv
                if (current == null) {
                    Text("Not paired yet", style = MaterialTheme.typography.titleMedium)
                    Text("On the TV: Free My TV › Pair a Phone. Then scan the code here.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    when (val l = link) {
                        is Link.On -> {
                            Text("Connected to Room TV", style = MaterialTheme.typography.titleMedium)
                            Text(l.tvName, style = MaterialTheme.typography.bodyLarge)
                            if (l.rttMs != null) Text("Round trip ${l.rttMs} ms", style = MaterialTheme.typography.bodySmall)
                        }
                        Link.Connecting -> {
                            Text("Connecting to ${current.tvName}…", style = MaterialTheme.typography.titleMedium)
                        }
                        is Link.Retrying -> {
                            Text("Reconnecting to ${current.tvName}…", style = MaterialTheme.typography.titleMedium)
                            Text(l.reason, style = MaterialTheme.typography.bodyMedium)
                        }
                        is Link.Failed -> {
                            Text("Not connected to ${current.tvName}", style = MaterialTheme.typography.titleMedium)
                            Text(l.reason, style = MaterialTheme.typography.bodyMedium)
                        }
                        Link.Off -> Text("Paired with ${current.tvName}", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${current.ip}:${current.port}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)

        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(if (tv == null) "Scan TV code" else "Scan a new TV code") }
        if (tv != null) {
            Button(onClick = onRemote, modifier = Modifier.fillMaxWidth()) { Text("Open the remote") }
            OutlinedButton(onClick = onVideos, modifier = Modifier.fillMaxWidth()) { Text("Approved videos") }
            SendPanel(app)
            OutlinedButton(onClick = { app.connection.connect(); onMessage(null) }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
            OutlinedButton(
                onClick = { onMessage(if (app.connection.ping()) "Ping sent" else "Not connected") },
                modifier = Modifier.fillMaxWidth(),
                enabled = link is Link.On,
            ) { Text("Ping the TV") }
            OutlinedButton(onClick = { searching = !searching }, modifier = Modifier.fillMaxWidth()) {
                Text(if (searching) "Stop searching" else "Find the TV on this network")
            }
            if (searching) {
                if (found.isEmpty()) Text("Searching for _anannttv._tcp…", style = MaterialTheme.typography.bodySmall)
                for (f in found) {
                    val mine = f.tvId != null && f.tvId == tv?.tvId
                    TextButton(onClick = {
                        if (mine) {
                            app.store.updateAddress(f.host, f.port)
                            onMessage("Using ${f.host}:${f.port}")
                        } else {
                            onMessage("That is another TV. Scan its code to pair with it.")
                        }
                    }) {
                        Text("${f.name}  ${f.host}:${f.port}" + if (mine) "  (this TV)" else "")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                app.connection.disconnect()
                app.store.save(null)
                onMessage("Forgot the TV. Revoke this phone on the TV too (Free My TV › Paired Phones).")
            }) { Text("Forget this TV") }
        }
    }
}
