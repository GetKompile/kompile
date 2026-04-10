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

package ai.kompile.app.services;

import ai.kompile.app.config.ModelWarmupConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages model warmup to eliminate first-request latency spikes caused by
 * uncompiled CUDA kernels and unallocated memory.
 *
 * <p>Listens for {@link GpuLifecycleEvent#SERVICE_RESTORED} events and automatically
 * runs warmup iterations on services implementing {@link ModelLifecycleManager.WarmableService}.</p>
 */
@Service
public class ModelWarmupService {

    private static final Logger log = LoggerFactory.getLogger(ModelWarmupService.class);

    /**
     * Result of a warmup run for a specific service.
     */
    public record WarmupResult(
            String serviceType,
            boolean success,
            long latencyMs,
            int iterations,
            Instant completedAt,
            String error
    ) {}

    private final ModelWarmupConfigService configService;
    private final ModelLifecycleManager lifecycleManager;
    private final Map<String, WarmupResult> warmupResults = new ConcurrentHashMap<>();

    public ModelWarmupService(ModelWarmupConfigService configService,
                              ModelLifecycleManager lifecycleManager) {
        this.configService = configService;
        this.lifecycleManager = lifecycleManager;
        log.info("ModelWarmupService initialized");
    }

    @EventListener
    public void onGpuLifecycleEvent(GpuLifecycleEvent event) {
        if (event.getEventType() == GpuLifecycleEvent.EventType.SERVICE_RESTORED) {
            String serviceType = event.getServiceType();
            ModelWarmupConfig config = configService.getConfiguration();
            if (config.isEnabled()) {
                warmupService(serviceType, config);
            }
        }
    }

    /**
     * Trigger warmup for a specific service.
     */
    public WarmupResult warmupService(String serviceType) {
        return warmupService(serviceType, configService.getConfiguration());
    }

    /**
     * Trigger warmup for all registered warmable services.
     */
    public Map<String, WarmupResult> warmupAll() {
        ModelWarmupConfig config = configService.getConfiguration();
        Map<String, WarmupResult> results = new LinkedHashMap<>();
        for (var entry : getWarmableServices().entrySet()) {
            WarmupResult result = warmupService(entry.getKey(), config);
            results.put(entry.getKey(), result);
        }
        return results;
    }

    private WarmupResult warmupService(String serviceType, ModelWarmupConfig config) {
        var serviceOpt = lifecycleManager.getService(serviceType);
        if (serviceOpt.isEmpty()) {
            log.debug("No managed service '{}' for warmup", serviceType);
            return null;
        }

        var service = serviceOpt.get();
        if (!(service instanceof ModelLifecycleManager.WarmableService warmable)) {
            log.debug("Service '{}' does not implement WarmableService, skipping warmup", serviceType);
            return null;
        }

        if (warmable.isWarmedUp()) {
            log.debug("Service '{}' already warmed up", serviceType);
            WarmupResult existing = warmupResults.get(serviceType);
            return existing != null ? existing : new WarmupResult(serviceType, true, 0, 0, Instant.now(), null);
        }

        log.info("Starting warmup for service '{}' ({} iterations, timeout={}s)",
                serviceType, config.getIterations(), config.getTimeoutSeconds());

        long startMs = System.currentTimeMillis();
        try {
            long latency = warmable.warmup(config.getIterations(), config.getWarmupText());
            long totalMs = System.currentTimeMillis() - startMs;

            WarmupResult result = new WarmupResult(serviceType, true, latency, config.getIterations(),
                    Instant.now(), null);
            warmupResults.put(serviceType, result);

            log.info("Warmup complete for '{}': {}ms ({} iterations)", serviceType, totalMs, config.getIterations());
            return result;

        } catch (Exception e) {
            long totalMs = System.currentTimeMillis() - startMs;
            WarmupResult result = new WarmupResult(serviceType, false, totalMs, config.getIterations(),
                    Instant.now(), e.getMessage());
            warmupResults.put(serviceType, result);

            if (config.isFailFast()) {
                log.error("Warmup FAILED for '{}' (fail-fast=true): {}", serviceType, e.getMessage());
                throw new RuntimeException("Warmup failed for service '" + serviceType + "'", e);
            } else {
                log.warn("Warmup failed for '{}' (fail-fast=false, continuing): {}", serviceType, e.getMessage());
            }
            return result;
        }
    }

    /**
     * Get warmup results for all services that have been warmed up.
     */
    public Map<String, WarmupResult> getWarmupResults() {
        return Map.copyOf(warmupResults);
    }

    /**
     * Get warmup result for a specific service.
     */
    public WarmupResult getWarmupResult(String serviceType) {
        return warmupResults.get(serviceType);
    }

    /**
     * Get status map for monitoring/REST API.
     */
    public Map<String, Object> getStatus() {
        ModelWarmupConfig config = configService.getConfiguration();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.isEnabled());
        status.put("iterations", config.getIterations());
        status.put("timeoutSeconds", config.getTimeoutSeconds());

        Map<String, Object> results = new LinkedHashMap<>();
        for (var entry : warmupResults.entrySet()) {
            WarmupResult r = entry.getValue();
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("success", r.success());
            rm.put("latencyMs", r.latencyMs());
            rm.put("iterations", r.iterations());
            rm.put("completedAt", r.completedAt() != null ? r.completedAt().toString() : null);
            rm.put("error", r.error());
            results.put(entry.getKey(), rm);
        }
        status.put("results", results);
        status.put("warmableServices", getWarmableServices().keySet());
        return status;
    }

    private Map<String, ModelLifecycleManager.WarmableService> getWarmableServices() {
        Map<String, ModelLifecycleManager.WarmableService> warmable = new LinkedHashMap<>();
        // Access managed services via lifecycle manager's getService by checking known types
        for (String type : new String[]{"embedding", "vlm", "ingest", "vectorPopulation", "modelInit"}) {
            var svc = lifecycleManager.getService(type);
            if (svc.isPresent() && svc.get() instanceof ModelLifecycleManager.WarmableService ws) {
                warmable.put(type, ws);
            }
        }
        return warmable;
    }
}
