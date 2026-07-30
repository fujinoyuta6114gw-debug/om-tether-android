package com.example.omtether.history

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoMetadataFormatterTest {
    @Test
    fun formatsActualExposureValuesForPhotographers() {
        assertEquals("f/2.8", PhotoMetadataFormatter.aperture(2.8))
        assertEquals("1/400", PhotoMetadataFormatter.exposureTime(1.0 / 400.0))
        assertEquals("1.3 s", PhotoMetadataFormatter.exposureTime(1.3))
        assertEquals("ISO 1600", PhotoMetadataFormatter.iso(1600))
        assertEquals("-0.7 EV", PhotoMetadataFormatter.exposureBias(-2.0 / 3.0))
        assertEquals("40 mm", PhotoMetadataFormatter.focalLength(40.0))
        assertEquals("12.5 mm", PhotoMetadataFormatter.focalLength(12.5))
    }

    @Test
    fun formatsExifAndPtpCaptureDates() {
        assertEquals(
            "2026/07/31 14:05:09",
            PhotoMetadataFormatter.capturedAt("2026:07:31 14:05:09"),
        )
        assertEquals(
            "2026/07/31 14:05:09",
            PhotoMetadataFormatter.capturedAt("20260731T140509"),
        )
    }

    @Test
    fun neverInventsMissingMetadata() {
        assertEquals("—", PhotoMetadataFormatter.aperture(null))
        assertEquals("—", PhotoMetadataFormatter.exposureTime(null))
        assertEquals("—", PhotoMetadataFormatter.iso(null))
        assertEquals("—", PhotoMetadataFormatter.capturedAt(null))
    }
}
