package com.maven.feedback

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Feedback mode (STATE.md § 26). A floating button on every page; tap it and it takes a screenshot of what you're
 * looking at, notes the page and app state, and asks what you'd change. Notes pile up in filesDir/feedback/ until
 * "Share feedback" zips them (notes.md + screenshots) for attaching in chat. Off by default; nothing leaves the
 * phone unless you share it.
 */
object Feedback {
    private const val PREFS = "feedback"
    var enabled by mutableStateOf(false)
        private set
    /** Bumped on every save / clear, so counts on screen refresh. */
    var version by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) { enabled = ctx.getSharedPreferences(PREFS, 0).getBoolean("on", false) }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("on", on).apply()
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "feedback").apply { mkdirs() }

    fun count(ctx: Context): Int = dir(ctx).listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0

    private fun stamp(at: Long) = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(at))

    /** Screenshot of the activity's window (Compose included). Calls back on the main thread; null if it failed. */
    fun screenshot(activity: Activity, done: (Bitmap?) -> Unit) {
        val v = activity.window.decorView
        if (v.width == 0 || v.height == 0) { done(null); return }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(activity.window, bmp, { r -> done(if (r == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
        }.onFailure { done(null) }
    }

    fun save(ctx: Context, app: String, page: String, state: String, comment: String, shot: Bitmap?) {
        val at = System.currentTimeMillis()
        val base = "${stamp(at)}_${page.replace(Regex("[^A-Za-z0-9-]"), "-").take(30)}"
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        if (shot != null) runCatching {
            // Half size is plenty to see the layout and keeps the share small.
            val small = Bitmap.createScaledBitmap(shot, shot.width / 2, shot.height / 2, true)
            FileOutputStream(File(dir(ctx), "$base.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir(ctx), "$base.txt").writeText(
            buildString {
                appendLine("## $app $version · $page · ${SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(at))}")
                appendLine()
                appendLine(comment.trim())
                if (state.isNotBlank()) { appendLine(); appendLine("On screen: ${state.trim()}") }
                if (shot != null) { appendLine(); appendLine("Screenshot: $base.png") }
            },
        )
        this.version++
    }

    /**
     * Share every note as plain files: one notes.md (all notes, oldest first) plus the screenshots, so they can be
     * attached in a chat directly (no zip to unpack). Same files also go into one zip, offered as "Save as zip".
     */
    fun share(ctx: Context, app: String, asZip: Boolean = false): Boolean {
        val files = dir(ctx).listFiles()?.sortedBy { it.name }.orEmpty()
        val notes = files.filter { it.name.endsWith(".txt") }
        if (notes.isEmpty()) return false
        val out = File(ctx.cacheDir, "feedback-share").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val tag = "${app.replace(' ', '-')}-feedback-${stamp(System.currentTimeMillis())}"
        val md = File(out, "$tag.md")
        md.writeText("# $app feedback (${notes.size} notes)\n\n" + notes.joinToString("\n---\n\n") { it.readText() })
        val pngs = files.filter { it.name.endsWith(".png") }.map { f -> File(out, f.name).also { f.copyTo(it, overwrite = true) } }
        val uris = ArrayList<android.net.Uri>()
        if (asZip) {
            val zip = File(out, "$tag.zip")
            ZipOutputStream(FileOutputStream(zip)).use { z ->
                for (f in listOf(md) + pngs) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
            uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.feedback", zip)
        } else {
            for (f in listOf(md) + pngs) uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.feedback", f)
        }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.setType(if (asZip) "application/zip" else "*/*").putExtra(Intent.EXTRA_SUBJECT, "$app feedback").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("feedback", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        ctx.startActivity(Intent.createChooser(send, "Share feedback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() }; version++ }
}

/** Empty FileProvider subclass so its authority doesn't clash with an app's own FileProvider. */
class FeedbackFiles : FileProvider()

/**
 * The floating button. Put it last inside a full-screen Box. Drag it out of the way; tap to comment on what's on
 * screen. [page] = where you are, [state] = a line about what's showing (read when tapped).
 */
@Composable
fun BoxScope.FeedbackButton(app: String, page: String, state: () -> String = { "" }) {
    if (!Feedback.enabled) return
    val ctx = LocalContext.current
    var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(0f, 0f)) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var open by remember { mutableStateOf(false) }
    var where by remember { mutableStateOf("") }
    var seen by remember { mutableStateOf("") }
    SmallFloatingActionButton(
        onClick = {
            where = page
            seen = runCatching(state).getOrDefault("")
            val act = ctx as? Activity
            if (act == null) { shot = null; open = true } else Feedback.screenshot(act) { b -> shot = b; open = true }
        },
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 96.dp)
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); drag += d } },
        shape = CircleShape,
    ) { Icon(Icons.Filled.RateReview, contentDescription = "Leave feedback") }

    if (open) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("What would you change?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$app · $where", style = MaterialTheme.typography.labelMedium)
                    shot?.let {
                        androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "Screenshot", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                    }
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        placeholder = { Text("Say it like you'd say it to me. The keyboard's mic works too.") })
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    Feedback.save(ctx, app, where, seen, text, shot); open = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** The settings block: switch, how many notes, share, clear. */
@Composable
fun FeedbackSettings(app: String) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = Feedback.version
    val n = Feedback.count(ctx)
    var confirmClear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Feedback mode", style = MaterialTheme.typography.titleSmall)
                Text("A floating button on every page: tap it to screenshot what you see and say what you'd change. Drag it out of the way.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(Feedback.enabled, { Feedback.setEnabled(ctx, it) })
        }
        Text(if (n == 0) "No notes yet." else "$n note${if (n == 1) "" else "s"} saved.", style = MaterialTheme.typography.bodySmall)
        if (n > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Feedback.share(ctx, app) }) { Text("Share feedback") }
            TextButton(onClick = { Feedback.share(ctx, app, asZip = true) }) { Text("As one zip") }
            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete $n notes?") },
        text = { Text("Share them first if you haven't.") },
        confirmButton = { TextButton(onClick = { Feedback.clear(ctx); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
