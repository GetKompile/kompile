package {{packageName}}.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SourceType {
    FILE,
    URL,
    TEXT,
    PDF,
    DOCX,
    CSV
}

@Entity(tableName = "document_sources")
data class DocumentSource(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceType: SourceType,
    val uri: String = "",
    val chunkCount: Int = 0,
    val importedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0,
    val status: String = "imported" // imported, indexing, indexed, error
) {
    fun formattedDate(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(importedAt))
    }

    fun formattedSize(): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${"%.1f".format(sizeBytes / (1024.0 * 1024.0))} MB"
        }
    }

    companion object {
        fun fromFile(name: String, uri: String, sizeBytes: Long): DocumentSource {
            val type = when {
                name.endsWith(".pdf", ignoreCase = true) -> SourceType.PDF
                name.endsWith(".docx", ignoreCase = true) -> SourceType.DOCX
                name.endsWith(".csv", ignoreCase = true) -> SourceType.CSV
                else -> SourceType.FILE
            }
            return DocumentSource(
                name = name,
                sourceType = type,
                uri = uri,
                sizeBytes = sizeBytes
            )
        }

        fun fromUrl(url: String): DocumentSource {
            return DocumentSource(
                name = url.substringAfterLast("/").take(50).ifBlank { url.take(50) },
                sourceType = SourceType.URL,
                uri = url
            )
        }

        fun fromText(name: String, textLength: Int): DocumentSource {
            return DocumentSource(
                name = name,
                sourceType = SourceType.TEXT,
                sizeBytes = textLength.toLong()
            )
        }
    }
}
