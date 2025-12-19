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

package ai.kompile.staging.cli.archive;

import ai.kompile.staging.archive.ArchiveImporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * CLI command for importing Kompile archives (.karch).
 */
@Component
@Command(
    name = "import",
    description = "Import models from a Kompile archive (.karch) file",
    mixinStandardHelpOptions = true
)
public class ArchiveImportCommand implements Callable<Integer> {

    @Autowired
    private ArchiveImporter archiveImporter;

    @Parameters(index = "0", arity = "0..1",
            description = "Archive file to import (.karch)")
    private String archiveFile;

    @Option(names = {"-f", "--file"},
            description = "Archive file to import (.karch)")
    private String file;

    @Option(names = {"--verify"},
            description = "Verify checksums before importing",
            defaultValue = "true")
    private boolean verify;

    @Option(names = {"--force"},
            description = "Force overwrite of existing models")
    private boolean force;

    @Option(names = {"--skip-compatibility"},
            description = "Skip Kompile version compatibility check")
    private boolean skipCompatibility;

    @Option(names = {"--dry-run"},
            description = "Preview import without making changes")
    private boolean dryRun;

    @Override
    public Integer call() {
        // Determine archive file
        String inputFile = archiveFile != null ? archiveFile : file;
        if (inputFile == null) {
            System.err.println("Please specify an archive file to import");
            return 1;
        }

        Path archivePath = Paths.get(inputFile);
        if (!Files.exists(archivePath)) {
            System.err.println("Archive file not found: " + archivePath);
            return 1;
        }

        // Build options
        ArchiveImporter.ImportOptions options = ArchiveImporter.ImportOptions.builder()
                .verifyChecksums(verify)
                .forceOverwrite(force)
                .skipCompatibilityCheck(skipCompatibility)
                .build();

        if (dryRun) {
            System.out.println("DRY RUN - No changes will be made");
            System.out.println();
        }

        System.out.println("Importing archive: " + archivePath);

        // Import with progress callback
        ArchiveImporter.ImportResult result = archiveImporter.importArchive(archivePath, options, progress -> {
            printProgress(progress);
        });

        System.out.println();

        if (result.isSuccess()) {
            System.out.println("Import completed successfully!");
            System.out.println();
            System.out.println("Archive ID: " + result.getManifest().getArchiveId());
            System.out.println("Version:    " + result.getManifest().getContentVersion());
            System.out.println("Models:     " + result.getImportedCount());

            if (result.getSkippedCount() > 0) {
                System.out.println("Skipped:    " + result.getSkippedCount() +
                        " (already exist, use --force to overwrite)");
            }

            if (!result.getImportedModels().isEmpty()) {
                System.out.println();
                System.out.println("Imported models:");
                for (String modelId : result.getImportedModels()) {
                    System.out.println("  - " + modelId);
                }
            }

            if (!result.getSkippedModels().isEmpty()) {
                System.out.println();
                System.out.println("Skipped models:");
                for (String modelId : result.getSkippedModels()) {
                    System.out.println("  - " + modelId);
                }
            }

            return 0;
        } else {
            System.err.println("Import failed: " + result.getErrorMessage());
            return 1;
        }
    }

    private void printProgress(ArchiveImporter.ImportProgress progress) {
        String bar = createProgressBar(progress.getProgressPercent());
        System.out.print("\r" + progress.getPhase() + ": " + bar + " " +
                progress.getProgressPercent() + "% - " + progress.getCurrentItem() + "   ");
    }

    private String createProgressBar(int percent) {
        int width = 30;
        int filled = percent * width / 100;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                sb.append("=");
            } else if (i == filled) {
                sb.append(">");
            } else {
                sb.append(" ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
