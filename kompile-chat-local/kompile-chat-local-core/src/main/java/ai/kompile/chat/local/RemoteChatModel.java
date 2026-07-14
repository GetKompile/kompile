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
    public String generate(List<Message> messages, GenOptions opts) throws ChatException {
        String requestBody = buildRequestBody(messages, opts);

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

        return extractContent(body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildRequestBody(List<Message> messages, GenOptions opts) {
        List<Object> msgArr = new ArrayList<>();
        for (Message m : messages) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", m.content());
            msgArr.add(msg);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgArr);
        body.put("stream", false);
        body.put("max_tokens", (long) opts.maxTokens());
        body.put("temperature", opts.temperature());

        return MiniJson.write(body);
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(String responseBody) throws ChatException {
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

        Object contentObj = messageMap.get("content");
        if (!(contentObj instanceof String content)) {
            throw new ChatException("Unexpected choices[0].message.content type: " + contentObj);
        }

        return content;
    }
}
