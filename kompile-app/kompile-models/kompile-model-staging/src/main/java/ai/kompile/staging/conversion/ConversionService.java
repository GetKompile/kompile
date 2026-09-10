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

package ai.kompile.staging.conversion;

import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.eclipse.deeplearning4j.vlm.model.loading.OnnxModelCache;
import org.nd4j.ggml.GGMLModelImport;
import org.nd4j.common.config.ND4JInferenceWeightDataType;
import org.nd4j.ggml.convert.ConversionOptions;
import org.eclipse.deeplearning4j.pipeline.PipelineLoader;
import org.eclipse.deeplearning4j.safetensors.SafeTensorsPipelineLoader;
import ai.kompile.staging.download.StagingCancellation;
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter;
import org.nd4j.samediff.frameworkimport.tensorflow.importer.TensorflowFrameworkImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Service for converting models from various formats to SameDiff.
 * Supports the format loaders shipped by the staging distribution: ONNX, TensorFlow,
 * GGUF/GGML, and SafeTensors.
 */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    private static final int BUFFER_SIZE = 8192;

    /**
     * Convert a model file to SameDiff format.
     *
     * @param inputPath Path to the input model file
     * @param outputPath Path for the output .sd file
     * @param format Original format: "onnx", "tensorflow", "gguf", or "safetensors"
     * @return Result of the conversion
     */
    public ConversionResult convert(Path inputPath, Path outputPath, String format) {
        return convert(inputPath, outputPath, format, StagingCancellation.NONE);
    }

    /**
     * Convert with an explicit GGUF weight storage dtype policy. Ignored for
     * non-GGUF inputs. Accepted values mirror {@code ConversionOptions.forInference(String)}:
     * fp32, fp16, bf16, fp8, fp8_e5m2, int8, int4.
     */
    public ConversionResult convert(
            Path inputPath,
            Path outputPath,
            String format,
            String weightDtype) {
        return convertInternal(
                inputPath, outputPath, format, StagingCancellation.NONE, false,
                weightDtype == null || weightDtype.isBlank() ? null : weightDtype.trim());
    }

    /**
     * Convert to one canonical SDZ while honoring cooperative staging cancellation.
     */
    public ConversionResult convert(
            Path inputPath,
            Path outputPath,
            String format,
            StagingCancellation cancellation) {
        return convertInternal(inputPath, outputPath, format, cancellation, false);
    }

    /**
     * Convert one VLM ONNX component through the canonical SameDiff-VLM import and
     * graph-optimization path used by production inference.
     */
    public ConversionResult convertVlmOnnx(
            Path inputPath,
            Path outputPath,
            StagingCancellation cancellation) {
        return convertInternal(inputPath, outputPath, "onnx", cancellation, true);
    }

    private ConversionResult convertInternal(
            Path inputPath,
            Path outputPath,
            String format,
            StagingCancellation cancellation,
            boolean vlmOptimizedImport) {
        return convertInternal(inputPath, outputPath, format, cancellation, vlmOptimizedImport, null);
    }

    /** Thread-local weight-dtype handoff for the current conversion; null = default (fp16). */
    private final ThreadLocal<String> pendingWeightDtype = new ThreadLocal<>();

    private ConversionResult convertInternal(
            Path inputPath,
            Path outputPath,
            String format,
            StagingCancellation cancellation,
            boolean vlmOptimizedImport,
            String weightDtype) {
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        StagingCancellation signal =
                cancellation == null ? StagingCancellation.NONE : cancellation;
        Path pendingOutput = null;

        try {
            this.pendingWeightDtype.set(weightDtype);
            signal.checkpoint();
            // Validate input
            if (!Files.isRegularFile(inputPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(inputPath)) {
                return ConversionResult.failure("Input file does not exist: " + inputPath);
            }

            String resolvedFormat = resolveFormat(inputPath, format);
            log.info("Converting {} model: {} -> {}", resolvedFormat, inputPath, outputPath);

            // Create output directory if needed
            Path canonicalOutput = outputPath.toAbsolutePath().normalize();
            if (!canonicalOutput.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".sdz")) {
                return ConversionResult.failure(
                        "Canonical conversion output must end with .sdz: " + canonicalOutput);
            }
            Files.createDirectories(canonicalOutput.getParent());

            // VLM components must use the same graph optimizer and cache-fingerprint
            // ownership as production inference. Generic ONNX conversion intentionally remains
            // unchanged for non-VLM models.
            SameDiff sameDiff = vlmOptimizedImport
                    ? OnnxModelCache.importWithCache(inputPath.toString())
                    : importModel(inputPath, resolvedFormat);
            signal.checkpoint();

            if (sameDiff == null) {
                return ConversionResult.failure("Failed to import model - null result");
            }

            // Validate the imported model
            int numOps = sameDiff.ops().length;
            int numVars = sameDiff.variables().size();

            if (numOps == 0) {
                warnings.add("Model has no operations - may not be a valid model");
            }

            log.info("Imported model with {} operations and {} variables", numOps, numVars);

            // SDZSerializer is the canonical format owner. It creates the complete atomic
            // SDNB shard set internally and packages those shards into one runtime .sdz.
            pendingOutput = canonicalOutput.resolveSibling(
                    "." + UUID.randomUUID() + ".pending-" + canonicalOutput.getFileName());
            SDZSerializer.save(
                    sameDiff,
                    pendingOutput.toFile(),
                    true,
                    Collections.emptyMap());
            signal.checkpoint();

            SameDiff reloaded = SDZSerializer.load(pendingOutput.toFile(), true);
            if (reloaded == null || reloaded.ops().length != numOps) {
                return ConversionResult.failure(
                        "Canonical SDZ validation did not preserve the imported graph");
            }
            signal.checkpoint();
            publishAtomically(pendingOutput, canonicalOutput);
            pendingOutput = null;
            if (vlmOptimizedImport) {
                removeTransientVlmImportCache(inputPath, canonicalOutput);
            }

            ConversionArtifact artifact = ConversionArtifact.canonicalSdz(canonicalOutput);
            String checksum = calculateSha256(artifact.canonicalPath());

            long duration = System.currentTimeMillis() - startTime;
            log.info("Conversion completed in {}ms", duration);

            return ConversionResult.builder()
                    .success(true)
                    .artifact(artifact)
                    .originalFormat(resolvedFormat)
                    .checksum(checksum)
                    .numOperations(numOps)
                    .numVariables(numVars)
                    .durationMs(duration)
                    .warnings(warnings.isEmpty() ? null : warnings.toArray(new String[0]))
                    .build();

        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.error("Conversion failed for: {}", inputPath, e);
            // Do NOT wrap in "Conversion failed: " here — StagingService already adds
            // that context when it wraps this message into info.failed(...). Wrapping
            // twice produces the confusing "Conversion failed: Conversion failed: …" message
            // that was observed in production.
            return ConversionResult.failure(e.getMessage());
        } finally {
            this.pendingWeightDtype.remove();
            if (pendingOutput != null) {
                try {
                    Files.deleteIfExists(pendingOutput);
                } catch (IOException cleanupFailure) {
                    log.warn("Could not remove partial canonical SDZ {}", pendingOutput, cleanupFailure);
                }
            }
        }
    }

    /**
     * Convert an ONNX model to SameDiff.
     */
    public ConversionResult convertOnnx(Path inputPath, Path outputPath) {
        return convert(inputPath, outputPath, "onnx");
    }

    /**
     * Convert a TensorFlow model to SameDiff.
     */
    public ConversionResult convertTensorFlow(Path inputPath, Path outputPath) {
        return convert(inputPath, outputPath, "tensorflow");
    }

    /**
     * Check if the conversion service can handle a format.
     */
    public boolean supportsFormat(String format) {
        if (format == null) return false;
        switch (format.toLowerCase()) {
            case "onnx":
            case "tensorflow":
            case "tf":
            case "pb":
            case "keras":
            case "h5":
            case "gguf":
            case "ggml":
            case "safetensors":
            case "safetensor":
            case "safe_tensors":
                return true;
            default:
                return false;
        }
    }

    /**
     * Resolve the effective conversion format, preferring the explicit
     * user-supplied value but falling back to file-extension sniffing so
     * that misconfigured or missing format strings still work correctly.
     */
    private String resolveFormat(Path inputPath, String format) {
        if (format != null && supportsFormat(format)) {
            return format.toLowerCase();
        }
        String name = inputPath.getFileName().toString().toLowerCase();
        if (name.endsWith(".gguf")) return "gguf";
        if (name.endsWith(".ggml")) return "ggml";
        if (name.endsWith(".safetensors")) return "safetensors";
        if (name.endsWith(".onnx")) return "onnx";
        if (name.endsWith(".pb")) return "tensorflow";
        if (name.endsWith(".h5") || name.endsWith(".keras")) return "tensorflow";
        // Fall back to whatever the caller supplied; importModel will throw if unknown.
        return format == null ? "" : format.toLowerCase();
    }

    /**
     * Import a model based on format.
     */
    private SameDiff importModel(Path inputPath, String format) throws Exception {
        switch (format) {
            case "onnx":
                return importOnnx(inputPath);
            case "tensorflow":
            case "tf":
            case "pb":
            case "keras":
            case "h5":
                return importTensorFlow(inputPath);
            case "gguf":
            case "ggml":
                return importGgml(inputPath);
            case "safetensors":
            case "safetensor":
            case "safe_tensors":
                return importSafeTensors(inputPath);
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    /**
     * Import an ONNX model.
     */
    private SameDiff importOnnx(Path inputPath) throws Exception {
        log.debug("Importing ONNX model from: {}", inputPath);
        OnnxFrameworkImporter importer = new OnnxFrameworkImporter();
        return importer.runImport(
                inputPath.toFile().getAbsolutePath(),
                Collections.emptyMap(),
                false,
                false
        );
    }

    /**
     * Import a TensorFlow frozen graph.
     */
    private SameDiff importTensorFlow(Path inputPath) throws Exception {
        log.debug("Importing TensorFlow model from: {}", inputPath);
        TensorflowFrameworkImporter importer = new TensorflowFrameworkImporter();
        return importer.runImport(
                inputPath.toFile().getAbsolutePath(),
                Collections.emptyMap(),
                false,
                false
        );
    }

    /**
     * Import a GGML/GGUF model into a SameDiff graph. Weight storage dtype is the
     * conversion-wide policy (fp16 dense by default; int4/int8 keep GGUF-packed
     * weights for runtime-quantized matmul). Backed by {@link GGMLModelImport}.
     */
    private SameDiff importGgml(Path inputPath) throws Exception {
        log.debug("Importing GGML/GGUF model from: {}", inputPath);
        String weightDtype = this.pendingWeightDtype.get();
        ConversionOptions options;
        if (weightDtype == null) {
            options = ConversionOptions.builder()
                    .quantizationMode(ConversionOptions.QuantizationMode.DEQUANTIZE_TO_FLOAT16)
                    .preserveTokenizerInfo(true)
                    .useMemoryMapping(true)
                    .build();
        } else {
            ND4JInferenceWeightDataType resolved =
                    ND4JInferenceWeightDataType.fromString(weightDtype);
            ConversionOptions base = ConversionOptions.forInference(resolved);
            options = ConversionOptions.builder()
                    .quantizationMode(base.getQuantizationMode())
                    .targetDataType(base.getTargetDataType())
                    .preserveTokenizerInfo(true)
                    .useMemoryMapping(true)
                    .build();
        }
        log.info("GGUF conversion weight dtype: {}",
                weightDtype == null ? "fp16 (default)" : weightDtype);
        return GGMLModelImport.importModel(inputPath.toFile(), options);
    }

    /**
     * Import a SafeTensors weight file through the upstream pipeline loader. The
     * loader keeps format ownership in deeplearning4j and uses the configured
     * ND4J inference data type; no provider or backend is selected here.
     */
    private SameDiff importSafeTensors(Path inputPath) throws Exception {
        log.debug("Importing SafeTensors model from: {}", inputPath);
        PipelineLoader.LoadConfig config = PipelineLoader.LoadConfig.defaults();
        return new SafeTensorsPipelineLoader().loadModel(inputPath.toFile(), config);
    }

    /**
     * Validate a SameDiff model file. The path may refer to a single-file model
     * (existing .sdnb / .fb) or to the base name of a sharded model (in which case
     * the actual files on disk are "{baseName}.shard{i}-of-{N}.sdnb"); SameDiffSerializer
     * resolves the shard case internally from the parent directory.
     */
    public ValidationResult validate(Path modelPath) {
        try {
            if (modelPath == null) {
                return ValidationResult.failure("Model path is null");
            }
            if (!Files.exists(modelPath) && findShard0(modelPath) == null) {
                return ValidationResult.failure("Model file does not exist: " + modelPath);
            }

            SameDiff sd = modelPath.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".sdz")
                    ? SDZSerializer.load(modelPath.toFile(), true)
                    : SameDiff.load(modelPath.toFile(), true);
            int numOps = sd.ops().length;
            int numVars = sd.variables().size();

            if (numOps == 0) {
                return ValidationResult.failure("Model has no operations");
            }

            return ValidationResult.success(numOps, numVars);
        } catch (Exception e) {
            log.error("Validation failed for: {}", modelPath, e);
            return ValidationResult.failure("Validation failed: " + e.getMessage());
        }
    }

    private Path findShard0(Path modelPath) throws IOException {
        Path parent = modelPath.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        String baseName = modelPath.getFileName().toString();
        try (var entries = Files.list(parent)) {
            return entries
                    .filter(path -> path.getFileName().toString()
                            .startsWith(baseName + ".shard0-of-"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String calculateSha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void removeTransientVlmImportCache(Path inputPath, Path canonicalOutput) {
        String inputName = inputPath.getFileName().toString();
        int extension = inputName.toLowerCase(Locale.ROOT).lastIndexOf(".onnx");
        String baseName = extension >= 0 ? inputName.substring(0, extension) : inputName;
        Path baseSdz = inputPath.resolveSibling(baseName + ".sdz").toAbsolutePath().normalize();
        Path optimizedSdz = inputPath.resolveSibling(baseName + ".opt.sdz").toAbsolutePath().normalize();
        Path normalizedOutput = canonicalOutput.toAbsolutePath().normalize();

        List<Path> transientArtifacts = new ArrayList<>();
        if (!baseSdz.equals(normalizedOutput)) {
            transientArtifacts.add(baseSdz);
        }
        transientArtifacts.add(Path.of(baseSdz + ".meta"));
        transientArtifacts.add(Path.of(baseSdz + ".lock"));
        transientArtifacts.add(optimizedSdz);
        transientArtifacts.add(Path.of(optimizedSdz + ".meta"));

        for (Path artifact : transientArtifacts) {
            try {
                Files.deleteIfExists(artifact);
            } catch (IOException cleanupFailure) {
                log.warn("Could not remove transient VLM import cache {}", artifact, cleanupFailure);
            }
        }
    }

    private static void publishAtomically(Path pending, Path output) throws IOException {
        try {
            Files.move(
                    pending,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(pending, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Result of model validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final int numOperations;
        private final int numVariables;

        private ValidationResult(boolean valid, String errorMessage, int numOps, int numVars) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.numOperations = numOps;
            this.numVariables = numVars;
        }

        public static ValidationResult success(int numOps, int numVars) {
            return new ValidationResult(true, null, numOps, numVars);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage, 0, 0);
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage; }
        public int getNumOperations() { return numOperations; }
        public int getNumVariables() { return numVariables; }
    }
}
