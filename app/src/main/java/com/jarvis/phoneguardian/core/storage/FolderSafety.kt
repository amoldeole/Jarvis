package com.jarvis.phoneguardian.core.storage

import java.util.Locale

/** Conservative folder policy: an unfamiliar folder is protected until the user opts in. */
object FolderSafety {
    private val knownRoots = setOf(
        "dcim", "pictures", "movies", "music", "podcasts", "ringtones", "alarms", "notifications",
        "download", "downloads", "documents", "bluetooth", "recordings", "screenshots",
        "android", "whatsapp", "telegram", "instagram", "facebook", "nearby share", "shareit"
    )
    private val knownSubfolders = setOf(
        "camera", "screenrecord", "screen recordings", "screenshots", "whatsapp images", "whatsapp video",
        "whatsapp documents", "telegram images", "telegram video", "sent", "received"
    )

    fun isLikelyUserCreated(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
            .map { it.trim().lowercase(Locale.ROOT) }
        if (parts.size < 2) return false
        val folders = parts.drop(1).dropLast(1)
        return folders.any { it !in knownRoots && it !in knownSubfolders && !it.startsWith("android/") }
    }
}
