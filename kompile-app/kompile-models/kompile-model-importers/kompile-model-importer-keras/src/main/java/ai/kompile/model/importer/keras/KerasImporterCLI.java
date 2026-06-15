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
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Standalone CLI tool for converting Keras models to DL4J format.
 * This tool is designed to be used as middleware for model conversion without
 * embedding the heavy Keras import framework in the main application.
 */
@CommandLine.Command(
    name = "keras-importer",
    description = "Convert Keras models (.h5, .hdf5, .json+.h5) to DL4J format",
    mixinStandardHelpOptions = true,
    version = "1.0.0"
)
@Slf4j
public class KerasImporterCLI implements Callable<Integer> {

    @CommandLine.Parameters(
        index = "0", 
        description = "Input Keras model file (.h5/.hdf5) or JSON config file (.json)"
    )
    private File inputFile;

    @CommandLine.Parameters(
        index = "1", 
        description = "Output DL4J model file (.zip)"
    )
    private File outputFile;

    @CommandLine.Option(
        names = {"-w", "--weights"},
        description = "HDF5 weights file (required when input is JSON config)"
    )
    private File weightsFile;

    @CommandLine.Option(
        names = {"-s", "--sequential"},
        description = "Import as Sequential model (MultiLayerNetwork) instead of Functional API (ComputationGraph)"
    )
    private boolean sequential = false;

    @CommandLine.Option(
        names = {"-e", "--enforce-training"},
        description = "Enforce training configuration options"
    )
    private boolean enforceTrainingConfig = true;

    @CommandLine.Option(
        names = {"--input-shape"},
        description = "Input shape for models without explicit input (format: d1,d2,d3, e.g., 224,224,3)",
        paramLabel = "SHAPE"
    )
    private String inputShapeSpec;

    @CommandLine.Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose logging"
    )
    private boolean verbose = false;

    @CommandLine.Option(
        names = {"--dry-run"},
        description = "Parse and validate inputs without performing conversion"
    )
    private boolean dryRun = false;

    @CommandLine.Option(
        names = {"--force"},
        description = "Overwrite output file if it exists"
    )
    private boolean force = false;

    @CommandLine.Option(
        names = {"--model-info"},
        description = "Display detailed model information after import"
    )
    private boolean showModelInfo = false;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KerasImporterCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (verbose) {
            // Enable debug logging
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        }

        log.info("Keras Model Importer CLI v1.0.0");
        log.info("Input file: {}", inputFile.getAbsolutePath());
        log.info("Output file: {}", outputFile.getAbsolutePath());

        // Validate input file
        if (!inputFile.exists()) {
            log.error("Input file does not exist: {}", inputFile.getAbsolutePath());
            return 1;
        }

        if (!inputFile.canRead()) {
            log.error("Cannot read input file: {}", inputFile.getAbsolutePath());
            return 1;
        }

        // Determine input file type
        String fileName = inputFile.getName().toLowerCase();
        boolean isHdf5 = fileName.endsWith(".h5") || fileName.endsWith(".hdf5");
        boolean isJson = fileName.endsWith(".json");

        if (!isHdf5 && !isJson) {
            log.error("Unsupported file format. Expected .h5, .hdf5, or .json file: {}", inputFile.getName());
            return 1;
        }

        // Validate weights file for JSON input
        if (isJson) {
            if (weightsFile == null) {
                log.error("Weights file is required when input is JSON config file. Use -w/--weights option.");
                return 1;
            }
            if (!weightsFile.exists()) {
                log.error("Weights file does not exist: {}", weightsFile.getAbsolutePath());
                return 1;
            }
        }

        // Check output file
        if (outputFile.exists() && !force) {
            log.error("Output file already exists. Use --force to overwrite: {}", outputFile.getAbsolutePath());
            return 1;
        }

        // Create output directory if needed
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                log.error("Failed to create output directory: {}", outputDir.getAbsolutePath());
                return 1;
            }
        }

        try {
            // Parse input shape if provided
            int[] inputShape = parseInputShape();
            
            if (dryRun) {
                log.info("Dry run mode - validating inputs only");
                validateInputs(isHdf5, inputShape);
                log.info("Validation successful - conversion would proceed");
                return 0;
            }

            // Perform the actual conversion
            return performConversion(isHdf5, inputShape);

        } catch (Exception e) {
            log.error("Conversion failed", e);
            return 1;
        }
    }

    private int[] parseInputShape() {
        if (inputShapeSpec == null || inputShapeSpec.trim().isEmpty()) {
            return null;
        }

        try {
            String[] parts = inputShapeSpec.split(",");
            int[] shape = new int[parts.length];
            
            for (int i = 0; i < parts.length; i++) {
                shape[i] = Integer.parseInt(parts[i].trim());
            }

            log.info("Input shape specified: {}", Arrays.toString(shape));
            return shape;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid input shape: " + inputShapeSpec + 
                ". Expected format: d1,d2,d3 (e.g., 224,224,3)");
        }
    }

    private void validateInputs(boolean isHdf5, int[] inputShape) throws IOException {
        log.info("Validating Keras model structure...");
        
        try {
            if (sequential) {
                // Try to load as sequential model for validation
                if (isHdf5) {
                    if (inputShape != null) {
                        KerasModelImport.importKerasSequentialModelAndWeights(
                            inputFile.getAbsolutePath(), inputShape, false);
                    } else {
                        KerasModelImport.importKerasSequentialModelAndWeights(
                            inputFile.getAbsolutePath(), false);
                    }
                } else {
                    KerasModelImport.importKerasSequentialModelAndWeights(
                        inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), false);
                }
            } else {
                // Try to load as functional model for validation
                if (isHdf5) {
                    if (inputShape != null) {
                        KerasModelImport.importKerasModelAndWeights(
                            inputFile.getAbsolutePath(), inputShape, false);
                    } else {
                        KerasModelImport.importKerasModelAndWeights(
                            inputFile.getAbsolutePath(), false);
                    }
                } else {
                    KerasModelImport.importKerasModelAndWeights(
                        inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), false);
                }
            }
            
            log.info("Input validation completed successfully");
        } catch (Exception e) {
            log.error("Validation failed: {}", e.getMessage());
            throw new IOException("Model validation failed", e);
        }
    }

    private int performConversion(boolean isHdf5, int[] inputShape) throws IOException {
        log.info("Starting Keras to DL4J conversion...");
        log.info("Model type: {}", sequential ? "Sequential (MultiLayerNetwork)" : "Functional API (ComputationGraph)");
        
        long startTime = System.currentTimeMillis();
        
        try {
            if (sequential) {
                return convertSequentialModel(isHdf5, inputShape);
            } else {
                return convertFunctionalModel(isHdf5, inputShape);
            }
        } catch (Exception e) {
            log.error("Model conversion failed", e);
            throw new IOException("Conversion failed", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            log.info("Conversion process completed in {} ms", duration);
        }
    }

    private int convertSequentialModel(boolean isHdf5, int[] inputShape) throws Exception {
        MultiLayerNetwork model;
        
        if (isHdf5) {
            if (inputShape != null) {
                model = KerasModelImport.importKerasSequentialModelAndWeights(
                    inputFile.getAbsolutePath(), inputShape, enforceTrainingConfig);
            } else {
                model = KerasModelImport.importKerasSequentialModelAndWeights(
                    inputFile.getAbsolutePath(), enforceTrainingConfig);
            }
        } else {
            model = KerasModelImport.importKerasSequentialModelAndWeights(
                inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), enforceTrainingConfig);
        }

        // Save the converted model
        log.info("Saving MultiLayerNetwork to: {}", outputFile.getAbsolutePath());
        ModelSerializer.writeModel(model, outputFile, true);

        log.info("Sequential model conversion completed successfully");
        if (showModelInfo) {
            displaySequentialModelInfo(model);
        }

        return 0;
    }

    private int convertFunctionalModel(boolean isHdf5, int[] inputShape) throws Exception {
        ComputationGraph model;
        
        if (isHdf5) {
            if (inputShape != null) {
                model = KerasModelImport.importKerasModelAndWeights(
                    inputFile.getAbsolutePath(), inputShape, enforceTrainingConfig);
            } else {
                model = KerasModelImport.importKerasModelAndWeights(
                    inputFile.getAbsolutePath(), enforceTrainingConfig);
            }
        } else {
            model = KerasModelImport.importKerasModelAndWeights(
                inputFile.getAbsolutePath(), weightsFile.getAbsolutePath(), enforceTrainingConfig);
        }

        // Save the converted model
        log.info("Saving ComputationGraph to: {}", outputFile.getAbsolutePath());
        ModelSerializer.writeModel(model, outputFile, true);

        log.info("Functional model conversion completed successfully");
        if (showModelInfo) {
            displayFunctionalModelInfo(model);
        }

        return 0;
    }

    private void displaySequentialModelInfo(MultiLayerNetwork model) {
        log.info("Sequential Model Summary:");
        log.info("  Layers: {}", model.getnLayers());
        log.info("  Parameters: {}", model.numParams());
        log.info("  Configuration: {}", model.getLayerWiseConfigurations().toJson());
    }

    private void displayFunctionalModelInfo(ComputationGraph model) {
        log.info("Functional Model Summary:");
        log.info("  Vertices: {}", model.getVertices().length);
        log.info("  Parameters: {}", model.numParams());
        log.info("  Inputs: {}",model.getConfiguration().getNetworkInputs());
        log.info("  Outputs: {}", model.getConfiguration().getNetworkOutputs());
    }
}
