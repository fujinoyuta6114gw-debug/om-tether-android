package com.example.omtether.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureSavePolicyTest {
    @Test
    fun `selects requested type across card one and card two`() {
        val cardOneRaw = objectInfo(
            storageId = 0x0001_0001L,
            filename = "P7260001.ORF",
            format = 0x3000,
            size = 24_000_000L,
        )
        val cardTwoJpeg = objectInfo(
            storageId = 0x0002_0001L,
            filename = "P7260001.JPG",
            format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
            size = 8_000_000L,
        )
        val candidates = listOf(cardOneRaw, cardTwoJpeg)

        assertSame(
            cardTwoJpeg,
            CaptureSavePolicy.selectPreferred(PhoneSaveFormat.JPEG, candidates),
        )
        assertSame(
            cardOneRaw,
            CaptureSavePolicy.selectPreferred(PhoneSaveFormat.RAW, candidates),
        )
    }

    @Test
    fun `prefers largest copy when both cards contain jpeg`() {
        val smaller = objectInfo(
            storageId = 0x0001_0001L,
            filename = "P7260002.JPG",
            format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
            size = 4_000_000L,
        )
        val larger = objectInfo(
            storageId = 0x0002_0001L,
            filename = "P7260002.JPG",
            format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
            size = 9_000_000L,
        )

        val ordered = CaptureSavePolicy.orderedPreferred(
            PhoneSaveFormat.JPEG,
            listOf(smaller, larger),
        )

        assertEquals(listOf(larger, smaller), ordered)
    }

    @Test
    fun `jpeg selection stays empty for raw only capture`() {
        val raw = objectInfo(
            storageId = 0x0001_0001L,
            filename = "P7260003.orf",
            format = 0x3000,
            size = 25_000_000L,
        )

        assertNull(CaptureSavePolicy.selectPreferred(PhoneSaveFormat.JPEG, listOf(raw)))
        assertSame(raw, CaptureSavePolicy.selectPreferred(PhoneSaveFormat.RAW, listOf(raw)))
    }

    @Test
    fun `coherent batch keeps dual-card companions and drops adjacent older image`() {
        val eventRaw = objectInfo(
            storageId = 0x0001_0001L,
            filename = "P7260100.ORF",
            format = 0x3000,
            size = 24_000_000L,
            captureDate = "20260726T120001",
        )
        val olderJpeg = objectInfo(
            storageId = 0x0002_0001L,
            filename = "P7260099.JPG",
            format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
            size = 12_000_000L,
            captureDate = "20260726T115959",
        )
        val companionJpeg = objectInfo(
            storageId = 0x0002_0001L,
            filename = "P7260100.JPG",
            format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
            size = 8_000_000L,
            captureDate = "20260726T120001",
        )

        assertEquals(
            listOf(eventRaw, companionJpeg),
            CaptureSavePolicy.coherentCaptureBatch(listOf(eventRaw, olderJpeg, companionJpeg)),
        )
    }

    @Test
    fun `coherent batch can pair different card names by capture timestamp`() {
        val eventRaw = objectInfo(
            storageId = 0x0001_0001L,
            filename = "RAW0101.ORF",
            format = 0x3000,
            size = 24_000_000L,
            captureDate = "20260726T120002",
        )
        val companionJpeg = objectInfo(
            storageId = 0x0002_0001L,
            filename = "JPEG7777.JPG",
            format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
            size = 8_000_000L,
            captureDate = "20260726T120002",
        )

        assertEquals(
            listOf(eventRaw, companionJpeg),
            CaptureSavePolicy.coherentCaptureBatch(listOf(eventRaw, companionJpeg)),
        )
    }

    private fun objectInfo(
        storageId: Long,
        filename: String,
        format: Int,
        size: Long,
        captureDate: String = "20260726T120000",
    ) = PtpObjectInfo(
        storageId = storageId,
        format = format,
        compressedSize = size,
        thumbFormat = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
        thumbSize = 320_000L,
        imageWidth = 5_184L,
        imageHeight = 3_888L,
        filename = filename,
        captureDate = captureDate,
    )
}
