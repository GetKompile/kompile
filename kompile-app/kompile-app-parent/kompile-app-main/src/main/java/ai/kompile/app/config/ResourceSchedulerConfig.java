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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    // --- GPU→CPU migration (sustained-load failover onto the multi-backend's CPU side) ---

    /** Master switch for migrating crawl GPU stages to CPU under sustained GPU pressure. */
    @JsonProperty("gpuToCpuMigrationEnabled")
    private boolean gpuToCpuMigrationEnabled = true;

    /** Seconds of sustained worst-GPU HIGH+ pressure (with CPU headroom) required before migrating to CPU. */
    @JsonProperty("gpuToCpuMigrateAfterSeconds")
    private int gpuToCpuMigrateAfterSeconds = 30;

    /** Seconds of sustained GPU relief (below HIGH) required before restoring migrated stages to their prior device. */
    @JsonProperty("gpuToCpuRestoreAfterSeconds")
    private int gpuToCpuRestoreAfterSeconds = 20;

    /** Crawl subprocess service routes migrated to CPU on sustained GPU pressure (DeviceRoutingConfig.SERVICE_* values). */
    @JsonProperty("gpuToCpuMigrationServices")
    private List<String> gpuToCpuMigrationServices = new ArrayList<>(List.of(
            "vectorPopulation", "embedding", "ingest"));

    // --- Crawl cluster (remote-peer CrawlWorkers, capabilities shared over HTTP) ---

    /** This node's cluster role: {@code none} (standalone), {@code orchestrator}, {@code worker}, or {@code both}. */
    @JsonProperty("clusterRole")
    private String clusterRole = "none";

    /** Orchestrator base URL a {@code worker}/{@code both} node registers + heartbeats to (e.g. http://host:8080). */
    @JsonProperty("clusterOrchestratorUrl")
    private String clusterOrchestratorUrl = "";

    /** This node's externally-reachable base URL advertised to peers (blank = auto-derive from host/port). */
    @JsonProperty("clusterAdvertiseBaseUrl")
    private String clusterAdvertiseBaseUrl = "";

    /** Stable worker id advertised to the cluster (blank = auto-derive from host + port). */
    @JsonProperty("clusterWorkerId")
    private String clusterWorkerId = "";

    /** Seconds between a worker's capability heartbeats to the orchestrator. */
    @JsonProperty("clusterHeartbeatSeconds")
    private int clusterHeartbeatSeconds = 15;

    /** Seconds without a heartbeat after which the orchestrator evicts a worker from the live registry. */
    @JsonProperty("clusterWorkerTimeoutSeconds")
    private int clusterWorkerTimeoutSeconds = 45;

    /** Job types this worker advertises it can run. Includes "crawl" so a worker can take distributed-crawl
     *  partitions out of the box (the only ClusterJobRunner is the crawl runner — without this the advertised
     *  set would be empty after intersecting with the runnable types). */
    @JsonProperty("clusterSupportedJobTypes")
    private List<String> clusterSupportedJobTypes = new ArrayList<>(List.of(
            "crawl", "ingest", "vectorPopulation", "graph", "embedding"));

    /** Max concurrent delegated jobs this worker will accept. */
    @JsonProperty("clusterMaxConcurrentJobs")
    private int clusterMaxConcurrentJobs = 4;

    /**
     * When does an orchestrator hand scheduler jobs to remote peers? {@code failover} (default) only when the
     * local host is saturated (the 3rd tier after local GPU → local CPU); {@code always} whenever a capable
     * peer exists. (Explicit distributed crawls via the coordinator are unaffected — they always distribute.)
     */
    @JsonProperty("clusterOffloadMode")
    private String clusterOffloadMode = "failover";

    /**
     * When true, an orchestrator divides the global remote-LLM / backend concurrency across the workers it
     * scatters a distributed crawl to (each per-worker request gets {@code global / workerCount}), so N
     * workers don't each open the full concurrency against the same external API. Approximate (static
     * division); a true global lease is future work. Default-off → no behavior change.
     */
    @JsonProperty("clusterBackendCapScalingEnabled")
    private boolean clusterBackendCapScalingEnabled = false;

    /** Fallback global remote-LLM parallelism to divide across workers when the request doesn't specify one. */
    @JsonProperty("clusterDefaultRemoteParallelism")
    private int clusterDefaultRemoteParallelism = 2;

    /**
     * When true, the orchestrator re-dispatches a distributed-crawl partition to another worker if its worker
     * is heartbeat-evicted or stops reporting progress. Default-off — enable only for restartable/idempotent
     * crawls (a re-dispatch may double-process the partition if the original worker silently revived).
     */
    @JsonProperty("clusterReassignOnLoss")
    private boolean clusterReassignOnLoss = false;

    /** Seconds with no progress from a RUNNING partition before it's considered stalled/lost. */
    @JsonProperty("clusterPartitionProgressTimeoutSeconds")
    private int clusterPartitionProgressTimeoutSeconds = 300;

    /** How often (seconds) the partition-loss reaper scans active sessions. */
    @JsonProperty("clusterPartitionReaperIntervalSeconds")
    private int clusterPartitionReaperIntervalSeconds = 30;

    /** Max re-dispatch attempts per partition before it's marked failed. */
    @JsonProperty("clusterMaxReassignments")
    private int clusterMaxReassignments = 2;

    /**
     * When true, the orchestrator persists distributed-crawl sessions to disk and, on restart, reloads in-flight
     * sessions and reconciles each worker against the live scheduler (resume / complete / reassign) instead of
     * silently losing them. Default-ON: it's pure IO safety scoped to the orchestrator, with no crawl-behavior
     * change — a default-off persistence layer would protect nobody from the crash it exists for.
     */
    @JsonProperty("clusterSessionPersistenceEnabled")
    private boolean clusterSessionPersistenceEnabled = true;

    // --- Computed / non-trivial methods ---

    public int getMaxConcurrentForType(String type) {
        return maxConcurrentByType.getOrDefault(type, 1);
    }

    public boolean isExternalSchedulerEnabled() {
        return externalSchedulerMode != null
                && !externalSchedulerMode.isBlank()
                && !"none".equalsIgnoreCase(externalSchedulerMode);
    }

    /** This node accepts worker registrations and routes work to the cluster. */
    public boolean isClusterOrchestrator() {
        return "orchestrator".equalsIgnoreCase(clusterRole) || "both".equalsIgnoreCase(clusterRole);
    }

    /** This node advertises itself as a CrawlWorker and runs delegated jobs. */
    public boolean isClusterWorker() {
        return "worker".equalsIgnoreCase(clusterRole) || "both".equalsIgnoreCase(clusterRole);
    }

    /** Scheduler offload to peers only when the local host is saturated (vs {@code always}). */
    public boolean isClusterFailoverMode() {
        return !"always".equalsIgnoreCase(clusterOffloadMode);
    }

    public static ResourceSchedulerConfig defaults() {
        return new ResourceSchedulerConfig();
    }
}
