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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SamediffBenchmarkConfig;
import ai.kompile.app.web.dto.SamediffBenchmarkResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for managing SameDiff benchmark configurations and running benchmarks.
 * Persists configs and results to ~/.kompile/config/.
 */
@Service
public class SamediffBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(SamediffBenchmarkService.class);
    private static final String CONFIGS_FILENAME = "benchmark-configs.json";
    private static final String RESULTS_FILENAME = "benchmark-results.json";

    private final ObjectMapper objectMapper;
    private final Path configsFilePath;
    private final Path resultsFilePath;
    private final Nd4jEnvironmentConfigService nd4jConfigService;

    private final Map<String, SamediffBenchmarkConfig> configs = new ConcurrentHashMap<>();
    private final List<SamediffBenchmarkResult> results = new CopyOnWriteArrayList<>();
    private volatile String activeConfigName;

    public SamediffBenchmarkService(
            @Value("${kompile.data.dir:#{null}}") String dataDir,
            Nd4jEnvironmentConfigService nd4jConfigService) {
        this.objectMapper = new ObjectMapper();
        this.nd4jConfigService = nd4jConfigService;

        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configsFilePath = Paths.get(effectiveDataDir, "config", CONFIGS_FILENAME);
        this.resultsFilePath = Paths.get(effectiveDataDir, "config", RESULTS_FILENAME);
    }

    @PostConstruct
    public void init() {
        loadConfigs();
        loadResults();

        // If no configs exist, create the optimal default
        if (configs.isEmpty()) {
            SamediffBenchmarkConfig optimal = SamediffBenchmarkConfig.optimal();
            configs.put(optimal.name(), optimal);
            activeConfigName = optimal.name();
            persistConfigs();
            log.info("Created default 'optimal' benchmark config");
        } else {
            // Find the active config
            for (var entry : configs.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().isActive())) {
                    activeConfigName = entry.getKey();
                    break;
                }
            }
        }

        log.info("SamediffBenchmarkService initialized with {} configs, active: {}",
                configs.size(), activeConfigName);
    }

    // === CRUD Operations ===

    public List<SamediffBenchmarkConfig> listConfigs() {
        return new ArrayList<>(configs.values());
    }

    public SamediffBenchmarkConfig getConfig(String name) {
        return configs.get(name);
    }

    public SamediffBenchmarkConfig saveConfig(SamediffBenchmarkConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("Config name is required");
        }

        // Set creation timestamp if new
        SamediffBenchmarkConfig toSave = config;
        if (!configs.containsKey(config.name()) && config.createdAt() == null) {
            toSave = new SamediffBenchmarkConfig(
                    config.name(), config.isActive(), Instant.now().toString(), config.lastUsedAt(),
                    config.tritonBuildThreads(), config.tritonCacheEnabled(), config.tritonVerbose(),
                    config.tritonAlwaysCompile(), config.tritonNumWarps(), config.tritonNumStages(),
                    config.tritonNumCTAs(), config.tritonEnableFpFusion(), config.tritonCacheDir(),
                    config.tritonDumpDir(), config.tritonOverrideArch(),
                    config.cudaTensorCoreEnabled(), config.cudaGraphOptimization(),
                    config.maxTokens(), config.captureMinExec(),
                    config.minDiversityPct(), config.expectedSubstrings(), config.expectStructuralTags()
            );
        }

        configs.put(toSave.name(), toSave);
        persistConfigs();
        log.info("Saved benchmark config: {}", toSave.name());
        return toSave;
    }

    public boolean deleteConfig(String name) {
        SamediffBenchmarkConfig removed = configs.remove(name);
        if (removed != null) {
            if (name.equals(activeConfigName)) {
                activeConfigName = null;
            }
            persistConfigs();
            log.info("Deleted benchmark config: {}", name);
            return true;
        }
        return false;
    }

    // === Active Config Management ===

    public SamediffBenchmarkConfig getActiveConfig() {
        if (activeConfigName == null) return null;
        return configs.get(activeConfigName);
    }

    public SamediffBenchmarkConfig activateConfig(String name) {
        SamediffBenchmarkConfig config = configs.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Config not found: " + name);
        }

        // Deactivate all, activate the selected one
        Map<String, SamediffBenchmarkConfig> updated = new HashMap<>();
        for (var entry : configs.entrySet()) {
            SamediffBenchmarkConfig c = entry.getValue();
            boolean shouldBeActive = entry.getKey().equals(name);
            updated.put(entry.getKey(), new SamediffBenchmarkConfig(
                    c.name(), shouldBeActive, c.createdAt(), c.lastUsedAt(),
                    c.tritonBuildThreads(), c.tritonCacheEnabled(), c.tritonVerbose(),
                    c.tritonAlwaysCompile(), c.tritonNumWarps(), c.tritonNumStages(),
                    c.tritonNumCTAs(), c.tritonEnableFpFusion(), c.tritonCacheDir(),
                    c.tritonDumpDir(), c.tritonOverrideArch(),
                    c.cudaTensorCoreEnabled(), c.cudaGraphOptimization(),
                    c.maxTokens(), c.captureMinExec(),
                    c.minDiversityPct(), c.expectedSubstrings(), c.expectStructuralTags()
            ));
        }

        configs.clear();
        configs.putAll(updated);
        activeConfigName = name;

        // Apply to ND4J environment
        applyConfigToEnvironment(config);

        persistConfigs();
        log.info("Activated benchmark config: {}", name);
        return configs.get(name);
    }

    /**
     * Apply a benchmark config's Triton/CUDA settings to the ND4J environment.
     */
    public void applyConfigToEnvironment(SamediffBenchmarkConfig config) {
        if (Nd4j.getEnvironment().isCPU()) {
            log.warn("Cannot apply Triton/CUDA benchmark settings on CPU backend");
            return;
        }

        Nd4jEnvironmentConfig envUpdate = config.toNd4jEnvironmentConfig();
        nd4jConfigService.updateConfiguration(envUpdate);
        log.info("Applied benchmark config '{}' to ND4J environment", config.name());
    }

    /**
     * Apply the optimal benchmark defaults to the ND4J environment.
     */
    public SamediffBenchmarkConfig applyOptimalDefaults() {
        SamediffBenchmarkConfig optimal = SamediffBenchmarkConfig.optimal();
        configs.put(optimal.name(), optimal);
        activeConfigName = optimal.name();
        applyConfigToEnvironment(optimal);
        persistConfigs();
        log.info("Applied optimal benchmark defaults");
        return optimal;
    }

    // === Benchmark Execution ===

    /**
     * Run a benchmark with the specified config.
     * This applies the config, measures timing, and records results.
     */
    public SamediffBenchmarkResult runBenchmark(String configName) {
        SamediffBenchmarkConfig config = configs.get(configName);
        if (config == null) {
            return SamediffBenchmarkResult.failed(configName, "Config not found: " + configName);
        }

        if (Nd4j.getEnvironment().isCPU()) {
            return SamediffBenchmarkResult.failed(configName, "Benchmarks require CUDA backend");
        }

        log.info("Running benchmark with config: {}", configName);

        try {
            // Apply the config
            long resetStart = System.currentTimeMillis();
            applyConfigToEnvironment(config);
            long resetMs = System.currentTimeMillis() - resetStart;

            // Read current Triton metrics (before inference)
            var env = Nd4j.getEnvironment();
            int tritonLaunchesBefore = 0;
            int tritonCacheHitsBefore = 0;

            // Perform a simple benchmark — matrix operations as proxy for inference
            long compileStart = System.currentTimeMillis();
            // Warm-up: create and compute with arrays to trigger Triton compilation
            var a = Nd4j.rand(new int[]{512, 512});
            var b = Nd4j.rand(new int[]{512, 512});
            var warmup = a.mmul(b);
            warmup.close();
            long compileMs = System.currentTimeMillis() - compileStart;

            // Decode phase — simulated inference iterations
            int iterations = config.maxTokens() != null ? config.maxTokens() : 256;
            int actualIterations = Math.min(iterations, 100); // Cap for benchmark
            long decodeStart = System.currentTimeMillis();
            long firstTokenMs = 0;
            for (int i = 0; i < actualIterations; i++) {
                var result = a.mmul(b);
                if (i == 0) {
                    firstTokenMs = System.currentTimeMillis() - decodeStart;
                }
                result.close();
            }
            long decodeMs = System.currentTimeMillis() - decodeStart;

            a.close();
            b.close();

            long totalMs = resetMs + compileMs + decodeMs;
            double tokPerSec = actualIterations > 0 ? (actualIterations * 1000.0) / totalMs : 0;
            double decodeTokPerSec = decodeMs > 0 ? (actualIterations * 1000.0) / decodeMs : 0;

            SamediffBenchmarkResult result = new SamediffBenchmarkResult(
                    configName, true, null,
                    resetMs, compileMs, decodeMs, 0, totalMs,
                    actualIterations, tokPerSec, decodeTokPerSec, firstTokenMs,
                    0, 0,
                    "Benchmark completed: " + actualIterations + " iterations",
                    "completed",
                    Instant.now().toString()
            );

            results.add(result);
            persistResults();

            // Update lastUsedAt
            SamediffBenchmarkConfig updated = new SamediffBenchmarkConfig(
                    config.name(), config.isActive(), config.createdAt(), Instant.now().toString(),
                    config.tritonBuildThreads(), config.tritonCacheEnabled(), config.tritonVerbose(),
                    config.tritonAlwaysCompile(), config.tritonNumWarps(), config.tritonNumStages(),
                    config.tritonNumCTAs(), config.tritonEnableFpFusion(), config.tritonCacheDir(),
                    config.tritonDumpDir(), config.tritonOverrideArch(),
                    config.cudaTensorCoreEnabled(), config.cudaGraphOptimization(),
                    config.maxTokens(), config.captureMinExec(),
                    config.minDiversityPct(), config.expectedSubstrings(), config.expectStructuralTags()
            );
            configs.put(configName, updated);
            persistConfigs();

            log.info("Benchmark '{}' completed: {} tok/s (decode: {} tok/s)",
                    configName, String.format("%.1f", tokPerSec), String.format("%.1f", decodeTokPerSec));
            return result;

        } catch (Exception e) {
            log.error("Benchmark '{}' failed: {}", configName, e.getMessage(), e);
            SamediffBenchmarkResult failed = SamediffBenchmarkResult.failed(configName, e.getMessage());
            results.add(failed);
            persistResults();
            return failed;
        }
    }

    /**
     * Run a matrix of benchmarks varying Triton parameters.
     */
    public List<SamediffBenchmarkResult> runMatrix(List<Integer> warpsRange, List<Integer> stagesRange,
                                                    List<Boolean> fpFusionRange) {
        List<SamediffBenchmarkResult> matrixResults = new ArrayList<>();

        for (int warps : warpsRange) {
            for (int stages : stagesRange) {
                for (boolean fpFusion : fpFusionRange) {
                    String configName = String.format("matrix_w%d_s%d_fp%s", warps, stages, fpFusion);

                    SamediffBenchmarkConfig config = new SamediffBenchmarkConfig(
                            configName, false, Instant.now().toString(), null,
                            Runtime.getRuntime().availableProcessors(), true, false, false,
                            warps, stages, 1, fpFusion,
                            null, null, null,
                            true, true,
                            100, 3,
                            null, null, null
                    );

                    configs.put(configName, config);
                    SamediffBenchmarkResult result = runBenchmark(configName);
                    matrixResults.add(result);
                }
            }
        }

        persistConfigs();
        log.info("Matrix benchmark completed: {} configurations tested", matrixResults.size());
        return matrixResults;
    }

    /**
     * Search for the optimal profile by running a grid search.
     */
    public SamediffBenchmarkResult searchOptimalProfile(
            List<Integer> warpsRange, List<Integer> stagesRange, List<Boolean> fpFusionRange) {

        List<SamediffBenchmarkResult> searchResults = runMatrix(warpsRange, stagesRange, fpFusionRange);

        // Find the best result by decode tok/s
        SamediffBenchmarkResult best = searchResults.stream()
                .filter(SamediffBenchmarkResult::passed)
                .max(Comparator.comparingDouble(SamediffBenchmarkResult::decodeTokPerSec))
                .orElse(null);

        if (best != null) {
            log.info("Best profile found: {} with {} decode tok/s",
                    best.configName(), String.format("%.1f", best.decodeTokPerSec()));
            // Activate the best config
            activateConfig(best.configName());
        }

        return best;
    }

    // === Results History ===

    public List<SamediffBenchmarkResult> getResults() {
        return new ArrayList<>(results);
    }

    public void clearResults() {
        results.clear();
        persistResults();
    }

    // === Persistence ===

    private void loadConfigs() {
        if (!Files.exists(configsFilePath)) return;
        try {
            String json = Files.readString(configsFilePath);
            List<SamediffBenchmarkConfig> loaded = objectMapper.readValue(json,
                    new TypeReference<List<SamediffBenchmarkConfig>>() {});
            for (SamediffBenchmarkConfig c : loaded) {
                configs.put(c.name(), c);
            }
            log.info("Loaded {} benchmark configs from {}", configs.size(), configsFilePath);
        } catch (IOException e) {
            log.error("Failed to load benchmark configs: {}", e.getMessage());
        }
    }

    private void loadResults() {
        if (!Files.exists(resultsFilePath)) return;
        try {
            String json = Files.readString(resultsFilePath);
            List<SamediffBenchmarkResult> loaded = objectMapper.readValue(json,
                    new TypeReference<List<SamediffBenchmarkResult>>() {});
            results.addAll(loaded);
            log.info("Loaded {} benchmark results from {}", results.size(), resultsFilePath);
        } catch (IOException e) {
            log.error("Failed to load benchmark results: {}", e.getMessage());
        }
    }

    private void persistConfigs() {
        persistToFile(configsFilePath, new ArrayList<>(configs.values()));
    }

    private void persistResults() {
        // Keep last 1000 results
        while (results.size() > 1000) {
            results.remove(0);
        }
        persistToFile(resultsFilePath, new ArrayList<>(results));
    }

    private void persistToFile(Path filePath, Object data) {
        try {
            Path parentDir = filePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Path tempFile = filePath.resolveSibling(
                    filePath.getFileName().toString() + ".tmp." + UUID.randomUUID());
            try {
                Files.writeString(tempFile, json);
                Files.move(tempFile, filePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            log.error("Failed to persist to {}: {}", filePath, e.getMessage());
        }
    }
}
