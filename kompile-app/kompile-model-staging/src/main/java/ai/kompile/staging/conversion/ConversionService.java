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
import org.nd4j.autodiff.samediff.serde.SameDiffSerializer;
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter;
import org.nd4j.samediff.frameworkimport.tensorflow.importer.TensorflowFrameworkImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service for converting models from various formats to SameDiff.
 * Supports ONNX and TensorFlow formats.
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
     * @param format Original format: "onnx", "tensorflow"
     * @return Result of the conversion
     */
    public ConversionResult convert(Path inputPath, Path outputPath, String format) {
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        try {
            log.info("Converting {} model: {} -> {}", format, inputPath, outputPath);

            // Validate input
            if (!Files.exists(inputPath)) {
                return ConversionResult.failure("Input file does not exist: " + inputPath);
            }

            // Create output directory if needed
            Files.createDirectories(outputPath.getParent());

            // Import model based on format
            SameDiff sameDiff = importModel(inputPath, format.toLowerCase());

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

            // Save to SameDiff format
            SameDiffSerializer.saveAutoShard(sameDiff, outputPath.toFile(), true, Collections.emptyMap());

            if (!Files.exists(outputPath)) {
                return ConversionResult.failure("Output file was not created");
            }

            // Calculate checksum
            String checksum = calculateSha256(outputPath);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Conversion completed in {}ms", duration);

            return ConversionResult.builder()
                    .success(true)
                    .outputModelPath(outputPath)
                    .originalFormat(format)
                    .checksum(checksum)
                    .numOperations(numOps)
                    .numVariables(numVars)
                    .durationMs(duration)
                    .warnings(warnings.isEmpty() ? null : warnings.toArray(new String[0]))
                    .build();

        } catch (Exception e) {
            log.error("Conversion failed for: {}", inputPath, e);
            return ConversionResult.failure("Conversion failed: " + e.getMessage());
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
                return true;
            default:
                return false;
        }
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
                return importTensorFlow(inputPath);
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
                true,
                true
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
                true,
                true
        );
    }

    /**
     * Validate a SameDiff model file.
     */
    public ValidationResult validate(Path modelPath) {
        try {
            if (!Files.exists(modelPath)) {
                return ValidationResult.failure("Model file does not exist");
            }

            SameDiff sd = SameDiff.load(modelPath.toFile(), true);
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
