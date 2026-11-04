package com.maven.notafraud.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.store.Lock

/**
 * Two-step settings (STATE.md § 19 item 2, built in § 22): every settings control asks "Change X from A to B?"
 * first, so scrolling can't change anything by accident. One dialog for the whole app, shown by [ConfirmHost].
 */
object Confirm {
    data class Req(val question: String, val detail: String?, val action: () -> Unit)

    var req by mutableStateOf<Req?>(null)

    fun ask(question: String, detail: String? = null, action: () -> Unit) { req = Req(question, detail, action) }

    /** "Blocking on: on → off?" */
    fun switch(label: String, to: Boolean, action: () -> Unit) =
        ask("$label: turn ${if (to) "on" else "off"}?", null, action)

    fun change(label: String, from: String, to: String, action: () -> Unit) {
        if (from == to) return
        ask("Change ${label.replaceFirstChar { it.lowercase() }} from $from to $to?", null, action)
    }

    /** Waiting changes we've already asked "why?" about this session. */
    val askedReason = mutableSetOf<String>()
}

@Composable
fun ConfirmHost() {
    val r = Confirm.req ?: return
    AlertDialog(
        onDismissRequest = { Confirm.req = null },
        title = { Text(r.question, style = MaterialTheme.typography.titleMedium) },
        text = if (r.detail != null) ({ Text(r.detail, color = Palette.textDim) }) else null,
        confirmButton = { TextButton(onClick = { Confirm.req = null; r.action() }) { Text("Yes, change it") } },
        dismissButton = { TextButton(onClick = { Confirm.req = null }) { Text("Cancel") } },
    )
}

/**
 * A loosening change was just queued for the morning: ask why, in your own words. The answer is shown back in the
 * morning next to "still want this?", when the urge has passed.
 */
@Composable
fun ReasonHost(data: AppData, now: Long) {
    val c = data.pending.firstOrNull { it.reason == null && it.createdAt > now - 60_000 && it.id !in Confirm.askedReason } ?: return
    var text by remember(c.id) { mutableStateOf("") }
    fun done(reason: String) {
        Confirm.askedReason += c.id
        Graph.update { Lock.setReason(it, c.id, reason) }
    }
    AlertDialog(
        onDismissRequest = { done("") },
        title = { Text("Waits until the morning") },
        text = {
            Column {
                Text("\"${c.label}\" happens after ${java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US).format(java.util.Date(c.applyAfter))}, if you still want it then. Why do you want it right now?", color = Palette.textDim)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Honest answer, for morning you") })
            }
        },
        confirmButton = { TextButton(onClick = { done(text) }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = {
                Confirm.askedReason += c.id
                Graph.update { Lock.discard(it, c.id) }
            }) { Text("Never mind, cancel it") }
        },
    )
}
