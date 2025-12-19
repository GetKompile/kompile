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

import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ExportService.ExportResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for exporting models to a bundle.
 */
@Component
@Command(
    name = "export",
    description = "Export models to a portable bundle for air-gap transfer",
    mixinStandardHelpOptions = true
)
public class ExportCommand implements Callable<Integer> {

    @Autowired
    private ExportService exportService;

    @Option(names = {"-m", "--models"},
            description = "Model IDs to export (comma-separated)")
    private String models;

    @Option(names = {"-o", "--output"},
            description = "Output file path (.tar.gz)")
    private String output;

    @Option(names = {"--all"},
            description = "Export all models in the registry")
    private boolean exportAll;

    @Option(names = {"--description"},
            description = "Bundle description")
    private String description;

    @Override
    public Integer call() {
        // Determine output path
        Path outputPath;
        if (output != null) {
            outputPath = Paths.get(output);
        } else {
            outputPath = Paths.get(exportService.generateBundleFilename());
        }

        ExportResult result;

        if (exportAll) {
            System.out.println("Exporting all models to: " + outputPath);
            result = exportService.exportAll(outputPath);
        } else if (models != null) {
            List<String> modelIds = Arrays.asList(models.split(","));
            System.out.println("Exporting " + modelIds.size() + " models to: " + outputPath);
            result = exportService.export(modelIds, outputPath, description);
        } else {
            System.err.println("Please specify --models or --all");
            return 1;
        }

        if (result.isSuccess()) {
            System.out.println("Export completed successfully!");
            System.out.println("Bundle: " + result.getBundlePath());
            System.out.println("Models: " + result.getModelCount());
            System.out.println("Size: " + formatSize(result.getBundleSize()));
            return 0;
        } else {
            System.err.println("Export failed: " + result.getErrorMessage());
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
