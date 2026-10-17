// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
class ExifMetadataTest {
    @Test
    fun `privacy sensitive tags are never copied`() {
        val leaks = ExifMetadata.COPIED_TAGS.filter { it in ExifMetadata.EXCLUDED_TAGS }
        assertTrue("balises interdites copiées : $leaks", leaks.isEmpty())
        for (tag in listOf(
            ExifInterface.TAG_XMP,
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_SUBJECT_AREA,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_ORIENTATION,
        )) {
            assertTrue(tag, tag in ExifMetadata.EXCLUDED_TAGS)
        }
    }
    @Test
    fun `useful tags are kept when the user asks for it`() {
        for (tag in listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_SENSITIVITY_TYPE,
        )) {
            assertTrue(tag, tag in ExifMetadata.COPIED_TAGS)
        }
    }
    @Test
    fun `location zeroed by Android is not copied`() {
        assertFalse(ExifMetadata.isUsable("0/0,0/0,0/0"))
        assertFalse(ExifMetadata.isUsable(""))
        assertFalse(ExifMetadata.isUsable("\u0000\u0000"))
        assertFalse(ExifMetadata.isUsable("NaN"))
        assertTrue(ExifMetadata.isUsable("48/1,54/1,5534/100"))
        assertTrue(ExifMetadata.isUsable("0/1"))
        assertTrue(ExifMetadata.isUsable("N"))
    }
    @Test
    fun `gps data without position is dropped`() {
        val redacted = mapOf(
            ExifInterface.TAG_MAKE to "Google",
            ExifInterface.TAG_GPS_ALTITUDE to "0/1",
            ExifInterface.TAG_GPS_IMG_DIRECTION to "0/1",
        )
        assertEquals(mapOf(ExifInterface.TAG_MAKE to "Google"), ExifMetadata.withoutOrphanGps(redacted))
        val located = redacted + mapOf(
            ExifInterface.TAG_GPS_LATITUDE to "48/1,54/1,5534/100",
            ExifInterface.TAG_GPS_LONGITUDE to "2/1,21/1,4058/100",
        )
        assertEquals(located, ExifMetadata.withoutOrphanGps(located))
    }
}
