/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.a2a.client;

import ai.kompile.a2a.model.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * A2A protocol client for communicating with remote A2A-compatible agents.
 * <p>
 * Supports:
 * <ul>
 *   <li>Agent card discovery from {@code /.well-known/agent-card.json}</li>
 *   <li>Synchronous {@code message/send} (JSON-RPC)</li>
 *   <li>Streaming {@code message/stream} (SSE)</li>
 *   <li>Task status queries ({@code tasks/get})</li>
 *   <li>Task cancellation ({@code tasks/cancel})</li>
 * </ul>
 */
public class A2AClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(A2AClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private AgentCard cachedAgentCard;

    public A2AClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(300));
    }

    public A2AClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Resolve the remote agent's card from {@code /.well-known/agent-card.json}.
     */
    public AgentCard resolveAgentCard() throws A2AClientException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/.well-known/agent-card.json"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new A2AClientException("Failed to resolve agent card: HTTP " + response.statusCode());
            }

            cachedAgentCard = objectMapper.readValue(response.body(), AgentCard.class);
            logger.info("Resolved A2A agent card: name={}, skills={}",
                    cachedAgentCard.getName(),
                    cachedAgentCard.getSkills() != null ? cachedAgentCard.getSkills().size() : 0);
            return cachedAgentCard;
        } catch (A2AClientException e) {
            throw e;
        } catch (Exception e) {
            throw new A2AClientException("Failed to resolve agent card from " + baseUrl, e);
        }
    }

    /**
     * Send a synchronous message to the remote agent (JSON-RPC {@code message/send}).
     */
    public A2ATask sendMessage(String text) throws A2AClientException {
        return sendMessage(A2AMessage.userText(text), null);
    }

    /**
     * Send a synchronous message with context to the remote agent.
     */
    public A2ATask sendMessage(A2AMessage message, String contextId) throws A2AClientException {
        try {
            JsonRpcRequest rpcRequest = JsonRpcRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .method(JsonRpcRequest.METHOD_MESSAGE_SEND)
                    .params(Map.of(
                            "message", message,
                            "contextId", contextId != null ? contextId : UUID.randomUUID().toString()
                    ))
                    .build();

            String body = objectMapper.writeValueAsString(rpcRequest);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/a2a"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new A2AClientException("message/send failed: HTTP " + response.statusCode()
                        + " - " + response.body());
            }

            JsonRpcResponse rpcResponse = objectMapper.readValue(response.body(), JsonRpcResponse.class);

            if (rpcResponse.getError() != null) {
                throw new A2AClientException("A2A error " + rpcResponse.getError().getCode()
                        + ": " + rpcResponse.getError().getMessage());
            }

            return objectMapper.convertValue(rpcResponse.getResult(), A2ATask.class);
        } catch (A2AClientException e) {
            throw e;
        } catch (Exception e) {
            throw new A2AClientException("Failed to send message to " + baseUrl, e);
        }
    }

    /**
     * Stream a message to the remote agent (SSE {@code message/stream}).
     * The chunkConsumer receives (text, isFinal) pairs as the agent produces output.
     * Returns the complete response text.
     */
    public CompletableFuture<String> streamMessage(String text, BiConsumer<String, Boolean> chunkConsumer) {
        return streamMessage(A2AMessage.userText(text), null, chunkConsumer);
    }

    /**
     * Stream a message with context to the remote agent.
     */
    public CompletableFuture<String> streamMessage(A2AMessage message, String contextId,
                                                     BiConsumer<String, Boolean> chunkConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonRpcRequest rpcRequest = JsonRpcRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .method(JsonRpcRequest.METHOD_MESSAGE_STREAM)
                        .params(Map.of(
                                "message", message,
                                "contextId", contextId != null ? contextId : UUID.randomUUID().toString()
                        ))
                        .build();

                String body = objectMapper.writeValueAsString(rpcRequest);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/a2a/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<java.io.InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new A2AClientException("message/stream failed: HTTP " + response.statusCode());
                }

                StringBuilder fullResponse = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String line;
                    String currentEvent = null;
                    StringBuilder dataBuffer = new StringBuilder();

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            dataBuffer.append(line.substring(5).trim());
                        } else if (line.isEmpty() && currentEvent != null) {
                            processStreamEvent(currentEvent, dataBuffer.toString(),
                                    fullResponse, chunkConsumer);
                            currentEvent = null;
                            dataBuffer.setLength(0);
                        }
                    }
                }

                return fullResponse.toString();
            } catch (A2AClientException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(new A2AClientException("Stream failed", e));
            }
        });
    }

    /**
     * Get task status from the remote agent.
     */
    public A2ATask getTask(String taskId) throws A2AClientException {
        try {
            JsonRpcRequest rpcRequest = JsonRpcRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .method(JsonRpcRequest.METHOD_TASKS_GET)
                    .params(Map.of("taskId", taskId))
                    .build();

            HttpResponse<String> response = sendJsonRpc(rpcRequest);
            JsonRpcResponse rpcResponse = objectMapper.readValue(response.body(), JsonRpcResponse.class);

            if (rpcResponse.getError() != null) {
                throw new A2AClientException("tasks/get error: " + rpcResponse.getError().getMessage());
            }

            return objectMapper.convertValue(rpcResponse.getResult(), A2ATask.class);
        } catch (A2AClientException e) {
            throw e;
        } catch (Exception e) {
            throw new A2AClientException("Failed to get task " + taskId, e);
        }
    }

    /**
     * Cancel a task on the remote agent.
     */
    public boolean cancelTask(String taskId) throws A2AClientException {
        try {
            JsonRpcRequest rpcRequest = JsonRpcRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .method(JsonRpcRequest.METHOD_TASKS_CANCEL)
                    .params(Map.of("taskId", taskId))
                    .build();

            HttpResponse<String> response = sendJsonRpc(rpcRequest);
            JsonRpcResponse rpcResponse = objectMapper.readValue(response.body(), JsonRpcResponse.class);

            return rpcResponse.getError() == null;
        } catch (Exception e) {
            throw new A2AClientException("Failed to cancel task " + taskId, e);
        }
    }

    /**
     * Get the cached agent card, resolving if necessary.
     */
    public AgentCard getAgentCard() throws A2AClientException {
        if (cachedAgentCard == null) {
            resolveAgentCard();
        }
        return cachedAgentCard;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit close in Java 11+
    }

    private HttpResponse<String> sendJsonRpc(JsonRpcRequest rpcRequest) throws Exception {
        String body = objectMapper.writeValueAsString(rpcRequest);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/a2a"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new A2AClientException("JSON-RPC call failed: HTTP " + response.statusCode());
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    private void processStreamEvent(String eventName, String data, StringBuilder fullResponse,
                                     BiConsumer<String, Boolean> chunkConsumer) {
        try {
            Map<String, Object> eventData = objectMapper.readValue(data, Map.class);

            if ("artifact".equals(eventName)) {
                Map<String, Object> artifact = (Map<String, Object>) eventData.get("artifact");
                if (artifact != null) {
                    var parts = (java.util.List<Map<String, Object>>) artifact.get("parts");
                    if (parts != null) {
                        for (Map<String, Object> part : parts) {
                            String text = (String) part.get("text");
                            if (text != null) {
                                fullResponse.append(text);
                                boolean isFinal = Boolean.TRUE.equals(artifact.get("lastChunk"));
                                if (chunkConsumer != null) {
                                    chunkConsumer.accept(text, isFinal);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse SSE event '{}': {}", eventName, e.getMessage());
        }
    }
}
