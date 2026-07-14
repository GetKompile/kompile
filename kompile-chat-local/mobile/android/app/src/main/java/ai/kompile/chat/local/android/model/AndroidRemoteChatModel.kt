package ai.kompile.chat.local.android.model

import android.util.Log
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.ChatModel
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Android-safe [ChatModel] that calls any OpenAI-compatible HTTP endpoint.
 *
 * Uses [HttpURLConnection] — zero extra dependencies, works on all Android API levels.
 * This is the replacement for [ai.kompile.chat.local.RemoteChatModel], which depends on
 * [java.net.http.HttpClient] (not available on Android/ART).
 *
 * Thread-safety: sequential calls from [ai.kompile.chat.local.ChatEngine] only;
 * each call opens its own connection.
 *
 * @param baseUrl        OpenAI-compatible base URL, e.g. "https://api.openai.com"
 *                       or "http://192.168.1.10:11434" (no trailing slash)
 * @param model          Model id sent in the request body, e.g. "gpt-4o-mini"
 * @param apiKey         Bearer token, or null/empty for unauthenticated endpoints
 * @param timeoutMillis  Connect + read timeout in milliseconds (default 60 s)
 */
class AndroidRemoteChatModel(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    private val timeoutMillis: Int = 60_000
) : ChatModel {

    companion object {
        private const val TAG = "AndroidRemoteChat"
    }

    // ── ChatModel ─────────────────────────────────────────────────────────────

    override fun isAvailable(): Boolean = baseUrl.isNotBlank()

    override fun modelId(): String = "remote:$model@$baseUrl"

    override fun generate(messages: List<Message>, opts: GenOptions): String {
        if (!isAvailable()) {
            throw ChatException("Remote base URL is not configured")
        }

        val requestBody = buildRequestBody(messages, opts)
        val responseBody = doPost(requestBody)
        return extractContent(responseBody)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun doPost(body: String): String {
        val url = URL("$baseUrl/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMillis
            conn.readTimeout = timeoutMillis
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")

            if (!apiKey.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }

            conn.outputStream.use { out ->
                OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val status = conn.responseCode
            val responseText = if (status in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            } else {
                val errText = runCatching {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
                }.getOrDefault("")
                throw ChatException("Remote HTTP $status: $errText")
            }

            Log.d(TAG, "HTTP $status, body length=${responseText.length}")
            return responseText

        } finally {
            conn.disconnect()
        }
    }

    // ── Request / response ────────────────────────────────────────────────────

    private fun buildRequestBody(messages: List<Message>, opts: GenOptions): String {
        val msgArray = JSONArray()
        for (m in messages) {
            // The ChatEngine uses "tool_result" as the role for tool outputs;
            // OpenAI does not recognise that role — remap to "user" so the
            // model sees the context without a 400.
            val role = when (m.role()) {
                "tool_result" -> "user"
                else -> m.role()
            }
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", m.content())
            msgArray.put(msgObj)
        }

        val root = JSONObject()
        root.put("model", model)
        root.put("messages", msgArray)
        root.put("stream", false)
        root.put("max_tokens", opts.maxTokens())
        root.put("temperature", opts.temperature())
        return root.toString()
    }

    private fun extractContent(responseBody: String): String {
        return try {
            val root = JSONObject(responseBody)
            val choices = root.getJSONArray("choices")
            if (choices.length() == 0) {
                throw ChatException("'choices' array is empty in response: $responseBody")
            }
            val choice = choices.getJSONObject(0)
            val message = choice.getJSONObject("message")

            // OpenAI tool-calls path: content is null but tool_calls is present.
            // Serialize the first tool call into the {"tool": ..., "args": {...}} shape
            // that ToolCallParser expects, so ChatEngine can dispatch it.
            val finishReason = choice.optString("finish_reason", "")
            val toolCalls = message.optJSONArray("tool_calls")
            if (finishReason == "tool_calls" || (toolCalls != null && toolCalls.length() > 0)) {
                val tc = toolCalls!!.getJSONObject(0)
                val fn = tc.getJSONObject("function")
                val toolName = fn.getString("name")
                val argsStr = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsStr) }.getOrElse { JSONObject() }
                val out = JSONObject()
                out.put("tool", toolName)
                out.put("args", args)
                Log.d(TAG, "tool_calls response: tool=$toolName args=$argsStr")
                return out.toString()
            }

            // Normal text response — content must be non-null.
            val content = message.opt("content")
            if (content == null || content == JSONObject.NULL) {
                throw ChatException("Response has null content and no tool_calls: $responseBody")
            }
            content.toString().trim()
        } catch (e: ChatException) {
            throw e
        } catch (e: Exception) {
            throw ChatException("Failed to parse response JSON: ${e.message}", e)
        }
    }
}
