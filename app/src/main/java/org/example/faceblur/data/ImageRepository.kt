package org.example.faceblur.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.example.faceblur.FaceDetectionLog
import org.example.faceblur.domain.ExportFormat
import org.example.faceblur.domain.ExportOptions
import org.example.faceblur.domain.exportFileName
import java.io.File
import java.io.InputStream

/** Image ouverte : pixels redressés et métadonnées lues à l'ouverture (l'URI du sélecteur peut expirer). */
class SourceImage(val name: String, val bitmap: Bitmap, val metadata: PhotoMetadata)

enum class MetadataOutcome { STRIPPED, KEPT, NONE_AVAILABLE, FAILED }

class SaveResult(val path: String, val metadata: MetadataOutcome)

/** Lecture depuis la photothèque et enregistrement dans Pictures/FaceBlur via MediaStore. */
class ImageRepository(private val context: Context) {

    fun load(uri: Uri): SourceImage {
        FaceDetectionLog.heap(TAG, "Before decode")
        val bitmap = FaceDetectionLog.timed(TAG, "Decoding image", { "${it.width}x${it.height}" }) { decode(uri) }
        FaceDetectionLog.heap(TAG, "After decode")
        val metadata = try {
            openOriginal(uri)?.use(ExifMetadata::read) ?: PhotoMetadata.NONE
        } catch (e: Exception) {
            FaceDetectionLog.w(TAG, e) { "Unreadable metadata, ignored" }
            PhotoMetadata.NONE
        }
        FaceDetectionLog.d(TAG) { "${metadata.exif.size} EXIF tags readable, XMP ${metadata.xmp?.length ?: 0} chars" }
        FaceDetectionLog.d(TAG) { "DEBUGTMP ${metadata.exif}" }
        return SourceImage(displayName(uri), bitmap, metadata)
    }

    /**
     * Sans ACCESS_MEDIA_LOCATION, Android remet à zéro la position GPS des fichiers lus.
     * Avec, il faut encore demander explicitement l'original non expurgé.
     */
    private fun openOriginal(uri: Uri): InputStream? {
        val resolver = context.contentResolver
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                return resolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (e: Exception) {
                FaceDetectionLog.w(TAG, e) { "Original unavailable, location may be redacted" }
            }
        }
        return resolver.openInputStream(uri)
    }

    /** Décode l'image (orientation EXIF appliquée par ImageDecoder), plafonnée à MAX_SIDE px. */
    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_SIDE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringBeforeLast('.') ?: "image"

    /**
     * Encode dans un fichier temporaire (ExifInterface exige un fichier seekable),
     * y ajoute les métadonnées si demandé, puis le copie dans MediaStore.
     * Sans métadonnées, Bitmap.compress n'en écrit aucune : rien à supprimer.
     */
    fun save(
        bitmap: Bitmap,
        source: SourceImage,
        suffix: String,
        options: ExportOptions,
    ): SaveResult {
        val name = exportFileName(source.name, suffix, options.format, System.currentTimeMillis())
        val tmp = File.createTempFile("export", ".${options.format.extension}", context.cacheDir)
        try {
            FaceDetectionLog.timed(TAG, "Encoding ${options.format}", { "${tmp.length() / 1024} KiB" }) {
                tmp.outputStream().use { bitmap.compress(options.format.compressFormat(), QUALITY, it) }
            }
            val metadata = when {
                !options.keepMetadata -> MetadataOutcome.STRIPPED
                source.metadata.isEmpty -> MetadataOutcome.NONE_AVAILABLE
                else -> try {
                    ExifMetadata.write(tmp, source.metadata, bitmap.width, bitmap.height)
                    MetadataOutcome.KEPT
                } catch (e: Exception) {
                    FaceDetectionLog.w(TAG, e) { "Metadata could not be written, image saved without it" }
                    // Le fichier a pu être laissé à moitié réécrit : on réencode sans métadonnées.
                    tmp.outputStream().use { bitmap.compress(options.format.compressFormat(), QUALITY, it) }
                    MetadataOutcome.FAILED
                }
            }
            val path = publish(tmp, name, options.format)
            FaceDetectionLog.i(TAG) { "Saved $path (metadata $metadata)" }
            return SaveResult(path, metadata)
        } finally {
            tmp.delete()
        }
    }

    private fun publish(file: File, name: String, format: ExportFormat): String {
        val dir = "${Environment.DIRECTORY_PICTURES}/FaceBlur"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, dir)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create $name")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "$dir/$name"
    }

    private fun ExportFormat.compressFormat(): Bitmap.CompressFormat = when (this) {
        ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ExportFormat.PNG -> Bitmap.CompressFormat.PNG
        ExportFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    private companion object {
        const val TAG = "ImageRepo"
        const val MAX_SIDE = 4096
        const val QUALITY = 95
    }
}
