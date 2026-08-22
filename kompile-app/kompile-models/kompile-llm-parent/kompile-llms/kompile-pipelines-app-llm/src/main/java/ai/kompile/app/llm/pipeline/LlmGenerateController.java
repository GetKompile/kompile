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
package ai.kompile.app.llm.pipeline;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serving subprocess endpoint for synchronous text generation.
 *
 * <p>{@code POST /api/llm/generate} — body: {@code { "prompt": "..." }}
 * Response: {@code { "generatedText": "...", "finishReason": "completed",
 *   "totalTimeMs": 123, "tokensPerSecond": 0.0, "firstTokenLatencyMs": 0, "totalTokens": 0 }}</p>
 *
 * <p>On error (no model loaded, or generation failure) the endpoint returns HTTP&nbsp;200 with
 * {@code finishReason} set to {@code "error: <message>"}, matching the contract expected by
 * {@code ServingSubprocessBackend} in app-main which calls
 * {@code finishReason.toLowerCase().startsWith("error")} to detect failures and propagates an
 * {@code IOException} to the {@code CrawlLlmDispatcher} for fallback routing to other lanes.
 * Returning 200 rather than a 4xx/5xx is intentional: {@code postJson()} in
 * {@code ServingSubprocessLauncher} throws an {@code IOException} on any non-2xx, which would
 * surface as a transport error rather than a clean generation-level error to the dispatcher.</p>
 *
 * <p>Wire-compat note: {@code ServingSubprocessLauncher.generate()} sends only
 * {@code {"prompt":"..."}}; requests without maxTokens use the generation budget baked
 * into the model at load time. This endpoint honors an optional positive maxTokens (capped at 4096) and
 * ignores other extra fields so it stays forward-compatible with callers that
 * send a richer payload (e.g. {@code LocalStagingLlmService}).</p>
 *
 * <p>This controller is gated by the same {@code @ConditionalOnProperty} as
 * {@link LlmModelController} so both activate together inside the serving subprocess
 * ({@code kompile.llm.direct-serving.enabled=true}) and are inactive in app-main.</p>
 */
@RestController
@RequestMapping("/api/llm")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "kompile.llm.direct-serving.enabled", havingValue = "true", matchIfMissing = false)
public class LlmGenerateController {

    private static final Logger logger = LoggerFactory.getLogger(LlmGenerateController.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    static final int MAX_REQUEST_MAX_TOKENS = 4096;

    private final SameDiffLanguageModelImpl languageModel;

    @Autowired
    public LlmGenerateController(SameDiffLanguageModelImpl languageModel) {
        this.languageModel = languageModel;
    }

    /**
     * Synchronous text generation against the currently loaded SameDiff model.
     *
     * <p>When no model is loaded, returns HTTP 200 with
     * {@code finishReason: "error: no model loaded"} — not a 4xx/5xx — so the
     * caller's {@code postJson()} does not throw on a non-2xx, and
     * {@code ServingSubprocessBackend} cleanly propagates an {@code IOException}
     * that the dispatcher uses to fall back to other extraction lanes.</p>
     *
     * @param request JSON request body; {@code "prompt"} is the only required key.
     * @return HTTP 200 with {@code generatedText} + {@code finishReason} on both
     *         success ({@code "completed"}) and failure ({@code "error: …"}).
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> request) {
        if (request == null || !request.containsKey("prompt")) {
            return ResponseEntity.ok(errorResponse("prompt field is required"));
        }
        Object rawPrompt = request.get("prompt");
        String prompt = rawPrompt instanceof String s ? s : String.valueOf(rawPrompt);
        if (prompt.isBlank()) {
            return ResponseEntity.ok(errorResponse("prompt must not be blank"));
        }

        Integer maxTokens;
        try {
            maxTokens = requestedMaxTokens(request.get("maxTokens"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(errorResponse(e.getMessage()));
        }

        if (!languageModel.isLoaded()) {
            logger.warn("POST /api/llm/generate called but no model is loaded; returning error response");
            return ResponseEntity.ok(errorResponse("no model loaded; POST /api/llm/load first"));
        }

        long startMs = System.currentTimeMillis();
        try {
            String generatedText = maxTokens != null
                    ? languageModel.generateResponse(prompt, List.of(), maxTokens)
                    : languageModel.generateResponse(prompt, List.of());
            long totalTimeMs = System.currentTimeMillis() - startMs;
            logger.debug("POST /api/llm/generate: generated {} chars in {} ms",
                    generatedText.length(), totalTimeMs);
            return ResponseEntity.ok(successResponse(generatedText, totalTimeMs));
        } catch (Exception e) {
            logger.error("POST /api/llm/generate: generation failed for model '{}'",
                    languageModel.getLoadedModelId(), e);
            return ResponseEntity.ok(errorResponse(e.getMessage()));
        }
    }

    /**
     * Structured chat endpoint used by graph extraction. Messages and function schemas reach the
     * SameDiff chat template unchanged, and the parsed native calls are returned as structured data.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        if (request == null || request.get("request") == null) {
            return ResponseEntity.ok(structuredErrorResponse("request field is required"));
        }
        Integer maxTokens;
        try {
            maxTokens = requestedMaxTokens(request.get("maxTokens"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(structuredErrorResponse(e.getMessage()));
        }
        if (maxTokens == null) {
            return ResponseEntity.ok(structuredErrorResponse("maxTokens field is required"));
        }
        if (!languageModel.isLoaded()) {
            return ResponseEntity.ok(structuredErrorResponse(
                    "no model loaded; POST /api/llm/load first"));
        }
        long startMs = System.currentTimeMillis();
        try {
            StructuredChatLanguageModel.Request chatRequest = MAPPER.convertValue(
                    request.get("request"), StructuredChatLanguageModel.Request.class);
            StructuredChatLanguageModel.Response response =
                    languageModel.generateChat(chatRequest, maxTokens);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rawText", response.rawText());
            body.put("content", response.content());
            body.put("reasoningContent", response.reasoningContent());
            body.put("outputBlocks", response.outputBlocks());
            body.put("toolCalls", response.toolCalls());
            body.put("parseErrors", response.parseErrors());
            body.put("finishReason", "completed");
            body.put("totalTimeMs", System.currentTimeMillis() - startMs);
            if (request.get("correlation") != null) {
                body.put("correlation", request.get("correlation"));
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("POST /api/llm/chat: structured generation failed for model '{}'",
                    languageModel.getLoadedModelId(), e);
            Map<String, Object> body = structuredErrorResponse(e.getMessage());
            if (request.get("correlation") != null) {
                body.put("correlation", request.get("correlation"));
            }
            return ResponseEntity.ok(body);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Integer requestedMaxTokens(Object rawMaxTokens) {
        if (rawMaxTokens == null) {
            return null;
        }

        BigInteger parsed;
        try {
            if (rawMaxTokens instanceof Number number) {
                parsed = new BigDecimal(number.toString()).toBigIntegerExact();
            } else {
                parsed = BigInteger.valueOf(Long.parseLong(String.valueOf(rawMaxTokens).trim()));
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("maxTokens must be a positive integer");
        }

        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException("maxTokens must be a positive integer");
        }
        BigInteger safetyLimit = BigInteger.valueOf(MAX_REQUEST_MAX_TOKENS);
        return parsed.compareTo(safetyLimit) > 0 ? MAX_REQUEST_MAX_TOKENS : parsed.intValueExact();
    }

    private static Map<String, Object> successResponse(String text, long totalTimeMs) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("generatedText", text);
        resp.put("finishReason", "completed");
        resp.put("totalTimeMs", totalTimeMs);
        resp.put("tokensPerSecond", 0.0);
        resp.put("firstTokenLatencyMs", 0);
        resp.put("totalTokens", 0);
        return resp;
    }

    private static Map<String, Object> errorResponse(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("generatedText", "");
        resp.put("finishReason", "error: " + message);
        resp.put("totalTimeMs", 0);
        resp.put("tokensPerSecond", 0.0);
        resp.put("firstTokenLatencyMs", 0);
        resp.put("totalTokens", 0);
        return resp;
    }

    private static Map<String, Object> structuredErrorResponse(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rawText", "");
        resp.put("content", "");
        resp.put("reasoningContent", "");
        resp.put("toolCalls", List.of());
        resp.put("parseErrors", List.of(message == null ? "structured generation failed" : message));
        resp.put("finishReason", "error: " + message);
        resp.put("totalTimeMs", 0);
        return resp;
    }
}
