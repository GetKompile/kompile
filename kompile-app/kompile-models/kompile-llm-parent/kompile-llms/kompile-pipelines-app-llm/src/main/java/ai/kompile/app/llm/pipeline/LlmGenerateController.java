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
 * {@code {"prompt":"..."}}; generation parameters (maxTokens, temperature, etc.) are baked
 * into the model at load time via {@code POST /api/llm/load}.  This endpoint accepts but
 * ignores extra fields in the request body so it stays forward-compatible with callers that
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

        if (!languageModel.isLoaded()) {
            logger.warn("POST /api/llm/generate called but no model is loaded; returning error response");
            return ResponseEntity.ok(errorResponse("no model loaded; POST /api/llm/load first"));
        }

        long startMs = System.currentTimeMillis();
        try {
            String generatedText = languageModel.generateResponse(prompt, List.of());
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

    // ── helpers ───────────────────────────────────────────────────────────────

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
}
