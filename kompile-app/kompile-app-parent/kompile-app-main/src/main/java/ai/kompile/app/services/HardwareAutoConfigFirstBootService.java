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

package ai.kompile.app.services;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.PipelineConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.web.dto.PipelineConfigDto;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.config.HardwareAutoConfigurator;
import ai.kompile.cli.common.config.HardwareAutoConfigurator.AutoConfigResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * First-boot hardware auto-configuration hook.
 *
 * <p>On every startup this service checks for a persistent marker file
 * ({@code <dataDir>/config/.hardware-autoconfigured}). If the marker is already
 * present, the service exits immediately — the user may have tuned those configs
 * by hand and we must not overwrite them.</p>
 *
 * <p>When the marker is absent (first boot, or after the user deletes it to
 * re-trigger), the same hardware-detection logic as
 * {@code POST /api/auto-configure/apply} is executed, but in <em>write-only-when-missing</em>
 * mode: a config file is only written if it does not already exist on disk.
 * This means configs pre-written by {@code kompile project init} (or a previous
 * manual apply) are left untouched.</p>
 *
 * <p>After a successful pass the marker is written with an ISO timestamp and a
 * one-line summary. Failures are logged as warnings; the listener never throws.</p>
 *
 * <p>Fires on {@link ApplicationReadyEvent} at {@link Order}{@code (5)}, between
 * the staging-server auto-start ({@code Order(0)}) and the crawl-profile auto-start
 * ({@code Order(10)}).</p>
 */
@Service
public class HardwareAutoConfigFirstBootService {

    private static final Logger log = LoggerFactory.getLogger(HardwareAutoConfigFirstBootService.class);

    /** Marker filename written under {@code <dataDir>/config/} after a successful first-boot pass. */
    static final String MARKER_FILENAME = ".hardware-autoconfigured";

    private final SubprocessConfigService subprocessConfigService;
    private final Nd4jEnvironmentConfigService nd4jConfigService;
    private final PipelineConfigService pipelineConfigService;
    private final Path markerPath;

    @Autowired
    public HardwareAutoConfigFirstBootService(
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jConfigService,
            @Autowired(required = false) PipelineConfigService pipelineConfigService) {
        this(subprocessConfigService, nd4jConfigService, pipelineConfigService,
                resolveDataDir());
    }

    /**
     * Package-private constructor for unit tests — accepts an explicit dataDir so tests
     * can point at a {@code @TempDir} without touching the real {@code ~/.kompile}.
     */
    HardwareAutoConfigFirstBootService(
            SubprocessConfigService subprocessConfigService,
            Nd4jEnvironmentConfigService nd4jConfigService,
            PipelineConfigService pipelineConfigService,
            String dataDir) {
        this.subprocessConfigService = subprocessConfigService;
        this.nd4jConfigService = nd4jConfigService;
        this.pipelineConfigService = pipelineConfigService;
        this.markerPath = Paths.get(dataDir, "config", MARKER_FILENAME);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(5) // After StagingAutoStartService (0), before CrawlProfileAutoStartService (10)
    public void onApplicationReady() {
        try {
            runFirstBootAutoConfig();
        } catch (Exception e) {
            // Never propagate — a misconfigured auto-configure must not abort startup
            log.warn("First-boot hardware auto-configure failed unexpectedly: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks the marker, optionally runs the auto-configure pass, writes the marker.
     * Extracted for testability (tests call this directly rather than firing Spring events).
     */
    void runFirstBootAutoConfig() {
        // ── Idempotency guard ────────────────────────────────────────────────
        if (Files.exists(markerPath)) {
            log.debug("Hardware auto-configure marker present at {} — skipping first-boot pass", markerPath);
            return;
        }

        log.info("First-boot hardware auto-configure: no marker found at {} — running pass", markerPath);

        AutoConfigResult result = HardwareAutoConfigurator.autoConfigure(true);

        int written = 0;
        int skipped = 0;
        StringBuilder summary = new StringBuilder();

        // ── Subprocess config ────────────────────────────────────────────────
        if (subprocessConfigService != null) {
            if (subprocessConfigService.isConfigFilePersisted()) {
                log.debug("subprocess-ingest-config.json already exists — skipping");
                skipped++;
                summary.append("subprocess=skipped ");
            } else {
                try {
                    SubprocessConfigUpdate update = new SubprocessConfigUpdate(
                            (Boolean) result.subprocessConfig.get("enabled"),
                            (String)  result.subprocessConfig.get("javaPath"),
                            (String)  result.subprocessConfig.get("heapSize"),
                            null, // offHeapMaxBytes — let multiplier handle it
                            (Integer) result.subprocessConfig.get("offHeapMultiplier"),
                            (Integer) result.subprocessConfig.get("timeoutMinutes"),
                            null, null, null, null, // VLM — leave default
                            (Integer) result.subprocessConfig.get("heartbeatIntervalSeconds"),
                            (Integer) result.subprocessConfig.get("staleThresholdSeconds"),
                            (Integer) result.subprocessConfig.get("queueCapacity"),
                            (Boolean) result.subprocessConfig.get("parallelIndexing"),
                            (Integer) result.subprocessConfig.get("indexingWorkers"),
                            (Integer) result.subprocessConfig.get("indexingBatchAccumulationSize"),
                            null, // embeddingThreads
                            null, null, null, null, null, null, // restart — leave default
                            null, null, null, null,             // stall — leave default
                            null, null, null, null, null, null, null, // native executable — leave default
                            null, null, null, null, null, null, // memory watchdog — leave default
                            null  // gpuSoftLimitPercent
                    );
                    subprocessConfigService.updateConfiguration(update);
                    written++;
                    summary.append("subprocess=written ");
                } catch (Exception e) {
                    log.warn("First-boot: failed to write subprocess config: {}", e.getMessage(), e);
                    summary.append("subprocess=error ");
                }
            }
        }

        // ── ND4J config ──────────────────────────────────────────────────────
        if (nd4jConfigService != null) {
            if (nd4jConfigService.isConfigFilePersisted()) {
                log.debug("nd4j-environment-config.json already exists — skipping");
                skipped++;
                summary.append("nd4j=skipped ");
            } else {
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
                    written++;
                    summary.append("nd4j=written ");
                } catch (Exception e) {
                    log.warn("First-boot: failed to write ND4J config: {}", e.getMessage(), e);
                    summary.append("nd4j=error ");
                }
            }
        }

        // ── Pipeline config ──────────────────────────────────────────────────
        if (pipelineConfigService != null) {
            if (pipelineConfigService.isConfigFilePersisted()) {
                log.debug("pipeline-config.json already exists — skipping");
                skipped++;
                summary.append("pipeline=skipped ");
            } else {
                try {
                    PipelineConfigDto pipelineDto = PipelineConfigDto.builder()
                            .minBatchSize((Integer)  result.pipelineConfig.get("minBatchSize"))
                            .defaultBatchSize((Integer) result.pipelineConfig.get("defaultBatchSize"))
                            .maxBatchSize((Integer)  result.pipelineConfig.get("maxBatchSize"))
                            .queueCapacity((Integer)  result.pipelineConfig.get("queueCapacity"))
                            .embeddingThreads((Integer) result.pipelineConfig.get("embeddingThreads"))
                            .chunkingThreads((Integer) result.pipelineConfig.get("chunkingThreads"))
                            .indexingThreads((Integer) result.pipelineConfig.get("indexingThreads"))
                            .indexingBatchAccumulationSize((Integer) result.pipelineConfig.get("indexingBatchAccumulationSize"))
                            .skipEmbedding((Boolean) result.pipelineConfig.get("skipEmbedding"))
                            .optimizeGraphOnLoad((Boolean) result.pipelineConfig.get("optimizeGraphOnLoad"))
                            .build();
                    pipelineConfigService.updateConfig(pipelineDto);
                    written++;
                    summary.append("pipeline=written ");
                } catch (Exception e) {
                    log.warn("First-boot: failed to write pipeline config: {}", e.getMessage(), e);
                    summary.append("pipeline=error ");
                }
            }
        }

        // ── Write marker ─────────────────────────────────────────────────────
        String tier = (String) result.hardware.get("tier");
        Object ramGb = result.hardware.get("totalRamGb");
        Object cpus  = result.hardware.get("cpuCount");

        writeMarker(tier, ramGb, cpus, written, skipped);

        log.info("First-boot hardware auto-configure complete: tier={}, ramGb={}, cpus={}, written={}, skipped={} [{}]",
                tier, ramGb, cpus, written, skipped, summary.toString().trim());
    }

    /**
     * Returns the path to the idempotency marker (for testing).
     */
    Path getMarkerPath() {
        return markerPath;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void writeMarker(String tier, Object ramGb, Object cpus, int written, int skipped) {
        try {
            Path configDir = markerPath.getParent();
            if (configDir != null && !Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            String content = Instant.now().toString() + "\n" +
                    "tier=" + tier + " ramGb=" + ramGb + " cpus=" + cpus +
                    " written=" + written + " skipped=" + skipped + "\n";
            Files.writeString(markerPath, content);
        } catch (IOException e) {
            log.warn("First-boot: could not write marker at {}: {}", markerPath, e.getMessage());
        }
    }

    private static String resolveDataDir() {
        // Honour -Dkompile.data.dir or whatever KompileBootstrapEnvironmentPostProcessor set,
        // then fall back to KompileHome (same priority order as SubprocessConfigService).
        String prop = System.getProperty("kompile.data.dir");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        return KompileHome.resolvedProjectDirectory().getAbsolutePath();
    }
}
