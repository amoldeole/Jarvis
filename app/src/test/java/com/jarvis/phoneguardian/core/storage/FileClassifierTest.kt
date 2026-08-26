package com.jarvis.phoneguardian.core.storage

import com.jarvis.phoneguardian.core.model.MediaTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileClassifierTest {
    @Test fun `magic bytes win over misleading extension`() {
        val pdf = "%PDF-1.7".toByteArray()
        assertEquals(MediaTypes.DOCUMENT, FileClassifier.classify("invoice.jpg", "image/jpeg", "Download/invoice.jpg", pdf).mediaType)
    }

    @Test fun `renamed camera photo is detected`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        assertEquals(MediaTypes.PHOTO, FileClassifier.classify("unknown_file", null, "DCIM/Camera/unknown_file", jpeg).mediaType)
    }

    @Test fun `document destinations are stable`() {
        val file = com.jarvis.phoneguardian.core.model.FileEntity("u", "Download/a.pdf", "a.pdf", "pdf", "application/pdf", 10, null, 1, MediaTypes.DOCUMENT, "Download")
        val destination = FileClassifier.destinationFor(file).second
        assertTrue(destination.startsWith("Phone/Documents/PDF/"))
    }
}
