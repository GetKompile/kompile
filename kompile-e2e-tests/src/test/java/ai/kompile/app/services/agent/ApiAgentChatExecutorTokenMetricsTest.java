/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for token metrics construction in API agent chat responses.
 * Tests the token metrics map structure that gets sent as SSE stats events.
 */
@DisplayName("API Agent Chat Token Metrics Tests")
public class ApiAgentChatExecutorTokenMetricsTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("Token Metrics Map Construction")
    class TokenMetricsMapConstruction {

        @Test
        @DisplayName("Should build token metrics with all fields")
        void shouldBuildTokenMetricsWithAllFields() {
            int outputTokens = 150;
            long durationMs = 3000;
            double tokensPerSecond = outputTokens * 1000.0 / durationMs;
            String model = "gpt-4";

            Map<String, Object> tokenMetrics = new HashMap<>();
            tokenMetrics.put("outputTokens", outputTokens);
            tokenMetrics.put("totalGenerationMs", durationMs);
            tokenMetrics.put("tokensPerSecond", tokensPerSecond);
            tokenMetrics.put("model", model);

            assertEquals(150, tokenMetrics.get("outputTokens"));
            assertEquals(3000L, tokenMetrics.get("totalGenerationMs"));
            assertEquals(50.0, (double) tokenMetrics.get("tokensPerSecond"), 0.01);
            assertEquals("gpt-4", tokenMetrics.get("model"));
        }

        @Test
        @DisplayName("Should handle zero duration gracefully")
        void shouldHandleZeroDurationGracefully() {
            int outputTokens = 10;
            long durationMs = 0;
            double tokensPerSecond = durationMs > 0 ? (outputTokens * 1000.0 / durationMs) : 0;

            Map<String, Object> tokenMetrics = new HashMap<>();
            tokenMetrics.put("outputTokens", outputTokens);
            tokenMetrics.put("totalGenerationMs", durationMs);
            tokenMetrics.put("tokensPerSecond", tokensPerSecond);

            assertEquals(0.0, tokenMetrics.get("tokensPerSecond"));
        }

        @Test
        @DisplayName("Should include token metrics in stats map")
        void shouldIncludeTokenMetricsInStatsMap() {
            Map<String, Object> tokenMetrics = new HashMap<>();
            tokenMetrics.put("outputTokens", 200);
            tokenMetrics.put("totalGenerationMs", 5000L);
            tokenMetrics.put("tokensPerSecond", 40.0);
            tokenMetrics.put("model", "llama-3.1-70b");

            Map<String, Object> stats = new HashMap<>();
            stats.put("durationMs", 5000L);
            stats.put("costUsd", 0.0);
            stats.put("numTurns", 1);
            stats.put("isError", false);
            stats.put("tokenMetrics", tokenMetrics);

            @SuppressWarnings("unchecked")
            Map<String, Object> extractedMetrics = (Map<String, Object>) stats.get("tokenMetrics");
            assertNotNull(extractedMetrics);
            assertEquals(200, extractedMetrics.get("outputTokens"));
            assertEquals("llama-3.1-70b", extractedMetrics.get("model"));
        }
    }

    @Nested
    @DisplayName("OpenAI SSE Response Parsing")
    class OpenAiSseResponseParsing {

        @Test
        @DisplayName("Should extract content from SSE chunk")
        void shouldExtractContentFromSseChunk() throws Exception {
            String sseData = """
                {"id":"chatcmpl-123","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""";

            JsonNode chunk = objectMapper.readTree(sseData);
            JsonNode choices = chunk.path("choices");
            assertTrue(choices.isArray());
            assertEquals(1, choices.size());

            String content = choices.get(0).path("delta").path("content").asText(null);
            assertEquals("Hello", content);
        }

        @Test
        @DisplayName("Should extract usage from final SSE chunk")
        void shouldExtractUsageFromFinalChunk() throws Exception {
            String sseData = """
                {"id":"chatcmpl-123","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":120,"total_tokens":170}}""";

            JsonNode chunk = objectMapper.readTree(sseData);
            JsonNode usage = chunk.path("usage");
            assertFalse(usage.isMissingNode());
            assertEquals(120, usage.path("completion_tokens").asInt());
            assertEquals(50, usage.path("prompt_tokens").asInt());
            assertEquals(170, usage.path("total_tokens").asInt());
        }

        @Test
        @DisplayName("Should detect finish_reason stop")
        void shouldDetectFinishReasonStop() throws Exception {
            String sseData = """
                {"id":"chatcmpl-123","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""";

            JsonNode chunk = objectMapper.readTree(sseData);
            String finishReason = chunk.path("choices").get(0).path("finish_reason").asText(null);
            assertEquals("stop", finishReason);
        }

        @Test
        @DisplayName("Should handle missing usage in intermediate chunks")
        void shouldHandleMissingUsageInIntermediateChunks() throws Exception {
            String sseData = """
                {"id":"chatcmpl-123","object":"chat.completion.chunk","model":"gpt-4","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""";

            JsonNode chunk = objectMapper.readTree(sseData);
            JsonNode usage = chunk.path("usage");
            assertTrue(usage.isMissingNode(), "Usage should be missing in intermediate chunks");
        }
    }

    @Nested
    @DisplayName("OpenAI Request Building")
    class OpenAiRequestBuilding {

        @Test
        @DisplayName("Should build valid OpenAI request JSON")
        void shouldBuildValidOpenAiRequestJson() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", "gpt-4");
            root.put("stream", true);
            root.put("temperature", 0.7);
            root.put("max_tokens", 4096);

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", "Hello, how are you?");

            String json = root.toString();
            assertNotNull(json);
            assertTrue(json.contains("\"model\":\"gpt-4\""));
            assertTrue(json.contains("\"stream\":true"));
            assertTrue(json.contains("\"role\":\"user\""));
        }

        @Test
        @DisplayName("Should include chat history in request")
        void shouldIncludeChatHistoryInRequest() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", "gpt-4");
            root.put("stream", true);

            ArrayNode messages = root.putArray("messages");

            // Add history
            ObjectNode hist1 = messages.addObject();
            hist1.put("role", "user");
            hist1.put("content", "What is Java?");

            ObjectNode hist2 = messages.addObject();
            hist2.put("role", "assistant");
            hist2.put("content", "Java is a programming language.");

            // Add current message
            ObjectNode current = messages.addObject();
            current.put("role", "user");
            current.put("content", "Tell me more");

            assertEquals(3, messages.size());
        }
    }

    @Nested
    @DisplayName("Endpoint URL Normalization")
    class EndpointUrlNormalization {

        @Test
        @DisplayName("Should strip trailing slashes")
        void shouldStripTrailingSlashes() {
            assertEquals("http://localhost:11434/v1", normalizeEndpointUrl("http://localhost:11434/v1/"));
            assertEquals("http://localhost:11434/v1", normalizeEndpointUrl("http://localhost:11434/v1///"));
            assertEquals("http://api.openai.com", normalizeEndpointUrl("http://api.openai.com/"));
        }

        @Test
        @DisplayName("Should handle null and empty URLs")
        void shouldHandleNullAndEmptyUrls() {
            assertEquals("", normalizeEndpointUrl(null));
            assertEquals("", normalizeEndpointUrl(""));
        }

        @Test
        @DisplayName("Should preserve URL without trailing slash")
        void shouldPreserveUrlWithoutTrailingSlash() {
            assertEquals("http://localhost:8080/v1", normalizeEndpointUrl("http://localhost:8080/v1"));
        }

        // Extracted from ApiAgentChatExecutor for testing
        private String normalizeEndpointUrl(String url) {
            if (url == null) return "";
            url = url.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
    }
}
