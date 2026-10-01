package {{packageName}}.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import {{packageName}}.data.db.AppDatabase
import {{packageName}}.data.model.ChatMessage
import {{packageName}}.data.model.ChatSession

class ChatRepository(context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val chatDao = database.chatDao()
    private val sessionDao = database.sessionDao()

    // Session operations

    fun getAllSessions(): Flow<List<ChatSession>> = sessionDao.getAllSessions()

    suspend fun getSession(sessionId: String): ChatSession? = sessionDao.getSessionById(sessionId)

    fun observeSession(sessionId: String): Flow<ChatSession?> = sessionDao.observeSession(sessionId)

    suspend fun createSession(title: String = "New Chat"): ChatSession {
        val session = ChatSession.create(title)
        sessionDao.insertSession(session)
        return session
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title)
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesForSession(sessionId)
        sessionDao.deleteSessionById(sessionId)
    }

    // Message operations

    fun getMessages(sessionId: String): Flow<List<ChatMessage>> =
        chatDao.getMessagesForSession(sessionId)

    suspend fun getMessagesOnce(sessionId: String): List<ChatMessage> =
        chatDao.getMessagesForSessionOnce(sessionId)

    suspend fun addMessage(message: ChatMessage) {
        chatDao.insertMessage(message)
        val count = chatDao.getMessageCount(message.sessionId)
        sessionDao.updateMessageCount(message.sessionId, count)

        // Auto-generate title from first user message
        if (count == 1 && message.role == {{packageName}}.data.model.MessageRole.USER) {
            val title = message.content.take(40).let {
                if (message.content.length > 40) "$it..." else it
            }
            sessionDao.updateSessionTitle(message.sessionId, title)
        }
    }

    suspend fun updateMessage(message: ChatMessage) {
        chatDao.updateMessage(message)
    }

    suspend fun getLastMessage(sessionId: String): ChatMessage? =
        chatDao.getLastMessage(sessionId)

    suspend fun deleteMessage(message: ChatMessage) {
        chatDao.deleteMessage(message)
        val count = chatDao.getMessageCount(message.sessionId)
        sessionDao.updateMessageCount(message.sessionId, count)
    }

    suspend fun getOrCreateSession(sessionId: String?): ChatSession {
        if (sessionId != null) {
            val existing = sessionDao.getSessionById(sessionId)
            if (existing != null) return existing
        }
        return createSession()
    }
}
