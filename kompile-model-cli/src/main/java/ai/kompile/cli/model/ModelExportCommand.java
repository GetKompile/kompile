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

package ai.kompile.cli.model;

import ai.kompile.cli.common.KompileHome;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import picocli.CommandLine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@CommandLine.Command(name = "export", description = "Export locally cached models to a .tar.gz bundle.")
public class ModelExportCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--models"}, required = true, split = ",", description = "Comma-separated model IDs (directory names under ~/.kompile/models/)")
    private List<String> modelIds;

    @CommandLine.Option(names = {"--output", "-o"}, required = true, description = "Output file path (.tar.gz)")
    private String output;

    @Override
    public Integer call() throws Exception {
        File modelsDir = KompileHome.modelsDirectory();
        if (!modelsDir.exists()) {
            System.err.println("Models directory does not exist: " + modelsDir.getAbsolutePath());
            return 1;
        }

        // Validate all model directories exist before starting
        for (String modelId : modelIds) {
            Path modelPath = modelsDir.toPath().resolve(modelId);
            if (!Files.exists(modelPath)) {
                System.err.println("Model not found locally: " + modelId);
                System.err.println("  Expected at: " + modelPath);
                return 1;
            }
        }

        Path outputPath = Path.of(output);
        if (!output.endsWith(".tar.gz") && !output.endsWith(".tgz")) {
            outputPath = Path.of(output + ".tar.gz");
        }

        // Ensure parent directory exists
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        System.out.println("Exporting models " + modelIds + " to " + outputPath);

        int totalFiles = 0;
        long totalBytes = 0;

        try (OutputStream fo = Files.newOutputStream(outputPath);
             BufferedOutputStream bo = new BufferedOutputStream(fo);
             GzipCompressorOutputStream gzo = new GzipCompressorOutputStream(bo);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzo)) {

            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);

            for (String modelId : modelIds) {
                Path modelPath = modelsDir.toPath().resolve(modelId);
                System.out.println("  Adding: " + modelId);

                if (Files.isDirectory(modelPath)) {
                    try (Stream<Path> walker = Files.walk(modelPath)) {
                        List<Path> files = walker.sorted().toList();
                        for (Path file : files) {
                            String entryName = modelsDir.toPath().relativize(file).toString();
                            if (Files.isDirectory(file)) {
                                TarArchiveEntry dirEntry = new TarArchiveEntry(file.toFile(), entryName + "/");
                                tar.putArchiveEntry(dirEntry);
                                tar.closeArchiveEntry();
                            } else {
                                TarArchiveEntry fileEntry = new TarArchiveEntry(file.toFile(), entryName);
                                fileEntry.setSize(Files.size(file));
                                tar.putArchiveEntry(fileEntry);
                                Files.copy(file, tar);
                                tar.closeArchiveEntry();
                                totalFiles++;
                                totalBytes += Files.size(file);
                            }
                        }
                    }
                } else {
                    // Single file model
                    String entryName = modelId;
                    TarArchiveEntry fileEntry = new TarArchiveEntry(modelPath.toFile(), entryName);
                    fileEntry.setSize(Files.size(modelPath));
                    tar.putArchiveEntry(fileEntry);
                    Files.copy(modelPath, tar);
                    tar.closeArchiveEntry();
                    totalFiles++;
                    totalBytes += Files.size(modelPath);
                }
            }

            tar.finish();
        }

        long archiveSize = Files.size(outputPath);
        System.out.printf("Successfully exported %d files (%s) to %s (%s)%n",
                totalFiles, formatSize(totalBytes), outputPath, formatSize(archiveSize));
        return 0;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
