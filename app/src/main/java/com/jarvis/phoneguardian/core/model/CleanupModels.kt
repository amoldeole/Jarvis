package com.jarvis.phoneguardian.core.model

import android.net.Uri

/** A SAF folder that was re-checked and found to contain no children. */
data class EmptyFolder(
    val uri: Uri,
    val path: String
)
