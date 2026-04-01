package {{packageName}}.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import {{packageName}}.config.AppConfig
import {{packageName}}.data.model.ChatMessage
import {{packageName}}.data.model.MessageRole
import {{packageName}}.data.model.SourceReference
import {{packageName}}.data.repository.ChatRepository
import {{packageName}}.service.HybridInferenceService
import {{packageName}}.service.RemoteLLMService
import {{packageName}}.service.SdxInferenceService
import {{packageName}}.service.VectorSearchService
import {{packageName}}.ui.components.ChatBubble
import {{packageName}}.ui.components.ChatInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(sessionId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val chatRepository = remember { ChatRepository(context) }
    val vectorSearchService = remember { VectorSearchService() }

    var currentSessionId by remember { mutableStateOf(sessionId) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var streamingContent by remember { mutableStateOf("") }

    // Initialize or load session
    LaunchedEffect(currentSessionId) {
        val session = chatRepository.getOrCreateSession(currentSessionId)
        currentSessionId = session.id
        chatRepository.getMessages(session.id).collectLatest { msgs ->
            messages = msgs
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotEmpty()) {
            val targetIndex = if (streamingContent.isNotEmpty()) messages.size else messages.size - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex.coerceAtMost(messages.size))
            }
        }
    }

    fun sendMessage(userText: String) {
        val sid = currentSessionId ?: return
        scope.launch {
            // Add user message
            val userMessage = ChatMessage.userMessage(sid, userText)
            chatRepository.addMessage(userMessage)

            isGenerating = true
            streamingContent = ""

            try {
                // Build context from conversation history
                val history = chatRepository.getMessagesOnce(sid)

                // Perform vector search if documents are indexed
                val searchResults = vectorSearchService.search(userText, AppConfig.ragTopK)
                val contextSnippets = searchResults.map { it.chunkText }
                val sources = searchResults.map { result ->
                    SourceReference(
                        documentId = result.documentId,
                        documentName = result.documentName,
                        chunkText = result.chunkText.take(200),
                        score = result.score
                    )
                }

                // Build prompt with RAG context
                val prompt = buildPrompt(userText, history, contextSnippets)

                // Stream the response
                val fullResponse = StringBuilder()
                val inferenceFlow = when (AppConfig.inferenceMode) {
                    "local" -> {
                        val sdxService = SdxInferenceService(context)
                        sdxService.generate(prompt)
                    }
                    "remote" -> {
                        val remoteService = RemoteLLMService()
                        remoteService.generateStream(prompt, history)
                    }
                    "hybrid" -> {
                        val hybridService = HybridInferenceService(context)
                        hybridService.generate(prompt, history, contextSnippets)
                    }
                    else -> {
                        val remoteService = RemoteLLMService()
                        remoteService.generateStream(prompt, history)
                    }
                }

                inferenceFlow.collectLatest { token ->
                    fullResponse.append(token)
                    streamingContent = fullResponse.toString()
                }

                // Save assistant message
                val assistantMessage = ChatMessage.assistantMessage(
                    sessionId = sid,
                    content = fullResponse.toString(),
                    sources = sources
                )
                chatRepository.addMessage(assistantMessage)

            } catch (e: Exception) {
                val errorMessage = ChatMessage.assistantMessage(
                    sessionId = sid,
                    content = "Error: ${e.message ?: "An unexpected error occurred."}"
                )
                chatRepository.addMessage(errorMessage)
            } finally {
                isGenerating = false
                streamingContent = ""
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "{{projectName}}",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Progress indicator during generation
            if (isGenerating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Messages list
            if (messages.isEmpty() && streamingContent.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Text(
                            text = "Start a conversation",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Ask anything or import documents for RAG",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message = message)
                    }

                    // Show streaming content as a temporary bubble
                    if (streamingContent.isNotEmpty()) {
                        item(key = "streaming") {
                            ChatBubble(
                                message = ChatMessage(
                                    sessionId = currentSessionId ?: "",
                                    role = MessageRole.ASSISTANT,
                                    content = streamingContent
                                ),
                                isStreaming = true
                            )
                        }
                    }
                }
            }

            // Input bar
            ChatInput(
                onSend = { text -> sendMessage(text) },
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun buildPrompt(
    userQuery: String,
    history: List<ChatMessage>,
    contextSnippets: List<String>
): String {
    val sb = StringBuilder()

    if (contextSnippets.isNotEmpty()) {
        sb.appendLine("Use the following context to answer the question. If the context is not relevant, answer based on your general knowledge.")
        sb.appendLine()
        sb.appendLine("Context:")
        contextSnippets.forEachIndexed { index, snippet ->
            sb.appendLine("[${index + 1}] $snippet")
        }
        sb.appendLine()
    }

    sb.appendLine("Question: $userQuery")
    return sb.toString()
}
