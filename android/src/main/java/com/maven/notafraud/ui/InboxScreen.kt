package com.maven.notafraud.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.sense.AppLabels
import com.maven.notafraud.sense.MirrorService
import com.maven.notafraud.sense.Perms

/** Messages from mirrored apps (Snapchat), so the app itself can stay blocked. */
@Composable
fun InboxScreen(data: AppData) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val enabled = remember { Perms.notificationListener(ctx) }
    var open by remember { mutableStateOf<String?>(null) }
    val conversations = data.messages.groupBy { MirrorService.conversationKey(it) }
        .entries.sortedByDescending { e -> e.value.maxOf { it.at } }

    LaunchedEffect(Unit) { Graph.update { Actions.markMessagesRead(it, null) } }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (!enabled) item {
            Panel(highlight = true) {
                Text("Turn on the message mirror", style = MaterialTheme.typography.titleMedium)
                Text("Give Not a Fraud notification access so Snapchat messages show up here. Then Snapchat itself can stay blocked.", color = Palette.textDim)
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open notification access") }
            }
        }
        if (conversations.isEmpty()) item {
            Text(
                "No messages yet. Mirrored apps: " + data.rules.mirrored.joinToString { AppLabels.label(ctx, it) },
                color = Palette.textDim,
            )
        }
        items(conversations, key = { it.key }) { (key, msgs) ->
            val sorted = msgs.sortedBy { it.at }
            val last = sorted.last()
            Panel(Modifier.clickable { open = if (open == key) null else key }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.substringAfter('|'), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(Time.clockAmPm(last.at, zone), style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
                }
                Text(AppLabels.label(ctx, last.pkg), style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
                if (open == key) {
                    sorted.takeLast(15).forEach { m ->
                        val who = m.sender.substringBefore(" · ")
                        Text("$who: ${m.text}")
                    }
                    ReplyBox(last)
                } else {
                    Text(last.text, maxLines = 2)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReplyBox(m: com.maven.notafraud.core.model.MirroredMessage) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    if (!MirrorService.canReply(m)) {
        Text("Reply isn't available for this one (the app didn't offer it, or the phone restarted since it arrived).", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Reply…") })
        TextButton(onClick = {
            val ok = MirrorService.reply(ctx, m, text)
            status = if (ok) "Sent" else "Couldn't send"
            if (ok) text = ""
        }, enabled = text.isNotBlank()) { Text("Send") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.textDim) }
}
