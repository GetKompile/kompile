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
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.samediff.frameworkimport.tensorflow.importer.TensorflowFrameworkImporter;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Standalone CLI tool for converting TensorFlow models to SameDiff format.
 * This tool is designed to be used as middleware for model conversion without
 * embedding the heavy TensorFlow import framework in the main application.
 */
@CommandLine.Command(
    name = "tensorflow-importer",
    description = "Convert TensorFlow models (.pb files) to SameDiff format",
    mixinStandardHelpOptions = true,
    version = "1.0.0"
)
@Slf4j
public class TensorFlowImporterCLI implements Callable<Integer> {

    @CommandLine.Parameters(
        index = "0", 
        description = "Input TensorFlow GraphDef file (.pb)"
    )
    private File inputFile;

    @CommandLine.Parameters(
        index = "1", 
        description = "Output SameDiff file (.sd)"
    )
    private File outputFile;

    @CommandLine.Option(
        names = {"-s", "--suggest-dynamic"},
        description = "Automatically suggest dynamic variables for placeholders"
    )
    private boolean suggestDynamicVariables = false;

    @CommandLine.Option(
        names = {"-t", "--track-changes"},
        description = "Track variable changes during import"
    )
    private boolean trackVariableChanges = false;

    @CommandLine.Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose logging"
    )
    private boolean verbose = false;

    @CommandLine.Option(
        names = {"--dynamic-var"},
        description = "Specify dynamic variables (format: name=shape, e.g., input=1,224,224,3)",
        split = ",",
        paramLabel = "NAME=SHAPE"
    )
    private String[] dynamicVariableSpecs = {};

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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TensorFlowImporterCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (verbose) {
            // Enable debug logging
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        }

        log.info("TensorFlow Model Importer CLI v1.0.0");
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
            // Parse dynamic variables
            Map<String, INDArray> dynamicVariables = parseDynamicVariables();
            
            if (dryRun) {
                log.info("Dry run mode - validating inputs only");
                validateInputs(dynamicVariables);
                log.info("Validation successful - conversion would proceed");
                return 0;
            }

            // Perform the actual conversion
            return performConversion(dynamicVariables);

        } catch (Exception e) {
            log.error("Conversion failed", e);
            return 1;
        }
    }

    private Map<String, INDArray> parseDynamicVariables() {
        Map<String, INDArray> dynamicVariables = new HashMap<>();

        for (String spec : dynamicVariableSpecs) {
            String[] parts = spec.split("=");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid dynamic variable spec: " + spec + 
                    ". Expected format: name=shape (e.g., input=1,224,224,3)");
            }

            String name = parts[0].trim();
            String shapeStr = parts[1].trim();

            // Parse shape
            String[] shapeParts = shapeStr.split(",");
            long[] shape = new long[shapeParts.length];
            
            for (int i = 0; i < shapeParts.length; i++) {
                try {
                    shape[i] = Long.parseLong(shapeParts[i].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid shape dimension in '" + spec + "': " + shapeParts[i]);
                }
            }

            // Create a dummy array with ones for the specified shape
            INDArray array = Nd4j.ones(shape);
            dynamicVariables.put(name, array);
            
            log.info("Dynamic variable '{}' configured with shape: {}", name, java.util.Arrays.toString(shape));
        }

        return dynamicVariables;
    }

    private void validateInputs(Map<String, INDArray> dynamicVariables) throws IOException {
        log.info("Validating TensorFlow model structure...");
        
        TensorflowFrameworkImporter importer = new TensorflowFrameworkImporter();
        
        if (suggestDynamicVariables) {
            log.info("Analyzing model for dynamic variable suggestions...");
            Map<String, INDArray> suggestedVars = importer.suggestDynamicVariables(inputFile.getAbsolutePath());
            
            log.info("Suggested dynamic variables:");
            for (Map.Entry<String, INDArray> entry : suggestedVars.entrySet()) {
                log.info("  {}: shape={}", entry.getKey(), java.util.Arrays.toString(entry.getValue().shape()));
            }
        }

        log.info("Input validation completed successfully");
    }

    private int performConversion(Map<String, INDArray> dynamicVariables) throws IOException {
        log.info("Starting TensorFlow to SameDiff conversion...");
        
        long startTime = System.currentTimeMillis();
        
        TensorflowFrameworkImporter importer = new TensorflowFrameworkImporter();
        
        // Import the model
        SameDiff sameDiff = importer.runImport(
            inputFile.getAbsolutePath(),
            dynamicVariables,
            suggestDynamicVariables,
            trackVariableChanges
        );

        // Save the converted model
        log.info("Saving SameDiff model to: {}", outputFile.getAbsolutePath());
        SameDiffSerializer.saveAutoShard(sameDiff,outputFile,true, Collections.emptyMap());

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        log.info("Conversion completed successfully in {} ms", duration);
        log.info("Model summary:");
        log.info("  Variables: {}", sameDiff.variableMap().size());
        log.info("  Operations: {}", sameDiff.ops().length);
        log.info("  Inputs: {}", sameDiff.inputs());
        log.info("  Outputs: {}", sameDiff.outputs());

        return 0;
    }
}
