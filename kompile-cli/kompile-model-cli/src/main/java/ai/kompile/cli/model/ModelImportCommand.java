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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import picocli.CommandLine;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "import", description = "Import a model bundle (.tar.gz) into the local model cache.")
public class ModelImportCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--bundle", "-b"}, required = true, description = "Path to model bundle (.tar.gz)")
    private String bundlePath;

    @CommandLine.Option(names = {"--name", "-n"}, description = "Model name (defaults to bundle filename without extension)")
    private String modelName;

    @Override
    public Integer call() throws Exception {
        Path bundle = Path.of(bundlePath);
        if (!Files.exists(bundle)) {
            System.err.println("Bundle not found: " + bundlePath);
            return 1;
        }

        if (!bundlePath.endsWith(".tar.gz") && !bundlePath.endsWith(".tgz")) {
            System.err.println("Bundle must be a .tar.gz or .tgz file: " + bundlePath);
            return 1;
        }

        // Determine model name from filename if not provided
        String name = modelName;
        if (name == null || name.isBlank()) {
            String fileName = bundle.getFileName().toString();
            if (fileName.endsWith(".tar.gz")) {
                name = fileName.substring(0, fileName.length() - ".tar.gz".length());
            } else if (fileName.endsWith(".tgz")) {
                name = fileName.substring(0, fileName.length() - ".tgz".length());
            }
        }

        Path modelsDir = KompileHome.modelsDirectory().toPath();
        Files.createDirectories(modelsDir);
        Path targetDir = modelsDir.resolve(name);

        System.out.println("Importing model bundle from " + bundlePath);
        System.out.println("  Target: " + targetDir);

        if (Files.exists(targetDir)) {
            System.out.println("  Warning: target directory already exists, files may be overwritten.");
        }

        Files.createDirectories(targetDir);

        int fileCount = 0;
        long totalBytes = 0;
        try (InputStream fi = Files.newInputStream(bundle);
             BufferedInputStream bi = new BufferedInputStream(fi);
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                // Strip the top-level directory if the archive has one
                String entryName = entry.getName();
                // Normalize: remove leading "./" or "/"
                if (entryName.startsWith("./")) {
                    entryName = entryName.substring(2);
                }
                if (entryName.startsWith("/")) {
                    entryName = entryName.substring(1);
                }
                if (entryName.isEmpty()) {
                    continue;
                }

                Path newPath = targetDir.resolve(entryName);

                // Path traversal protection
                if (!newPath.normalize().startsWith(targetDir.normalize())) {
                    System.err.println("  Skipping suspicious entry: " + entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    Files.copy(ti, newPath, StandardCopyOption.REPLACE_EXISTING);
                    fileCount++;
                    totalBytes += entry.getSize();
                }
            }
        }

        System.out.printf("Successfully imported %d files (%s) to %s%n",
                fileCount, formatSize(totalBytes), targetDir);
        return 0;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
