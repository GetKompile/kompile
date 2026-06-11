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

package ai.kompile.cli.main.config;

import ai.kompile.cli.common.config.ComponentFilter;
import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ConfigArchiveService;
import ai.kompile.cli.common.config.ImportMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Imports a Kompile configuration archive, restoring configs with append or override semantics.
 *
 * <p>By default, runs an interactive wizard to select which components to import
 * and the merge mode. Use {@code --all} to import everything without the wizard.
 *
 * <p>Usage:
 * <pre>
 *   kompile config import archive.zip                    # Interactive wizard
 *   kompile config import archive.zip --all              # Import everything (append)
 *   kompile config import archive.zip --all --mode=override
 *   kompile config import archive.zip --preview          # Preview only
 * </pre>
 */
@Command(name = "import",
        mixinStandardHelpOptions = true,
        description = "Import Kompile and chat provider configs from a zip archive")
public class ConfigImportCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the config archive zip file")
    private Path archivePath;

    @Option(names = {"-m", "--mode"},
            defaultValue = "append",
            description = "Import mode: 'append' (merge, keep existing) or 'override' (replace). " +
                    "Only used with --all; the wizard prompts for this. Default: ${DEFAULT-VALUE}")
    private String modeStr;

    @Option(names = {"--preview"},
            description = "Preview archive contents without importing")
    private boolean preview;

    @Option(names = {"--all", "-a"},
            description = "Import all components without interactive selection")
    private boolean all;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(archivePath)) {
            System.err.println("Error: File not found: " + archivePath);
            return 1;
        }

        // Read manifest
        ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

        // Preview header
        System.out.println("Archive: " + archivePath.getFileName());
        System.out.println("Created: " + manifest.getCreatedAt());
        System.out.println("Host:    " + manifest.getHostname());
        if (manifest.getDescription() != null) {
            System.out.println("Note:    " + manifest.getDescription());
        }
        System.out.println();

        if (preview) {
            ConfigExportCommand.printManifestSummary(manifest);
            System.out.println();
            System.out.println("(Preview only — no changes made)");
            return 0;
        }

        ComponentFilter filter;
        ImportMode effectiveMode;

        if (all) {
            filter = ComponentFilter.all();
            try {
                effectiveMode = ImportMode.valueOf(modeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Error: Invalid mode '" + modeStr + "'. Use 'append' or 'override'.");
                return 1;
            }
        } else {
            // Run the interactive wizard
            ConfigArchiveWizard.ImportWizardResult wizardResult =
                    ConfigArchiveWizard.runImportWizard(manifest);
            if (wizardResult == null) {
                System.out.println("Import cancelled.");
                return 0;
            }
            filter = wizardResult.getFilter();
            effectiveMode = wizardResult.getMode();
        }

        // Perform import
        System.out.println("Importing with mode: " + effectiveMode.name().toLowerCase() + "...");

        ConfigArchiveService.ImportResult result =
                ConfigArchiveService.importArchive(archivePath, effectiveMode, filter);

        System.out.println();
        printImportResult(result);
        return 0;
    }

    static void printImportResult(ConfigArchiveService.ImportResult result) {
        System.out.println("Import complete:");
        if (!result.getCreated().isEmpty()) {
            System.out.println("  Created:     " + result.getCreated().size() + " files");
            for (String f : result.getCreated()) {
                System.out.println("    + " + f);
            }
        }
        if (!result.getOverwritten().isEmpty()) {
            System.out.println("  Overwritten: " + result.getOverwritten().size() + " files");
            for (String f : result.getOverwritten()) {
                System.out.println("    ~ " + f);
            }
        }
        if (!result.getMerged().isEmpty()) {
            System.out.println("  Merged:      " + result.getMerged().size() + " files");
            for (String f : result.getMerged()) {
                System.out.println("    * " + f);
            }
        }
        if (!result.getSkipped().isEmpty()) {
            System.out.println("  Skipped:     " + result.getSkipped().size() + " files");
            for (String f : result.getSkipped()) {
                System.out.println("    - " + f);
            }
        }
        System.out.println();
        System.out.println("Total processed: " + result.totalProcessed() + " files");
    }
}
