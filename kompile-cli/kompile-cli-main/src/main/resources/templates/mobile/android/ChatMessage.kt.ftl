package {{packageName}}.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sources: String = "" // JSON-serialized list of source references
) {
    fun getSourceList(): List<SourceReference> {
        if (sources.isBlank()) return emptyList()
        return try {
            com.google.gson.Gson().fromJson(
                sources,
                com.google.gson.reflect.TypeToken.getParameterized(
                    List::class.java,
                    SourceReference::class.java
                ).type
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun userMessage(sessionId: String, content: String): ChatMessage {
            return ChatMessage(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = content
            )
        }

        fun assistantMessage(
            sessionId: String,
            content: String,
            sources: List<SourceReference> = emptyList()
        ): ChatMessage {
            val sourcesJson = if (sources.isNotEmpty()) {
                com.google.gson.Gson().toJson(sources)
            } else ""
            return ChatMessage(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = content,
                sources = sourcesJson
            )
        }

        fun systemMessage(sessionId: String, content: String): ChatMessage {
            return ChatMessage(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = content
            )
        }
    }
}

data class SourceReference(
    val documentId: String = "",
    val documentName: String = "",
    val chunkText: String = "",
    val score: Float = 0f
)
