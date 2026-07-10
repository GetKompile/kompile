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

package ai.kompile.staging.download;

import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.utils.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Copies model artifacts that are already present on local disk.
 *
 * <p>Catalog entries created from a registry model use {@code source=local} and a repository
 * value that is usually relative to the staging model directory, for example
 * {@code vlm-pipelines/smoldocling-256m}. Treating those entries as downloadable remote
 * models is wrong; they are already managed artifacts.</p>
 */
@Service
public class LocalDownloader implements DownloadService {

    private static final Logger log = LoggerFactory.getLogger(LocalDownloader.class);

    private final RegistryService registryService;
    private final Path modelDirOverride;
    private final Path projectDirOverride;

    @Value("${kompile.staging.project-dir:}")
    private String projectDir;

    @Autowired
    public LocalDownloader(RegistryService registryService) {
        this.registryService = registryService;
        this.modelDirOverride = null;
        this.projectDirOverride = null;
    }

    LocalDownloader(Path modelDir, Path projectDir) {
        this.registryService = null;
        this.modelDirOverride = modelDir;
        this.projectDirOverride = projectDir;
        this.projectDir = projectDir != null ? projectDir.toString() : "";
    }

    @Override
    public String getSourceName() {
        return "local";
    }

    @Override
    public boolean canHandle(String source) {
        return "local".equalsIgnoreCase(source);
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination) {
        return download(request, destination, progress -> {});
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination,
                                   Consumer<DownloadProgress> progressCallback) {
        long start = System.currentTimeMillis();
        try {
            progressCallback.accept(DownloadProgress.initializing("Resolving local model artifact"));
            Path sourcePath = resolveSourcePath(request);
            if (sourcePath == null || !Files.exists(sourcePath)) {
                return DownloadResult.failure("Local model artifact not found: " + request.getRepository());
            }

            Files.createDirectories(destination);
            long totalBytes = sizeOf(sourcePath);
            progressCallback.accept(DownloadProgress.downloading(
                    sourcePath.getFileName().toString(), 0, totalBytes, 0));

            if (Files.isDirectory(sourcePath)) {
                copyDirectory(sourcePath, destination);
            } else {
                Files.copy(sourcePath, destination.resolve(sourcePath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }

            Path modelPath = resolveDownloadedModelPath(request, destination, sourcePath);
            if (modelPath == null || !Files.exists(modelPath)) {
                return DownloadResult.failure("Local model copy completed but no model artifact was found for: "
                        + request.getModelId());
            }
            Path vocabPath = resolveDownloadedFile(request, destination, "vocab");
            String checksum = Files.isRegularFile(modelPath) ? sha256(modelPath) : null;

            progressCallback.accept(DownloadProgress.completed("Local model artifact copied"));
            return DownloadResult.builder()
                    .success(true)
                    .modelPath(modelPath)
                    .vocabPath(vocabPath)
                    .checksum(checksum)
                    .totalBytes(totalBytes)
                    .durationMs(System.currentTimeMillis() - start)
                    .downloadedFiles(resolveDownloadedFiles(request, destination))
                    .build();
        } catch (Exception e) {
            log.warn("Local model download failed for {}: {}", request != null ? request.getModelId() : null, e.getMessage());
            progressCallback.accept(DownloadProgress.failed(e.getMessage()));
            return DownloadResult.failure(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable(DownloadRequest request) {
        Path sourcePath = resolveSourcePath(request);
        return sourcePath != null && Files.exists(sourcePath);
    }

    private Path resolveSourcePath(DownloadRequest request) {
        if (request == null || request.getRepository() == null || request.getRepository().isBlank()) {
            return null;
        }
        Path raw = Paths.get(request.getRepository());
        if (raw.isAbsolute()) {
            return raw.normalize();
        }

        Path modelDir = modelDir();
        if (modelDir != null) {
            Path candidate = modelDir.resolve(raw).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        Path project = projectDir();
        if (project != null) {
            Path candidate = project.resolve(raw).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            candidate = project.resolve("data/models").resolve(raw).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return modelDir != null ? modelDir.resolve(raw).normalize() : raw.normalize();
    }

    private Path modelDir() {
        if (modelDirOverride != null) {
            return modelDirOverride;
        }
        return registryService != null ? registryService.getModelDir() : null;
    }

    private Path projectDir() {
        if (projectDirOverride != null) {
            return projectDirOverride;
        }
        return projectDir != null && !projectDir.isBlank() ? Paths.get(projectDir) : null;
    }

    private Path resolveDownloadedModelPath(DownloadRequest request, Path destination, Path sourcePath) throws IOException {
        Path requestedModel = resolveDownloadedFile(request, destination, "model");
        if (requestedModel != null && Files.exists(requestedModel)) {
            return requestedModel;
        }
        if (Files.isRegularFile(sourcePath)) {
            return destination.resolve(sourcePath.getFileName());
        }
        return findPreferredModelFile(destination);
    }

    private Map<String, Path> resolveDownloadedFiles(DownloadRequest request, Path destination) {
        Map<String, Path> out = new LinkedHashMap<>();
        if (request != null && request.getFiles() != null) {
            for (Map.Entry<String, String> entry : request.getFiles().entrySet()) {
                Path path = destination.resolve(entry.getValue()).normalize();
                if (path.startsWith(destination.normalize()) && Files.exists(path)) {
                    out.put(entry.getKey(), path);
                }
            }
        }
        return out;
    }

    private Path resolveDownloadedFile(DownloadRequest request, Path destination, String key) {
        if (request == null || request.getFiles() == null) {
            return null;
        }
        String fileName = request.getFiles().get(key);
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path resolved = destination.resolve(fileName).normalize();
        return resolved.startsWith(destination.normalize()) ? resolved : null;
    }

    private Path findPreferredModelFile(Path dir) throws IOException {
        String[] preferred = {"pipeline.json", "model.sdz", "model.sdnb", "model.onnx", "model.gguf", "model.ggml"};
        for (String name : preferred) {
            Path candidate = dir.resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".sdz") || n.endsWith(".sdnb") || n.endsWith(".onnx")
                                || n.endsWith(".gguf") || n.endsWith(".ggml") || "pipeline.json".equals(n);
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path target = destination.resolve(source.relativize(path)).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Refusing to copy outside destination: " + target);
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private long sizeOf(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        }
    }

    private String sha256(Path path) throws Exception {
        return "sha256:" + HashUtils.sha256Hex(path);
    }
}
