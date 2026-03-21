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

import ai.kompile.staging.export.BundleManifest;
import ai.kompile.staging.export.ImportService;
import ai.kompile.staging.export.ImportService.ImportResult;
import ai.kompile.modelmanager.registry.ModelEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * CLI command for importing model bundles.
 */
@Component
@Command(
    name = "import",
    description = "Import a model bundle (for air-gapped environments)",
    mixinStandardHelpOptions = true
)
public class ImportCommand implements Callable<Integer> {

    @Autowired
    private ImportService importService;

    @Parameters(index = "0", arity = "0..1",
            description = "Bundle file to import (.tar.gz)")
    private String bundleFile;

    @Option(names = {"-b", "--bundle"},
            description = "Bundle file to import (.tar.gz)")
    private String bundle;

    @Option(names = {"--verify"},
            description = "Verify checksums during import", defaultValue = "true")
    private boolean verifyChecksums;

    @Option(names = {"--preview"},
            description = "Preview bundle contents without importing")
    private boolean preview;

    @Override
    public Integer call() {
        String file = bundleFile != null ? bundleFile : bundle;
        if (file == null) {
            System.err.println("Please specify a bundle file");
            return 1;
        }

        Path bundlePath = Paths.get(file);

        if (preview) {
            return previewBundle(bundlePath);
        } else {
            return importBundle(bundlePath);
        }
    }

    private Integer previewBundle(Path bundlePath) {
        System.out.println("Previewing bundle: " + bundlePath);
        System.out.println();

        BundleManifest manifest = importService.previewBundle(bundlePath);

        if (manifest == null) {
            System.err.println("Failed to read bundle manifest");
            return 1;
        }

        System.out.println("=== Bundle Manifest ===");
        System.out.println("Version: " + manifest.getVersion());
        System.out.println("Created: " + manifest.getCreatedAt());
        System.out.println("Description: " + manifest.getDescription());
        System.out.println("Total size: " + formatSize(manifest.getTotalSizeBytes()));
        System.out.println();

        System.out.println("=== Models ===");
        System.out.printf("%-30s %-15s %-10s%n", "MODEL ID", "TYPE", "STATUS");
        System.out.println("-".repeat(60));

        for (ModelEntry entry : manifest.getModels()) {
            System.out.printf("%-30s %-15s %-10s%n",
                    entry.getModelId(),
                    entry.getType().getValue(),
                    entry.getStatus().getValue());
        }

        System.out.println();
        System.out.println("Total: " + manifest.getModelCount() + " models");
        return 0;
    }

    private Integer importBundle(Path bundlePath) {
        System.out.println("Importing bundle: " + bundlePath);
        System.out.println("Verify checksums: " + verifyChecksums);
        System.out.println();

        ImportResult result = importService.importBundle(bundlePath, verifyChecksums);

        if (result.isSuccess()) {
            System.out.println("Import completed successfully!");
            System.out.println("Imported: " + result.getImportedCount() + "/" + result.getTotalCount() + " models");
            return 0;
        } else {
            System.err.println("Import failed: " + result.getErrorMessage());
            return 1;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
