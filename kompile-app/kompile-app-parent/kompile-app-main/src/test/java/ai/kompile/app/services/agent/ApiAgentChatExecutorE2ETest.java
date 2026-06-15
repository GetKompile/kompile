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

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import ai.kompile.app.web.dto.AgentChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for multimodal chat via {@link ApiAgentChatExecutor}.
 * <p>
 * Uses WireMock as a fake LLM API endpoint to capture the actual HTTP
 * request body that the executor sends over the wire, then asserts that
 * image and file attachments are serialized into correct OpenAI-compatible
 * content blocks.  Also verifies that SSE events are relayed back to the
 * client emitter.
 */
class ApiAgentChatExecutorE2ETest {

    private static final String LLM_PATH = "/v1/chat/completions";

    private WireMockServer wireMock;
    private ApiAgentChatExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        executor = new ApiAgentChatExecutor(new ModelCapabilityService(null));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // ─── E2E: image attachment flows through to LLM as image_url block ────

    @Test
    void imageAttachmentSentAsImageUrlBlockToLlm() throws Exception {
        stubLlmStreaming("I can see the red pixel.");

        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("What color is this pixel?");
        request.setAttachments(List.of(
                new AgentChatRequest.MessageAttachment(
                        "red-pixel.png", "image/png", TINY_PNG_BASE64, null, true)
        ));

        CapturingEmitter emitter = execute(agent, request, "What color is this pixel?");

        // ── Verify the HTTP request that hit WireMock ──
        List<LoggedRequest> llmRequests = wireMock.findAll(postRequestedFor(urlEqualTo(LLM_PATH)));
        assertEquals(1, llmRequests.size(), "Expected exactly one LLM API call");

        JsonNode body = mapper.readTree(llmRequests.get(0).getBodyAsString());
        assertEquals("gpt-4o", body.get("model").asText());

        JsonNode userMsg = lastMessage(body);
        assertEquals("user", userMsg.get("role").asText());

        // Content must be a multimodal array, not a flat string
        assertTrue(userMsg.get("content").isArray(),
                "Image attachment must produce a content array");

        JsonNode contentArray = userMsg.get("content");

        // image_url block
        JsonNode imageBlock = findBlock(contentArray, "image_url");
        assertNotNull(imageBlock, "Must contain an image_url block");
        String dataUrl = imageBlock.get("image_url").get("url").asText();
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        assertTrue(dataUrl.contains(TINY_PNG_BASE64));

        // text block with user prompt
        JsonNode textBlock = lastBlock(contentArray, "text");
        assertNotNull(textBlock);
        assertEquals("What color is this pixel?", textBlock.get("text").asText());

        // ── Verify SSE lifecycle events ──
        emitter.assertHasEvent("start");
        emitter.assertHasEvent("chunk");
        emitter.assertHasEvent("complete");
    }

    // ─── E2E: text file attachment flows through as text block ────────────

    @Test
    void textFileAttachmentSentAsTextBlockToLlm() throws Exception {
        stubLlmStreaming("The CSV has two columns.");

        AgentProvider agent = buildAgent("deepseek-chat");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Describe this data");
        request.setAttachments(List.of(
                new AgentChatRequest.MessageAttachment(
                        "people.csv", "text/csv", null, "name,age\nAlice,30\nBob,25", false)
        ));

        execute(agent, request, "Describe this data");

        JsonNode body = parseLlmRequest();
        JsonNode contentArray = lastMessage(body).get("content");
        assertTrue(contentArray.isArray());

        // File block
        JsonNode fileBlock = contentArray.get(0);
        assertEquals("text", fileBlock.get("type").asText());
        assertTrue(fileBlock.get("text").asText().contains("people.csv"));
        assertTrue(fileBlock.get("text").asText().contains("Alice,30"));

        // Prompt block
        JsonNode promptBlock = contentArray.get(contentArray.size() - 1);
        assertEquals("Describe this data", promptBlock.get("text").asText());
    }

    // ─── E2E: mixed image + text file produces correct block order ────────

    @Test
    void mixedAttachmentsSentInCorrectOrderToLlm() throws Exception {
        stubLlmStreaming("The chart matches the spreadsheet.");

        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Compare the chart to the data");
        request.setAttachments(List.of(
                new AgentChatRequest.MessageAttachment(
                        "chart.png", "image/png", TINY_PNG_BASE64, null, true),
                new AgentChatRequest.MessageAttachment(
                        "revenue.csv", "text/csv", null, "quarter,revenue\nQ1,100\nQ2,150", false)
        ));

        execute(agent, request, "Compare the chart to the data");

        JsonNode contentArray = lastMessage(parseLlmRequest()).get("content");
        assertEquals(3, contentArray.size(), "image + text file + prompt = 3 blocks");
        assertEquals("image_url", contentArray.get(0).get("type").asText());
        assertEquals("text", contentArray.get(1).get("type").asText());
        assertTrue(contentArray.get(1).get("text").asText().contains("revenue.csv"));
        assertEquals("Compare the chart to the data",
                contentArray.get(2).get("text").asText());
    }

    // ─── E2E: no attachments keeps flat string content (regression) ───────

    @Test
    void noAttachmentsUsesFlatStringContent() throws Exception {
        stubLlmStreaming("Hello!");

        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Hello");

        execute(agent, request, "Hello");

        JsonNode userMsg = lastMessage(parseLlmRequest());
        assertTrue(userMsg.get("content").isTextual(),
                "Without attachments, content must be a flat string for backward compat");
        assertEquals("Hello", userMsg.get("content").asText());
    }

    // ─── E2E: image + text-only model → validation blocks the call ────────

    @Test
    void imageAttachmentWithTextOnlyModelIsRejected() throws Exception {
        AgentProvider agent = buildAgent("deepseek-chat");
        var att = new AgentChatRequest.MessageAttachment(
                "photo.png", "image/png", TINY_PNG_BASE64, null, true);

        String error = executor.validateAttachments(agent, List.of(att));
        assertNotNull(error, "Must reject image for text-only model");
        assertTrue(error.contains("deepseek-chat"));
        assertTrue(error.contains("does not support image"));

        // No HTTP request should have been made
        wireMock.verify(0, postRequestedFor(urlEqualTo(LLM_PATH)));
    }

    // ─── E2E: streaming chunks are relayed back through SSE ──────────────

    @Test
    void streamingChunksAreRelayedViaSse() throws Exception {
        stubLlmStreaming("The", " image", " shows", " a", " cat.");

        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("What's in this image?");
        request.setAttachments(List.of(
                new AgentChatRequest.MessageAttachment(
                        "cat.jpg", "image/jpeg", TINY_PNG_BASE64, null, true)
        ));

        CapturingEmitter emitter = execute(agent, request, "What's in this image?");

        // Collect all "chunk" payloads
        String fullResponse = emitter.collectChunks();
        assertEquals("The image shows a cat.", fullResponse,
                "Concatenated chunks must reconstruct the full LLM response");

        // Complete event must also contain the full response
        String completeJson = emitter.firstEventData("complete");
        assertNotNull(completeJson);
        JsonNode completeNode = mapper.readTree(completeJson);
        assertEquals("The image shows a cat.", completeNode.get("content").asText());
    }

    // ─── E2E: chat history + image round-trips together ──────────────────

    @Test
    void chatHistoryWithImagePreservesAllMessages() throws Exception {
        stubLlmStreaming("Beautiful sunset.");

        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("What about this one?");
        request.setChatHistory(List.of(
                new AgentChatRequest.ChatHistoryEntry("user", "Hello!"),
                new AgentChatRequest.ChatHistoryEntry("assistant", "Hi there!")
        ));
        request.setAttachments(List.of(
                new AgentChatRequest.MessageAttachment(
                        "sunset.jpg", "image/jpeg", TINY_PNG_BASE64, null, true)
        ));

        execute(agent, request, "What about this one?");

        JsonNode messages = parseLlmRequest().get("messages");
        assertEquals(3, messages.size(), "2 history + 1 current");

        // History: flat strings
        assertTrue(messages.get(0).get("content").isTextual());
        assertEquals("Hello!", messages.get(0).get("content").asText());
        assertTrue(messages.get(1).get("content").isTextual());

        // Current: multimodal array with image
        JsonNode current = messages.get(2);
        assertTrue(current.get("content").isArray(),
                "Current message with image must use content array");
        assertNotNull(findBlock(current.get("content"), "image_url"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** Minimal valid 1×1 PNG, base64-encoded. */
    private static final String TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwADhQGAWjR9awAAAABJRU5ErkJggg==";

    private AgentProvider buildAgent(String modelName) {
        return AgentProvider.builder()
                .name("test-agent")
                .displayName("Test Agent")
                .agentType(AgentType.API)
                .modelName(modelName)
                .endpointUrl("http://localhost:" + wireMock.port() + "/v1")
                .temperature(0.7)
                .maxTokens(4096)
                .available(true)
                .build();
    }

    private void stubLlmStreaming(String... textChunks) {
        StringBuilder sseBody = new StringBuilder();
        for (String chunk : textChunks) {
            sseBody.append("data: {\"choices\":[{\"delta\":{\"content\":\"")
                    .append(chunk.replace("\"", "\\\""))
                    .append("\"},\"finish_reason\":null}]}\n\n");
        }
        sseBody.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
        sseBody.append("data: [DONE]\n\n");

        wireMock.stubFor(post(urlEqualTo(LLM_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody.toString())));
    }

    /** Parse the single request body that WireMock received. */
    private JsonNode parseLlmRequest() throws Exception {
        List<LoggedRequest> reqs = wireMock.findAll(postRequestedFor(urlEqualTo(LLM_PATH)));
        assertEquals(1, reqs.size(), "Expected exactly one LLM request");
        return mapper.readTree(reqs.get(0).getBodyAsString());
    }

    private JsonNode lastMessage(JsonNode requestBody) {
        JsonNode messages = requestBody.get("messages");
        return messages.get(messages.size() - 1);
    }

    private JsonNode findBlock(JsonNode contentArray, String type) {
        for (JsonNode b : contentArray) {
            if (type.equals(b.path("type").asText())) return b;
        }
        return null;
    }

    private JsonNode lastBlock(JsonNode contentArray, String type) {
        JsonNode last = null;
        for (JsonNode b : contentArray) {
            if (type.equals(b.path("type").asText())) last = b;
        }
        return last;
    }

    private CapturingEmitter execute(AgentProvider agent, AgentChatRequest req, String prompt)
            throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        executor.executeApiChat(agent, req, prompt, Collections.emptyList(), emitter);
        assertTrue(emitter.awaitCompletion(10, TimeUnit.SECONDS),
                "Chat execution must complete within 10 seconds");
        return emitter;
    }

    // ─── CapturingEmitter ─────────────────────────────────────────────────

    /**
     * SseEmitter subclass that intercepts event data for test assertions.
     * <p>
     * The executor sends events like:
     * {@code emitter.send(SseEmitter.event().name("chunk").data("text"))}
     * <p>
     * {@code SseEventBuilder.build()} returns a {@code Set<DataWithMediaType>}
     * where each element wraps a raw object + media type. The SSE frame
     * components (event name line, data line) are separate elements in this
     * set. We iterate them and extract the event name and JSON/string data.
     */
    static class CapturingEmitter extends SseEmitter {

        record Event(String name, String data) {}

        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ObjectMapper om = new ObjectMapper();

        CapturingEmitter() { super(30_000L); }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            // SseEventBuilder.build() returns Set<DataWithMediaType>.
            // Each element wraps getData() (Object) + getMediaType().
            // Spring's SseEventBuilderImpl packs SSE frame lines as strings:
            //   "event:<name>\n", "data:", <actual-data-object>, "\n\n"
            // We iterate all parts, extract the event name from string fragments,
            // and pick the non-string object (or non-frame-fragment string) as data.
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();

            String eventName = null;
            String eventData = null;

            for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                Object raw = part.getData();
                MediaType mt = part.getMediaType();

                if (raw instanceof String s) {
                    // String parts include both SSE frame syntax and data payloads.
                    // SSE frame fragments: "event:<name>\n", "data:", "\n"
                    // Data payloads (for chunk events): plain text strings
                    // Distinguish by content pattern.
                    for (String line : s.split("\n", -1)) {
                        String stripped = line.strip();
                        if (stripped.startsWith("event:")) {
                            eventName = stripped.substring(6).strip();
                        } else if (stripped.equals("data:") || stripped.isEmpty()) {
                            // SSE frame syntax — skip
                        } else {
                            // Actual string data payload — preserve whitespace
                            eventData = line;
                        }
                    }
                } else {
                    // Non-string data payload (Map, List, etc.)
                    try {
                        eventData = om.writeValueAsString(raw);
                    } catch (Exception e) {
                        eventData = raw.toString();
                    }
                }
            }

            if (eventName != null) {
                events.add(new Event(eventName, eventData));
            }
            // Don't call super — no servlet response in a test
        }

        @Override public void complete() { latch.countDown(); }
        @Override public void completeWithError(Throwable ex) { latch.countDown(); }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        void assertHasEvent(String name) {
            assertTrue(events.stream().anyMatch(e -> name.equals(e.name)),
                    "Expected SSE event '" + name + "' but got: " +
                            events.stream().map(Event::name).toList());
        }

        String firstEventData(String name) {
            return events.stream()
                    .filter(e -> name.equals(e.name))
                    .map(Event::data)
                    .findFirst()
                    .orElse(null);
        }

        /** Concatenate all "chunk" event payloads. */
        String collectChunks() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                if ("chunk".equals(e.name) && e.data != null) {
                    // Chunk data may be JSON-quoted string — strip quotes
                    String d = e.data;
                    if (d.startsWith("\"") && d.endsWith("\"")) {
                        try { d = om.readValue(d, String.class); } catch (Exception ignored) {}
                    }
                    sb.append(d);
                }
            }
            return sb.toString();
        }
    }
}
