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

package ai.kompile.model.importer.onnx;

import lombok.extern.slf4j.Slf4j;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.serde.SameDiffSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter;
import org.nd4j.samediff.frameworkimport.onnx.ir.OnnxIRGraph;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Service class for ONNX model operations.
 * Provides programmatic access to ONNX import functionality.
 */
@Slf4j
public class OnnxImporterService {

    private final OnnxFrameworkImporter importer;

    public OnnxImporterService() {
        this.importer = new OnnxFrameworkImporter();
    }

    /**
     * Import an ONNX model to SameDiff format.
     *
     * @param inputFile Path to the ONNX model file (.onnx)
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
        
        log.info("Importing ONNX model: {} -> {}", inputFile.getAbsolutePath(), outputFile.getAbsolutePath());
        
        // Import the model
        SameDiff sameDiff = importer.runImport(
            inputFile.getAbsolutePath(),
            dynamicVariables,
            suggestDynamicVariables,
            trackVariableChanges
        );
        SameDiffSerializer.saveAutoShard(sameDiff,outputFile,true, Collections.emptyMap());

        
        log.info("Successfully imported ONNX model with {} variables and {} operations", 
                sameDiff.variableMap().size(), sameDiff.ops().length);
        
        return sameDiff;
    }

    /**
     * Analyze an ONNX model and suggest dynamic variables.
     *
     * @param inputFile Path to the ONNX model file (.onnx)
     * @return Map of suggested dynamic variables (name -> INDArray with suggested shape)
     * @throws IOException if there's an error reading the file
     */
    public Map<String, INDArray> suggestDynamicVariables(File inputFile) throws IOException {
        log.info("Analyzing ONNX model for dynamic variables: {}", inputFile.getAbsolutePath());
        
        Map<String, INDArray> suggestions = importer.suggestDynamicVariables(inputFile.getAbsolutePath());
        
        log.info("Found {} suggested dynamic variables", suggestions.size());
        
        return suggestions;
    }

    /**
     * Validate that an ONNX model file can be loaded.
     *
     * @param inputFile Path to the ONNX model file (.onnx)
     * @return true if the file can be loaded successfully
     */
    public boolean validateModel(File inputFile) {
        try {
            // Try to load the graph - this will validate the ONNX file
            importer.loadGraph(inputFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.error("Model validation failed for {}: {}", inputFile.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * Get basic information about an ONNX model without fully importing it.
     *
     * @param inputFile Path to the ONNX model file (.onnx)
     * @return ModelInfo object containing basic model information
     * @throws IOException if there's an error reading the file
     */
    public ModelInfo getModelInfo(File inputFile) throws IOException {
        OnnxIRGraph onnxGraph = importer.loadGraph(inputFile.getAbsolutePath());
        
        List<InputInfo> inputs = new ArrayList<>();
        for (int i = 0; i < onnxGraph.getInputList().size(); i++) {
            String inputName = onnxGraph.inputAt(i);
            long[] shape = onnxGraph.shapeOfInput(inputName);
            var dtype = onnxGraph.dataTypeForVariable(inputName);
            
            inputs.add(InputInfo.builder()
                    .name(inputName)
                    .shape(shape)
                    .dataType(dtype != null ? dtype.toString() : "unknown")
                    .build());
        }
        
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < onnxGraph.getOutputList().size(); i++) {
            outputs.add(onnxGraph.outputAt(i));
        }
        
        return ModelInfo.builder()
                .inputFile(inputFile.getAbsolutePath())
                .inputs(inputs)
                .outputs(outputs)
                .build();
    }

    /**
     * Data class for input information.
     */
    @lombok.Data
    @lombok.Builder
    public static class InputInfo {
        private String name;
        private long[] shape;
        private String dataType;
    }

    /**
     * Data class for basic model information.
     */
    @lombok.Data
    @lombok.Builder
    public static class ModelInfo {
        private String inputFile;
        private List<InputInfo> inputs;
        private List<String> outputs;
    }
}
