package io.anannt.roomtv.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
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
import io.anannt.roomtv.protocol.Backoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** A Discovery of its own for the reconnect logic, so it never fights the "Find the TV" button for the listener. */
    private val locator = Discovery(c)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _searching = MutableStateFlow(false)
    /** True while the phone is looking for its TV by mDNS because reconnecting keeps failing (it may have a new address). */
    val searching: StateFlow<Boolean> get() = _searching

    init {
        // Retries that keep failing: look the TV up again, and if it moved, follow it.
        scope.launch {
            connection.link.collect { l ->
                if (l is Link.Retrying && Backoff.shouldRelocate(l.attempt)) relocate()
            }
        }
        // The phone's network came back: try at once instead of waiting out the backoff.
        runCatching {
            c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = connection.nudge()
            })
        }.onFailure { Log.w("RoomTV", "network callback: $it") }
    }

    private suspend fun relocate() {
        val tv = store.tv.value ?: return
        _searching.value = true
        try {
            val f = locator.findTv(tv.tvId, 5_000) ?: return
            if (f.host == tv.ip && f.port == tv.port) return
            Log.i("RoomTV", "the TV moved to ${f.host}:${f.port}")
            store.updateAddress(f.host, f.port)
            connection.connect()
        } finally {
            _searching.value = false
        }
    }

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

private enum class Screen { Home, Scan, Remote, Videos, Today }

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
            onToday = { screen = Screen.Today },
            onMessage = { message = it },
        )
        Screen.Today -> {
            BackHandler { screen = Screen.Home }
            TodayScreen(app, onBack = { screen = Screen.Home })
        }
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
private fun HomeScreen(app: PhoneApp, message: String?, onScan: () -> Unit, onRemote: () -> Unit, onVideos: () -> Unit, onToday: () -> Unit, onMessage: (String?) -> Unit) {
    val tv by app.store.tv.collectAsState()
    val link by app.connection.link.collectAsState()
    val found by app.discovery.found.collectAsState()
    var searching by remember { mutableStateOf(false) }
    val looking by app.searching.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Only while a retry is pending: tick once a second so "trying again in 4 s" counts down.
    LaunchedEffect(link is Link.Retrying) {
        while (link is Link.Retrying) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
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
                            val secs = ((l.nextTryAt - now) / 1000).coerceAtLeast(0)
                            Text(
                                if (looking) "Looking for the TV on this network…" else "Trying again in $secs s (attempt ${l.attempt})",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
            OutlinedButton(onClick = onToday, modifier = Modifier.fillMaxWidth()) { Text("Today's meetings") }
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
