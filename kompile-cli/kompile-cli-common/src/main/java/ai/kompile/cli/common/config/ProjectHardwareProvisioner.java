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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.kompile.cli.common.config.HardwareAutoConfigurator.AutoConfigResult;
import ai.kompile.cli.common.config.HardwareAutoConfigurator.Tier;

/**
 * Writes per-project, box-sized configuration files into
 * {@code <projectRoot>/config/} at init time.
 *
 * <p><b>Write-once, skip-if-present</b>: each file is written only when it
 * does not already exist.  Re-running {@code kompile project init} on an
 * existing project is therefore safe.</p>
 *
 * <p>All config files are written to match the exact field names expected by
 * the app-side readers:
 * <ul>
 *   <li>{@code subprocess-ingest-config.json} — read by
 *       {@code SubprocessConfigService} (line 241; resolves via
 *       {@code <dataDir>/config/subprocess-ingest-config.json})</li>
 *   <li>{@code resource-scheduler-config.json} — read by
 *       {@code ResourceSchedulerConfigService} (extends
 *       {@code AbstractJsonConfigService})</li>
 *   <li>{@code nd4j-environment-config.json} — read by
 *       {@code MainApplication} static init</li>
 *   <li>{@code pipeline-config.json} — read by
 *       {@code PipelineConfigService}</li>
 *   <li>{@code app-index-config.json} — read by
 *       {@code AppIndexConfigService}</li>
 *   <li>{@code device-routing-config.json} — read by
 *       {@code ProcessingRouteConfigService}</li>
 *   <li>{@code project-runtime.json} — CLI-owned bookkeeping file</li>
 * </ul>
 * </p>
 *
 * <p>The config path resolution mechanism:
 * {@code KompileBootstrapEnvironmentPostProcessor} reads
 * {@code kompile.data.dir} from the Spring Environment and seeds it as a
 * system property; every {@code AbstractJsonConfigService} subclass then
 * builds its path as {@code Paths.get(dataDir, "config", configFilename)}.
 * Therefore all files must live at {@code <projectRoot>/config/<filename>}.</p>
 */
public final class ProjectHardwareProvisioner {

    private ProjectHardwareProvisioner() { }

    // ── Tier tables for subprocess type-specific heap sizes ────────────────

    /**
     * Heap size strings for the {@code graph-matrix} subprocess type,
     * as written into the {@code subprocessTypes.graph-matrix.heapSize}
     * key of {@code subprocess-ingest-config.json}.
     *
     * <p>These override the {@code GraphMatrixSubprocessLauncher.DEFAULT_HEAP_MB}
     * of 32768 MB for boxes that cannot afford that much.</p>
     */
    static String graphMatrixHeapForTier(Tier tier) {
        return switch (tier) {
            case SMALL  -> "4g";
            case MEDIUM -> "6g";
            case LARGE  -> "12g";
            case XLARGE -> "24g";
            case SERVER -> "32g";
        };
    }

    /**
     * Heap size strings for the {@code learning} subprocess type,
     * as written into the {@code subprocessTypes.learning.heapSize} key.
     *
     * <p>These override the {@code LearningSubprocessLauncher} default of
     * 8192 MB for small and medium boxes.</p>
     */
    static String learningHeapForTier(Tier tier) {
        return switch (tier) {
            case SMALL  -> "2g";
            case MEDIUM -> "4g";
            case LARGE  -> "6g";
            case XLARGE -> "8g";
            case SERVER -> "8g";
        };
    }

    // ── Resource-scheduler tier tables ─────────────────────────────────────

    static long governorRamFloorMbForTier(Tier tier) {
        return switch (tier) {
            case SMALL  -> 2048L;
            case MEDIUM -> 3072L;
            case LARGE  -> 4096L;
            case XLARGE -> 6144L;
            case SERVER -> 8192L;
        };
    }

    static long kgeTrainingEstimateMbForTier(Tier tier) {
        return switch (tier) {
            case SMALL  -> 4096L;
            case MEDIUM -> 8192L;
            case LARGE  -> 16384L;
            case XLARGE -> 28672L;
            case SERVER -> 40960L;
        };
    }

    static long embeddingEstimateMbForTier(Tier tier) {
        return switch (tier) {
            case SMALL  -> 2048L;
            case MEDIUM -> 4096L;
            case LARGE  -> 8192L;
            case XLARGE -> 12288L;
            case SERVER -> 12288L;
        };
    }

    // ── App-index tier values ───────────────────────────────────────────────

    static String appIndexSubprocessHeapForTier(Tier tier) {
        // Matches the subprocess heapSize from buildSubprocessConfig, no-embedding variant
        return switch (tier) {
            case SMALL  -> "2g";
            case MEDIUM -> "4g";
            case LARGE  -> "4g";
            case XLARGE -> "6g";
            case SERVER -> "8g";
        };
    }

    static int appIndexEmbeddingThreadsForTier(Tier tier) {
        return switch (tier) {
            case SMALL  -> 1;
            case MEDIUM -> 2;
            case LARGE  -> 2;
            case XLARGE -> 2;
            case SERVER -> 4;
        };
    }

    // ── ProvisionResult ─────────────────────────────────────────────────────

    /**
     * Outcome of a single {@link #provision} call.
     */
    public static class ProvisionResult {
        /** Hardware summary keys: tier, totalRamGb, cpus, gpus, freeDiskGb. */
        public final Map<String, Object> hardwareSummary;
        /** Config files that were written in this call. */
        public final List<Path> written;
        /** Config files that already existed and were skipped. */
        public final List<Path> skippedExisting;
        /** Non-fatal warnings collected during provisioning. */
        public final List<String> warnings;

        ProvisionResult(Map<String, Object> hardwareSummary,
                        List<Path> written,
                        List<Path> skippedExisting,
                        List<String> warnings) {
            this.hardwareSummary = hardwareSummary;
            this.written = List.copyOf(written);
            this.skippedExisting = List.copyOf(skippedExisting);
            this.warnings = List.copyOf(warnings);
        }

        /**
         * Human-readable summary lines for display in the init console.
         */
        public List<String> summaryLines() {
            List<String> lines = new ArrayList<>();
            String tier = String.valueOf(hardwareSummary.getOrDefault("tier", "unknown"));
            lines.add("Hardware tier : " + tier.toUpperCase());
            lines.add("Total RAM     : " + hardwareSummary.getOrDefault("totalRamGb", "?") + " GB");
            lines.add("CPUs          : " + hardwareSummary.getOrDefault("cpus", "?"));
            Object gpuCount = hardwareSummary.getOrDefault("gpuCount", 0);
            if (gpuCount instanceof Number n && n.intValue() > 0) {
                lines.add("GPUs found    : " + gpuCount
                        + " (see project-runtime.json / device-routing-config.json)");
            } else {
                lines.add("GPUs found    : none (CPU-only mode)");
            }
            Object freeDisk = hardwareSummary.get("freeDiskGb");
            if (freeDisk != null) {
                lines.add("Free disk     : " + freeDisk + " GB");
            }
            if (!written.isEmpty()) {
                lines.add("Config files written:");
                for (Path p : written) {
                    lines.add("  + " + p.getFileName());
                }
            }
            if (!skippedExisting.isEmpty()) {
                lines.add("Config files skipped (already exist):");
                for (Path p : skippedExisting) {
                    lines.add("  ~ " + p.getFileName());
                }
            }
            for (String w : warnings) {
                lines.add("WARNING: " + w);
            }
            return lines;
        }
    }

    // ── Main entry point ────────────────────────────────────────────────────

    /**
     * Probe hardware, then write per-project config files under
     * {@code <projectRoot>/config/}.  Each file is written only when absent.
     *
     * @param projectRoot        root directory of the kompile project
     *                           ({@code --kompile.data.dir} value at runtime)
     * @param hasLocalEmbedding  true when the project will use SameDiff/Anserini
     *                           local embedding (affects subprocess heap sizes)
     * @return result describing what was written and any warnings
     */
    public static ProvisionResult provision(Path projectRoot, boolean hasLocalEmbedding) {
        long ramBytes = HardwareAutoConfigurator.detectSystemRamBytes();
        int cpus = HardwareAutoConfigurator.detectCpuCount();
        boolean gpuClasspath = HardwareAutoConfigurator.detectGpuAvailable();
        List<GpuProbe.GpuInfo> gpus = GpuProbe.probe();
        Tier tier = HardwareAutoConfigurator.resolveTier(ramBytes);
        long ramGb = ramBytes / (1024L * 1024L * 1024L);

        long freeDiskGb = 0L;
        List<String> warnings = new ArrayList<>();
        try {
            FileStore fs = Files.getFileStore(projectRoot);
            freeDiskGb = fs.getUsableSpace() / (1024L * 1024L * 1024L);
        } catch (IOException e) {
            warnings.add("Could not read free disk space for " + projectRoot + ": " + e.getMessage());
        }

        Map<String, Object> hardwareSummary = new LinkedHashMap<>();
        hardwareSummary.put("tier", tier.name().toLowerCase());
        hardwareSummary.put("totalRamGb", ramGb);
        hardwareSummary.put("cpus", cpus);
        hardwareSummary.put("gpuCount", gpus.size());
        hardwareSummary.put("cudaClasspathPresent", gpuClasspath);
        hardwareSummary.put("freeDiskGb", freeDiskGb);

        AutoConfigResult auto = HardwareAutoConfigurator.autoConfigure(
                ramBytes, cpus, gpuClasspath || !gpus.isEmpty(), hasLocalEmbedding);

        Path configDir = projectRoot.resolve("config");
        List<Path> written = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();

        ObjectMapper mapper = buildMapper();

        // (a) subprocess-ingest-config.json
        writeIfAbsent(configDir.resolve("subprocess-ingest-config.json"),
                buildSubprocessConfig(auto, tier), mapper, written, skipped, warnings);

        // (b) resource-scheduler-config.json
        writeIfAbsent(configDir.resolve("resource-scheduler-config.json"),
                buildResourceSchedulerConfig(tier), mapper, written, skipped, warnings);

        // (c) nd4j-environment-config.json (GPU boxes get DSP capture-pressure defaults)
        Map<String, Object> nd4jConfig = buildNd4jConfig(auto, projectRoot);
        applyGpuDspDefaults(nd4jConfig, gpus);
        writeIfAbsent(configDir.resolve("nd4j-environment-config.json"),
                nd4jConfig, mapper, written, skipped, warnings);

        // (d) pipeline-config.json
        writeIfAbsent(configDir.resolve("pipeline-config.json"),
                auto.pipelineConfig, mapper, written, skipped, warnings);

        // (e) app-index-config.json
        writeIfAbsent(configDir.resolve("app-index-config.json"),
                buildAppIndexConfig(tier), mapper, written, skipped, warnings);

        // (f) GPU-only files. Do not generate gpu-device-config.json: ND4J owns device discovery,
        // and that file is reserved for explicit user overrides of edge-case index mappings.
        if (!gpus.isEmpty()) {
            writeIfAbsent(configDir.resolve("device-routing-config.json"),
                    buildDeviceRoutingConfig(gpus), mapper, written, skipped, warnings);
        }

        // (g) project-runtime.json
        writeIfAbsent(configDir.resolve("project-runtime.json"),
                buildProjectRuntime(tier, ramGb, cpus, gpus), mapper, written, skipped, warnings);

        return new ProvisionResult(hardwareSummary, written, skipped, warnings);
    }

    // ── Config builders ─────────────────────────────────────────────────────

    /**
     * Build subprocess-ingest-config.json.
     *
     * <p>Merges the tier-derived base config from
     * {@link HardwareAutoConfigurator.AutoConfigResult#subprocessConfig} with
     * a {@code subprocessTypes} map containing per-type heap overrides. The
     * shape {@code {"subprocessTypes": {"graph-matrix": {"heapSize": "12g"}}}}
     * is exactly what {@code SubprocessConfigService.heapSizeForType()} reads
     * (lines 505–511: looks up key in {@code subprocessTypeOverrides}, casts to
     * {@code Map}, reads the {@code "heapSize"} string value).</p>
     */
    private static Map<String, Object> buildSubprocessConfig(AutoConfigResult auto, Tier tier) {
        Map<String, Object> config = new LinkedHashMap<>(auto.subprocessConfig);

        // Per-type heap overrides read by SubprocessConfigService.heapSizeForType()
        Map<String, Object> graphMatrixType = new LinkedHashMap<>();
        graphMatrixType.put("enabled", true);
        graphMatrixType.put("heapSize", graphMatrixHeapForTier(tier));

        Map<String, Object> learningType = new LinkedHashMap<>();
        learningType.put("enabled", true);
        learningType.put("heapSize", learningHeapForTier(tier));

        Map<String, Object> subprocessTypes = new LinkedHashMap<>();
        subprocessTypes.put("graph-matrix", graphMatrixType);
        subprocessTypes.put("learning", learningType);

        config.put("subprocessTypes", subprocessTypes);
        return config;
    }

    /**
     * Build resource-scheduler-config.json.
     *
     * <p>Only writes fields that differ meaningfully from the reader's
     * hard-coded defaults. {@code ResourceSchedulerConfig} uses Jackson
     * deserialization with {@code ObjectMapper.readerForUpdating(defaults)}
     * so partial JSON merges safely.</p>
     */
    private static Map<String, Object> buildResourceSchedulerConfig(Tier tier) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("governorEnabled", true);
        config.put("serializedHeavyOps", true);
        config.put("heavyMemoryBudgetedAdmission", true);
        config.put("heavyMemoryBudgetSafetyFraction", 0.6);
        config.put("governorRamFloorMb", governorRamFloorMbForTier(tier));

        Map<String, Long> estimates = new LinkedHashMap<>();
        estimates.put("embedding", embeddingEstimateMbForTier(tier));
        estimates.put("kge-training", kgeTrainingEstimateMbForTier(tier));
        estimates.put("kge-training-inline", kgeTrainingEstimateMbForTier(tier));
        config.put("heavyMemoryOpEstimatesMb", estimates);

        return config;
    }

    /**
     * Build nd4j-environment-config.json.
     *
     * <p>Starts from the auto-configured map, then ensures any path-bearing
     * fields use user-home-relative references via
     * {@code System.getProperty("user.home")} — never a literal
     * {@code /home/<username>}. The fpna-v8 config has no path fields in
     * this file so we emit only the non-path fields from autoConfigure().</p>
     */
    private static Map<String, Object> buildNd4jConfig(AutoConfigResult auto, Path projectRoot) {
        // The auto-generated nd4jConfig contains no path fields — use as-is.
        // If future versions add a tritonCacheDir or similar, compute it as:
        //   System.getProperty("user.home") + "/.kompile/triton-cache"
        return new LinkedHashMap<>(auto.nd4jConfig);
    }

    /**
     * GPU-tier DSP capture-pressure defaults for {@code nd4j-environment-config.json}.
     * Real values, not nulls: the 2026-07-05 capture OOM prescribed a larger
     * {@code dspCaptureWorkspaceMb} and the proactive-evict knobs existed but were never
     * provisioned. Applied only when GPUs are present.
     */
    private static void applyGpuDspDefaults(Map<String, Object> nd4jConfig, List<GpuProbe.GpuInfo> gpus) {
        if (gpus == null || gpus.isEmpty()) {
            return;
        }
        long largestVramMb = gpus.stream().mapToLong(GpuProbe.GpuInfo::vramMb).max().orElse(0);
        nd4jConfig.putIfAbsent("dspCaptureWorkspaceMb", largestVramMb >= 16_384 ? 2048 : 1024);
        nd4jConfig.putIfAbsent("dspProactiveEvictBeforeCapture", true);
        nd4jConfig.putIfAbsent("dspLruEviction", true);
    }

    /**
     * Build app-index-config.json.
     *
     * <p>Uses RELATIVE index paths (relative to projectRoot at runtime) matching
     * the fpna-v8 semantics but without any absolute prefix. The app resolves
     * these against {@code kompile.data.dir} in {@code AppIndexConfigService}.</p>
     */
    private static Map<String, Object> buildAppIndexConfig(Tier tier) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("appTitle", "Kompile RAG Console");
        config.put("logoUrl", "assets/branding/kompile-logo.svg");
        config.put("logoAlt", "Kompile");
        config.put("showLogo", true);
        config.put("faviconUrl", "assets/branding/kompile-logo.svg");

        // Vector store type — Anserini is the default hybrid store
        config.put("vectorStoreType", "ANSERINI");
        // Relative paths; AppIndexConfigService prepends dataDir at runtime
        config.put("vectorStorePath", "anserini/indexes/vector_index");
        config.put("keywordIndexPath", "anserini/indexes/default_index");

        // Subprocess settings derived from tier
        config.put("subprocessEnabled", true);
        config.put("subprocessHeapSize", appIndexSubprocessHeapForTier(tier));

        // Batch / embedding settings derived from tier
        config.put("indexBatchSize", 100);
        config.put("adaptiveBatchSize", true);
        config.put("embeddingTargetBatchSize", 256);
        config.put("embeddingThreads", appIndexEmbeddingThreadsForTier(tier));

        // Other store endpoints left at defaults (blank/localhost) —
        // operators configure these only when switching store backends
        config.put("vespaEndpoint", "http://localhost:8080");
        config.put("vespaNamespace", "default");
        config.put("vespaDocumentType", "document");
        config.put("vespaVectorField", "embedding");
        config.put("vespaHybridSearchEnabled", false);
        config.put("vespaHybridVectorWeight", 0.7);
        config.put("pgvectorUrl", "jdbc:postgresql://localhost:5432/kompile");
        config.put("pgvectorUsername", "postgres");
        config.put("pgvectorPassword", "");
        config.put("pgvectorTableName", "vector_store");
        config.put("chromaHost", "localhost");
        config.put("chromaPort", 8000);
        config.put("chromaCollectionName", "kompile");

        return config;
    }

    /**
     * Per-service device-memory budget fractions sized from the best local GPU. Each bounds one
     * subprocess's ND4J pool so no single lane can consume the whole card — the 2026-07-05 capture
     * OOM ran with null (=unbounded), and the 2026-07-07 v12 crawl still thrashed when embedding was
     * allowed to reserve most of a 24 GB card. Overridable per project in the JSON.
     */
    private static final double EMBEDDING_DEVICE_FRACTION = 0.40;
    private static final double LLM_DEVICE_FRACTION = 0.35;
    private static final double VLM_DEVICE_FRACTION = 0.25;

    /**
     * Build device-routing-config.json with memory budgets only. Backend and device selection remain
     * unset so ND4J automatic placement and failover decide where work runs.
     */
    static Map<String, Object> buildDeviceRoutingConfig(List<GpuProbe.GpuInfo> gpus) {
        long budgetVramMb = fastestGpuVramMb(gpus);

        Map<String, Object> vlmRoute = route(budgetVramMb, VLM_DEVICE_FRACTION);
        Map<String, Object> llmRoute = route(budgetVramMb, LLM_DEVICE_FRACTION);
        // Embedding route: the SameDiff encoder subprocess — historically the heaviest device
        // tenant (DSP warmup capture). SERVICE_EMBEDDING is consumed by
        // DeviceRoutingAutoConfiguration → AnseriniEmbeddingModelImpl; fpna-v4 hand-patched
        // .serviceRoutes.embedding via jq, so the golden path must provision it.
        Map<String, Object> embeddingRoute = route(budgetVramMb, EMBEDDING_DEVICE_FRACTION);

        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("vlm", vlmRoute);
        routes.put("llm", llmRoute);
        routes.put("embedding", embeddingRoute);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serviceRoutes", routes);
        config.put("enabled", true);
        return config;
    }

    private static Map<String, Object> route(long vramMb, double fraction) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("maxThreads", null);
        r.put("maxDeviceMemory", vramMb > 0
                ? (long) (vramMb * fraction) * 1024L * 1024L
                : null);
        return r;
    }

    private static long fastestGpuVramMb(List<GpuProbe.GpuInfo> gpus) {
        if (gpus == null || gpus.isEmpty()) {
            return 0;
        }
        Map<Integer, Integer> cudaOrder = GpuProbe.assignCudaRuntimeOrder(gpus);
        return gpus.stream()
                .filter(g -> cudaOrder.getOrDefault(g.index(), g.index()) == 0)
                .mapToLong(GpuProbe.GpuInfo::vramMb)
                .findFirst()
                .orElse(gpus.get(0).vramMb());
    }

    /**
     * Build project-runtime.json — CLI-owned bookkeeping.
     */
    private static Map<String, Object> buildProjectRuntime(Tier tier, long ramGb,
                                                            int cpus,
                                                            List<GpuProbe.GpuInfo> gpus) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tier", tier.name().toLowerCase());
        config.put("appHeap", HardwareAutoConfigurator.appHeapForTier(tier));
        // One key per persona process. The launcher looks these up as
        // `KompileService.id() + "Heap"`, so the names here are load-bearing: chat -> chatHeap,
        // crawl -> crawlHeap. A missing key is not an error — the launcher falls back to the
        // ServiceManager machine-tier default — which is what keeps pre-split projects working.
        config.put("chatHeap", HardwareAutoConfigurator.chatHeapForTier(tier));
        config.put("crawlHeap", HardwareAutoConfigurator.crawlHeapForTier(tier));
        config.put("stagingHeap", HardwareAutoConfigurator.stagingHeapForTier(tier));
        config.put("servingHeap", HardwareAutoConfigurator.servingHeapForTier(tier));

        List<Map<String, Object>> gpuList = new ArrayList<>();
        for (GpuProbe.GpuInfo g : gpus) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", g.index());
            entry.put("name", g.name());
            entry.put("vramMb", g.vramMb());
            gpuList.add(entry);
        }
        config.put("gpus", gpuList);
        config.put("totalRamGb", ramGb);
        config.put("cpus", cpus);
        config.put("provisionedAt", DateTimeFormatter.ISO_INSTANT
                .format(Instant.now().atOffset(ZoneOffset.UTC)));
        return config;
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private static ObjectMapper buildMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Write {@code content} to {@code target} only when the file does not
     * already exist.  Creates parent directories as needed.
     */
    private static void writeIfAbsent(Path target,
                                       Map<String, Object> content,
                                       ObjectMapper mapper,
                                       List<Path> written,
                                       List<Path> skipped,
                                       List<String> warnings) {
        if (Files.exists(target)) {
            skipped.add(target);
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            mapper.writeValue(target.toFile(), content);
            written.add(target);
        } catch (IOException e) {
            warnings.add("Failed to write " + target.getFileName() + ": " + e.getMessage());
        }
    }
}
