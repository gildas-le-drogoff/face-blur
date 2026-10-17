// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import fr.gildasledrogoff.faceblur.FaceDetectionLog
import fr.gildasledrogoff.faceblur.domain.ExportFormat
import fr.gildasledrogoff.faceblur.domain.ExportOptions
import fr.gildasledrogoff.faceblur.domain.exportFileName
import java.io.File
import java.io.InputStream

class SourceImage(val name: String, val bitmap: Bitmap, val metadata: PhotoMetadata)
enum class MetadataOutcome { STRIPPED, KEPT, NONE_AVAILABLE, FAILED }
class SaveResult(val path: String, val uri: Uri, val metadata: MetadataOutcome)
class ImageRepository(private val context: Context) {
    fun load(uri: Uri): SourceImage = load(
        displayName(uri),
        ImageDecoder.createSource(context.contentResolver, uri),
    ) { context.contentResolver.openInputStream(uri) }

    /** Demo photo bundled in the debug assets only (absent from release builds). */
    val hasDemoImage: Boolean by lazy { context.assets.list("").orEmpty().contains(DEMO_ASSET) }

    fun loadDemoImage(): SourceImage = load(
        DEMO_ASSET.substringBeforeLast('.'),
        ImageDecoder.createSource(context.assets, DEMO_ASSET),
    ) { context.assets.open(DEMO_ASSET) }

    private fun load(
        name: String,
        source: ImageDecoder.Source,
        openStream: () -> InputStream?,
    ): SourceImage {
        FaceDetectionLog.heap(TAG, "Before decode")
        val bitmap = FaceDetectionLog.timed(TAG, "Decoding image", { "${it.width}x${it.height}" }) {
            decode(source)
        }
        FaceDetectionLog.heap(TAG, "After decode")
        val metadata = try {
            openStream()?.use(ExifMetadata::read) ?: PhotoMetadata.NONE
        } catch (e: Exception) {
            FaceDetectionLog.w(TAG, e) { "Unreadable metadata, ignored" }
            PhotoMetadata.NONE
        }
        FaceDetectionLog.d(TAG) { "${metadata.exif.size} EXIF tags readable, XMP ${metadata.xmp?.length ?: 0} chars" }
        return SourceImage(name, bitmap, metadata)
    }

    private fun decode(source: ImageDecoder.Source): Bitmap {
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val scale = MAX_SIDE.toFloat() / maxOf(width, height)
            if (scale < 1f) decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.substringBeforeLast('.') ?: "image"

    fun save(
        bitmap: Bitmap,
        source: SourceImage,
        suffix: String,
        options: ExportOptions,
    ): SaveResult {
        val name = exportFileName(source.name, suffix, options.format, System.currentTimeMillis())
        val tmp = File.createTempFile("export", ".${options.format.extension}", context.cacheDir)
        try {
            FaceDetectionLog.timed(
                TAG,
                "Encoding ${options.format}",
                { "${tmp.length() / 1024} KiB" }) {
                tmp.outputStream()
                    .use { bitmap.compress(options.format.compressFormat(), QUALITY, it) }
            }
            val metadata = when {
                !options.keepMetadata -> MetadataOutcome.STRIPPED
                source.metadata.isEmpty -> MetadataOutcome.NONE_AVAILABLE
                else -> try {
                    ExifMetadata.write(tmp, source.metadata, bitmap.width, bitmap.height)
                    MetadataOutcome.KEPT
                } catch (e: Exception) {
                    FaceDetectionLog.w(
                        TAG,
                        e
                    ) { "Metadata could not be written, image saved without it" }
                    tmp.outputStream()
                        .use { bitmap.compress(options.format.compressFormat(), QUALITY, it) }
                    MetadataOutcome.FAILED
                }
            }
            val uri = publish(tmp, name, options.format)
            val path = "$EXPORT_DIR/$name"
            FaceDetectionLog.i(TAG) { "Saved $path as $uri (metadata $metadata)" }
            return SaveResult(path, uri, metadata)
        } finally {
            tmp.delete()
        }
    }

    private fun publish(file: File, name: String, format: ExportFormat): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, EXPORT_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create $name")
        try {
            resolver.openOutputStream(uri)!!
                .use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
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
        const val DEMO_ASSET = "screenshot_demo.jpg"
        val EXPORT_DIR = "${Environment.DIRECTORY_PICTURES}/FaceBlur"
    }
}
