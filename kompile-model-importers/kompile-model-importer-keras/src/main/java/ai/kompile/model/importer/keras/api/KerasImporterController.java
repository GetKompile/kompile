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

package ai.kompile.model.importer.keras.api;

import ai.kompile.model.importer.keras.KerasImporterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * REST API Controller for Keras model import operations.
 */
@RestController
@RequestMapping("/api/v1/keras")
@RequiredArgsConstructor
@Slf4j
public class KerasImporterController {

    private final KerasImporterService importerService;

    /**
     * Import a Keras model from HDF5 file.
     */
    @PostMapping(value = "/import/hdf5", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importHdf5Model(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sequential", required = false, defaultValue = "false") boolean sequential,
            @RequestParam(value = "enforceTraining", required = false, defaultValue = "true") boolean enforceTraining,
            @RequestParam(value = "inputShape", required = false) String inputShapeSpec) {

        try {
            log.info("Received Keras HDF5 model import request: {}", file.getOriginalFilename());

            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("No file provided"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null || !(filename.toLowerCase().endsWith(".h5") || filename.toLowerCase().endsWith(".hdf5"))) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("File must be a Keras HDF5 model (.h5 or .hdf5) file"));
            }

            Path tempInputFile = Files.createTempFile("keras_import_" + UUID.randomUUID(), 
                filename.toLowerCase().endsWith(".h5") ? ".h5" : ".hdf5");
            Path tempOutputFile = Files.createTempFile("keras_output_" + UUID.randomUUID(), ".zip");

            try {
                Files.copy(file.getInputStream(), tempInputFile, StandardCopyOption.REPLACE_EXISTING);

                int[] inputShape = parseInputShape(inputShapeSpec);

                Object model = importerService.importModelFromHdf5(
                    tempInputFile.toFile(),
                    tempOutputFile.toFile(),
                    sequential,
                    enforceTraining,
                    inputShape
                );

                byte[] modelBytes = Files.readAllBytes(tempOutputFile);

                String modelType;
                int layerCount;
                long parameterCount;

                if (model instanceof MultiLayerNetwork) {
                    MultiLayerNetwork mln = (MultiLayerNetwork) model;
                    modelType = "Sequential";
                    layerCount = mln.getnLayers();
                    parameterCount = mln.numParams();
                } else if (model instanceof ComputationGraph) {
                    ComputationGraph cg = (ComputationGraph) model;
                    modelType = "Functional";
                    layerCount = cg.getVertices().length;
                    parameterCount = cg.numParams();
                } else {
                    modelType = "Unknown";
                    layerCount = 0;
                    parameterCount = 0;
                }

                return ResponseEntity.ok(ImportResponse.success(
                    "Keras model imported successfully",
                    modelType,
                    layerCount,
                    parameterCount,
                    modelBytes
                ));

            } finally {
                Files.deleteIfExists(tempInputFile);
                Files.deleteIfExists(tempOutputFile);
            }

        } catch (Exception e) {
            log.error("Keras HDF5 import failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ImportResponse.error("Import failed: " + e.getMessage()));
        }
    }

    /**
     * Import a Keras model from JSON config + HDF5 weights.
     */
    @PostMapping(value = "/import/json", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importJsonModel(
            @RequestParam("config") MultipartFile configFile,
            @RequestParam("weights") MultipartFile weightsFile,
            @RequestParam(value = "sequential", required = false, defaultValue = "false") boolean sequential,
            @RequestParam(value = "enforceTraining", required = false, defaultValue = "true") boolean enforceTraining) {

        try {
            log.info("Received Keras JSON+weights import request: {} + {}", 
                configFile.getOriginalFilename(), weightsFile.getOriginalFilename());

            if (configFile.isEmpty() || weightsFile.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("Both config and weights files must be provided"));
            }

            String configFilename = configFile.getOriginalFilename();
            String weightsFilename = weightsFile.getOriginalFilename();

            if (configFilename == null || !configFilename.toLowerCase().endsWith(".json")) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("Config file must be a JSON (.json) file"));
            }

            if (weightsFilename == null || !(weightsFilename.toLowerCase().endsWith(".h5") || weightsFilename.toLowerCase().endsWith(".hdf5"))) {
                return ResponseEntity.badRequest()
                    .body(ImportResponse.error("Weights file must be a HDF5 (.h5 or .hdf5) file"));
            }

            Path tempConfigFile = Files.createTempFile("keras_config_" + UUID.randomUUID(), ".json");
            Path tempWeightsFile = Files.createTempFile("keras_weights_" + UUID.randomUUID(), 
                weightsFilename.toLowerCase().endsWith(".h5") ? ".h5" : ".hdf5");
            Path tempOutputFile = Files.createTempFile("keras_output_" + UUID.randomUUID(), ".zip");

            try {
                Files.copy(configFile.getInputStream(), tempConfigFile, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(weightsFile.getInputStream(), tempWeightsFile, StandardCopyOption.REPLACE_EXISTING);

                Object model = importerService.importModelFromJsonAndWeights(
                    tempConfigFile.toFile(),
                    tempWeightsFile.toFile(),
                    tempOutputFile.toFile(),
                    sequential,
                    enforceTraining
                );

                byte[] modelBytes = Files.readAllBytes(tempOutputFile);

                String modelType;
                int layerCount;
                long parameterCount;

                if (model instanceof MultiLayerNetwork) {
                    MultiLayerNetwork mln = (MultiLayerNetwork) model;
                    modelType = "Sequential";
                    layerCount = mln.getnLayers();
                    parameterCount = mln.numParams();
                } else if (model instanceof ComputationGraph) {
                    ComputationGraph cg = (ComputationGraph) model;
                    modelType = "Functional";
                    layerCount = cg.getVertices().length;
                    parameterCount = cg.numParams();
                } else {
                    modelType = "Unknown";
                    layerCount = 0;
                    parameterCount = 0;
                }

                return ResponseEntity.ok(ImportResponse.success(
                    "Keras model imported successfully",
                    modelType,
                    layerCount,
                    parameterCount,
                    modelBytes
                ));

            } finally {
                Files.deleteIfExists(tempConfigFile);
                Files.deleteIfExists(tempWeightsFile);
                Files.deleteIfExists(tempOutputFile);
            }

        } catch (Exception e) {
            log.error("Keras JSON+weights import failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ImportResponse.error("Import failed: " + e.getMessage()));
        }
    }

    /**
     * Analyze a Keras model and get detailed information.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisResponse> analyzeModel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "weights", required = false) MultipartFile weightsFile,
            @RequestParam(value = "sequential", required = false, defaultValue = "false") boolean sequential,
            @RequestParam(value = "inputShape", required = false) String inputShapeSpec) {

        try {
            log.info("Received Keras model analysis request: {}", file.getOriginalFilename());

            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(AnalysisResponse.error("No file provided"));
            }

            String filename = file.getOriginalFilename();
            boolean isHdf5 = filename != null && (filename.toLowerCase().endsWith(".h5") || filename.toLowerCase().endsWith(".hdf5"));
            boolean isJson = filename != null && filename.toLowerCase().endsWith(".json");

            if (!isHdf5 && !isJson) {
                return ResponseEntity.badRequest()
                    .body(AnalysisResponse.error("File must be a Keras model (.h5, .hdf5, or .json) file"));
            }

            if (isJson && (weightsFile == null || weightsFile.isEmpty())) {
                return ResponseEntity.badRequest()
                    .body(AnalysisResponse.error("Weights file is required for JSON config files"));
            }

            Path tempFile = Files.createTempFile("keras_analyze_" + UUID.randomUUID(), 
                isHdf5 ? (filename.toLowerCase().endsWith(".h5") ? ".h5" : ".hdf5") : ".json");
            Path tempWeightsPath = null;

            try {
                Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);

                if (weightsFile != null && !weightsFile.isEmpty()) {
                    tempWeightsPath = Files.createTempFile("keras_weights_" + UUID.randomUUID(), ".h5");
                    Files.copy(weightsFile.getInputStream(), tempWeightsPath, StandardCopyOption.REPLACE_EXISTING);
                }

                int[] inputShape = parseInputShape(inputShapeSpec);

                var modelInfo = importerService.getModelInfo(
                    tempFile.toFile(),
                    sequential,
                    tempWeightsPath != null ? tempWeightsPath.toFile() : null,
                    inputShape
                );

                return ResponseEntity.ok(AnalysisResponse.success(modelInfo));

            } finally {
                Files.deleteIfExists(tempFile);
                if (tempWeightsPath != null) {
                    Files.deleteIfExists(tempWeightsPath);
                }
            }

        } catch (Exception e) {
            log.error("Keras analysis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AnalysisResponse.error("Analysis failed: " + e.getMessage()));
        }
    }

    /**
     * Validate a Keras model file.
     */
    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ValidationResponse> validateModel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "weights", required = false) MultipartFile weightsFile,
            @RequestParam(value = "sequential", required = false, defaultValue = "false") boolean sequential,
            @RequestParam(value = "inputShape", required = false) String inputShapeSpec) {

        try {
            log.info("Received Keras model validation request: {}", file.getOriginalFilename());

            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(ValidationResponse.error("No file provided"));
            }

            Path tempFile = Files.createTempFile("keras_validate_" + UUID.randomUUID(), ".tmp");
            Path tempWeightsPath = null;

            try {
                Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);

                if (weightsFile != null && !weightsFile.isEmpty()) {
                    tempWeightsPath = Files.createTempFile("keras_weights_validate_" + UUID.randomUUID(), ".h5");
                    Files.copy(weightsFile.getInputStream(), tempWeightsPath, StandardCopyOption.REPLACE_EXISTING);
                }

                int[] inputShape = parseInputShape(inputShapeSpec);

                boolean isValid = importerService.validateModel(
                    tempFile.toFile(),
                    sequential,
                    tempWeightsPath != null ? tempWeightsPath.toFile() : null,
                    inputShape
                );

                return ResponseEntity.ok(ValidationResponse.of(isValid, 
                    isValid ? "Model is valid" : "Model validation failed"));

            } finally {
                Files.deleteIfExists(tempFile);
                if (tempWeightsPath != null) {
                    Files.deleteIfExists(tempWeightsPath);
                }
            }

        } catch (Exception e) {
            log.error("Keras validation failed", e);
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
            .serviceName("Keras Model Importer")
            .version("1.0.0")
            .supportedInputFormats(List.of(".h5", ".hdf5", ".json+.h5"))
            .outputFormat(".zip (DL4J)")
            .description("Import Keras models to DL4J format (MultiLayerNetwork or ComputationGraph)")
            .build());
    }

    private int[] parseInputShape(String inputShapeSpec) {
        if (inputShapeSpec == null || inputShapeSpec.trim().isEmpty()) {
            return null;
        }

        try {
            String[] parts = inputShapeSpec.split(",");
            int[] shape = new int[parts.length];
            
            for (int i = 0; i < parts.length; i++) {
                shape[i] = Integer.parseInt(parts[i].trim());
            }

            return shape;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse input shape: {}", inputShapeSpec, e);
            return null;
        }
    }

    // Response DTOs
    @lombok.Data
    @lombok.Builder
    public static class ImportResponse {
        private boolean success;
        private String message;
        private String modelType;
        private Integer layerCount;
        private Long parameterCount;
        private byte[] modelData;
        private String error;

        public static ImportResponse success(String message, String modelType, int layerCount, long parameterCount, byte[] modelData) {
            return ImportResponse.builder()
                .success(true)
                .message(message)
                .modelType(modelType)
                .layerCount(layerCount)
                .parameterCount(parameterCount)
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
        private KerasImporterService.ModelInfo modelInfo;
        private String error;

        public static AnalysisResponse success(KerasImporterService.ModelInfo modelInfo) {
            return AnalysisResponse.builder()
                .success(true)
                .modelInfo(modelInfo)
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
