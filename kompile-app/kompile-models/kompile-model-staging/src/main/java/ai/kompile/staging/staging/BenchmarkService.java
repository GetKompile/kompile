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
 *  limitations under the License.
 */

package ai.kompile.staging.staging;

import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.RegistryService;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Service for running benchmark validation on staged models.
 * Measures throughput, latency, and output quality metrics before promotion.
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final RegistryService registryService;

    @Autowired
    public BenchmarkService(RegistryService registryService) {
        this.registryService = registryService;
    }

    /**
     * Run benchmark on a model and return results.
     *
     * @param modelId Model identifier for logging
     * @param modelPath Path to the model file
     * @param config Benchmark configuration with thresholds
     * @return Benchmark result with measured metrics
     */
    public ModelMetadata.BenchmarkResult runBenchmark(String modelId, Path modelPath, BenchmarkConfig config) {
        log.info("Starting benchmark for model: {} with {} warmup + {} measurement iterations",
                modelId, config.getWarmupIterations(), config.getMeasurementIterations());

        try {
            SameDiff sd = SDZSerializer.load(modelPath.toFile(), true);
            if (sd == null) {
                throw new IllegalStateException("Failed to load model for benchmarking: " + modelPath);
            }

            List<String> inputs = sd.inputs();
            List<String> outputs = sd.outputs();
            if (config.isValidateStructure()) {
                throw new UnsupportedOperationException("Benchmark structure validation requires a real output validator");
            }

            Map<String, INDArray> placeholders = loadBenchmarkInputs(modelPath, config, inputs);

            double p99LatencyMs;
            double throughput;
            double tokenDiversity;
            Double throughputDelta = null;
            boolean regression = false;
            String baselineModel = config.getBaselineModelId();
            boolean structureValid = true;
            try {
                // Warmup
                for (int i = 0; i < config.getWarmupIterations(); i++) {
                    sd.output(placeholders, outputs);
                }

                // Measurement
                List<Long> latencies = new ArrayList<>();
                Set<Integer> uniqueTokens = new HashSet<>();
                int totalTokens = 0;

                for (int i = 0; i < config.getMeasurementIterations(); i++) {
                    long start = System.nanoTime();
                    Map<String, INDArray> result = sd.output(placeholders, outputs);
                    long elapsed = System.nanoTime() - start;
                    latencies.add(elapsed);

                    // Collect token diversity from first output
                    for (INDArray output : result.values()) {
                        if (output.rank() >= 1) {
                            long len = output.length();
                            for (long j = 0; j < Math.min(len, 512); j++) {
                                int token = output.getInt((int) j);
                                uniqueTokens.add(token);
                                totalTokens++;
                            }
                        }
                        break;
                    }
                }

                Collections.sort(latencies);
                p99LatencyMs = latencies.get((int) (latencies.size() * 0.99)) / 1_000_000.0;
                double avgLatencyMs = latencies.stream().mapToLong(l -> l).average().orElse(0) / 1_000_000.0;
                throughput = avgLatencyMs > 0 ? (1000.0 / avgLatencyMs) : 0;
                tokenDiversity = totalTokens > 0 ? (double) uniqueTokens.size() / totalTokens : 0;

                // Baseline comparison
                if (baselineModel != null) {
                    var baselineEntry = registryService.getModel(baselineModel);
                    if (baselineEntry.isPresent() && baselineEntry.get().getMetadata() != null
                            && baselineEntry.get().getMetadata().getBenchmarkResult() != null) {
                        double baselineThroughput = baselineEntry.get().getMetadata().getBenchmarkResult().getThroughputTokPerSec();
                        if (baselineThroughput > 0) {
                            throughputDelta = ((throughput - baselineThroughput) / baselineThroughput) * 100.0;
                            regression = throughputDelta < -config.getMaxRegressionPercent();
                        }
                    }
                }
            } finally {
                for (INDArray arr : placeholders.values()) {
                    if (arr != null) {
                        arr.close();
                    }
                }
            }

            ModelMetadata.BenchmarkResult result = ModelMetadata.BenchmarkResult.builder()
                    .throughputTokPerSec(throughput)
                    .latencyP99Ms(p99LatencyMs)
                    .tokenDiversity(tokenDiversity)
                    .structureValid(structureValid)
                    .baselineModel(baselineModel)
                    .throughputDeltaPercent(throughputDelta)
                    .regression(regression)
                    .benchmarkedAt(Instant.now().toString())
                    .build();

            log.info("Benchmark completed for {}: throughput={:.2f} tok/s, p99={:.2f}ms, diversity={:.3f}",
                    modelId, throughput, p99LatencyMs, tokenDiversity);

            return result;

        } catch (RuntimeException e) {
            log.error("Benchmark failed for model {}: {}", modelId, e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Benchmark failed for model {}: {}", modelId, e.getMessage(), e);
            throw new IllegalStateException("Benchmark failed for model " + modelId, e);
        }
    }

    /**
     * Validate a benchmark result against the configured thresholds.
     *
     * @param result The benchmark result to validate
     * @param config The benchmark config with thresholds
     * @return true if the result passes all threshold checks
     */
    public boolean validateBenchmark(ModelMetadata.BenchmarkResult result, BenchmarkConfig config) {
        if (config.getMinThroughput() > 0 && result.getThroughputTokPerSec() < config.getMinThroughput()) {
            log.warn("Benchmark failed: throughput {:.2f} < min {:.2f}",
                    result.getThroughputTokPerSec(), config.getMinThroughput());
            return false;
        }

        if (config.getMaxLatencyP99() > 0 && result.getLatencyP99Ms() > config.getMaxLatencyP99()) {
            log.warn("Benchmark failed: p99 latency {:.2f}ms > max {:.2f}ms",
                    result.getLatencyP99Ms(), config.getMaxLatencyP99());
            return false;
        }

        if (config.getMinTokenDiversity() > 0 && result.getTokenDiversity() < config.getMinTokenDiversity()) {
            log.warn("Benchmark failed: token diversity {:.3f} < min {:.3f}",
                    result.getTokenDiversity(), config.getMinTokenDiversity());
            return false;
        }

        if (config.isValidateStructure() && !result.isStructureValid()) {
            log.warn("Benchmark failed: output structure validation failed");
            return false;
        }

        if (result.isRegression()) {
            log.warn("Benchmark failed: throughput regression of {:.2f}% exceeds max {:.2f}%",
                    result.getThroughputDeltaPercent(), config.getMaxRegressionPercent());
            return false;
        }

        return true;
    }

    private Map<String, INDArray> loadBenchmarkInputs(Path modelPath, BenchmarkConfig config, List<String> inputNames) throws IOException {
        Map<String, String> configuredInputs = config.getSampleInputs();
        if (configuredInputs == null || configuredInputs.isEmpty()) {
            throw new IllegalArgumentException("Benchmark requires sample_inputs keyed by SameDiff placeholder name");
        }
        Map<String, INDArray> placeholders = new LinkedHashMap<>();
        try {
            for (String name : inputNames) {
                String configuredPath = configuredInputs.get(name);
                if (configuredPath == null || configuredPath.isBlank()) {
                    throw new IllegalArgumentException("Missing benchmark sample input for placeholder: " + name);
                }
                Path inputPath = Path.of(configuredPath);
                if (!inputPath.isAbsolute()) {
                    Path parent = modelPath.toAbsolutePath().getParent();
                    inputPath = (parent == null ? inputPath : parent.resolve(inputPath)).normalize();
                }
                if (!Files.isRegularFile(inputPath)) {
                    throw new IllegalArgumentException("Benchmark sample input does not exist: " + inputPath);
                }
                placeholders.put(name, readBenchmarkArray(inputPath));
            }
            return placeholders;
        } catch (RuntimeException | IOException e) {
            for (INDArray arr : placeholders.values()) {
                if (arr != null) {
                    arr.close();
                }
            }
            throw e;
        }
    }

    private INDArray readBenchmarkArray(Path inputPath) throws IOException {
        if (inputPath.getFileName().toString().endsWith(".npy")) {
            return readNpy(inputPath.toFile());
        }
        return Nd4j.readBinary(inputPath.toFile());
    }

    private INDArray readNpy(File inputFile) throws IOException {
        try {
            return Nd4j.createFromNpyFile(inputFile);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read NPY benchmark input: " + inputFile, e);
        }
    }
}
