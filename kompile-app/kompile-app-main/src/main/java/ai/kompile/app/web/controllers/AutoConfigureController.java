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

package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.config.HardwareAutoConfigurator;
import ai.kompile.cli.common.config.HardwareAutoConfigurator.AutoConfigResult;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.PipelineConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.web.dto.PipelineConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for hardware-based auto-configuration.
 * <p>
 * Detects system hardware (RAM, CPUs, GPU) and generates optimal settings
 * for subprocess, ND4J, and pipeline configurations. The generated values
 * are persisted to {@code ~/.kompile/config/} JSON files and can be
 * overridden via the existing UI components (Subprocess Environment,
 * ND4J Environment, Processing Settings).
 */
@RestController
@RequestMapping("/api/auto-configure")
@CrossOrigin(origins = "*")
public class AutoConfigureController {

    private static final Logger log = LoggerFactory.getLogger(AutoConfigureController.class);

    private final SubprocessConfigService subprocessConfigService;
    private final Nd4jEnvironmentConfigService nd4jConfigService;
    private final PipelineConfigService pipelineConfigService;

    @Autowired
    public AutoConfigureController(
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jConfigService,
            @Autowired(required = false) PipelineConfigService pipelineConfigService) {
        this.subprocessConfigService = subprocessConfigService;
        this.nd4jConfigService = nd4jConfigService;
        this.pipelineConfigService = pipelineConfigService;
    }

    /**
     * Detect hardware and return recommended configuration WITHOUT applying it.
     * Use this as a preview before calling {@code POST /api/auto-configure/apply}.
     *
     * @param hasLocalEmbedding whether local SameDiff/Anserini embeddings are used
     *                          (needs more memory). Defaults to true.
     * @return detected hardware info and recommended config values
     */
    @GetMapping("/detect")
    public ResponseEntity<Map<String, Object>> detect(
            @RequestParam(defaultValue = "true") boolean hasLocalEmbedding) {

        AutoConfigResult result = HardwareAutoConfigurator.autoConfigure(hasLocalEmbedding);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hardware", result.hardware);
        response.put("recommended", Map.of(
                "subprocessConfig", result.subprocessConfig,
                "nd4jConfig", result.nd4jConfig,
                "pipelineConfig", result.pipelineConfig
        ));
        response.put("note", "These are recommended values based on detected hardware. " +
                "Call POST /api/auto-configure/apply to write them, " +
                "then override any individual values via the UI.");

        return ResponseEntity.ok(response);
    }

    /**
     * Detect hardware, generate optimal configuration, and apply it.
     * <p>
     * This writes to:
     * <ul>
     *   <li>{@code subprocess-ingest-config.json} via SubprocessConfigService</li>
     *   <li>{@code nd4j-environment-config.json} via Nd4jEnvironmentConfigService</li>
     *   <li>{@code pipeline-config.json} via PipelineConfigService</li>
     * </ul>
     * All values remain overridable via the existing UI components.
     *
     * @param request optional request body to override hasLocalEmbedding
     * @return applied configuration values
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(
            @RequestBody(required = false) AutoConfigureRequest request) {

        boolean hasLocalEmbedding = request == null || request.hasLocalEmbedding;

        log.info("=== AUTO-CONFIGURE APPLY REQUESTED (hasLocalEmbedding={}) ===", hasLocalEmbedding);

        AutoConfigResult result = HardwareAutoConfigurator.autoConfigure(hasLocalEmbedding);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hardware", result.hardware);

        Map<String, Object> applied = new LinkedHashMap<>();

        // 1. Apply subprocess config
        if (subprocessConfigService != null) {
            try {
                SubprocessConfigUpdate update = new SubprocessConfigUpdate(
                        (Boolean) result.subprocessConfig.get("enabled"),
                        (String) result.subprocessConfig.get("javaPath"),
                        (String) result.subprocessConfig.get("heapSize"),
                        null, // offHeapMaxBytes — let multiplier handle it
                        (Integer) result.subprocessConfig.get("offHeapMultiplier"),
                        (Integer) result.subprocessConfig.get("timeoutMinutes"),
                        // VLM subprocess — leave unchanged (null = keep current)
                        null, null, null, null,
                        (Integer) result.subprocessConfig.get("heartbeatIntervalSeconds"),
                        (Integer) result.subprocessConfig.get("staleThresholdSeconds"),
                        (Integer) result.subprocessConfig.get("queueCapacity"),
                        (Boolean) result.subprocessConfig.get("parallelIndexing"),
                        (Integer) result.subprocessConfig.get("indexingWorkers"),
                        (Integer) result.subprocessConfig.get("indexingBatchAccumulationSize"),
                        null, // embeddingThreads
                        // Restart config — leave unchanged
                        null, null, null, null, null, null,
                        // Stall detection — leave unchanged
                        null, null, null, null,
                        // Native executable — leave unchanged
                        null, null, null, null, null, null, null,
                        // Memory watchdog — leave unchanged
                        null, null, null, null, null, null,
                        null // gpuSoftLimitPercent
                );
                subprocessConfigService.updateConfiguration(update);
                applied.put("subprocessConfig", result.subprocessConfig);
                log.info("Applied subprocess config: heapSize={}, offHeapMultiplier={}, workers={}",
                        result.subprocessConfig.get("heapSize"),
                        result.subprocessConfig.get("offHeapMultiplier"),
                        result.subprocessConfig.get("indexingWorkers"));
            } catch (Exception e) {
                log.error("Failed to apply subprocess config: {}", e.getMessage(), e);
                applied.put("subprocessConfigError", e.getMessage());
            }
        } else {
            applied.put("subprocessConfig", "skipped (service not available)");
        }

        // 2. Apply ND4J config
        if (nd4jConfigService != null) {
            try {
                Nd4jEnvironmentConfig nd4jUpdate = Nd4jEnvironmentConfig.builder()
                        .maxThreads((Integer) result.nd4jConfig.get("maxThreads"))
                        .maxMasterThreads((Integer) result.nd4jConfig.get("maxMasterThreads"))
                        .ompNumThreads((Integer) result.nd4jConfig.get("ompNumThreads"))
                        .openBlasThreads((Integer) result.nd4jConfig.get("openBlasThreads"))
                        .enableBlas((Boolean) result.nd4jConfig.get("enableBlas"))
                        .helpersAllowed((Boolean) result.nd4jConfig.get("helpersAllowed"))
                        .blasSerializationEnabled((Boolean) result.nd4jConfig.get("blasSerializationEnabled"))
                        .lifecycleTracking((Boolean) result.nd4jConfig.get("lifecycleTracking"))
                        .debug((Boolean) result.nd4jConfig.get("debug"))
                        .verbose((Boolean) result.nd4jConfig.get("verbose"))
                        .profiling((Boolean) result.nd4jConfig.get("profiling"))
                        .leaksDetector((Boolean) result.nd4jConfig.get("leaksDetector"))
                        .optimizerEnabled((Boolean) result.nd4jConfig.get("optimizerEnabled"))
                        .optimizerFp16((Boolean) result.nd4jConfig.getOrDefault("optimizerFp16", null))
                        .build();
                nd4jConfigService.updateConfiguration(nd4jUpdate);
                applied.put("nd4jConfig", result.nd4jConfig);
                log.info("Applied ND4J config: maxThreads={}, ompNumThreads={}, openBlasThreads={}",
                        result.nd4jConfig.get("maxThreads"),
                        result.nd4jConfig.get("ompNumThreads"),
                        result.nd4jConfig.get("openBlasThreads"));
            } catch (Exception e) {
                log.error("Failed to apply ND4J config: {}", e.getMessage(), e);
                applied.put("nd4jConfigError", e.getMessage());
            }
        } else {
            applied.put("nd4jConfig", "skipped (service not available)");
        }

        // 3. Apply pipeline config
        if (pipelineConfigService != null) {
            try {
                PipelineConfigDto pipelineDto = PipelineConfigDto.builder()
                        .minBatchSize((Integer) result.pipelineConfig.get("minBatchSize"))
                        .defaultBatchSize((Integer) result.pipelineConfig.get("defaultBatchSize"))
                        .maxBatchSize((Integer) result.pipelineConfig.get("maxBatchSize"))
                        .queueCapacity((Integer) result.pipelineConfig.get("queueCapacity"))
                        .embeddingThreads((Integer) result.pipelineConfig.get("embeddingThreads"))
                        .chunkingThreads((Integer) result.pipelineConfig.get("chunkingThreads"))
                        .indexingThreads((Integer) result.pipelineConfig.get("indexingThreads"))
                        .indexingBatchAccumulationSize((Integer) result.pipelineConfig.get("indexingBatchAccumulationSize"))
                        .skipEmbedding((Boolean) result.pipelineConfig.get("skipEmbedding"))
                        .optimizeGraphOnLoad((Boolean) result.pipelineConfig.get("optimizeGraphOnLoad"))
                        .build();
                pipelineConfigService.updateConfig(pipelineDto);
                applied.put("pipelineConfig", result.pipelineConfig);
                log.info("Applied pipeline config: defaultBatchSize={}, embeddingThreads={}, chunkingThreads={}",
                        result.pipelineConfig.get("defaultBatchSize"),
                        result.pipelineConfig.get("embeddingThreads"),
                        result.pipelineConfig.get("chunkingThreads"));
            } catch (Exception e) {
                log.error("Failed to apply pipeline config: {}", e.getMessage(), e);
                applied.put("pipelineConfigError", e.getMessage());
            }
        } else {
            applied.put("pipelineConfig", "skipped (service not available)");
        }

        response.put("applied", applied);
        response.put("message", "Auto-configuration applied based on detected hardware. " +
                "Override any values via Developer Hub > Processing Settings / Subprocess Environment / ND4J Environment.");

        log.info("Auto-configure completed: tier={}, ramGb={}, cpus={}",
                result.hardware.get("tier"),
                result.hardware.get("totalRamGb"),
                result.hardware.get("cpuCount"));

        return ResponseEntity.ok(response);
    }

    /**
     * Request body for the apply endpoint.
     */
    public static class AutoConfigureRequest {
        /** Whether the project uses local SameDiff/Anserini embeddings (default: true). */
        public boolean hasLocalEmbedding = true;
    }
}
