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

package ai.kompile.model.importer.tensorflow;

import lombok.extern.slf4j.Slf4j;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SameDiffSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.samediff.frameworkimport.tensorflow.importer.TensorflowFrameworkImporter;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Service class for TensorFlow model operations.
 * Provides programmatic access to TensorFlow import functionality.
 */
@Slf4j
public class TensorFlowImporterService {

    private final TensorflowFrameworkImporter importer;

    public TensorFlowImporterService() {
        this.importer = new TensorflowFrameworkImporter();
    }

    /**
     * Import a TensorFlow model to SameDiff format.
     *
     * @param inputFile Path to the TensorFlow GraphDef file (.pb)
     * @param outputFile Path where the SameDiff model should be saved (.sd)
     * @param dynamicVariables Map of dynamic variables (name -> INDArray)
     * @param suggestDynamicVariables Whether to automatically suggest dynamic variables
     * @param trackVariableChanges Whether to track variable changes during import
     * @return The imported SameDiff model
     * @throws IOException if there's an error reading/writing files
     */
    public SameDiff importModel(File inputFile, File outputFile,
                                Map<String, INDArray> dynamicVariables,
                                boolean suggestDynamicVariables,
                                boolean trackVariableChanges) throws IOException {
        
        log.info("Importing TensorFlow model: {} -> {}", inputFile.getAbsolutePath(), outputFile.getAbsolutePath());
        
        // Import the model
        SameDiff sameDiff = importer.runImport(
            inputFile.getAbsolutePath(),
            dynamicVariables,
            suggestDynamicVariables,
            trackVariableChanges
        );

        SameDiffSerializer.saveAutoShard(sameDiff,outputFile,true, Collections.emptyMap());

        
        log.info("Successfully imported TensorFlow model with {} variables and {} operations", 
                sameDiff.variableMap().size(), sameDiff.ops().length);
        
        return sameDiff;
    }

    /**
     * Analyze a TensorFlow model and suggest dynamic variables.
     *
     * @param inputFile Path to the TensorFlow GraphDef file (.pb)
     * @return Map of suggested dynamic variables (name -> INDArray with suggested shape)
     * @throws IOException if there's an error reading the file
     */
    public Map<String, INDArray> suggestDynamicVariables(File inputFile) throws IOException {
        log.info("Analyzing TensorFlow model for dynamic variables: {}", inputFile.getAbsolutePath());
        
        Map<String, INDArray> suggestions = importer.suggestDynamicVariables(inputFile.getAbsolutePath());
        
        log.info("Found {} suggested dynamic variables", suggestions.size());
        
        return suggestions;
    }

    /**
     * Validate that a TensorFlow model file can be loaded.
     *
     * @param inputFile Path to the TensorFlow GraphDef file (.pb)
     * @return true if the file can be loaded successfully
     */
    public boolean validateModel(File inputFile) {
        try {
            // Try to suggest dynamic variables - this will load and parse the model
            importer.suggestDynamicVariables(inputFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.error("Model validation failed for {}: {}", inputFile.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * Get basic information about a TensorFlow model without fully importing it.
     *
     * @param inputFile Path to the TensorFlow GraphDef file (.pb)
     * @return ModelInfo object containing basic model information
     * @throws IOException if there's an error reading the file
     */
    public ModelInfo getModelInfo(File inputFile) throws IOException {
        Map<String, INDArray> dynamicVars = importer.suggestDynamicVariables(inputFile.getAbsolutePath());
        
        return ModelInfo.builder()
                .inputFile(inputFile.getAbsolutePath())
                .inputCount(dynamicVars.size())
                .suggestedDynamicVariables(dynamicVars)
                .build();
    }

    /**
     * Data class for basic model information.
     */
    @lombok.Data
    @lombok.Builder
    public static class ModelInfo {
        private String inputFile;
        private int inputCount;
        private Map<String, INDArray> suggestedDynamicVariables;
    }
}
