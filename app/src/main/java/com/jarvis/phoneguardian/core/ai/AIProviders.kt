package com.jarvis.phoneguardian.core.ai

import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.storage.FileClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AIProvider {
    val id: String
    val isOnDevice: Boolean
    suspend fun classify(files: List<FileEntity>): Map<String, String>
    suspend fun explain(file: FileEntity): String
}

class DisabledAIProvider : AIProvider {
    override val id = "disabled"
    override val isOnDevice = true
    override suspend fun classify(files: List<FileEntity>) = emptyMap<String, String>()
    override suspend fun explain(file: FileEntity) = "Rule-based metadata only."
}

/** No model download or network is required. This is a privacy-preserving local enhancement hook. */
class LocalMetadataAIProvider : AIProvider {
    override val id = "local_metadata"
    override val isOnDevice = true

    override suspend fun classify(files: List<FileEntity>): Map<String, String> = withContext(Dispatchers.Default) {
        files.associate { file ->
            val lower = file.fileName.lowercase()
            val category = when {
                listOf("payslip", "salary", "payroll", "form 16").any { lower.contains(it) } -> "Payslips"
                listOf("insurance", "policy", "premium").any { lower.contains(it) } -> "Insurance"
                listOf("receipt", "invoice", "bill").any(lower::contains) -> "Receipts"
                file.mediaType == "photo" && file.displayPath.contains("screenshot", true) -> "Screenshots"
                else -> FileClassifier.destinationFor(file).first
            }
            file.uri to category
        }
    }

    override suspend fun explain(file: FileEntity): String = when {
        file.displayPath.contains("whatsapp", true) -> "The source folder indicates WhatsApp media."
        file.fileName.contains("payslip", true) || file.fileName.contains("salary", true) -> "The filename resembles a payslip; review before creating any rule."
        else -> "The recommendation uses local metadata only."
    }
}

/** A cloud implementation must be injected by the product owner with consent and a privacy policy. */
interface ExplicitCloudAIProvider : AIProvider {
    override val isOnDevice: Boolean get() = false
}
