package com.maven.diet.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.maven.diet.core.OpenFoodFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Barcode → Open Food Facts (§ 5 step 2): photo of the barcode, read on the phone (ML Kit), then one lookup on
 * openfoodfacts.org for the name, brand and label. Everything lands in the form to check before saving.
 */
object BarcodeLookup {
    fun read(ctx: Context, uri: Uri, done: (Result<String>) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrElse { done(Result.failure(it)); return }
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { codes ->
                val v = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { r -> r.any(Char::isDigit) } }
                if (v == null) done(Result.failure(IllegalStateException("No barcode found in that photo. Fill the frame with it, in good light.")))
                else done(Result.success(v))
            }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    fun fetch(barcode: String): String {
        val c = URL(OpenFoodFacts.url(barcode)).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "NotATwig/0.3 (personal diet app; Android)")
        return try {
            if (c.responseCode == 404) """{"status":0}""" else c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}

@Composable
fun BarcodeButton(newId: (String) -> String, onResult: (Result<OpenFoodFacts.Parsed>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val photoUri = remember {
        val f = File(ctx.cacheDir, "labels").apply { mkdirs() }.let { File(it, "barcode.jpg") }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        busy = true
        BarcodeLookup.read(ctx, photoUri) { r ->
            r.onFailure { busy = false; onResult(Result.failure(it)) }
            r.onSuccess { code ->
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching { OpenFoodFacts.parse(BarcodeLookup.fetch(code), code, newId(code)) ?: error("$code isn't on Open Food Facts yet. Scan the label instead.") }
                    }
                    busy = false
                    onResult(res)
                }
            }
        }
    }
    OutlinedButton(enabled = !busy, onClick = { runCatching { camera.launch(photoUri) }.onFailure { onResult(Result.failure(it)) } }) {
        Text(if (busy) "Looking up…" else "Scan barcode")
    }
}
