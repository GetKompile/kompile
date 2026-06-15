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

package ai.kompile.staging.cli;

import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * CLI command for converting models to SameDiff format.
 */
@Component
@Command(
    name = "convert",
    description = "Convert a model to SameDiff format",
    mixinStandardHelpOptions = true
)
public class ConvertCommand implements Callable<Integer> {

    @Autowired
    private ConversionService conversionService;

    @Option(names = {"-i", "--input"}, required = true,
            description = "Input model file (ONNX, TensorFlow, Keras)")
    private String input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Output SameDiff file (.sdz)")
    private String output;

    @Option(names = {"-f", "--format"},
            description = "Input format: onnx, tensorflow, keras (auto-detected if not specified)")
    private String format;

    @Override
    public Integer call() {
        Path inputPath = Paths.get(input);
        Path outputPath = Paths.get(output);

        // Auto-detect format if not specified
        String detectedFormat = format;
        if (detectedFormat == null) {
            String fileName = inputPath.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".onnx")) {
                detectedFormat = "onnx";
            } else if (fileName.endsWith(".pb")) {
                detectedFormat = "tensorflow";
            } else if (fileName.endsWith(".h5") || fileName.endsWith(".keras")) {
                detectedFormat = "keras";
            } else {
                System.err.println("Cannot auto-detect format. Please specify --format");
                return 1;
            }
        }

        System.out.println("Converting model: " + inputPath);
        System.out.println("Format: " + detectedFormat);
        System.out.println("Output: " + outputPath);

        ConversionResult result = conversionService.convert(inputPath, outputPath, detectedFormat);

        if (result.isSuccess()) {
            System.out.println("Conversion completed successfully!");
            System.out.println("Output: " + result.getOutputModelPath());
            System.out.println("Operations: " + result.getNumOperations());
            System.out.println("Variables: " + result.getNumVariables());
            System.out.println("Checksum: " + result.getChecksum());
            System.out.println("Duration: " + result.getDurationMs() + "ms");

            if (result.getWarnings() != null && result.getWarnings().length > 0) {
                System.out.println("Warnings:");
                for (String warning : result.getWarnings()) {
                    System.out.println("  - " + warning);
                }
            }
            return 0;
        } else {
            System.err.println("Conversion failed: " + result.getErrorMessage());
            return 1;
        }
    }
}
