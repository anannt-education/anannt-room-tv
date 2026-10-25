package io.freemytv.room

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.anannt.roomtv.protocol.WhitelistItem
import io.anannt.roomtv.protocol.YtKind
import io.freemytv.RoomText

/**
 * The home screen's Videos tile: only what is on the approved list, newest first. OK plays; there is no search, no
 * browsing and no link to YouTube itself. The Admin PIN button is for the person at the TV (set or reset the PIN).
 */
class VideosActivity : RoomActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        RoomService.start(this)
        setContent { RoomTheme { VideosScreen() } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideosScreen() {
    val context = LocalContext.current
    val flow = remember { RoomRuntime.store.observe() }
    val items by flow.collectAsState(initial = emptyList<WhitelistItem>())
    val first = remember { FocusRequester() }
    val admin = remember { FocusRequester() }
    LaunchedEffect(items.isEmpty()) { runCatching { (if (items.isEmpty()) admin else first).requestFocus() } }

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(RoomText.videos(), color = RoomColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = { context.startActivity(Intent(context, AdminPinActivity::class.java)) }, modifier = Modifier.focusRequester(admin)) {
                    Text(RoomText.adminPin(), fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            if (items.isEmpty()) {
                Text(RoomText.noVideosYet(), color = RoomColors.Muted, fontSize = 24.sp)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(items, key = { _, it -> it.kind + ":" + it.id }) { index, item ->
                        Button(
                            onClick = { RoomRuntime.playVideo(item.ref) },
                            modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(item.title.ifEmpty { item.id }, color = RoomColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    (if (item.ref.parsedKind == YtKind.PLAYLIST) RoomText.playlistKind() else RoomText.videoKind()) +
                                        (if (item.addedBy.isNotEmpty()) "  ·  " + RoomText.addedBy(item.addedBy) else ""),
                                    color = RoomColors.Muted, fontSize = 18.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
