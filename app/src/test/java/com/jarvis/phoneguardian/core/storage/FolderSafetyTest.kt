package com.jarvis.phoneguardian.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSafetyTest {
    @Test fun `known source folders are eligible for recommendation`() {
        assertFalse(FolderSafety.isLikelyUserCreated("DCIM/Camera/IMG_1.jpg"))
        assertFalse(FolderSafety.isLikelyUserCreated("Download/report.pdf"))
    }

    @Test fun `unfamiliar existing folder is protected`() {
        assertTrue(FolderSafety.isLikelyUserCreated("Pictures/Wedding Photos/wedding.jpg"))
        assertTrue(FolderSafety.isLikelyUserCreated("Documents/2025 Tax/return.pdf"))
    }
}
