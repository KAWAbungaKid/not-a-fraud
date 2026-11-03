package com.maven.diet.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.maven.diet.core.LabelParser
import java.io.File

/**
 * Photo of a Nutrition Facts label → on-device text recognition (ML Kit, no internet, nothing leaves the
 * phone) → [LabelParser] → the form gets filled for checking.
 */
object LabelOcr {
    /**
     * ML Kit returns text block by block; on a label the number column can land in its own block. Rebuild rows by
     * position instead: group lines whose vertical centres are close, then read each row left to right.
     */
    fun read(ctx: Context, uri: Uri, done: (Result<String>) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrElse { done(Result.failure(it)); return }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { t ->
                val lines = t.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
                if (lines.isEmpty()) { done(Result.success(t.text)); return@addOnSuccessListener }
                val sorted = lines.sortedBy { it.boundingBox!!.centerY() }
                val rows = ArrayList<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                for (l in sorted) {
                    val b = l.boundingBox!!
                    val row = rows.lastOrNull()
                    val rb = row?.first()?.boundingBox
                    if (row != null && rb != null && kotlin.math.abs(b.centerY() - rb.centerY()) < (rb.height() * 0.6)) row += l else rows += mutableListOf(l)
                }
                val text = rows.joinToString("\n") { r -> r.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text } }
                done(Result.success(text + "\n\n" + t.text))
            }
            .addOnFailureListener { done(Result.failure(it)) }
    }
}

/** "Take photo" / "Choose photo" buttons that hand back the parsed label (or an error). */
@Composable
fun ScanButtons(onResult: (Result<LabelParser.Result>) -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    val photoUri = remember {
        val f = File(ctx.cacheDir, "labels").apply { mkdirs() }.let { File(it, "label.jpg") }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }
    fun run(uri: Uri) {
        busy = true
        LabelOcr.read(ctx, uri) { r -> busy = false; onResult(r.map { LabelParser.parse(it) }) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) run(photoUri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) run(uri) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { runCatching { camera.launch(photoUri) }.onFailure { onResult(Result.failure(it)) } }) {
            Text(if (busy) "Reading…" else "Scan label")
        }
        OutlinedButton(enabled = !busy, onClick = {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("From photo") }
    }
}
