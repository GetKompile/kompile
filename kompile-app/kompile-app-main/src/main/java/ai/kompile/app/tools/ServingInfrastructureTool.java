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

package ai.kompile.app.tools;

import ai.kompile.app.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Tool for LLM serving infrastructure operations:
 * warmup, memory pools, weight cache, scheduling, admission control, multi-backend testing.
 */
@Component
public class ServingInfrastructureTool {

    private static final Logger logger = LoggerFactory.getLogger(ServingInfrastructureTool.class);

    private final ModelWarmupService warmupService;
    private final MemoryPoolManager memoryPoolManager;
    private final ModelWeightCache weightCache;
    private final ModelScheduler modelScheduler;
    private final ModelAdmissionController admissionController;
    private final MultiBackendTestService multiBackendTestService;

    @Autowired
    public ServingInfrastructureTool(
            @Autowired(required = false) ModelWarmupService warmupService,
            @Autowired(required = false) MemoryPoolManager memoryPoolManager,
            @Autowired(required = false) ModelWeightCache weightCache,
            @Autowired(required = false) ModelScheduler modelScheduler,
            @Autowired(required = false) ModelAdmissionController admissionController,
            @Autowired(required = false) MultiBackendTestService multiBackendTestService) {
        this.warmupService = warmupService;
        this.memoryPoolManager = memoryPoolManager;
        this.weightCache = weightCache;
        this.modelScheduler = modelScheduler;
        this.admissionController = admissionController;
        this.multiBackendTestService = multiBackendTestService;
        logger.info("ServingInfrastructureTool initialized");
    }

    // ==================== Warmup Tools ====================

    public record GetWarmupStatusInput() {}
    public record TriggerWarmupInput(String serviceType) {}

    @Tool(name = "get_warmup_status",
            description = "Gets model warmup status including configuration, warmup results, and available warmable services.")
    public Map<String, Object> getWarmupStatus(GetWarmupStatusInput input) {
        try {
            if (warmupService == null) return Map.of("status", "error", "error", "ModelWarmupService not available");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(warmupService.getStatus());
            return result;
        } catch (Exception e) {
            logger.error("Error getting warmup status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "trigger_warmup",
            description = "Triggers model warmup. Optionally specify a serviceType to warm up a specific service, or leave null to warm up all.")
    public Map<String, Object> triggerWarmup(TriggerWarmupInput input) {
        try {
            if (warmupService == null) return Map.of("status", "error", "error", "ModelWarmupService not available");
            if (input.serviceType() != null && !input.serviceType().isBlank()) {
                var result = warmupService.warmupService(input.serviceType());
                if (result == null) return Map.of("status", "error", "error", "Service not found or not warmable: " + input.serviceType());
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "success");
                response.put("result", Map.of(
                        "serviceType", result.serviceType(),
                        "success", result.success(),
                        "latencyMs", result.latencyMs(),
                        "iterations", result.iterations(),
                        "error", result.error() != null ? result.error() : ""
                ));
                return response;
            } else {
                var results = warmupService.warmupAll();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "success");
                response.put("results", results);
                return response;
            }
        } catch (Exception e) {
            logger.error("Error triggering warmup: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Memory Pool Tools ====================

    public record GetMemoryPoolStatusInput() {}
    public record SetPoolFractionsInput(Double weightsFraction, Double activationsFraction, Double kvCacheFraction) {}

    @Tool(name = "get_memory_pool_status",
            description = "Gets memory pool status showing weight, activation, and KV cache pool utilization per GPU device.")
    public Map<String, Object> getMemoryPoolStatus(GetMemoryPoolStatusInput input) {
        try {
            if (memoryPoolManager == null) return Map.of("status", "error", "error", "MemoryPoolManager not available (is it enabled?)");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(memoryPoolManager.getStatus());
            return result;
        } catch (Exception e) {
            logger.error("Error getting memory pool status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "set_pool_fractions",
            description = "Sets memory pool fractions for weights, activations, and KV cache. Fractions must sum to <= 1.0.")
    public Map<String, Object> setPoolFractions(SetPoolFractionsInput input) {
        try {
            if (memoryPoolManager == null) return Map.of("status", "error", "error", "MemoryPoolManager not available");
            if (input.weightsFraction() != null) memoryPoolManager.setWeightsFraction(input.weightsFraction());
            if (input.activationsFraction() != null) memoryPoolManager.setActivationsFraction(input.activationsFraction());
            if (input.kvCacheFraction() != null) memoryPoolManager.setKvCacheFraction(input.kvCacheFraction());
            return Map.of("status", "success", "message", "Pool fractions updated", "pools", memoryPoolManager.getStatus());
        } catch (Exception e) {
            logger.error("Error setting pool fractions: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Weight Cache Tools ====================

    public record GetWeightCacheStatusInput() {}
    public record DemoteModelWeightsInput(String modelId, String layerName) {}
    public record PromoteModelWeightsInput(String modelId, String layerName) {}

    @Tool(name = "get_weight_cache_status",
            description = "Gets three-tier weight cache status showing GPU/CPU/disk tier distribution and memory usage.")
    public Map<String, Object> getWeightCacheStatus(GetWeightCacheStatusInput input) {
        try {
            if (weightCache == null) return Map.of("status", "error", "error", "ModelWeightCache not available (is it enabled?)");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(weightCache.getStatus());
            return result;
        } catch (Exception e) {
            logger.error("Error getting weight cache status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "demote_model_weights",
            description = "Demotes model weights from GPU to CPU (or CPU to disk). Specify modelId and optionally layerName.")
    public Map<String, Object> demoteModelWeights(DemoteModelWeightsInput input) {
        try {
            if (weightCache == null) return Map.of("status", "error", "error", "ModelWeightCache not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            if (input.layerName() != null) {
                weightCache.demoteToHost(input.modelId(), input.layerName());
            } else {
                weightCache.demoteAllToHost(input.modelId());
            }
            return Map.of("status", "success", "message", "Weights demoted for " + input.modelId());
        } catch (Exception e) {
            logger.error("Error demoting weights: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "promote_model_weights",
            description = "Promotes model weights from CPU/disk back to GPU. Specify modelId and optionally layerName.")
    public Map<String, Object> promoteModelWeights(PromoteModelWeightsInput input) {
        try {
            if (weightCache == null) return Map.of("status", "error", "error", "ModelWeightCache not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            if (input.layerName() != null) {
                weightCache.promoteToGpu(input.modelId(), input.layerName());
            } else {
                weightCache.promoteAllToGpu(input.modelId());
            }
            return Map.of("status", "success", "message", "Weights promoted for " + input.modelId());
        } catch (Exception e) {
            logger.error("Error promoting weights: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Scheduler Tools ====================

    public record GetSchedulerStatsInput() {}

    @Tool(name = "get_scheduler_stats",
            description = "Gets request scheduler statistics including queue depths, batch sizes, throughput, and latency metrics.")
    public Map<String, Object> getSchedulerStats(GetSchedulerStatsInput input) {
        try {
            if (modelScheduler == null) return Map.of("status", "error", "error", "ModelScheduler not available (is it enabled?)");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(modelScheduler.getStatus());
            return result;
        } catch (Exception e) {
            logger.error("Error getting scheduler stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Admission Controller Tools ====================

    public record GetLoadedModelsInput() {}
    public record RequestModelLoadInput(String modelId) {}
    public record UnloadModelInput(String modelId) {}

    @Tool(name = "get_loaded_models",
            description = "Gets all loaded models with their states (LOADING, GPU_HOT, CPU_WARM, UNLOADED) and memory usage.")
    public Map<String, Object> getLoadedModels(GetLoadedModelsInput input) {
        try {
            if (admissionController == null) return Map.of("status", "error", "error", "ModelAdmissionController not available (is it enabled?)");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(admissionController.getStatus());
            return result;
        } catch (Exception e) {
            logger.error("Error getting loaded models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "request_model_load",
            description = "Requests a model to be loaded with memory-aware admission control. Returns admission decision.")
    public Map<String, Object> requestModelLoad(RequestModelLoadInput input) {
        try {
            if (admissionController == null) return Map.of("status", "error", "error", "ModelAdmissionController not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            var decision = admissionController.canAdmit(input.modelId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("admitted", decision.admitted());
            result.put("reason", decision.reason());
            result.put("estimatedMemoryBytes", decision.estimatedMemoryBytes());
            result.put("modelsToEvict", decision.modelsToEvict());
            return result;
        } catch (Exception e) {
            logger.error("Error requesting model load: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "unload_model",
            description = "Unloads a model, freeing all associated GPU/CPU memory.")
    public Map<String, Object> unloadModel(UnloadModelInput input) {
        try {
            if (admissionController == null) return Map.of("status", "error", "error", "ModelAdmissionController not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            admissionController.unload(input.modelId());
            return Map.of("status", "success", "message", "Model unloaded: " + input.modelId());
        } catch (Exception e) {
            logger.error("Error unloading model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Multi-Backend Test Tools ====================

    public record RunMultiBackendTestsInput() {}
    public record TestCpuFallbackInput() {}

    @Tool(name = "run_multi_backend_tests",
            description = "Runs the full multi-backend test suite validating CPU fallback, op delegation, transfer metrics, and numerical accuracy.")
    public Map<String, Object> runMultiBackendTests(RunMultiBackendTestsInput input) {
        try {
            if (multiBackendTestService == null) return Map.of("status", "error", "error", "MultiBackendTestService not available");
            var results = multiBackendTestService.runAllTests();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("totalTests", results.size());
            long passed = results.stream().filter(MultiBackendTestService.TestResult::passed).count();
            response.put("passed", passed);
            response.put("failed", results.size() - passed);
            response.put("results", results);
            return response;
        } catch (Exception e) {
            logger.error("Error running multi-backend tests: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_cpu_fallback",
            description = "Tests CPU fallback specifically: creates a GPU tensor, forces CPU execution, and verifies correctness.")
    public Map<String, Object> testCpuFallback(TestCpuFallbackInput input) {
        try {
            if (multiBackendTestService == null) return Map.of("status", "error", "error", "MultiBackendTestService not available");
            var result = multiBackendTestService.testCpuFallback();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("testName", result.testName());
            response.put("passed", result.passed());
            response.put("durationMs", result.durationMs());
            response.put("details", result.details());
            if (result.error() != null) response.put("error", result.error());
            return response;
        } catch (Exception e) {
            logger.error("Error testing CPU fallback: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
