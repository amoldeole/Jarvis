package com.jarvis.phoneguardian.core.storage

import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.MediaTypes
import java.util.Locale

/**
 * Deterministic, offline classification. Extensions are only one signal; a few common
 * signatures are checked so renamed media is not silently miscategorized.
 */
object FileClassifier {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "dng", "raw")
    private val videoExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "flv", "wmv", "mpeg", "mpg")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "mid", "midi", "wma")
    private val documentExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml", "md", "rtf", "odt", "ods", "odp", "kt", "java", "py", "js", "ts", "html", "css", "c", "cpp", "h", "log")
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "cab", "apk", "apkm", "xapk", "aab", "dmg", "msi", "exe")

    data class Result(val mediaType: String, val normalizedMime: String, val confidence: Float)

    fun classify(fileName: String, mimeType: String?, displayPath: String, signature: ByteArray = byteArrayOf()): Result {
        val name = fileName.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "")
        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        val path = displayPath.lowercase(Locale.ROOT)
        val signatureType = signatureType(signature)

        val type = when {
            extension in setOf("apk", "apkm", "xapk", "aab", "exe", "msi", "dmg") -> MediaTypes.INSTALLER
            signatureType != null -> signatureType
            mime.startsWith("image/") || extension in imageExtensions -> MediaTypes.PHOTO
            mime.startsWith("video/") || extension in videoExtensions -> MediaTypes.VIDEO
            mime.startsWith("audio/") || extension in audioExtensions -> MediaTypes.AUDIO
            mime.startsWith("text/") || mime.contains("pdf") || extension in documentExtensions -> MediaTypes.DOCUMENT
            extension in archiveExtensions -> if (extension in setOf("apk", "apkm", "xapk", "aab", "exe", "msi", "dmg")) MediaTypes.INSTALLER else MediaTypes.ARCHIVE
            path.contains("screenshot") || path.contains("screen_shot") -> MediaTypes.PHOTO
            else -> MediaTypes.OTHER
        }
        val normalized = when (type) {
            MediaTypes.PHOTO -> if (mime.startsWith("image/")) mime else "image/*"
            MediaTypes.VIDEO -> if (mime.startsWith("video/")) mime else "video/*"
            MediaTypes.AUDIO -> if (mime.startsWith("audio/")) mime else "audio/*"
            MediaTypes.DOCUMENT -> if (mime.isNotBlank()) mime else "application/octet-stream"
            MediaTypes.ARCHIVE -> if (mime.isNotBlank()) mime else "application/octet-stream"
            else -> mime.ifBlank { "application/octet-stream" }
        }
        return Result(type, normalized, if (signatureType != null) 1f else if (type != MediaTypes.OTHER) .8f else .2f)
    }

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun destinationFor(file: FileEntity): Pair<String, String> {
        val folder = when (file.mediaType) {
            MediaTypes.PHOTO -> "Photos/Other"
            MediaTypes.VIDEO -> "Videos/Other"
            MediaTypes.AUDIO -> "Audio"
            MediaTypes.DOCUMENT -> when (extension(file.fileName)) {
                "pdf" -> "Documents/PDF"
                "doc", "docx", "odt" -> "Documents/Word"
                "xls", "xlsx", "ods", "csv" -> "Documents/Excel"
                "ppt", "pptx", "odp" -> "Documents/PowerPoint"
                else -> "Documents/Other"
            }
            MediaTypes.ARCHIVE -> "Archives/${extension(file.fileName).uppercase(Locale.ROOT).ifBlank { "Other" }}"
            MediaTypes.INSTALLER -> if (extension(file.fileName) in setOf("exe", "msi", "dmg")) "Installers/Desktop" else "Installers/Android"
            else -> "Other"
        }
        return folder to "Phone/$folder/${file.fileName}"
    }

    private fun signatureType(bytes: ByteArray): String? {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return MediaTypes.PHOTO
        if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return MediaTypes.PHOTO
        if (bytes.size >= 6 && String(bytes.copyOfRange(0, 6), Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) return MediaTypes.PHOTO
        if (bytes.size >= 12 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF" && String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP") return MediaTypes.PHOTO
        if (bytes.size >= 12 && String(bytes.copyOfRange(4, 8), Charsets.US_ASCII) == "ftyp") return MediaTypes.VIDEO
        if (bytes.size >= 3 && String(bytes.copyOfRange(0, 3), Charsets.US_ASCII) == "ID3") return MediaTypes.AUDIO
        if (bytes.size >= 4 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "OggS") return MediaTypes.AUDIO
        if (bytes.size >= 4 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "%PDF") return MediaTypes.DOCUMENT
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) return MediaTypes.ARCHIVE
        return null
    }
}
