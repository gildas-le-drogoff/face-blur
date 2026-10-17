// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

class PhotoMetadata(val exif: Map<String, String>, val xmp: String?) {
    val isEmpty: Boolean get() = exif.isEmpty() && xmp == null

    companion object {
        val NONE = PhotoMetadata(emptyMap(), null)
    }
}

object ExifMetadata {
    val COPIED_TAGS: List<String> = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_RELATED_SOUND_FILE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY,
        ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ,
        ExifInterface.TAG_SPECTRAL_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_INDEX,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_INTEROPERABILITY_INDEX,
    )
    val EXCLUDED_TAGS: Set<String> = setOf(
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_XMP,
        ExifInterface.TAG_MAKER_NOTE,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
    )
    private val CHARSET_PREFIXED_TAGS = setOf(
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
    )
    private const val ASCII_PREFIX = "ASCII\u0000\u0000\u0000"
    private val REQUIRED_COMPANIONS = mapOf(
        ExifInterface.TAG_GPS_LATITUDE to ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE to ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE to ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE to ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP to ExifInterface.TAG_GPS_DATESTAMP,
    )
    private val ZERO_DENOMINATOR = Regex("""(^|,)-?\d+/0(,|$)""")
    fun read(input: InputStream): PhotoMetadata {
        val exif = ExifInterface(input)
        val tags = COPIED_TAGS.mapNotNull { tag ->
            exif.getAttribute(tag)?.takeIf(::isUsable)?.let { tag to it }
        }
            .toMap()
            .let { tags ->
                tags.filterKeys {
                    REQUIRED_COMPANIONS[it]?.let(tags::containsKey) ?: true
                }
            }
        val xmp = exif.getAttributeBytes(ExifInterface.TAG_XMP)?.let(XmpFilter::filter)
        return PhotoMetadata(withoutOrphanGps(tags), xmp)
    }

    internal fun withoutOrphanGps(tags: Map<String, String>): Map<String, String> =
        if (ExifInterface.TAG_GPS_LATITUDE in tags && ExifInterface.TAG_GPS_LONGITUDE in tags) tags
        else tags.filterKeys { !it.startsWith("GPS") }

    internal fun isUsable(value: String): Boolean {
        val trimmed = value.trim(' ', '\u0000')
        return trimmed.isNotEmpty() &&
                !ZERO_DENOMINATOR.containsMatchIn(trimmed) &&
                trimmed != "NaN" && !trimmed.contains("Infinity") &&
                !trimmed.startsWith("UNICODE") && !trimmed.startsWith("JIS")
    }

    fun write(file: File, metadata: PhotoMetadata, width: Int, height: Int) {
        val exif = ExifInterface(file)
        for ((tag, value) in metadata.exif) {
            if (tag in EXCLUDED_TAGS) continue
            exif.setAttribute(
                tag,
                if (tag in CHARSET_PREFIXED_TAGS) ASCII_PREFIX + value else value
            )
        }
        metadata.xmp?.let { exif.setAttribute(ExifInterface.TAG_XMP, it) }
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.saveAttributes()
    }
}
