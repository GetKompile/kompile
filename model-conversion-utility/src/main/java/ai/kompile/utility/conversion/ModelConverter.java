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

package ai.kompile.utility.conversion;

import ai.kompile.model.importer.onnx.OnnxImporterService;
import ai.kompile.utility.conversion.config.ModelDefinition;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for converting ONNX models to SameDiff format.
 */
public class ModelConverter {
    private static final Logger logger = LoggerFactory.getLogger(ModelConverter.class);
    
    private final OnnxImporterService onnxImporter;
    private final Path workingDirectory;

    public ModelConverter(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.onnxImporter = new OnnxImporterService();
    }

    /**
     * Convert an ONNX model to SameDiff format.
     *
     * @param modelDefinition The model definition containing conversion parameters
     * @param onnxModelPath Path to the ONNX model file
     * @param outputPath Path where the SameDiff model should be saved
     * @return ConversionResult containing success status and metadata
     */
    public ConversionResult convertModel(ModelDefinition modelDefinition, Path onnxModelPath, Path outputPath) {
        logger.info("Converting ONNX model {} to SameDiff format at {}", 
                   onnxModelPath.getFileName(), outputPath.getFileName());
        
        try {
            // Validate input file
            File inputFile = onnxModelPath.toFile();
            if (!inputFile.exists() || !inputFile.canRead()) {
                return ConversionResult.failure("Input ONNX file not found or not readable: " + onnxModelPath);
            }

            // Prepare conversion parameters
            Map<String, INDArray> dynamicVariables = prepareDynamicVariables(modelDefinition);
            boolean suggestDynamic = modelDefinition.getConversionParameters() != null && 
                                   modelDefinition.getConversionParameters().isSuggestDynamicVariables();
            boolean trackChanges = modelDefinition.getConversionParameters() != null && 
                                 modelDefinition.getConversionParameters().isTrackVariableChanges();

            // Perform the conversion
            long startTime = System.currentTimeMillis();
            
            var sameDiff = onnxImporter.importModel(
                inputFile, 
                outputPath.toFile(),
                dynamicVariables,
                suggestDynamic,
                trackChanges
            );
            
            long conversionTime = System.currentTimeMillis() - startTime;
            
            // Validate the output
            if (!outputPath.toFile().exists()) {
                return ConversionResult.failure("Output SameDiff file was not created: " + outputPath);
            }
            
            long outputSize = outputPath.toFile().length();
            
            logger.info("Successfully converted {} to SameDiff format in {} ms. Output size: {} bytes",
                       modelDefinition.getModelId(), conversionTime, outputSize);
            
            return ConversionResult.success(conversionTime, outputSize, 
                                          sameDiff.variableMap().size(), 
                                          sameDiff.ops().length);
            
        } catch (Exception e) {
            logger.error("Failed to convert model {}", modelDefinition.getModelId(), e);
            return ConversionResult.failure("Conversion failed: " + e.getMessage());
        }
    }

    /**
     * Validate that an ONNX model can be converted.
     */
    public ValidationResult validateModel(Path onnxModelPath) {
        logger.info("Validating ONNX model: {}", onnxModelPath.getFileName());
        
        try {
            File inputFile = onnxModelPath.toFile();
            if (!inputFile.exists() || !inputFile.canRead()) {
                return ValidationResult.failure("Input file not found or not readable");
            }
            
            // Use the ONNX importer to validate the model
            boolean isValid = onnxImporter.validateModel(inputFile);
            if (!isValid) {
                return ValidationResult.failure("Model validation failed");
            }
            
            // Get model information
            var modelInfo = onnxImporter.getModelInfo(inputFile);
            
            logger.info("Model validation successful. Inputs: {}, Outputs: {}", 
                       modelInfo.getInputs().size(), modelInfo.getOutputs().size());
            
            return ValidationResult.success(modelInfo.getInputs().size(), 
                                          modelInfo.getOutputs().size(),
                                          modelInfo);
            
        } catch (Exception e) {
            logger.error("Model validation failed for {}", onnxModelPath.getFileName(), e);
            return ValidationResult.failure("Validation error: " + e.getMessage());
        }
    }

    /**
     * Get detailed information about an ONNX model.
     */
    public OnnxImporterService.ModelInfo getModelInfo(Path onnxModelPath) throws IOException {
        return onnxImporter.getModelInfo(onnxModelPath.toFile());
    }

    private Map<String, INDArray> prepareDynamicVariables(ModelDefinition modelDefinition) {
        Map<String, INDArray> dynamicVariables = new HashMap<>();
        
        if (modelDefinition.getConversionParameters() != null && 
            modelDefinition.getConversionParameters().getDynamicVariables() != null) {
            
            Map<String, List<Long>> dynamicVarSpecs = modelDefinition.getConversionParameters().getDynamicVariables();
            
            for (Map.Entry<String, List<Long>> entry : dynamicVarSpecs.entrySet()) {
                String varName = entry.getKey();
                List<Long> shape = entry.getValue();
                
                // Convert List<Long> to long array
                long[] shapeArray = shape.stream().mapToLong(Long::longValue).toArray();
                
                // Replace negative dimensions with 1 (common convention for dynamic dims)
                for (int i = 0; i < shapeArray.length; i++) {
                    if (shapeArray[i] < 0) {
                        shapeArray[i] = 1;
                    }
                }
                
                INDArray array = Nd4j.ones(shapeArray);
                dynamicVariables.put(varName, array);
                
                logger.debug("Prepared dynamic variable '{}' with shape: {}", varName, shapeArray);
            }
        }
        
        return dynamicVariables;
    }

    /**
     * Result of a model conversion operation.
     */
    public static class ConversionResult {
        private final boolean success;
        private final String errorMessage;
        private final long conversionTimeMs;
        private final long outputSizeBytes;
        private final int variableCount;
        private final int operationCount;

        private ConversionResult(boolean success, String errorMessage, long conversionTimeMs, 
                               long outputSizeBytes, int variableCount, int operationCount) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.conversionTimeMs = conversionTimeMs;
            this.outputSizeBytes = outputSizeBytes;
            this.variableCount = variableCount;
            this.operationCount = operationCount;
        }

        public static ConversionResult success(long conversionTimeMs, long outputSizeBytes, 
                                             int variableCount, int operationCount) {
            return new ConversionResult(true, null, conversionTimeMs, outputSizeBytes, 
                                      variableCount, operationCount);
        }

        public static ConversionResult failure(String errorMessage) {
            return new ConversionResult(false, errorMessage, 0, 0, 0, 0);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public long getConversionTimeMs() { return conversionTimeMs; }
        public long getOutputSizeBytes() { return outputSizeBytes; }
        public int getVariableCount() { return variableCount; }
        public int getOperationCount() { return operationCount; }
    }

    /**
     * Result of a model validation operation.
     */
    public static class ValidationResult {
        private final boolean success;
        private final String errorMessage;
        private final int inputCount;
        private final int outputCount;
        private final OnnxImporterService.ModelInfo modelInfo;

        private ValidationResult(boolean success, String errorMessage, int inputCount, 
                               int outputCount, OnnxImporterService.ModelInfo modelInfo) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.inputCount = inputCount;
            this.outputCount = outputCount;
            this.modelInfo = modelInfo;
        }

        public static ValidationResult success(int inputCount, int outputCount, 
                                             OnnxImporterService.ModelInfo modelInfo) {
            return new ValidationResult(true, null, inputCount, outputCount, modelInfo);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage, 0, 0, null);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getInputCount() { return inputCount; }
        public int getOutputCount() { return outputCount; }
        public OnnxImporterService.ModelInfo getModelInfo() { return modelInfo; }
    }
}
