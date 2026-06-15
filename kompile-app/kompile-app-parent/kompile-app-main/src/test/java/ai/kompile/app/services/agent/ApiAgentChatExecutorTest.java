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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiAgentChatExecutorTest {

    private ApiAgentChatExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new ApiAgentChatExecutor(new ModelCapabilityService(null));
    }

    // --- validateAttachments ---

    @Test
    void validateAttachmentsReturnsNullForNoAttachments() {
        AgentProvider agent = buildAgent("gpt-4o");
        assertNull(executor.validateAttachments(agent, null));
        assertNull(executor.validateAttachments(agent, List.of()));
    }

    @Test
    void validateAttachmentsAllowsImageForVisionModel() {
        AgentProvider agent = buildAgent("gpt-4o");
        var att = new AgentChatRequest.MessageAttachment(
                "photo.png", "image/png", "base64data", null, true);
        assertNull(executor.validateAttachments(agent, List.of(att)));
    }

    @Test
    void validateAttachmentsRejectsImageForTextOnlyModel() {
        AgentProvider agent = buildAgent("deepseek-chat");
        var att = new AgentChatRequest.MessageAttachment(
                "photo.png", "image/png", "base64data", null, true);
        String error = executor.validateAttachments(agent, List.of(att));
        assertNotNull(error);
        assertTrue(error.contains("deepseek-chat"));
        assertTrue(error.contains("does not support image"));
    }

    @Test
    void validateAttachmentsAllowsTextFileForAnyModel() {
        AgentProvider agent = buildAgent("deepseek-chat");
        var att = new AgentChatRequest.MessageAttachment(
                "readme.txt", "text/plain", null, "file contents", false);
        assertNull(executor.validateAttachments(agent, List.of(att)));
    }

    // --- buildOpenAiRequest content block structure ---

    @Test
    void buildRequestWithoutAttachmentsUsesFlatStringContent() throws Exception {
        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Hello world");

        String json = invokeBuildOpenAiRequest(agent, request, "Hello world");
        JsonNode root = mapper.readTree(json);

        JsonNode messages = root.get("messages");
        assertNotNull(messages);
        assertTrue(messages.isArray());

        // Last message is the user message
        JsonNode userMsg = messages.get(messages.size() - 1);
        assertEquals("user", userMsg.get("role").asText());
        // Content should be a flat string
        assertTrue(userMsg.get("content").isTextual());
        assertEquals("Hello world", userMsg.get("content").asText());
    }

    @Test
    void buildRequestWithImageAttachmentUsesContentArray() throws Exception {
        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Describe this image");

        var att = new AgentChatRequest.MessageAttachment(
                "photo.png", "image/png", "iVBOR", null, true);
        request.setAttachments(List.of(att));

        String json = invokeBuildOpenAiRequest(agent, request, "Describe this image");
        JsonNode root = mapper.readTree(json);

        JsonNode userMsg = root.get("messages").get(root.get("messages").size() - 1);
        // Content should be an array
        assertTrue(userMsg.get("content").isArray());

        JsonNode contentArray = userMsg.get("content");
        // First block: image_url
        assertEquals("image_url", contentArray.get(0).get("type").asText());
        assertTrue(contentArray.get(0).get("image_url").get("url").asText().startsWith("data:image/png;base64,"));
        // Last block: text with the prompt
        JsonNode lastBlock = contentArray.get(contentArray.size() - 1);
        assertEquals("text", lastBlock.get("type").asText());
        assertEquals("Describe this image", lastBlock.get("text").asText());
    }

    @Test
    void buildRequestWithTextFileAttachmentUsesContentArray() throws Exception {
        AgentProvider agent = buildAgent("deepseek-chat");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Summarize this file");

        var att = new AgentChatRequest.MessageAttachment(
                "data.csv", "text/csv", null, "col1,col2\na,b", false);
        request.setAttachments(List.of(att));

        String json = invokeBuildOpenAiRequest(agent, request, "Summarize this file");
        JsonNode root = mapper.readTree(json);

        JsonNode userMsg = root.get("messages").get(root.get("messages").size() - 1);
        assertTrue(userMsg.get("content").isArray());

        JsonNode contentArray = userMsg.get("content");
        // First block: text with file content
        assertEquals("text", contentArray.get(0).get("type").asText());
        assertTrue(contentArray.get(0).get("text").asText().contains("data.csv"));
        assertTrue(contentArray.get(0).get("text").asText().contains("col1,col2"));
        // Last block: the user's prompt
        assertEquals("Summarize this file", contentArray.get(contentArray.size() - 1).get("text").asText());
    }

    @Test
    void buildRequestWithMixedAttachmentsOrdersCorrectly() throws Exception {
        AgentProvider agent = buildAgent("gpt-4o");
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("Analyze both");

        var imgAtt = new AgentChatRequest.MessageAttachment(
                "chart.png", "image/png", "base64img", null, true);
        var textAtt = new AgentChatRequest.MessageAttachment(
                "notes.txt", "text/plain", null, "some notes", false);
        request.setAttachments(List.of(imgAtt, textAtt));

        String json = invokeBuildOpenAiRequest(agent, request, "Analyze both");
        JsonNode root = mapper.readTree(json);

        JsonNode contentArray = root.get("messages").get(root.get("messages").size() - 1).get("content");
        assertEquals(3, contentArray.size()); // image + text file + user prompt
        assertEquals("image_url", contentArray.get(0).get("type").asText());
        assertEquals("text", contentArray.get(1).get("type").asText());
        assertTrue(contentArray.get(1).get("text").asText().contains("notes.txt"));
        assertEquals("text", contentArray.get(2).get("type").asText());
        assertEquals("Analyze both", contentArray.get(2).get("text").asText());
    }

    // --- helpers ---

    private AgentProvider buildAgent(String modelName) {
        return AgentProvider.builder()
                .name("test-agent")
                .displayName("Test Agent")
                .agentType(AgentType.API)
                .modelName(modelName)
                .endpointUrl("http://localhost:11434/v1")
                .temperature(0.7)
                .maxTokens(4096)
                .available(true)
                .build();
    }

    /**
     * Invoke the private buildOpenAiRequest method via reflection.
     */
    private String invokeBuildOpenAiRequest(AgentProvider agent, AgentChatRequest request, String prompt) throws Exception {
        Method method = ApiAgentChatExecutor.class.getDeclaredMethod(
                "buildOpenAiRequest", AgentProvider.class, AgentChatRequest.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, agent, request, prompt);
    }
}
