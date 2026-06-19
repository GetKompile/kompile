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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the {@link ai.kompile.app.services.scheduler.ResourceAwareJobScheduler}.
 * Persisted to {@code ~/.kompile/config/resource-scheduler-config.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class ResourceSchedulerConfig {

    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("globalQueueDepth")
    private int globalQueueDepth = 50;

    @JsonProperty("maxConcurrentByType")
    private Map<String, Integer> maxConcurrentByType = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("ingest", 4),
            Map.entry("vectorPopulation", 1),
            Map.entry("crawl", 4),
            Map.entry("unifiedCrawl", 1),
            Map.entry("training", 1),
            Map.entry("vlm", 1),
            Map.entry("modelInit", 1),
            Map.entry("llm", 1),
            Map.entry("llmServing", 1),
            Map.entry("embedding", 2)
    ));

    @JsonProperty("schedulingAlgorithm")
    private String schedulingAlgorithm = "PRIORITY";

    @JsonProperty("dispatchIntervalMs")
    private long dispatchIntervalMs = 500;

    @JsonProperty("queueTimeoutMs")
    private long queueTimeoutMs = 3_600_000;

    @JsonProperty("phaseAwareYieldEnabled")
    private boolean phaseAwareYieldEnabled = true;

    @JsonProperty("batchWindowMs")
    private long batchWindowMs = 2000;

    @JsonProperty("historyRetentionDays")
    private int historyRetentionDays = 30;

    @JsonProperty("maxHistoryEntries")
    private int maxHistoryEntries = 10_000;

    /**
     * External scheduler integration mode.
     * <ul>
     *   <li>{@code none} — use built-in scheduler only (default)</li>
     *   <li>{@code kubernetes} — delegate job execution to Kubernetes Jobs/CronJobs</li>
     *   <li>{@code webhook} — POST job submissions to an external webhook URL</li>
     * </ul>
     */
    @JsonProperty("externalSchedulerMode")
    private String externalSchedulerMode = "none";

    /** Kubernetes namespace for job pods (when externalSchedulerMode=kubernetes) */
    @JsonProperty("kubernetesNamespace")
    private String kubernetesNamespace = "kompile";

    /** Kubernetes service account for job pods */
    @JsonProperty("kubernetesServiceAccount")
    private String kubernetesServiceAccount = "kompile-worker";

    /** Container image for Kubernetes job pods */
    @JsonProperty("kubernetesJobImage")
    private String kubernetesJobImage = "konduitai/kompile:latest";

    /** Webhook URL for external scheduler (when externalSchedulerMode=webhook) */
    @JsonProperty("externalWebhookUrl")
    private String externalWebhookUrl = "";

    /** External scheduler API token for authentication */
    @JsonProperty("externalAuthToken")
    private String externalAuthToken = "";

    // --- Resource governor (CPU/RAM/GPU-VRAM aware admission, batch sizing, pool sizing) ---

    /** Master switch for the resource governor's CPU/RAM admission gate. */
    @JsonProperty("governorEnabled")
    private boolean governorEnabled = true;

    /** System CPU load fraction at/above which CPU-bound jobs are deferred. */
    @JsonProperty("governorCpuHighThreshold")
    private double governorCpuHighThreshold = 0.85;

    /** System CPU load fraction treated as critical. */
    @JsonProperty("governorCpuCriticalThreshold")
    private double governorCpuCriticalThreshold = 0.95;

    /** System RAM used fraction at/above which any job is deferred. */
    @JsonProperty("governorRamHighThreshold")
    private double governorRamHighThreshold = 0.85;

    /** System RAM used fraction treated as critical. */
    @JsonProperty("governorRamCriticalThreshold")
    private double governorRamCriticalThreshold = 0.92;

    /** Per-GPU VRAM used fraction at/above which GPU stages experience backpressure. */
    @JsonProperty("governorVramHighFraction")
    private double governorVramHighFraction = 0.85;

    /** Per-GPU VRAM used fraction treated as critical (triggers deferral of heavy local work). */
    @JsonProperty("governorVramCriticalFraction")
    private double governorVramCriticalFraction = 0.92;

    /** Minimum threads for the scheduler's job execution pool (dynamic, CPU-count derived). */
    @JsonProperty("governorSchedulerPoolMinThreads")
    private int governorSchedulerPoolMinThreads = 4;

    /** Maximum threads for the scheduler's job execution pool. */
    @JsonProperty("governorSchedulerPoolMaxThreads")
    private int governorSchedulerPoolMaxThreads = 32;

    /** Period (ms) at which the DeferredEmbeddingResumer drains pending-embedding jobs. */
    @JsonProperty("governorDeferredEmbeddingResumeMs")
    private long governorDeferredEmbeddingResumeMs = 60_000;

    /** Per-service GPU memory budgets as a fraction of the largest device's total VRAM.
     *  Used to auto-calibrate {@code GpuResourceManager} budgets to the actual hardware. */
    @JsonProperty("gpuBudgetFractions")
    private Map<String, Double> gpuBudgetFractions = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("embedding", 0.20),
            Map.entry("vlm", 0.70),
            Map.entry("ingest", 0.10),
            Map.entry("vectorPopulation", 0.05),
            Map.entry("modelInit", 0.08)
    ));

    // --- Computed / non-trivial methods ---

    public int getMaxConcurrentForType(String type) {
        return maxConcurrentByType.getOrDefault(type, 1);
    }

    public boolean isExternalSchedulerEnabled() {
        return externalSchedulerMode != null
                && !externalSchedulerMode.isBlank()
                && !"none".equalsIgnoreCase(externalSchedulerMode);
    }

    public static ResourceSchedulerConfig defaults() {
        return new ResourceSchedulerConfig();
    }
}
