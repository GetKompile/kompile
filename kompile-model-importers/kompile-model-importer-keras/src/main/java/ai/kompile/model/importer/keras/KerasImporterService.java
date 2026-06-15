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

package ai.kompile.model.importer.keras;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Service class for Keras model operations.
 * Provides programmatic access to Keras import functionality.
 */
@Slf4j
public class KerasImporterService {

    /**
     * Import a Keras model to DL4J format.
     *
     * @param inputFile Path to the Keras model file (.h5/.hdf5)
     * @param outputFile Path where the DL4J model should be saved (.zip)
     * @param sequential Whether to import as Sequential model (true) or Functional API (false)
     * @param enforceTrainingConfig Whether to enforce training configuration options
     * @param inputShape Optional input shape for models without explicit input
     * @return The imported model (either MultiLayerNetwork or ComputationGraph)
     * @throws IOException if there's an error reading/writing files
     */
    public Object importModelFromHdf5(File inputFile, File outputFile, 
                                     boolean sequential,
                                     boolean enforceTrainingConfig,
                                     int[] inputShape) throws Exception {
        
        log.info("Importing Keras model from HDF5: {} -> {}", inputFile.getAbsolutePath(), outputFile.getAbsolutePath());
        
        Object model;
        
        if (sequential) {
            MultiLayerNetwork mlnModel;
            if (inputShape != null) {
                mlnModel = KerasModelImport.importKerasSequentialModelAndWeights(
                    inputFile.getAbsolutePath(), inputShape, enforceTrainingConfig);
            } else {
                mlnModel = KerasModelImport.importKerasSequentialModelAndWeights(
                    inputFile.getAbsolutePath(), enforceTrainingConfig);
            }
            
            ModelSerializer.writeModel(mlnModel, outputFile, true);
            model = mlnModel;
            
            log.info("Successfully imported Sequential model with {} layers and {} parameters", 
                    mlnModel.getnLayers(), mlnModel.numParams());
        } else {
            ComputationGraph cgModel;
            if (inputShape != null) {
                cgModel = KerasModelImport.importKerasModelAndWeights(
                    inputFile.getAbsolutePath(), inputShape, enforceTrainingConfig);
            } else {
                cgModel = KerasModelImport.importKerasModelAndWeights(
                    inputFile.getAbsolutePath(), enforceTrainingConfig);
            }
            
            ModelSerializer.writeModel(cgModel, outputFile, true);
            model = cgModel;
            
            log.info("Successfully imported Functional model with {} vertices and {} parameters", 
                    cgModel.getVertices().length, cgModel.numParams());
        }
        
        return model;
    }

    /**
     * Import a Keras model from separate JSON config and HDF5 weights files.
     *
     * @param configFile Path to the JSON configuration file (.json)
     * @param weightsFile Path to the HDF5 weights file (.h5/.hdf5)
     * @param outputFile Path where the DL4J model should be saved (.zip)
     * @param sequential Whether to import as Sequential model (true) or Functional API (false)
     * @param enforceTrainingConfig Whether to enforce training configuration options
     * @return The imported model (either MultiLayerNetwork or ComputationGraph)
     * @throws IOException if there's an error reading/writing files
     */
    public Object importModelFromJsonAndWeights(File configFile, File weightsFile, File outputFile,
                                               boolean sequential,
                                               boolean enforceTrainingConfig) throws Exception {
        
        log.info("Importing Keras model from JSON+weights: {} + {} -> {}", 
                configFile.getAbsolutePath(), weightsFile.getAbsolutePath(), outputFile.getAbsolutePath());
        
        Object model;
        
        if (sequential) {
            MultiLayerNetwork mlnModel = KerasModelImport.importKerasSequentialModelAndWeights(
                configFile.getAbsolutePath(), weightsFile.getAbsolutePath(), enforceTrainingConfig);
            
            ModelSerializer.writeModel(mlnModel, outputFile, true);
            model = mlnModel;
            
            log.info("Successfully imported Sequential model with {} layers and {} parameters", 
                    mlnModel.getnLayers(), mlnModel.numParams());
        } else {
            ComputationGraph cgModel = KerasModelImport.importKerasModelAndWeights(
                configFile.getAbsolutePath(), weightsFile.getAbsolutePath(), enforceTrainingConfig);
            
            ModelSerializer.writeModel(cgModel, outputFile, true);
            model = cgModel;
            
            log.info("Successfully imported Functional model with {} vertices and {} parameters", 
                    cgModel.getVertices().length, cgModel.numParams());
        }
        
        return model;
    }

    /**
     * Import only the configuration from a Keras JSON file (no weights).
     *
     * @param configFile Path to the JSON configuration file (.json)
     * @param sequential Whether to import as Sequential model (true) or Functional API (false)
     * @param enforceTrainingConfig Whether to enforce training configuration options
     * @return The model configuration (either MultiLayerConfiguration or ComputationGraphConfiguration)
     * @throws IOException if there's an error reading the file
     */
    public Object importConfigurationOnly(File configFile, boolean sequential, boolean enforceTrainingConfig) throws Exception {
        log.info("Importing Keras configuration from: {}", configFile.getAbsolutePath());
        
        Object config;
        
        if (sequential) {
            MultiLayerConfiguration mlnConfig = KerasModelImport.importKerasSequentialConfiguration(
                configFile.getAbsolutePath(), enforceTrainingConfig);
            config = mlnConfig;
            
            log.info("Successfully imported Sequential configuration");
        } else {
            ComputationGraphConfiguration cgConfig = KerasModelImport.importKerasModelConfiguration(
                configFile.getAbsolutePath(), enforceTrainingConfig);
            config = cgConfig;
            
            log.info("Successfully imported Functional model configuration");
        }
        
        return config;
    }

    /**
     * Validate that a Keras model file can be loaded.
     *
     * @param inputFile Path to the Keras model file
     * @param sequential Whether to validate as Sequential model (true) or Functional API (false)
     * @param weightsFile Optional weights file for JSON+weights validation
     * @param inputShape Optional input shape
     * @return true if the file can be loaded successfully
     */
    public boolean validateModel(File inputFile, boolean sequential, File weightsFile, int[] inputShape) {
        try {
            String fileName = inputFile.getName().toLowerCase();
            boolean isHdf5 = fileName.endsWith(".h5") || fileName.endsWith(".hdf5");
            boolean isJson = fileName.endsWith(".json");

            if (sequential) {
                if (isHdf5) {
                    if (inputShape != null) {
                        KerasModelImport.importKerasSequentialModelAndWeights(
                            inputFile.getAbsolutePath(), inputShape, false);
                    } else {
                        KerasModelImport.importKerasSequentialModelAndWeights(
                            inputFile.getAbsolutePath(), false);
                    }
                } else if (isJson && weightsFile != null) {
                    KerasModelImport.importKerasSequentialModelAndWeights(
                        inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), false);
                } else {
                    return false;
                }
            } else {
                if (isHdf5) {
                    if (inputShape != null) {
                        KerasModelImport.importKerasModelAndWeights(
                            inputFile.getAbsolutePath(), inputShape, false);
                    } else {
                        KerasModelImport.importKerasModelAndWeights(
                            inputFile.getAbsolutePath(), false);
                    }
                } else if (isJson && weightsFile != null) {
                    KerasModelImport.importKerasModelAndWeights(
                        inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), false);
                } else {
                    return false;
                }
            }
            
            return true;
        } catch (Exception e) {
            log.error("Model validation failed for {}: {}", inputFile.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * Get basic information about a Keras model without fully importing it.
     *
     * @param inputFile Path to the Keras model file
     * @param sequential Whether to analyze as Sequential model (true) or Functional API (false)
     * @param weightsFile Optional weights file for JSON+weights analysis
     * @param inputShape Optional input shape
     * @return ModelInfo object containing basic model information
     * @throws IOException if there's an error reading the file
     */
    public ModelInfo getModelInfo(File inputFile, boolean sequential, File weightsFile, int[] inputShape) throws Exception {
        String fileName = inputFile.getName().toLowerCase();
        boolean isHdf5 = fileName.endsWith(".h5") || fileName.endsWith(".hdf5");
        boolean isJson = fileName.endsWith(".json");

        ModelInfo.ModelInfoBuilder builder = ModelInfo.builder()
                .inputFile(inputFile.getAbsolutePath())
                .modelType(sequential ? "Sequential" : "Functional")
                .fileFormat(isHdf5 ? "HDF5" : (isJson ? "JSON" : "Unknown"));

        if (weightsFile != null) {
            builder.weightsFile(weightsFile.getAbsolutePath());
        }

        if (inputShape != null) {
            builder.inputShape(Arrays.toString(inputShape));
        }

        try {
            if (sequential) {
                MultiLayerNetwork model;
                if (isHdf5) {
                    if (inputShape != null) {
                        model = KerasModelImport.importKerasSequentialModelAndWeights(
                            inputFile.getAbsolutePath(), inputShape, false);
                    } else {
                        model = KerasModelImport.importKerasSequentialModelAndWeights(
                            inputFile.getAbsolutePath(), false);
                    }
                } else if (isJson && weightsFile != null) {
                    model = KerasModelImport.importKerasSequentialModelAndWeights(
                        inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), false);
                } else {
                    throw new IllegalArgumentException("Unsupported file combination");
                }

                builder.layerCount(model.getnLayers())
                       .parameterCount(model.numParams());
            } else {
                ComputationGraph model;
                if (isHdf5) {
                    if (inputShape != null) {
                        model = KerasModelImport.importKerasModelAndWeights(
                            inputFile.getAbsolutePath(), inputShape, false);
                    } else {
                        model = KerasModelImport.importKerasModelAndWeights(
                            inputFile.getAbsolutePath(), false);
                    }
                } else if (isJson && weightsFile != null) {
                    model = KerasModelImport.importKerasModelAndWeights(
                        inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), false);
                } else {
                    throw new IllegalArgumentException("Unsupported file combination");
                }

                builder.layerCount(model.getVertices().length)
                       .parameterCount(model.numParams())
                       .inputs(model.getConfiguration().getNetworkInputs().toString())
                       .outputs(model.getConfiguration().getNetworkOutputs().toString());
            }
        } catch (Exception e) {
            builder.error("Failed to analyze model: " + e.getMessage());
        }

        return builder.build();
    }

    /**
     * Data class for basic model information.
     */
    @lombok.Data
    @lombok.Builder
    public static class ModelInfo {
        private String inputFile;
        private String weightsFile;
        private String modelType;
        private String fileFormat;
        private String inputShape;
        private int layerCount;
        private long parameterCount;
        private String inputs;
        private String outputs;
        private String error;
    }
}
