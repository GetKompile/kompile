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

import ai.kompile.staging.archive.ArchiveCompatibility;
import ai.kompile.staging.archive.ArchiveExporter;
import ai.kompile.staging.archive.ArchivePublisher;
import ai.kompile.staging.archive.KompileArchive;
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
 * CLI command for exporting models to a Kompile archive (.karch).
 */
@Component
@Command(
    name = "export",
    description = "Export models to a Kompile archive (.karch) file",
    mixinStandardHelpOptions = true
)
public class ArchiveExportCommand implements Callable<Integer> {

    @Autowired
    private ArchiveExporter archiveExporter;

    @Option(names = {"-m", "--models"},
            description = "Model IDs to export (comma-separated)")
    private String models;

    @Option(names = {"-o", "--output"},
            description = "Output file path (.karch)")
    private String output;

    @Option(names = {"--all"},
            description = "Export all models in the registry")
    private boolean exportAll;

    @Option(names = {"-v", "--version"},
            description = "Archive version (e.g., 1.0.0)",
            defaultValue = "1.0.0")
    private String version;

    @Option(names = {"--archive-id"},
            description = "Archive identifier (defaults to 'kompile-models')")
    private String archiveId;

    @Option(names = {"--description"},
            description = "Archive description")
    private String description;

    @Option(names = {"--publisher-name"},
            description = "Publisher name")
    private String publisherName;

    @Option(names = {"--publisher-url"},
            description = "Publisher URL")
    private String publisherUrl;

    @Option(names = {"--min-kompile-version"},
            description = "Minimum Kompile version required")
    private String minKompileVersion;

    @Option(names = {"--include-changelog"},
            description = "Include changelog file in archive")
    private boolean includeChangelog;

    @Option(names = {"--include-readme"},
            description = "Include README file in archive")
    private boolean includeReadme;

    @Override
    public Integer call() {
        // Validate input
        if (!exportAll && (models == null || models.isEmpty())) {
            System.err.println("Please specify --models or --all");
            return 1;
        }

        // Determine model IDs
        List<String> modelIds;
        if (exportAll) {
            modelIds = null; // Will be handled by exporter to get all models
        } else {
            modelIds = Arrays.asList(models.split(","));
        }

        // Determine output path
        Path outputPath;
        if (output != null) {
            outputPath = Paths.get(output);
            if (!output.endsWith(KompileArchive.EXTENSION)) {
                outputPath = Paths.get(output + KompileArchive.EXTENSION);
            }
        } else {
            String id = archiveId != null ? archiveId : "kompile-models";
            outputPath = Paths.get(id + "-" + version + KompileArchive.EXTENSION);
        }

        // Build options
        ArchiveExporter.ExportOptions.ExportOptionsBuilder optionsBuilder =
            ArchiveExporter.ExportOptions.builder()
                .archiveId(archiveId != null ? archiveId : "kompile-models")
                .version(version)
                .description(description);

        // Add publisher if specified
        if (publisherName != null) {
            optionsBuilder.publisher(ArchivePublisher.builder()
                    .name(publisherName)
                    .url(publisherUrl)
                    .build());
        }

        // Add compatibility if specified
        if (minKompileVersion != null) {
            optionsBuilder.compatibility(ArchiveCompatibility.builder()
                    .minKompileVersion(minKompileVersion)
                    .build());
        }

        ArchiveExporter.ExportOptions options = optionsBuilder.build();

        System.out.println("Exporting archive: " + outputPath);
        if (exportAll) {
            System.out.println("Including: all models in registry");
        } else {
            System.out.println("Including: " + modelIds.size() + " models");
        }
        System.out.println("Version: " + version);

        // Export with progress callback
        ArchiveExporter.ExportResult result;
        if (exportAll) {
            result = archiveExporter.exportAll(outputPath, options);
        } else {
            result = archiveExporter.export(modelIds, outputPath, options, progress -> {
                printProgress(progress);
            });
        }

        System.out.println();

        if (result.isSuccess()) {
            System.out.println("Export completed successfully!");
            System.out.println();
            System.out.println("Archive:    " + result.getArchivePath());
            System.out.println("Archive ID: " + result.getManifest().getArchiveId());
            System.out.println("Version:    " + result.getManifest().getContentVersion());
            System.out.println("Models:     " + result.getModelCount());
            System.out.println("Size:       " + formatSize(result.getArchiveSize()));
            System.out.println("Checksum:   " + result.getArchiveChecksum());
            return 0;
        } else {
            System.err.println("Export failed: " + result.getErrorMessage());
            return 1;
        }
    }

    private void printProgress(ArchiveExporter.ExportProgress progress) {
        String bar = createProgressBar(progress.getProgressPercent());
        System.out.print("\r" + progress.getPhase() + ": " + bar + " " +
                progress.getProgressPercent() + "% - " + progress.getCurrentModel() + "   ");
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

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
