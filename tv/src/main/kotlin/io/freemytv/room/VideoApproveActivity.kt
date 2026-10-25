package io.freemytv.room

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.anannt.roomtv.protocol.YtKind
import io.freemytv.RoomText

/**
 * "Approve this video?" (or playlist): what a paired phone asked to put on the list, after its admin PIN was right.
 * OK approves, Back (or Reject) rejects, leaving the screen drops the question; the phone is told which. The title and
 * thumbnail come from YouTube's oEmbed if the TV is online and it answers in time; without them the ID is shown.
 */
class VideoApproveActivity : RoomActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoomRuntime.start(this)
        RoomService.start(this)
        setContent { RoomTheme { ApproveScreen(onDone = { finish() }) } }
    }

    /** Another screen took over (Home, a phone's Return home): the question is not left hanging in the background. */
    override fun onStop() {
        if (!isChangingConfigurations) finish()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) RoomRuntime.clearApproval()
        super.onDestroy()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ApproveScreen(onDone: () -> Unit) {
    val pending by RoomRuntime.approval.collectAsState()
    val p = pending
    if (p == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(p.at) { runCatching { focus.requestFocus() } }
    BackHandler {
        RoomRuntime.decideApproval(false)
        onDone()
    }
    val playlist = p.ref.parsedKind == YtKind.PLAYLIST

    Box(Modifier.fillMaxSize().background(RoomColors.Navy).padding(horizontal = 64.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(RoomColors.Panel, RoundedCornerShape(24.dp)).padding(horizontal = 64.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (playlist) RoomText.approvePlaylist() else RoomText.approveVideo(), color = RoomColors.Ink, fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(RoomText.sentBy(p.from), color = RoomColors.Muted, fontSize = 22.sp)
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thumb = p.info?.thumbnail
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 400.dp, height = 225.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(32.dp))
                }
                Column(Modifier.width(520.dp)) {
                    val title = p.info?.title.orEmpty()
                    if (title.isNotEmpty()) Text(title, color = RoomColors.Ink, fontSize = 32.sp, fontWeight = FontWeight.Medium, maxLines = 3)
                    Text(
                        (if (playlist) RoomText.playlistKind() else RoomText.videoKind()) + "  ·  " + p.ref.id,
                        color = if (title.isEmpty()) RoomColors.Ink else RoomColors.Muted, fontSize = if (title.isEmpty()) 32.sp else 20.sp,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { RoomRuntime.decideApproval(true); onDone() }, modifier = Modifier.focusRequester(focus)) {
                    Text(RoomText.approve(), fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(24.dp))
                Button(onClick = { RoomRuntime.decideApproval(false); onDone() }) {
                    Text(RoomText.reject(), fontSize = 26.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
    }
}
