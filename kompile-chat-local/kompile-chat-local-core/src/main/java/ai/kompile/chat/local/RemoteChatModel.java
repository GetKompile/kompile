package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ChatModel} that forwards requests to any OpenAI-compatible HTTP endpoint.
 *
 * <p>Serialisation uses {@link MiniJson} — no Jackson or other third-party JSON library
 * is required.</p>
 */
public final class RemoteChatModel implements ChatModel {

    private final String baseUrl;
    private final String model;
    private final String apiKey;        // nullable — omit Authorization header when null
    private final int timeoutSeconds;

    private final HttpClient httpClient;

    /**
     * Create a remote model pointing at the given base URL.
     *
     * @param baseUrl        base URL of the OpenAI-compatible API (no trailing slash),
     *                       e.g. {@code "https://api.openai.com"} or {@code "http://localhost:11434"}
     * @param model          model identifier to pass in the request body
     * @param apiKey         bearer token, or {@code null} for unauthenticated endpoints
     * @param timeoutSeconds connect + request timeout in seconds
     */
    public RemoteChatModel(String baseUrl, String model, String apiKey, int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /** Convenience constructor using the default 60-second timeout. */
    public RemoteChatModel(String baseUrl, String model, String apiKey) {
        this(baseUrl, model, apiKey, 60);
    }

    // ── ChatModel ────────────────────────────────────────────────────────────

    @Override
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public String modelId() {
        return "remote:" + model + "@" + baseUrl;
    }

    @Override
    public ChatResponse generate(ChatRequest request, GenOptions opts) throws ChatException {
        String requestBody = buildRequestBody(request, opts);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatException("HTTP request interrupted", e);
        } catch (Exception e) {
            throw new ChatException("HTTP request failed: " + e.getMessage(), e);
        }

        int status = response.statusCode();
        String body = response.body();

        if (status != 200) {
            throw new ChatException("Remote HTTP " + status + ": " + body);
        }

        return extractResponse(body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildRequestBody(ChatRequest request, GenOptions opts) {
        List<Object> msgArr = new ArrayList<>();
        for (Message m : request.messages()) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", m.content());
            if (!m.toolCalls().isEmpty()) {
                List<Object> calls = new ArrayList<>();
                for (ChatToolCall call : m.toolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", MiniJson.write(call.arguments()));
                    Map<String, Object> encoded = new LinkedHashMap<>();
                    if (call.id() != null) encoded.put("id", call.id());
                    encoded.put("type", "function");
                    encoded.put("function", function);
                    calls.add(encoded);
                }
                msg.put("tool_calls", calls);
            }
            if (m.toolCallId() != null) msg.put("tool_call_id", m.toolCallId());
            if (m.toolName() != null) msg.put("name", m.toolName());
            msgArr.add(msg);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgArr);
        body.put("stream", false);
        body.put("max_tokens", (long) opts.maxTokens());
        body.put("temperature", opts.temperature());
        if (request.toolChoice() != ChatRequest.ToolChoice.NONE) {
            List<Object> tools = new ArrayList<>();
            for (Object value : request.parseTools()) {
                if (!(value instanceof Map<?, ?> function)) {
                    throw new ChatException("Graph tool catalog entry must be an object");
                }
                tools.add(Map.of("type", "function", "function", function));
            }
            if (!tools.isEmpty()) body.put("tools", tools);
            body.put("tool_choice", request.toolChoice() == ChatRequest.ToolChoice.REQUIRED
                    ? "required" : "auto");
        } else {
            body.put("tool_choice", "none");
        }

        return MiniJson.write(body);
    }

    @SuppressWarnings("unchecked")
    private static ChatResponse extractResponse(String responseBody) throws ChatException {
        Map<String, Object> root;
        try {
            root = MiniJson.parseObject(responseBody);
        } catch (Exception e) {
            throw new ChatException("Failed to parse response JSON: " + e.getMessage(), e);
        }

        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new ChatException("Unexpected response structure: 'choices' missing or empty. Body: "
                    + responseBody);
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            throw new ChatException("Unexpected choices[0] type: " + firstChoice);
        }

        Object messageObj = choiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            throw new ChatException("Unexpected choices[0].message type: " + messageObj);
        }

        String content = messageMap.get("content") instanceof String
                ? (String) messageMap.get("content") : "";
        String reasoning = messageMap.get("reasoning_content") instanceof String
                ? (String) messageMap.get("reasoning_content") : "";
        List<ChatToolCall> toolCalls = new ArrayList<>();
        List<String> protocolErrors = new ArrayList<>();
        Object callsObj = messageMap.get("tool_calls");
        if (callsObj != null) {
            if (!(callsObj instanceof List<?> calls)) {
                protocolErrors.add("provider tool_calls was not an array");
            } else {
                for (Object callObj : calls) {
                    if (!(callObj instanceof Map<?, ?> call)
                            || !(call.get("function") instanceof Map<?, ?> function)
                            || !(function.get("name") instanceof String name)
                            || name.isBlank()) {
                        protocolErrors.add("provider returned an invalid tool-call object");
                        continue;
                    }
                    Object argsValue = function.get("arguments");
                    Map<String, Object> arguments = null;
                    if (argsValue instanceof Map<?, ?> map) {
                        arguments = (Map<String, Object>) map;
                    } else if (argsValue instanceof String argsJson) {
                        try {
                            arguments = MiniJson.parseObject(argsJson);
                        } catch (RuntimeException e) {
                            protocolErrors.add("provider returned invalid arguments for tool " + name);
                        }
                    }
                    if (arguments == null) {
                        protocolErrors.add("provider omitted arguments for tool " + name);
                        continue;
                    }
                    toolCalls.add(new ChatToolCall(
                            call.get("id") instanceof String ? (String) call.get("id") : null,
                            name,
                            arguments));
                }
            }
        }
        if (content.isBlank() && toolCalls.isEmpty() && protocolErrors.isEmpty()) {
            protocolErrors.add("provider returned neither assistant content nor tool calls");
        }
        return new ChatResponse(content, content, reasoning, toolCalls, protocolErrors);
    }
}
