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

package ai.kompile.cli.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detects system hardware (RAM, CPU cores, GPU) and generates optimal
 * configuration maps for subprocess, ND4J, and pipeline settings.
 *
 * <p>Used by both CLI ({@code init-project}) and the REST API
 * ({@code POST /api/auto-configure/apply}) to produce configs that are
 * written to {@code ~/.kompile/config/} JSON files and overridable via UI.</p>
 *
 * <h3>Memory Tiers</h3>
 * <ul>
 *   <li><b>small</b>  (&lt;8 GB)  – conservative, avoid OOM on constrained machines</li>
 *   <li><b>medium</b> (8–16 GB)  – safe defaults that "just work"</li>
 *   <li><b>large</b>  (16–32 GB) – room for bigger batches and more workers</li>
 *   <li><b>xlarge</b> (32–64 GB) – high throughput, parallel everything</li>
 *   <li><b>server</b> (64 GB+)   – maximise hardware utilisation</li>
 * </ul>
 */
public final class HardwareAutoConfigurator {

    private HardwareAutoConfigurator() { }

    // ── Hardware detection ──────────────────────────────────────────────────

    /** Total physical RAM in bytes. */
    public static long detectSystemRamBytes() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                return sunOsBean.getTotalMemorySize();
            }
        } catch (Exception ignored) { }
        // Fallback: assume 4× JVM max memory
        return Runtime.getRuntime().maxMemory() * 4;
    }

    /** Number of logical CPUs. */
    public static int detectCpuCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    /** Best-effort GPU detection (true if CUDA backend classes are on the classpath). */
    public static boolean detectGpuAvailable() {
        try {
            Class.forName("org.nd4j.linalg.jcublas.JCublasBackend");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ── Tier resolution ─────────────────────────────────────────────────────

    public enum Tier {
        SMALL, MEDIUM, LARGE, XLARGE, SERVER
    }

    public static Tier resolveTier(long ramBytes) {
        long ramGb = ramBytes / (1024L * 1024L * 1024L);
        if (ramGb < 8)  return Tier.SMALL;
        if (ramGb < 16) return Tier.MEDIUM;
        if (ramGb < 32) return Tier.LARGE;
        if (ramGb < 64) return Tier.XLARGE;
        return Tier.SERVER;
    }

    // ── Full auto-configure result ──────────────────────────────────────────

    /**
     * Result of auto-configuration: contains separate config maps for each
     * JSON file, plus a human-readable hardware summary.
     */
    public static class AutoConfigResult {
        /** Hardware summary (ram, cpus, gpu, tier). */
        public final Map<String, Object> hardware;
        /** Fields for {@code subprocess-ingest-config.json}. */
        public final Map<String, Object> subprocessConfig;
        /** Fields for {@code nd4j-environment-config.json}. */
        public final Map<String, Object> nd4jConfig;
        /** Fields for {@code pipeline-config.json}. */
        public final Map<String, Object> pipelineConfig;

        public AutoConfigResult(Map<String, Object> hardware,
                                Map<String, Object> subprocessConfig,
                                Map<String, Object> nd4jConfig,
                                Map<String, Object> pipelineConfig) {
            this.hardware = hardware;
            this.subprocessConfig = subprocessConfig;
            this.nd4jConfig = nd4jConfig;
            this.pipelineConfig = pipelineConfig;
        }
    }

    /**
     * Detect hardware and produce optimal configs.
     *
     * @param hasLocalEmbedding  true if using SameDiff/Anserini embeddings (needs
     *                           more memory for model inference)
     * @return result containing config maps ready to persist
     */
    public static AutoConfigResult autoConfigure(boolean hasLocalEmbedding) {
        long ramBytes = detectSystemRamBytes();
        int cpus = detectCpuCount();
        boolean gpu = detectGpuAvailable();
        return autoConfigure(ramBytes, cpus, gpu, hasLocalEmbedding);
    }

    /**
     * Produce optimal configs for the given hardware specs.
     * Deterministic — same inputs always produce the same output.
     */
    public static AutoConfigResult autoConfigure(long ramBytes, int cpus, boolean gpu,
                                                  boolean hasLocalEmbedding) {
        long ramGb = ramBytes / (1024L * 1024L * 1024L);
        Tier tier = resolveTier(ramBytes);

        // ── Hardware summary ────────────────────────────────────────────
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("totalRamGb", ramGb);
        hardware.put("totalRamBytes", ramBytes);
        hardware.put("cpuCount", cpus);
        hardware.put("gpuAvailable", gpu);
        hardware.put("tier", tier.name().toLowerCase());
        hardware.put("hasLocalEmbedding", hasLocalEmbedding);

        // ── Subprocess config ───────────────────────────────────────────
        Map<String, Object> subprocess = buildSubprocessConfig(tier, ramGb, cpus, hasLocalEmbedding);

        // ── ND4J config ─────────────────────────────────────────────────
        Map<String, Object> nd4j = buildNd4jConfig(tier, cpus, gpu);

        // ── Pipeline config ─────────────────────────────────────────────
        Map<String, Object> pipeline = buildPipelineConfig(tier, cpus);

        return new AutoConfigResult(hardware, subprocess, nd4j, pipeline);
    }

    // ── Per-config builders ─────────────────────────────────────────────────

    private static Map<String, Object> buildSubprocessConfig(Tier tier, long ramGb,
                                                              int cpus, boolean hasLocalEmbedding) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", true);
        c.put("javaPath", "java");

        switch (tier) {
            case SMALL -> {
                c.put("heapSize", "2g");
                c.put("offHeapMultiplier", 2);
                c.put("timeoutMinutes", 60);
                c.put("queueCapacity", 500);
                c.put("parallelIndexing", true);
                c.put("indexingWorkers", Math.min(cpus, 2));
                c.put("indexingBatchAccumulationSize", 4);
            }
            case MEDIUM -> {
                c.put("heapSize", "4g");
                c.put("offHeapMultiplier", 4);
                c.put("timeoutMinutes", 60);
                c.put("queueCapacity", 1000);
                c.put("parallelIndexing", true);
                c.put("indexingWorkers", Math.min(cpus, 4));
                c.put("indexingBatchAccumulationSize", 8);
            }
            case LARGE -> {
                c.put("heapSize", hasLocalEmbedding ? "6g" : "4g");
                c.put("offHeapMultiplier", 4);
                c.put("timeoutMinutes", 60);
                c.put("queueCapacity", 2000);
                c.put("parallelIndexing", true);
                c.put("indexingWorkers", Math.min(cpus, 4));
                c.put("indexingBatchAccumulationSize", 16);
            }
            case XLARGE -> {
                c.put("heapSize", hasLocalEmbedding ? "8g" : "6g");
                c.put("offHeapMultiplier", 4);
                c.put("timeoutMinutes", 90);
                c.put("queueCapacity", 4000);
                c.put("parallelIndexing", true);
                c.put("indexingWorkers", Math.min(cpus, 8));
                c.put("indexingBatchAccumulationSize", 32);
            }
            case SERVER -> {
                // On 64GB+ machines, allocate up to 16g heap for subprocess
                String heapSize = hasLocalEmbedding ? "16g" : "8g";
                // Cap at ~25% of RAM
                long maxHeapGb = ramGb / 4;
                if (maxHeapGb < 16 && hasLocalEmbedding) {
                    heapSize = maxHeapGb + "g";
                }
                c.put("heapSize", heapSize);
                c.put("offHeapMultiplier", 4);
                c.put("timeoutMinutes", 120);
                c.put("queueCapacity", 8000);
                c.put("parallelIndexing", true);
                c.put("indexingWorkers", Math.min(cpus, 16));
                c.put("indexingBatchAccumulationSize", 64);
            }
        }

        c.put("heartbeatIntervalSeconds", 10);
        c.put("staleThresholdSeconds", 120);

        return c;
    }

    private static Map<String, Object> buildNd4jConfig(Tier tier, int cpus, boolean gpu) {
        Map<String, Object> c = new LinkedHashMap<>();

        // Thread counts — scale with CPUs but cap sensibly
        int ompThreads;
        int maxThreads;
        int maxMasterThreads;
        int openBlasThreads;

        switch (tier) {
            case SMALL -> {
                ompThreads = Math.min(cpus, 2);
                maxThreads = Math.min(cpus, 2);
                maxMasterThreads = Math.min(cpus, 2);
                openBlasThreads = 1;
            }
            case MEDIUM -> {
                ompThreads = Math.min(cpus, 4);
                maxThreads = Math.min(cpus, 4);
                maxMasterThreads = Math.min(cpus, 4);
                openBlasThreads = 1;
            }
            case LARGE -> {
                ompThreads = Math.min(cpus, 6);
                maxThreads = Math.min(cpus, 6);
                maxMasterThreads = Math.min(cpus, 4);
                openBlasThreads = 1;
            }
            case XLARGE -> {
                ompThreads = Math.min(cpus / 2, 8);
                maxThreads = Math.min(cpus / 2, 8);
                maxMasterThreads = Math.min(cpus / 2, 4);
                openBlasThreads = 2;
            }
            case SERVER -> {
                ompThreads = Math.min(cpus / 2, 16);
                maxThreads = Math.min(cpus / 2, 16);
                maxMasterThreads = Math.min(cpus / 4, 8);
                openBlasThreads = 2;
            }
            default -> {
                ompThreads = 4;
                maxThreads = 4;
                maxMasterThreads = 4;
                openBlasThreads = 1;
            }
        }

        c.put("maxThreads", maxThreads);
        c.put("maxMasterThreads", maxMasterThreads);
        c.put("ompNumThreads", ompThreads);
        c.put("openBlasThreads", openBlasThreads);

        // BLAS settings — always safe defaults
        c.put("enableBlas", true);
        c.put("helpersAllowed", true);
        c.put("blasSerializationEnabled", true);

        // No debug/profiling overhead for production defaults
        c.put("lifecycleTracking", false);
        c.put("debug", false);
        c.put("verbose", false);
        c.put("profiling", false);
        c.put("leaksDetector", false);

        // Graph optimiser on by default
        c.put("optimizerEnabled", true);

        // GPU-specific
        if (gpu) {
            c.put("optimizerFp16", true);
        }

        return c;
    }

    private static Map<String, Object> buildPipelineConfig(Tier tier, int cpus) {
        Map<String, Object> c = new LinkedHashMap<>();

        switch (tier) {
            case SMALL -> {
                c.put("minBatchSize", 4);
                c.put("defaultBatchSize", 16);
                c.put("maxBatchSize", 32);
                c.put("queueCapacity", 500);
                c.put("embeddingThreads", 1);
                c.put("chunkingThreads", Math.min(cpus, 2));
                c.put("indexingThreads", 1);
                c.put("indexingBatchAccumulationSize", 50);
            }
            case MEDIUM -> {
                c.put("minBatchSize", 8);
                c.put("defaultBatchSize", 32);
                c.put("maxBatchSize", 128);
                c.put("queueCapacity", 1000);
                c.put("embeddingThreads", 2);
                c.put("chunkingThreads", Math.min(cpus, 4));
                c.put("indexingThreads", 2);
                c.put("indexingBatchAccumulationSize", 100);
            }
            case LARGE -> {
                c.put("minBatchSize", 8);
                c.put("defaultBatchSize", 64);
                c.put("maxBatchSize", 256);
                c.put("queueCapacity", 2000);
                c.put("embeddingThreads", 2);
                c.put("chunkingThreads", Math.min(cpus, 4));
                c.put("indexingThreads", 2);
                c.put("indexingBatchAccumulationSize", 200);
            }
            case XLARGE -> {
                c.put("minBatchSize", 16);
                c.put("defaultBatchSize", 128);
                c.put("maxBatchSize", 512);
                c.put("queueCapacity", 4000);
                c.put("embeddingThreads", 2);
                c.put("chunkingThreads", Math.min(cpus, 8));
                c.put("indexingThreads", 4);
                c.put("indexingBatchAccumulationSize", 400);
            }
            case SERVER -> {
                c.put("minBatchSize", 32);
                c.put("defaultBatchSize", 256);
                c.put("maxBatchSize", 1024);
                c.put("queueCapacity", 8000);
                c.put("embeddingThreads", 4);
                c.put("chunkingThreads", Math.min(cpus, 16));
                c.put("indexingThreads", Math.min(cpus / 2, 8));
                c.put("indexingBatchAccumulationSize", 800);
            }
        }

        c.put("skipEmbedding", false);
        c.put("optimizeGraphOnLoad", true);

        return c;
    }
}
