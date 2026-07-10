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
 * Configuration for the {@code ResourceAwareJobScheduler}.
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

    /**
     * Hard absolute floor for host MemAvailable (Linux {@code /proc/meminfo MemAvailable}) in
     * megabytes.  When the available RAM falls below this value, {@code admitJob} defers new
     * work and {@code shouldThrottleHeavyMemory()} returns {@code true} — even if the fractional
     * RAM-used threshold has not yet been breached.  This prevents OOM crashes on large-model
     * hosts where a 92% fraction threshold may still leave only a few hundred MB free.
     *
     * <p>Set to 0 to disable the absolute floor (fraction-only behaviour, matching the old code).
     * Default: 8192 MB (8 GB).</p>
     */
    @JsonProperty("governorRamFloorMb")
    private long governorRamFloorMb = 8192;

    /**
     * When {@code true} (default), heavy in-memory model operations (KGE training, batch
     * embedding, etc.) are serialized through a single-permit semaphore so at most one such op
     * runs at a time on the host. This prevents KGE training triggered {@code @Async} after
     * ENRICHMENT from overlapping an in-flight embedding step — the root cause of the 128 GB OOM
     * crash.  Set to {@code false} to revert to the old concurrent behaviour (not recommended on
     * hosts with limited RAM).
     */
    @JsonProperty("serializedHeavyOps")
    private boolean serializedHeavyOps = true;

    /**
     * When {@code true} (default) and {@link #serializedHeavyOps} is on, heavy in-memory ops are
     * admitted by BUDGET rather than through a strict single permit: several ops run concurrently as
     * long as the sum of their declared native-memory estimates stays under
     * {@code availableMemoryMbForHeavyOps} (MemAvailable − OOM floor) ×
     * {@link #heavyMemoryBudgetSafetyFraction}. When the governor cannot report available memory the
     * budget is 0, which degrades to one heavy op at a time — equivalent to the legacy mutex.
     *
     * <p>Set to {@code false} to force the strict single-permit behaviour regardless of headroom.
     * This is the fix for the pre-subprocess-isolation binary mutex that forbade e.g. embedding ∥
     * KGE training even with tens of GB free.</p>
     */
    @JsonProperty("heavyMemoryBudgetedAdmission")
    private boolean heavyMemoryBudgetedAdmission = true;

    /** Fraction of {@code availableMemoryMbForHeavyOps} (MemAvailable minus the OOM floor) that
     *  concurrently-admitted heavy ops may collectively occupy under budgeted admission. Kept below
     *  1.0 to leave slack for transient allocation spikes. Default: 0.6. */
    @JsonProperty("heavyMemoryBudgetSafetyFraction")
    private double heavyMemoryBudgetSafetyFraction = 0.6;

    /** Fallback native-memory estimate (MB) for a heavy op whose label is absent from
     *  {@link #heavyMemoryOpEstimatesMb} and that does not declare a footprint. Conservative so
     *  unknown ops do not over-admit. Default: 4096 (4 GB). */
    @JsonProperty("heavyMemoryDefaultOpMb")
    private long heavyMemoryDefaultOpMb = 4096;

    /** Declared peak native/off-heap footprint (MB) per heavy-op label, keyed by the {@code opLabel}
     *  passed to {@code HeavyMemoryCoordinator.acquire}. Drives budgeted admission without changing
     *  call sites; tune per host. Conservative-high by default to bias toward under-admission. */
    @JsonProperty("heavyMemoryOpEstimatesMb")
    private Map<String, Long> heavyMemoryOpEstimatesMb = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("embedding", 12288L),
            // KGE/PSL/MEBN training runs out-of-process in a JVM whose native baseline (libnd4j +
            // BLAS + workspaces) alone is ~20–28 GB; this budget doubles as the learning
            // subprocess's declared native cap (see LearningSubprocessLauncher#getMaxPhysicalMb).
            Map.entry("kge-training", 40960L),
            Map.entry("kge-training-inline", 40960L)
    ));

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

    /**
     * When true, a worker that <em>reports</em> a retriable failure has its partition re-dispatched — the symmetry
     * partner of {@link #clusterReassignOnLoss} (which only covers silent loss). Bounded by
     * {@link #clusterMaxReassignments}; deterministic/fatal failures (cancelled, missing/invalid config, auth) are
     * never retried. Default-off — enable only for restartable/idempotent crawls.
     */
    @JsonProperty("clusterReassignOnFailure")
    private boolean clusterReassignOnFailure = false;

    /** Seconds with no progress from a RUNNING partition before it's considered stalled/lost. */
    @JsonProperty("clusterPartitionProgressTimeoutSeconds")
    private int clusterPartitionProgressTimeoutSeconds = 300;

    /**
     * A worker whose recent GC-overhead fraction is at/above this AND has stopped progressing is reaped on the
     * shorter {@link #clusterFastStallSeconds} timer instead of the full progress timeout (Phase 3). 0 disables
     * the GC-stall fast path. The {@code WorkerWeightFunction} also down-weights
     * GC-heavy workers at assignment time.
     */
    @JsonProperty("clusterGcOverheadCriticalFraction")
    private double clusterGcOverheadCriticalFraction = 0.5;

    /** Fast-stall timeout (seconds) for a GC-churning worker — see {@link #clusterGcOverheadCriticalFraction}. */
    @JsonProperty("clusterFastStallSeconds")
    private int clusterFastStallSeconds = 60;

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

    /**
     * When true, workers report LLM-backend failures to the orchestrator, which maintains a cluster-wide circuit
     * breaker so a flaky/rate-limited shared backend trips once for the whole cluster (advisory, fail-open) rather
     * than once per worker. Default-off.
     */
    @JsonProperty("clusterSharedBackendBreakerEnabled")
    private boolean clusterSharedBackendBreakerEnabled = false;

    /** Aggregate backend failures (across the cluster) before the shared breaker opens. */
    @JsonProperty("clusterBackendFailureThreshold")
    private int clusterBackendFailureThreshold = 8;

    /** Seconds the shared backend breaker stays open before it half-opens (auto-reset). */
    @JsonProperty("clusterBackendCooldownSeconds")
    private int clusterBackendCooldownSeconds = 60;

    // --- Subprocess RSS watchdog (parent-side per-process memory limit) ---

    /**
     * Hard per-subprocess RSS ceiling in megabytes.
     *
     * <p>The parent-side {@code SubprocessRssWatchdog} reads each alive subprocess's
     * {@code VmRSS} from {@code /proc/<pid>/status} on Linux and kills/restarts it
     * when the value exceeds this limit.  Takes precedence over
     * {@link #subprocessMaxRssFraction} when both are &gt; 0 and results in a lower
     * effective limit.  Set to 0 to disable the absolute ceiling and use the
     * fraction-only limit.</p>
     *
     * <p>Default: 0 (disabled — fraction-only).</p>
     */
    @JsonProperty("subprocessMaxRssMb")
    private long subprocessMaxRssMb = 0;

    /**
     * Per-subprocess RSS fraction of total system RAM at which the watchdog
     * kills/restarts the subprocess.
     *
     * <p>Computed as {@code fraction × system-total-RAM-MB}.  0 disables this
     * check.  When both {@link #subprocessMaxRssMb} and this field are &gt; 0 the
     * <em>lower</em> effective limit wins, providing a belt-and-suspenders cap.
     * A single subprocess consuming &gt; 50% of system RAM is almost certainly a
     * runaway, so the default of 0.5 is intentionally conservative.
     * Set to 0 to rely solely on {@link #subprocessMaxRssMb}.</p>
     *
     * <p>Default: 0.5 (50% of system RAM).</p>
     */
    @JsonProperty("subprocessMaxRssFraction")
    private double subprocessMaxRssFraction = 0.5;

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
