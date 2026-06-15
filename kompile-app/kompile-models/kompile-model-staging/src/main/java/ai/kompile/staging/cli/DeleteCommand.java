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

import ai.kompile.modelmanager.registry.RegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * CLI command for deleting models from the registry.
 *
 * <p>Examples:
 * <pre>
 *   # Delete a model (prompts for confirmation)
 *   kompile-staging delete bge-base-en-v1.5
 *
 *   # Delete without confirmation
 *   kompile-staging delete --force bge-base-en-v1.5
 *
 *   # Delete only from registry, keep files
 *   kompile-staging delete --keep-files bge-base-en-v1.5
 *
 *   # Delete multiple models
 *   kompile-staging delete model1 model2 model3
 * </pre>
 */
@Component
@Command(
    name = "delete",
    description = "Delete models from the registry",
    mixinStandardHelpOptions = true
)
public class DeleteCommand implements Callable<Integer> {

    @Autowired
    private RegistryService registryService;

    @Parameters(
        paramLabel = "MODEL_ID",
        description = "One or more model IDs to delete",
        arity = "1..*"
    )
    private String[] modelIds;

    @Option(
        names = {"-f", "--force"},
        description = "Delete without confirmation prompt"
    )
    private boolean force;

    @Option(
        names = {"--keep-files"},
        description = "Only remove from registry, keep model files on disk"
    )
    private boolean keepFiles;

    @Option(
        names = {"--dry-run"},
        description = "Show what would be deleted without actually deleting"
    )
    private boolean dryRun;

    @Override
    public Integer call() {
        if (modelIds == null || modelIds.length == 0) {
            System.err.println("Error: At least one model ID is required");
            return 1;
        }

        // Check if models exist first
        int existingCount = 0;
        for (String modelId : modelIds) {
            if (registryService.hasModel(modelId)) {
                existingCount++;
            } else {
                System.out.println("Warning: Model not found: " + modelId);
            }
        }

        if (existingCount == 0) {
            System.err.println("Error: No valid models found to delete");
            return 1;
        }

        // Show what will be deleted
        System.out.println("The following models will be deleted:");
        System.out.println();
        for (String modelId : modelIds) {
            var existsInfo = registryService.checkModelExists(modelId);
            if (existsInfo.isPresent()) {
                var info = existsInfo.get();
                System.out.printf("  - %s (%s, %s)%n",
                    modelId,
                    info.getType() != null ? info.getType().getValue() : "unknown",
                    info.getPath() != null ? info.getPath() : "no path");
            }
        }
        System.out.println();

        if (keepFiles) {
            System.out.println("Note: Files will be kept on disk (--keep-files)");
        } else {
            System.out.println("Note: Model files will also be deleted from disk");
        }
        System.out.println();

        // Dry run - just show what would happen
        if (dryRun) {
            System.out.println("[DRY RUN] No changes made.");
            return 0;
        }

        // Confirmation prompt if not forced
        if (!force) {
            System.out.print("Are you sure you want to delete " + existingCount + " model(s)? [y/N]: ");
            try (Scanner scanner = new Scanner(System.in)) {
                String response = scanner.nextLine().trim().toLowerCase();
                if (!response.equals("y") && !response.equals("yes")) {
                    System.out.println("Cancelled.");
                    return 0;
                }
            }
        }

        // Delete the models
        int successCount = 0;
        int failCount = 0;
        boolean deleteFiles = !keepFiles;

        for (String modelId : modelIds) {
            if (!registryService.hasModel(modelId)) {
                continue;
            }

            System.out.print("Deleting " + modelId + "... ");
            RegistryService.DeleteResult result = registryService.deleteModelCompletely(modelId, deleteFiles);

            if (result.isSuccess()) {
                successCount++;
                if (result.isFilesDeleted()) {
                    System.out.println("done (registry + files)");
                } else if (deleteFiles) {
                    System.out.println("done (registry only, files not found)");
                } else {
                    System.out.println("done (registry only)");
                }
            } else {
                failCount++;
                System.out.println("FAILED: " + result.getMessage());
            }
        }

        System.out.println();
        System.out.println("Summary: " + successCount + " deleted, " + failCount + " failed");

        return failCount > 0 ? 1 : 0;
    }
}
