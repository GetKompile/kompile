package {{packageName}}.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
) {
    @Ignore
    var messages: List<ChatMessage> = emptyList()

    companion object {
        fun create(title: String = "New Chat"): ChatSession {
            return ChatSession(title = title)
        }
    }

    fun withUpdatedTimestamp(): ChatSession {
        return copy(updatedAt = System.currentTimeMillis())
    }

    fun withTitle(newTitle: String): ChatSession {
        return copy(title = newTitle, updatedAt = System.currentTimeMillis())
    }

    fun withMessageCount(count: Int): ChatSession {
        return copy(messageCount = count, updatedAt = System.currentTimeMillis())
    }

    fun formattedDate(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(updatedAt))
    }
}
