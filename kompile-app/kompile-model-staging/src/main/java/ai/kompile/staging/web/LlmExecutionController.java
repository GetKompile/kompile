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

package ai.kompile.staging.web;

import ai.kompile.staging.execution.ChatTemplateService;
import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.execution.PromptTemplateService;
import ai.kompile.staging.execution.TextPipelineService;
import ai.kompile.staging.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * REST API controller for LLM text generation execution.
 * Provides endpoints for model loading/unloading, text generation,
 * sampling presets, speculative decoding, and decoder configuration.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/llm")
@CrossOrigin(origins = "*")
public class LlmExecutionController {

    private static final Logger log = LoggerFactory.getLogger(LlmExecutionController.class);

    private final LlmExecutionService executionService;
    private final ChatTemplateService chatTemplateService;
    private final PromptTemplateService promptTemplateService;
    private final TextPipelineService textPipelineService;

    public LlmExecutionController(LlmExecutionService executionService,
                                  ChatTemplateService chatTemplateService,
                                  PromptTemplateService promptTemplateService,
                                  TextPipelineService textPipelineService) {
        this.executionService = executionService;
        this.chatTemplateService = chatTemplateService;
        this.promptTemplateService = promptTemplateService;
        this.textPipelineService = textPipelineService;
    }

    // ==================== Model Management ====================

    /**
     * Load an LLM model for inference.
     */
    @PostMapping("/load")
    public ResponseEntity<LlmModelStatusResponse> loadModel(@RequestBody LlmLoadModelRequest request) {
        try {
            String modelId = request.getModelId();
            if (modelId == null || modelId.isBlank()) {
                return ResponseEntity.badRequest().body(LlmModelStatusResponse.builder()
                    .loaded(false)
                    .message("modelId is required")
                    .build());
            }

            // Use explicit model path if provided, otherwise use model ID as path placeholder
            String modelPath = request.getModelPath() != null && !request.getModelPath().isBlank()
                    ? request.getModelPath() : modelId;
            String kvCacheType = request.getKvCacheType() != null ? request.getKvCacheType() : "STATIC";
            LlmModelStatusResponse response = executionService.loadModel(modelId, modelPath, kvCacheType);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to load model", e);
            return ResponseEntity.ok(LlmModelStatusResponse.builder()
                .loaded(false)
                .message("Failed to load model: " + e.getMessage())
                .build());
        }
    }

    /**
     * Unload the currently loaded LLM model.
     */
    @PostMapping("/unload")
    public ResponseEntity<LlmModelStatusResponse> unloadModel() {
        try {
            LlmModelStatusResponse response = executionService.unloadModel();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to unload model", e);
            return ResponseEntity.ok(LlmModelStatusResponse.builder()
                .loaded(false)
                .message("Failed to unload model: " + e.getMessage())
                .build());
        }
    }

    /**
     * Get current LLM model status.
     */
    @GetMapping("/status")
    public ResponseEntity<LlmModelStatusResponse> getStatus() {
        return ResponseEntity.ok(executionService.getStatus());
    }

    // ==================== Text Generation ====================

    /**
     * Generate text from a prompt using the loaded LLM model.
     */
    @PostMapping("/generate")
    public ResponseEntity<LlmGenerateResponse> generate(@RequestBody LlmGenerateRequest request) {
        try {
            if (request.getPrompt() == null || request.getPrompt().isBlank()) {
                return ResponseEntity.badRequest().body(LlmGenerateResponse.builder()
                    .generatedText("")
                    .finishReason("error: prompt is required")
                    .build());
            }

            LlmGenerateResponse response = executionService.generate(request);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Generation failed", e);
            return ResponseEntity.ok(LlmGenerateResponse.builder()
                .generatedText("")
                .finishReason("error: " + e.getMessage())
                .build());
        }
    }

    // ==================== Presets ====================

    /**
     * Get available sampling presets.
     */
    @GetMapping("/presets")
    public ResponseEntity<List<Map<String, Object>>> getPresets() {
        return ResponseEntity.ok(executionService.getSamplingPresets());
    }

    // ==================== Speculative Decoding ====================

    /**
     * Get current speculative decoding configuration.
     */
    @GetMapping("/speculative")
    public ResponseEntity<SpeculativeDecodingConfig> getSpeculativeConfig() {
        return ResponseEntity.ok(executionService.getSpeculativeConfig());
    }

    /**
     * Update speculative decoding configuration.
     */
    @PutMapping("/speculative")
    public ResponseEntity<SpeculativeDecodingConfig> updateSpeculativeConfig(
            @RequestBody SpeculativeDecodingConfig config) {
        return ResponseEntity.ok(executionService.updateSpeculativeConfig(config));
    }

    // ==================== Decoder Configuration ====================

    /**
     * Get current decoder configuration.
     */
    @GetMapping("/decoder-config")
    public ResponseEntity<DecoderConfigRequest> getDecoderConfig() {
        return ResponseEntity.ok(executionService.getDecoderConfig());
    }

    /**
     * Update decoder configuration.
     */
    @PutMapping("/decoder-config")
    public ResponseEntity<DecoderConfigRequest> updateDecoderConfig(
            @RequestBody DecoderConfigRequest config) {
        return ResponseEntity.ok(executionService.updateDecoderConfig(config));
    }

    // ==================== Pipeline Info ====================

    /**
     * Get detailed pipeline information for the currently loaded model.
     */
    @GetMapping("/pipeline-info")
    public ResponseEntity<PipelineInfoResponse> getPipelineInfo() {
        return ResponseEntity.ok(executionService.getPipelineInfo());
    }

    // ==================== Generation Control ====================

    /**
     * Cancel any ongoing generation.
     */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelGeneration() {
        executionService.cancelGeneration();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Generation cancelled");
        return ResponseEntity.ok(result);
    }

    /**
     * Check if generation is in progress.
     */
    @GetMapping("/generating")
    public ResponseEntity<Map<String, Object>> isGenerating() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generating", executionService.isGenerating());
        return ResponseEntity.ok(result);
    }

    // ==================== Streaming ====================

    @GetMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestParam String prompt,
                                     @RequestParam(defaultValue = "256") int maxTokens,
                                     @RequestParam(required = false) String presetName) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 min timeout

        LlmGenerateRequest request = LlmGenerateRequest.builder()
                .prompt(prompt)
                .maxTokens(maxTokens)
                .presetName(presetName)
                .build();

        new Thread(() -> {
            try {
                LlmGenerateResponse response = executionService.generateStreaming(request, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (IOException e) {
                        log.warn("SSE send failed", e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(response));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        }, "llm-stream-generate").start();

        return emitter;
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStreamPost(@RequestBody LlmGenerateRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);

        new Thread(() -> {
            try {
                LlmGenerateResponse response = executionService.generateStreaming(request, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (IOException e) {
                        log.warn("SSE send failed", e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(response));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        }, "llm-stream-generate").start();

        return emitter;
    }

    // ==================== Chat ====================

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            ChatResponse response = executionService.chat(request, chatTemplateService);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat failed", e);
            return ResponseEntity.ok(ChatResponse.builder()
                    .assistantMessage("")
                    .finishReason("error: " + e.getMessage())
                    .build());
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);

        new Thread(() -> {
            try {
                ChatResponse response = executionService.chatStreaming(request, chatTemplateService, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (IOException e) {
                        log.warn("SSE send failed", e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(response));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        }, "llm-stream-chat").start();

        return emitter;
    }

    // ==================== Prompt Templates ====================

    @GetMapping("/templates")
    public ResponseEntity<List<PromptTemplate>> listTemplates() {
        return ResponseEntity.ok(promptTemplateService.listAll());
    }

    @GetMapping("/templates/builtin")
    public ResponseEntity<List<Map<String, String>>> getBuiltinTemplates() {
        return ResponseEntity.ok(chatTemplateService.getBuiltinTemplates());
    }

    @PostMapping("/templates")
    public ResponseEntity<PromptTemplate> createTemplate(@RequestBody PromptTemplate template) {
        return ResponseEntity.ok(promptTemplateService.create(template));
    }

    @GetMapping("/templates/{id}")
    public ResponseEntity<PromptTemplate> getTemplate(@PathVariable String id) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(template);
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<PromptTemplate> updateTemplate(@PathVariable String id, @RequestBody PromptTemplate template) {
        PromptTemplate updated = promptTemplateService.update(id, template);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(@PathVariable String id) {
        boolean deleted = promptTemplateService.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", deleted);
        result.put("id", id);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/templates/{id}/apply")
    public ResponseEntity<Map<String, Object>> applyTemplate(@PathVariable String id, @RequestBody Map<String, String> variables) {
        String result = promptTemplateService.apply(id, variables);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("templateId", id);
        return ResponseEntity.ok(response);
    }

    // ==================== Text Pipelines ====================

    @GetMapping("/pipelines")
    public ResponseEntity<List<TextPipelineDefinition>> listPipelines() {
        return ResponseEntity.ok(textPipelineService.listAll());
    }

    @PostMapping("/pipelines")
    public ResponseEntity<TextPipelineDefinition> createPipeline(@RequestBody TextPipelineDefinition definition) {
        return ResponseEntity.ok(textPipelineService.create(definition));
    }

    @GetMapping("/pipelines/{id}")
    public ResponseEntity<TextPipelineDefinition> getPipeline(@PathVariable String id) {
        TextPipelineDefinition pipeline = textPipelineService.getById(id);
        if (pipeline == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(pipeline);
    }

    @DeleteMapping("/pipelines/{id}")
    public ResponseEntity<Map<String, Object>> deletePipeline(@PathVariable String id) {
        boolean deleted = textPipelineService.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", deleted);
        result.put("id", id);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pipelines/{id}/execute")
    public ResponseEntity<TextPipelineResult> executePipeline(@PathVariable String id,
                                                               @RequestBody(required = false) Map<String, String> variables) {
        try {
            TextPipelineResult result = textPipelineService.execute(id, variables);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Pipeline execution failed", e);
            return ResponseEntity.ok(TextPipelineResult.builder()
                    .pipelineId(id)
                    .finishReason("error: " + e.getMessage())
                    .build());
        }
    }

    // ==================== Batch Generation ====================

    @PostMapping("/generate/batch")
    public ResponseEntity<BatchGenerateResponse> generateBatch(@RequestBody BatchGenerateRequest request) {
        try {
            if (request.getPrompts() == null || request.getPrompts().isEmpty()) {
                return ResponseEntity.badRequest().body(BatchGenerateResponse.builder()
                        .results(Collections.emptyList())
                        .build());
            }
            BatchGenerateResponse response = executionService.generateBatch(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Batch generation failed", e);
            return ResponseEntity.ok(BatchGenerateResponse.builder()
                    .results(Collections.emptyList())
                    .errorCount(request.getPrompts() != null ? request.getPrompts().size() : 0)
                    .build());
        }
    }
}
