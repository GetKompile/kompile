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

package ai.kompile.model.importer.onnx.api;

import ai.kompile.model.onnx.OnnxFrameworkImporterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API Controller for ONNX model import operations.
 */
@RestController
@RequestMapping("/api/v1/onnx")
@RequiredArgsConstructor
@Slf4j
public class OnnxImporterController {

    private final OnnxFrameworkImporterService importerService;

    /**
     * Import an ONNX model from uploaded file.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importModel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "suggestDynamic", required = false, defaultValue = "false") boolean suggestDynamic,
            @RequestParam(value = "trackChanges", required = false, defaultValue = "false") boolean trackChanges,
            @RequestParam(value = "dynamicVars", required = false) List<String> dynamicVarSpecs) {

        try {
            log.info("Received ONNX model import request: {}", file.getOriginalFilename());

            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("No file provided"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".onnx")) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("File must be an ONNX model (.onnx) file"));
            }

            Path tempInputFile = Files.createTempFile("onnx_import_" + UUID.randomUUID(), ".onnx");
            Path tempOutputFile = Files.createTempFile("onnx_output_" + UUID.randomUUID(), ".sd");

            try {
                Files.copy(file.getInputStream(), tempInputFile, StandardCopyOption.REPLACE_EXISTING);

                Map<String, INDArray> dynamicVariables = parseDynamicVariables(dynamicVarSpecs);

                var sameDiff = importerService.importModel(
                    tempInputFile.toFile(),
                    tempOutputFile.toFile(),
                    dynamicVariables,
                    suggestDynamic,
                    trackChanges
                );

                byte[] modelBytes = Files.readAllBytes(tempOutputFile);

                return ResponseEntity.ok(ImportResponse.success(
                    "Model imported successfully",
                    sameDiff.variableMap().size(),
                    sameDiff.ops().length,
                    modelBytes
                ));

            } finally {
                Files.deleteIfExists(tempInputFile);
                Files.deleteIfExists(tempOutputFile);
            }

        } catch (Exception e) {
            log.error("ONNX import failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ImportResponse.error("Import failed: " + e.getMessage()));
        }
    }

    /**
     * Analyze an ONNX model and get detailed information.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisResponse> analyzeModel(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Received ONNX model analysis request: {}", file.getOriginalFilename());

            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(AnalysisResponse.error("No file provided"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".onnx")) {
                return ResponseEntity.badRequest()
                    .body(AnalysisResponse.error("File must be an ONNX model (.onnx) file"));
            }

            Path tempFile = Files.createTempFile("onnx_analyze_" + UUID.randomUUID(), ".onnx");

            try {
                Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);

                var modelInfo = importerService.getModelInfo(tempFile.toFile());
                Map<String, INDArray> suggestions = importerService.suggestDynamicVariables(tempFile.toFile());

                Map<String, long[]> suggestionShapes = new HashMap<>();
                for (Map.Entry<String, INDArray> entry : suggestions.entrySet()) {
                    suggestionShapes.put(entry.getKey(), entry.getValue().shape());
                }

                return ResponseEntity.ok(AnalysisResponse.success(
                    modelInfo.getInputFile(),
                    modelInfo.getInputs(),
                    modelInfo.getOutputs(),
                    suggestionShapes
                ));

            } finally {
                Files.deleteIfExists(tempFile);
            }

        } catch (Exception e) {
            log.error("ONNX analysis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AnalysisResponse.error("Analysis failed: " + e.getMessage()));
        }
    }

    /**
     * Validate an ONNX model file.
     */
    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ValidationResponse> validateModel(@RequestParam("file") MultipartFile file) {
        try {
            log.info("Received ONNX model validation request: {}", file.getOriginalFilename());

            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(ValidationResponse.error("No file provided"));
            }

            Path tempFile = Files.createTempFile("onnx_validate_" + UUID.randomUUID(), ".onnx");

            try {
                Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);

                boolean isValid = importerService.validateModel(tempFile.toFile());

                return ResponseEntity.ok(ValidationResponse.of(isValid, 
                    isValid ? "Model is valid" : "Model validation failed"));

            } finally {
                Files.deleteIfExists(tempFile);
            }

        } catch (Exception e) {
            log.error("ONNX validation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ValidationResponse.error("Validation failed: " + e.getMessage()));
        }
    }

    /**
     * Get service information.
     */
    @GetMapping("/info")
    public ResponseEntity<ServiceInfo> getServiceInfo() {
        return ResponseEntity.ok(ServiceInfo.builder()
            .serviceName("ONNX Model Importer")
            .version("1.0.0")
            .supportedInputFormats(List.of(".onnx"))
            .outputFormat(".sd (SameDiff)")
            .description("Import ONNX models to SameDiff format")
            .build());
    }

    private Map<String, INDArray> parseDynamicVariables(List<String> dynamicVarSpecs) {
        Map<String, INDArray> dynamicVariables = new HashMap<>();

        if (dynamicVarSpecs == null) {
            return dynamicVariables;
        }

        for (String spec : dynamicVarSpecs) {
            try {
                String[] parts = spec.split("=");
                if (parts.length != 2) {
                    continue;
                }

                String name = parts[0].trim();
                String shapeStr = parts[1].trim();

                String[] shapeParts = shapeStr.split(",");
                long[] shape = new long[shapeParts.length];

                for (int i = 0; i < shapeParts.length; i++) {
                    long dim = Long.parseLong(shapeParts[i].trim());
                    // Handle dynamic dimensions (negative values typically mean dynamic)
                    shape[i] = dim < 0 ? 1 : dim;
                }

                INDArray array = Nd4j.ones(shape);
                dynamicVariables.put(name, array);

            } catch (Exception e) {
                log.warn("Failed to parse dynamic variable spec: {}", spec, e);
            }
        }

        return dynamicVariables;
    }

    // Response DTOs
    @lombok.Data
    @lombok.Builder
    public static class ImportResponse {
        private boolean success;
        private String message;
        private Integer variableCount;
        private Integer operationCount;
        private byte[] modelData;
        private String error;

        public static ImportResponse success(String message, int variableCount, int operationCount, byte[] modelData) {
            return ImportResponse.builder()
                .success(true)
                .message(message)
                .variableCount(variableCount)
                .operationCount(operationCount)
                .modelData(modelData)
                .build();
        }

        public static ImportResponse error(String error) {
            return ImportResponse.builder()
                .success(false)
                .error(error)
                .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class AnalysisResponse {
        private boolean success;
        private String inputFile;
        private List<OnnxFrameworkImporterService.InputInfo> inputs;
        private List<String> outputs;
        private Map<String, long[]> suggestedDynamicVariables;
        private String error;

        public static AnalysisResponse success(String inputFile, List<OnnxFrameworkImporterService.InputInfo> inputs,
                                             List<String> outputs, Map<String, long[]> suggestions) {
            return AnalysisResponse.builder()
                .success(true)
                .inputFile(inputFile)
                .inputs(inputs)
                .outputs(outputs)
                .suggestedDynamicVariables(suggestions)
                .build();
        }

        public static AnalysisResponse error(String error) {
            return AnalysisResponse.builder()
                .success(false)
                .error(error)
                .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class ValidationResponse {
        private boolean valid;
        private String message;
        private String error;

        public static ValidationResponse of(boolean valid, String message) {
            return ValidationResponse.builder()
                .valid(valid)
                .message(message)
                .build();
        }

        public static ValidationResponse error(String error) {
            return ValidationResponse.builder()
                .valid(false)
                .error(error)
                .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class ServiceInfo {
        private String serviceName;
        private String version;
        private List<String> supportedInputFormats;
        private String outputFormat;
        private String description;
    }
}
