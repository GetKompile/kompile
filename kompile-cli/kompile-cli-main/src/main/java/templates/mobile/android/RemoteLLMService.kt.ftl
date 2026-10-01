package {{packageName}}.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import {{packageName}}.config.AppConfig
import {{packageName}}.data.model.ChatMessage
import {{packageName}}.data.model.MessageRole
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service for remote LLM inference via an OpenAI-compatible API.
 * Supports Server-Sent Events (SSE) for streaming token responses.
 */
class RemoteLLMService {

    companion object {
        private const val TAG = "RemoteLLM"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send a chat completion request and stream the response tokens.
     */
    fun generateStream(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        model: String = "gpt-4o-mini",
        temperature: Float = AppConfig.defaultTemperature
    ): Flow<String> = callbackFlow {
        val messages = buildMessageList(prompt, history)

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(messages))
            addProperty("temperature", temperature)
            addProperty("max_tokens", AppConfig.maxGenerationTokens)
            addProperty("stream", true)
        }

        val request = Request.Builder()
            .url("${AppConfig.remoteApiBaseUrl}chat/completions")
            .header("Authorization", "Bearer ${AppConfig.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val eventSourceFactory = EventSources.createFactory(httpClient)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close()
                    return
                }

                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val delta = choices[0].asJsonObject
                            .getAsJsonObject("delta")
                        val content = delta?.get("content")?.asString
                        if (content != null) {
                            trySend(content)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE event: $data", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    response?.code == 401 -> "Invalid API key. Check your settings."
                    response?.code == 429 -> "Rate limited. Please wait and try again."
                    response?.code == 500 -> "Server error. Try again later."
                    t is IOException -> "Network error: ${t.message}"
                    t != null -> "Error: ${t.message}"
                    else -> "Unknown error (HTTP ${response?.code})"
                }
                Log.e(TAG, "SSE failure: $errorMsg", t)
                close(RuntimeException(errorMsg))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = eventSourceFactory.newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a non-streaming chat completion request.
     */
    suspend fun generateComplete(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        model: String = "gpt-4o-mini",
        temperature: Float = AppConfig.defaultTemperature
    ): String = withContext(Dispatchers.IO) {
        val messages = buildMessageList(prompt, history)

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(messages))
            addProperty("temperature", temperature)
            addProperty("max_tokens", AppConfig.maxGenerationTokens)
            addProperty("stream", false)
        }

        val request = Request.Builder()
            .url("${AppConfig.remoteApiBaseUrl}chat/completions")
            .header("Authorization", "Bearer ${AppConfig.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("API request failed: HTTP ${response.code} ${response.message}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response body")
        val json = JsonParser.parseString(body).asJsonObject
        val choices = json.getAsJsonArray("choices")
        if (choices != null && choices.size() > 0) {
            choices[0].asJsonObject
                .getAsJsonObject("message")
                ?.get("content")?.asString ?: ""
        } else {
            ""
        }
    }

    /**
     * Compute embeddings using the remote API.
     */
    suspend fun computeEmbedding(
        text: String,
        model: String = "text-embedding-3-small"
    ): FloatArray = withContext(Dispatchers.IO) {
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("input", text)
        }

        val request = Request.Builder()
            .url("${AppConfig.remoteApiBaseUrl}embeddings")
            .header("Authorization", "Bearer ${AppConfig.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Embedding request failed: HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response body")
        val json = JsonParser.parseString(body).asJsonObject
        val data = json.getAsJsonArray("data")
        if (data != null && data.size() > 0) {
            val embedding = data[0].asJsonObject.getAsJsonArray("embedding")
            FloatArray(embedding.size()) { i -> embedding[i].asFloat }
        } else {
            FloatArray(0)
        }
    }

    private fun buildMessageList(
        prompt: String,
        history: List<ChatMessage>
    ): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()

        // Add system message
        messages.add(mapOf(
            "role" to "system",
            "content" to "You are a helpful AI assistant powered by {{projectName}}. " +
                "Answer questions accurately and concisely. When provided with context from " +
                "documents, use it to inform your answers and cite the relevant sources."
        ))

        // Add conversation history (last 10 messages to stay within context window)
        history.takeLast(10).forEach { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            messages.add(mapOf("role" to role, "content" to msg.content))
        }

        // Add the current prompt
        messages.add(mapOf("role" to "user", "content" to prompt))

        return messages
    }
}
