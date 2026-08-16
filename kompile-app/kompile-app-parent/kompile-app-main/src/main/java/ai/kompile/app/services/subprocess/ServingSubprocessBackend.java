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

package ai.kompile.app.services.subprocess;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * App-main {@link LocalServingBackend} that delegates to the {@link ServingSubprocessLauncher}
 * (loopback {@code POST /api/llm/generate}) and unwraps the {@code {finishReason, generatedText}}
 * response contract into plain generated text.
 *
 * <p>Lets the crawl {@code CrawlLlmDispatcher} (in {@code kompile-crawl-graph}) route a
 * {@code LOCAL_MODEL} backend marked {@code agentName="serving"} to the serving subprocess as a
 * quota-free extraction lane, without depending on app-main. When the serving subprocess isn't
 * running (its idle default), {@link #isAvailable()} returns {@code false} so the dispatcher skips it
 * and falls back — the wiring is inert until an operator loads a model into the serving subprocess.</p>
 */
@Service
public class ServingSubprocessBackend implements LocalServingBackend {

    private static final Logger log = LoggerFactory.getLogger(ServingSubprocessBackend.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /** Optional: absent in contexts where the serving launcher bean isn't present. */
    @Autowired(required = false)
    private ServingSubprocessLauncher launcher;

    /**
     * Returns {@code true} when the serving subprocess is running AND has a model
     * fully loaded (ready to serve generation requests).
     *
     * <p>The subprocess-running check is instant (in-memory flag). The model-loaded
     * check calls {@code GET /api/llm/status} with a 3-second TTL cache so frequent
     * dispatcher polls do not spam the subprocess over HTTP. Any HTTP error is treated
     * as "not available" so the dispatcher falls through to the next backend cleanly.</p>
     */
    @Override
    public boolean isAvailable() {
        return launcher != null && launcher.isRunning() && launcher.isModelLoaded();
    }

    @Override
    public boolean matchesModel(String modelId) {
        if (launcher == null || modelId == null || modelId.isBlank()) {
            return false;
        }
        String activeModelId = launcher.getActiveModelId();
        return activeModelId != null && activeModelId.equals(modelId.trim());
    }

    @Override
    public boolean supportsStructuredChat() {
        return true;
    }

    @Override
    public StructuredChatLanguageModel.Response generateChat(
            StructuredChatLanguageModel.Request request, int maxNewTokens) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        return structuredResponse(launcher.generateChat(request, maxNewTokens));
    }

    @Override
    public StructuredChatLanguageModel.Response generateChatForModel(
            String modelId,
            StructuredChatLanguageModel.Request request,
            int maxNewTokens) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        return structuredResponse(
                launcher.generateChatForModel(modelId, request, maxNewTokens));
    }

    @Override
    public String generate(String prompt) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        return generatedText(launcher.generate(prompt));
    }

    @Override
    public String generate(String prompt, int maxNewTokens) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        return generatedText(launcher.generate(prompt, maxNewTokens));
    }

    @Override
    public String generateForModel(String modelId, String prompt) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        return generatedText(launcher.generateForModel(modelId, prompt));
    }

    @Override
    public String generateForModel(String modelId, String prompt, int maxNewTokens) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        return generatedText(launcher.generateForModel(modelId, prompt, maxNewTokens));
    }

    private StructuredChatLanguageModel.Response structuredResponse(String raw) throws IOException {
        JsonNode response = MAPPER.readTree(raw);
        String finishReason = response.path("finishReason").asText("");
        if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new IOException("Serving subprocess structured generation failed: " + finishReason);
        }
        List<StructuredChatLanguageModel.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : response.path("toolCalls")) {
            Map<String, Object> arguments = MAPPER.convertValue(
                    call.path("arguments"), new TypeReference<Map<String, Object>>() { });
            calls.add(new StructuredChatLanguageModel.ToolCall(
                    call.path("id").asText(""),
                    call.path("name").asText(""),
                    arguments));
        }
        List<StructuredChatLanguageModel.OutputBlock> outputBlocks = new ArrayList<>();
        for (JsonNode block : response.path("outputBlocks")) {
            outputBlocks.add(new StructuredChatLanguageModel.OutputBlock(
                    block.path("type").asText(""),
                    block.path("content").asText("")));
        }
        List<String> parseErrors = response.path("parseErrors").isArray()
                ? MAPPER.convertValue(response.path("parseErrors"),
                        new TypeReference<List<String>>() { })
                : List.of();
        return new StructuredChatLanguageModel.Response(
                response.path("rawText").asText(""),
                response.path("content").asText(""),
                response.path("reasoningContent").asText(""),
                outputBlocks,
                calls,
                parseErrors);
    }

    private String generatedText(String raw) throws IOException {
        // Raw JSON body from POST /api/llm/generate — same contract LocalStagingLlmService parses.
        JsonNode response = MAPPER.readTree(raw);
        String finishReason = response.path("finishReason").asText("");
        if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new IOException("Serving subprocess generation failed: " + finishReason);
        }
        String text = response.path("generatedText").asText("");
        log.debug("[serving-backend] generated {} chars (finishReason={})", text.length(), finishReason);
        return text;
    }
}
