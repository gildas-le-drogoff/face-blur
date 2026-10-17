package org.example.faceblur

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = dynamicLightColorScheme(this)) { FaceBlurScreen() }
        }
    }
}

private class Loaded(val name: String, val bitmap: Bitmap, val candidates: List<Face>)

@Composable
private fun FaceBlurScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detector = remember { ScrfdDetector(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { detector.close() } }

    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var busy by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(0.5f) }
    var blur by remember { mutableStateOf(true) }
    var boxes by remember { mutableStateOf(false) }

    val faces = remember(loaded, threshold) {
        loaded?.let { ScrfdDetector.nms(it.candidates, threshold) }.orEmpty()
    }
    // L'aperçu est exactement l'image qui sera enregistrée.
    val preview by produceState<Bitmap?>(null, loaded, faces, blur, boxes) {
        val img = loaded
        value = if (img == null) null else withContext(Dispatchers.Default) {
            render(img.bitmap, faces, blur, boxes)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                loaded = withContext(Dispatchers.Default) {
                    val bmp = decode(context, uri)
                    Loaded(displayName(context, uri), bmp, detector.detectCandidates(bmp))
                }
            } catch (e: Exception) {
                toast(context, "Erreur : ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ouvrir la photothèque") }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val bmp = preview
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (!busy) {
                    Text("Aucune image")
                }
                if (busy) CircularProgressIndicator()
            }

            if (loaded != null) {
                Text("${faces.size} visage(s) détecté(s) — seuil ${"%.2f".format(threshold)}")
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0.2f..0.95f)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(blur, { blur = it })
                Text("Flouter")
                Checkbox(boxes, { boxes = it })
                Text("Dessiner les bbox")
            }

            Button(
                onClick = {
                    val img = loaded ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            val suffix = listOfNotNull("blur".takeIf { blur }, "bbox".takeIf { boxes }).joinToString("_")
                            val saved = withContext(Dispatchers.IO) {
                                saveImage(context, render(img.bitmap, faces, blur, boxes), "${img.name}_$suffix")
                            }
                            toast(context, "Enregistré : $saved")
                        } catch (e: Exception) {
                            toast(context, "Erreur : ${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && loaded != null && (blur || boxes),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer") }
        }
    }
}

private const val MAX_SIDE = 4096

/** Décode l'image (orientation EXIF appliquée par ImageDecoder), plafonnée à MAX_SIDE px. */
private fun decode(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val scale = MAX_SIDE.toFloat() / maxOf(w, h)
        if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
    }.copy(Bitmap.Config.ARGB_8888, false)
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.substringBeforeLast('.') ?: "image"

private fun saveImage(context: Context, bitmap: Bitmap, baseName: String): String {
    val name = "${baseName}_${System.currentTimeMillis()}.jpg"
    val dir = "${Environment.DIRECTORY_PICTURES}/FaceBlur"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, dir)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Impossible de créer $name")
    resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    return "$dir/$name"
}

private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
