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

package ai.kompile.staging.optimization;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.RegistryService;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Service for A/B comparison testing between optimized and original models.
 * Loads each model sequentially (to avoid 2x memory), runs inference with the
 * same dummy inputs, and reports element-wise diff metrics.
 */
@Service
public class ComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);
    private static final double MATCH_THRESHOLD = 1e-5;

    private final RegistryService registryService;

    @Autowired
    public ComparisonService(RegistryService registryService) {
        this.registryService = registryService;
    }

    // ==================== Comparison Logic ====================

    /**
     * Compare an optimized model with its original (backup) version.
     * Models are loaded sequentially to avoid 2x memory usage.
     */
    public ComparisonResult compare(String modelId, ComparisonRequest request) {
        log.info("Starting A/B comparison for model: {}", modelId);

        try {
            // Look up model in registry
            Optional<ModelEntry> entryOpt = registryService.getModel(modelId);
            if (entryOpt.isEmpty()) {
                return ComparisonResult.failure(modelId, "Model not found: " + modelId);
            }

            ModelEntry entry = entryOpt.get();
            ModelMetadata metadata = entry.getMetadata();

            if (metadata == null || !Boolean.TRUE.equals(metadata.getOptimized())) {
                return ComparisonResult.failure(modelId, "Model is not optimized. Nothing to compare.");
            }

            String backupFileName = metadata.getUnoptimizedBackupFile();
            if (backupFileName == null || backupFileName.isEmpty()) {
                return ComparisonResult.failure(modelId, "No backup file recorded for this model.");
            }

            // Resolve paths
            Path modelDir = registryService.getModelDir().resolve(entry.getPath());
            Path optimizedPath = modelDir.resolve(entry.getModelFile());
            Path originalPath = modelDir.resolve(backupFileName);

            if (!Files.exists(optimizedPath)) {
                return ComparisonResult.failure(modelId, "Optimized model file not found: " + optimizedPath);
            }
            if (!Files.exists(originalPath)) {
                return ComparisonResult.failure(modelId, "Original backup file not found: " + originalPath);
            }

            boolean isZipFormat = isZipFile(optimizedPath);

            // Step 1: Run inference on optimized model
            log.info("Running inference on optimized model...");
            InferenceOutput optimizedOutput = runInference(optimizedPath, isZipFormat, request);
            ModelInferenceResult optimizedResult = buildInferenceResult("optimized", optimizedPath, optimizedOutput);

            // Step 2: Run inference on original model (using same placeholder shapes)
            log.info("Running inference on original model...");
            InferenceOutput originalOutput = runInference(originalPath, isZipFormat, request);
            ModelInferenceResult originalResult = buildInferenceResult("original", originalPath, originalOutput);

            // Step 3: Compute diff metrics
            double maxAbsDiff = 0.0;
            double totalAbsDiff = 0.0;
            long totalElements = 0;

            for (String outputName : optimizedOutput.outputs.keySet()) {
                INDArray optArr = optimizedOutput.outputs.get(outputName);
                INDArray origArr = originalOutput.outputs.get(outputName);

                if (origArr == null) {
                    log.warn("Output '{}' not found in original model", outputName);
                    continue;
                }

                // Cast both to FLOAT for comparison if needed
                INDArray optFloat = optArr.dataType() == DataType.FLOAT ? optArr : optArr.castTo(DataType.FLOAT);
                INDArray origFloat = origArr.dataType() == DataType.FLOAT ? origArr : origArr.castTo(DataType.FLOAT);

                INDArray diff = Transforms.abs(optFloat.sub(origFloat));
                double thisMax = diff.maxNumber().doubleValue();
                double thisMean = diff.meanNumber().doubleValue();
                long thisElements = diff.length();

                maxAbsDiff = Math.max(maxAbsDiff, thisMax);
                totalAbsDiff += thisMean * thisElements;
                totalElements += thisElements;

                // Clean up intermediate arrays
                diff.close();
                if (optFloat != optArr) optFloat.close();
                if (origFloat != origArr) origFloat.close();
            }

            double meanAbsDiff = totalElements > 0 ? totalAbsDiff / totalElements : 0.0;
            boolean outputsMatch = maxAbsDiff <= MATCH_THRESHOLD;

            double speedupFactor = originalResult.getInferenceTimeMs() > 0
                    ? (double) originalResult.getInferenceTimeMs() / optimizedResult.getInferenceTimeMs()
                    : 1.0;

            // Clean up output arrays
            cleanupOutputs(optimizedOutput.outputs);
            cleanupOutputs(originalOutput.outputs);

            // Build result
            ComparisonResult result = ComparisonResult.builder()
                    .success(true)
                    .modelId(modelId)
                    .optimizedResult(optimizedResult)
                    .originalResult(originalResult)
                    .maxAbsoluteDifference(maxAbsDiff)
                    .meanAbsoluteDifference(meanAbsDiff)
                    .outputsMatch(outputsMatch)
                    .speedupFactor(Math.round(speedupFactor * 100.0) / 100.0)
                    .build();

            log.info("Comparison complete for model {}: match={}, maxDiff={}, speedup={}x",
                    modelId, outputsMatch, maxAbsDiff, result.getSpeedupFactor());

            return result;

        } catch (Exception e) {
            log.error("Comparison failed for model {}: {}", modelId, e.getMessage(), e);
            return ComparisonResult.failure(modelId, "Comparison failed: " + e.getMessage());
        }
    }

    // ==================== Internal Helpers ====================

    private static class InferenceOutput {
        Map<String, INDArray> outputs;
        long inferenceTimeMs;
        int numOps;
        int numVars;
    }

    private InferenceOutput runInference(Path modelPath, boolean isZipFormat, ComparisonRequest request) throws Exception {
        SameDiff sd;
        if (isZipFormat) {
            sd = SDZSerializer.load(modelPath.toFile(), true);
        } else {
            sd = SameDiff.load(modelPath.toFile(), true);
        }

        if (sd == null) {
            throw new RuntimeException("Failed to load model from: " + modelPath);
        }

        InferenceOutput result = new InferenceOutput();
        result.numOps = sd.ops().length;
        result.numVars = sd.variables().size();

        List<String> outputs = sd.outputs();
        if (outputs == null || outputs.isEmpty()) {
            throw new RuntimeException("Model has no outputs defined");
        }

        // Build placeholder inputs
        Map<String, INDArray> placeholderValues = buildPlaceholderInputs(sd, request);

        try {
            long startTime = System.nanoTime();
            Map<String, INDArray> outputMap = sd.output(placeholderValues, outputs);
            long endTime = System.nanoTime();

            result.inferenceTimeMs = (endTime - startTime) / 1_000_000;

            // Detach outputs so they survive after SameDiff cleanup
            Map<String, INDArray> detachedOutputs = new LinkedHashMap<>();
            for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                detachedOutputs.put(entry.getKey(), entry.getValue().dup());
            }

            result.outputs = detachedOutputs;
            return result;

        } finally {
            // Clean up placeholder inputs
            for (INDArray arr : placeholderValues.values()) {
                if (arr != null) arr.close();
            }
        }
    }

    private Map<String, INDArray> buildPlaceholderInputs(SameDiff sd, ComparisonRequest request) {
        List<String> placeholders = sd.inputs();
        Map<String, INDArray> placeholderValues = new LinkedHashMap<>();

        if (placeholders == null || placeholders.isEmpty()) {
            return placeholderValues;
        }

        for (String placeholder : placeholders) {
            SDVariable var = sd.getVariable(placeholder);
            if (var == null) continue;

            long[] shape = var.getShape();
            if (shape == null) {
                shape = new long[]{1, request.getSequenceLength()};
            }

            // Replace dynamic (-1) dimensions
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] <= 0) {
                    shape[i] = (i == 0) ? 1 : request.getSequenceLength();
                }
            }

            DataType dtype = var.dataType();
            if (dtype == null) dtype = DataType.FLOAT;

            INDArray input = Nd4j.ones(dtype, shape);
            placeholderValues.put(placeholder, input);
        }

        return placeholderValues;
    }

    private ModelInferenceResult buildInferenceResult(String variant, Path modelPath, InferenceOutput output) throws IOException {
        Map<String, long[]> shapes = new LinkedHashMap<>();
        Map<String, double[]> samples = new LinkedHashMap<>();

        for (Map.Entry<String, INDArray> entry : output.outputs.entrySet()) {
            INDArray arr = entry.getValue();
            shapes.put(entry.getKey(), arr.shape());

            // Collect first 10 values as sample
            INDArray flat = arr.reshape(-1);
            int sampleSize = (int) Math.min(10, flat.length());
            double[] sampleValues = new double[sampleSize];
            for (int i = 0; i < sampleSize; i++) {
                sampleValues[i] = flat.getDouble(i);
            }
            samples.put(entry.getKey(), sampleValues);
        }

        return ModelInferenceResult.builder()
                .variant(variant)
                .inferenceTimeMs(output.inferenceTimeMs)
                .numOps(output.numOps)
                .numVars(output.numVars)
                .modelSizeBytes(Files.size(modelPath))
                .outputShapes(shapes)
                .outputSample(samples)
                .build();
    }

    private void cleanupOutputs(Map<String, INDArray> outputs) {
        if (outputs == null) return;
        for (INDArray arr : outputs.values()) {
            if (arr != null) arr.close();
        }
    }

    private boolean isZipFile(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] magic = new byte[4];
            int read = is.read(magic);
            if (read >= 2) {
                return magic[0] == 0x50 && magic[1] == 0x4B;
            }
        } catch (IOException e) {
            log.warn("Failed to read magic bytes from {}", filePath, e);
        }
        return false;
    }
}
