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

import ai.kompile.modelmanager.registry.*;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.staging.StagingStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * CLI command for replacing models in the registry.
 *
 * <p>This command stages a new model and replaces an existing one with the same ID.
 * It encourages versioning best practices while supporting development workflows
 * where models are frequently replaced.</p>
 *
 * <p>Examples:
 * <pre>
 *   # Replace with new model file (will warn and suggest versioning)
 *   kompile-staging replace bge-base-en-v1.5 --input=new-model.onnx
 *
 *   # Force replace without prompts
 *   kompile-staging replace bge-base-en-v1.5 --input=new-model.onnx --force
 *
 *   # Replace but keep old files (just update registry)
 *   kompile-staging replace bge-base-en-v1.5 --input=new-model.onnx --keep-old-files
 *
 *   # Use a versioned ID instead (recommended)
 *   kompile-staging replace bge-base-en-v1.5-v2 --input=new-model.onnx
 * </pre>
 */
@Component
@Command(
    name = "replace",
    description = "Replace an existing model with a new version",
    mixinStandardHelpOptions = true
)
public class ReplaceCommand implements Callable<Integer> {

    @Autowired
    private RegistryService registryService;

    @Autowired
    private StagingService stagingService;

    @Parameters(
        paramLabel = "MODEL_ID",
        description = "The model ID to replace (or create if not exists)",
        index = "0"
    )
    private String modelId;

    @Option(
        names = {"-i", "--input"},
        description = "Path to the new model file (ONNX, TensorFlow, or Keras)",
        required = true
    )
    private String inputPath;

    @Option(
        names = {"--format"},
        description = "Model format: onnx, tensorflow, keras, samediff (default: auto-detect)"
    )
    private String format;

    @Option(
        names = {"-t", "--type"},
        description = "Model type: encoder, cross_encoder (default: encoder)"
    )
    private String type = "encoder";

    @Option(
        names = {"-f", "--force"},
        description = "Replace without confirmation, even if model exists"
    )
    private boolean force;

    @Option(
        names = {"--keep-old-files"},
        description = "Keep old model files on disk (only update registry)"
    )
    private boolean keepOldFiles;

    @Option(
        names = {"--embedding-dim"},
        description = "Embedding dimension for encoder models"
    )
    private Integer embeddingDim;

    @Option(
        names = {"--description"},
        description = "Model description"
    )
    private String description;

    @Option(
        names = {"--wait"},
        description = "Wait for staging to complete (default: true)"
    )
    private boolean wait = true;

    @Option(
        names = {"--timeout"},
        description = "Timeout in seconds for staging (default: 300)"
    )
    private int timeout = 300;

    @Override
    public Integer call() {
        // Validate input file
        Path modelPath = Paths.get(inputPath);
        if (!Files.exists(modelPath)) {
            System.err.println("Error: Input file not found: " + inputPath);
            return 1;
        }

        // Auto-detect format if not specified
        if (format == null) {
            format = detectFormat(modelPath);
            if (format == null) {
                System.err.println("Error: Could not detect model format. Please specify --format");
                return 1;
            }
            System.out.println("Detected format: " + format);
        }

        // Check if model exists
        var existsInfo = registryService.checkModelExists(modelId);
        if (existsInfo.isPresent()) {
            var info = existsInfo.get();

            if (!force) {
                // Show warning and ask for confirmation
                System.out.println();
                System.out.println("WARNING: Model '" + modelId + "' already exists!");
                System.out.println();
                System.out.println("Current model details:");
                System.out.println("  Type:       " + (info.getType() != null ? info.getType().getValue() : "unknown"));
                System.out.println("  Status:     " + (info.getStatus() != null ? info.getStatus().getValue() : "unknown"));
                System.out.println("  Promoted:   " + (info.getPromotedAt() != null ? info.getPromotedAt() : "unknown"));
                System.out.println("  Path:       " + (info.getPath() != null ? info.getPath() : "unknown"));
                System.out.println();
                System.out.println("RECOMMENDATION: Use a versioned model ID instead of replacing:");
                System.out.println("  kompile-staging replace " + info.getSuggestedVersionedId() + " --input=" + inputPath);
                System.out.println();
                System.out.println("Benefits of versioning:");
                System.out.println("  1. Keep multiple versions for A/B testing");
                System.out.println("  2. Rollback to previous versions if needed");
                System.out.println("  3. Track model lineage and history");
                System.out.println();

                System.out.print("Do you want to replace the existing model? [y/N]: ");
                try (Scanner scanner = new Scanner(System.in)) {
                    String response = scanner.nextLine().trim().toLowerCase();
                    if (!response.equals("y") && !response.equals("yes")) {
                        System.out.println();
                        System.out.println("Cancelled. Consider using: " + info.getSuggestedVersionedId());
                        return 0;
                    }
                }
            } else {
                // Force mode - still show a warning
                System.out.println("Replacing existing model: " + modelId + " (--force specified)");
            }
        } else {
            System.out.println("Creating new model: " + modelId);
        }

        // Stage the model
        System.out.println();
        System.out.println("Staging model from: " + inputPath);

        boolean autoPromote = true;
        StagingModelInfo stagingInfo = stagingService.stageLocalModel(
            modelId,
            modelPath.toAbsolutePath().toString(),
            format,
            autoPromote
        );

        if (!wait) {
            System.out.println("Staging started in background. Use 'kompile-staging list --staging' to check status.");
            return 0;
        }

        // Wait for staging to complete
        System.out.println("Waiting for staging to complete...");
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout * 1000L;

        while (true) {
            StagingModelInfo currentInfo = stagingService.getStagingModel(modelId);
            if (currentInfo == null) {
                // Model may have been promoted already
                if (registryService.hasModel(modelId)) {
                    System.out.println();
                    System.out.println("Model replaced successfully: " + modelId);
                    if (existsInfo.isPresent()) {
                        System.out.println();
                        System.out.println("Note: For production use, consider versioning your models");
                        System.out.println("(e.g., " + modelId + "-v2, " + modelId + "-20251220)");
                    }
                    return 0;
                }
                break;
            }

            StagingStatus status = currentInfo.getStatus();

            // Print progress
            System.out.print("\r" + status.getValue() + " [" + currentInfo.getProgress() + "%] "
                + (currentInfo.getMessage() != null ? currentInfo.getMessage() : ""));

            if (status.isTerminal()) {
                System.out.println();

                if (status == StagingStatus.COMPLETED) {
                    System.out.println();
                    System.out.println("Model replaced successfully: " + modelId);
                    if (existsInfo.isPresent()) {
                        System.out.println();
                        System.out.println("Note: For production use, consider versioning your models");
                    }
                    return 0;
                } else if (status == StagingStatus.FAILED) {
                    System.err.println("Error: Staging failed: " + currentInfo.getMessage());
                    return 1;
                }
            }

            // Check timeout
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                System.out.println();
                System.err.println("Error: Staging timed out after " + timeout + " seconds");
                System.err.println("Use 'kompile-staging list --staging' to check status");
                return 1;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted");
                return 1;
            }
        }

        // Check final result
        if (registryService.hasModel(modelId)) {
            System.out.println("Model operation completed: " + modelId);
            return 0;
        } else {
            System.err.println("Error: Model operation failed");
            return 1;
        }
    }

    private String detectFormat(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".onnx")) {
            return "onnx";
        } else if (filename.endsWith(".pb") || filename.endsWith(".savedmodel")) {
            return "tensorflow";
        } else if (filename.endsWith(".h5") || filename.endsWith(".keras")) {
            return "keras";
        } else if (filename.endsWith(".sdz") || filename.endsWith(".fb")) {
            return "samediff";
        }
        return null;
    }
}
