package org.example.faceblur.data

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
