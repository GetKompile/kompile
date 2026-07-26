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
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.utils.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
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
    private final StagingAssetLimits limits;

    @Value("${kompile.staging.project-dir:}")
    private String projectDir;

    public LocalDownloader(RegistryService registryService) {
        this(registryService, new StagingAssetLimits());
    }

    @Autowired
    public LocalDownloader(RegistryService registryService, StagingAssetLimits limits) {
        this.registryService = registryService;
        this.modelDirOverride = null;
        this.projectDirOverride = null;
        this.limits = limits == null ? new StagingAssetLimits() : limits;
    }

    LocalDownloader(Path modelDir, Path projectDir) {
        this(modelDir, projectDir, new StagingAssetLimits());
    }

    LocalDownloader(Path modelDir, Path projectDir, StagingAssetLimits limits) {
        this.registryService = null;
        this.modelDirOverride = modelDir;
        this.projectDirOverride = projectDir;
        this.limits = limits == null ? new StagingAssetLimits() : limits;
        this.projectDir = projectDir != null ? projectDir.toString() : "";
    }

    @Override
    public String getSourceName() {
        return "local";
    }

    @Override
    public boolean canHandle(String source) {
        return "local".equalsIgnoreCase(source)
                || "trusted-local".equalsIgnoreCase(source);
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination) {
        return download(request, destination, progress -> {});
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination,
                                   Consumer<DownloadProgress> progressCallback) {
        return download(request, destination, progressCallback, StagingCancellation.NONE);
    }

    @Override
    public DownloadResult download(
            DownloadRequest request,
            Path destination,
            Consumer<DownloadProgress> progressCallback,
            StagingCancellation cancellation) {
        StagingCancellation signal = cancellation == null
                ? StagingCancellation.NONE
                : cancellation;
        long start = System.currentTimeMillis();
        try {
            signal.checkpoint();
            progressCallback.accept(DownloadProgress.initializing("Resolving local model artifact"));
            Path sourcePath = resolveSourcePath(request);
            if (sourcePath == null || !Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                return DownloadResult.failure("Local model artifact not found: " + request.getRepository());
            }
            requireSafeSourceTree(sourcePath, request);
            signal.checkpoint();

            Files.createDirectories(destination);
            long totalBytes = sizeOf(sourcePath, request);
            progressCallback.accept(DownloadProgress.downloading(
                    sourcePath.getFileName().toString(), 0, totalBytes, 0));

            if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(sourcePath, destination, request, signal);
            } else {
                copyRegularFile(sourcePath, destination.resolve(sourcePath.getFileName()),
                        TextModelAssetMap.MODEL, signal);
            }
            signal.checkpoint();

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
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.warn("Local model download failed for {}: {}", request != null ? request.getModelId() : null, e.getMessage());
            progressCallback.accept(DownloadProgress.failed(e.getMessage()));
            return DownloadResult.failure(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable(DownloadRequest request) {
        try {
            Path sourcePath = resolveSourcePath(request);
            return sourcePath != null && Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException invalid) {
            return false;
        }
    }

    private Path resolveSourcePath(DownloadRequest request) throws IOException {
        if (request == null || request.getRepository() == null || request.getRepository().isBlank()) {
            return null;
        }
        if ("local".equalsIgnoreCase(request.getSource())) {
            return resolveOpaqueUpload(request.getRepository());
        }
        if (!"trusted-local".equalsIgnoreCase(request.getSource())) {
            throw new IOException("Unsupported local source: " + request.getSource());
        }

        Path raw = Paths.get(request.getRepository());
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize();
        }

        Path modelDir = modelDir();
        Path candidate = existingContained(modelDir, raw);
        if (candidate != null) {
            return candidate;
        }

        Path project = projectDir();
        candidate = existingContained(project, raw);
        if (candidate != null) {
            return candidate;
        }
        return existingContained(project == null ? null : project.resolve("data/models"), raw);
    }

    private Path resolveOpaqueUpload(String handle) throws IOException {
        if (!handle.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("Invalid local upload handle");
        }
        Path models = Objects.requireNonNull(modelDir(), "Model directory is unavailable")
                .toAbsolutePath().normalize();
        Path uploadRoot = models.resolve(".staging/uploads").normalize();
        Path candidate = uploadRoot.resolve(handle).normalize();
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = uploadRoot.resolve("text-bundles").resolve(handle).normalize();
        }
        if (!candidate.startsWith(uploadRoot)
                || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unknown local upload handle");
        }
        Path realRoot = uploadRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realCandidate = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realCandidate.startsWith(realRoot)) {
            throw new IOException("Local upload handle escapes the trusted upload root");
        }
        return realCandidate;
    }

    private Path existingContained(Path root, Path relative) {
        if (root == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(relative).normalize();
        return candidate.startsWith(normalizedRoot)
                && Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                ? candidate
                : null;
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
        if (request != null) {
            for (Map.Entry<String, String> entry : request.effectiveFiles().entrySet()) {
                Path path = destination.resolve(entry.getValue()).normalize();
                if (path.startsWith(destination.normalize()) && Files.exists(path)) {
                    out.put(entry.getKey(), path);
                }
            }
        }
        return out;
    }

    private Path resolveDownloadedFile(DownloadRequest request, Path destination, String key) {
        if (request == null) {
            return null;
        }
        String fileName = request.effectiveFiles().get(key);
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

    private void copyDirectory(
            Path source,
            Path destination,
            DownloadRequest request,
            StagingCancellation cancellation) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        for (Map.Entry<Path, String> selected : selectedFiles(source, request).entrySet()) {
            cancellation.checkpoint();
            Path relative = source.relativize(selected.getKey());
            Path target = normalizedDestination.resolve(relative).normalize();
            if (!target.startsWith(normalizedDestination)) {
                throw new IOException("Refusing to copy outside destination: " + target);
            }
            copyRegularFile(selected.getKey(), target, selected.getValue(), cancellation);
        }
    }

    private void requireSafeSourceTree(Path source, DownloadRequest request) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Symbolic links are not accepted as local model inputs");
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            selectedFiles(source, request);
        } else if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local model input is not a regular file or directory");
        }
    }

    private Map<Path, String> selectedFiles(Path source, DownloadRequest request) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Map<Path, String> selected = new LinkedHashMap<>();
        Map<String, String> declared = request == null ? Map.of() : request.effectiveFiles();
        if (!declared.isEmpty()) {
            for (Map.Entry<String, String> asset : declared.entrySet()) {
                String value = asset.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                Path relative = Paths.get(value);
                if (relative.isAbsolute()
                        || relative.getNameCount() > limits.getLocalMaxDepth()) {
                    throw new IOException("Local asset path exceeds the bundle depth limit");
                }
                Path candidate = normalizedSource.resolve(relative).normalize();
                if (!candidate.startsWith(normalizedSource)
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(candidate)) {
                    throw new IOException("Invalid or missing local asset: " + value);
                }
                requireNoSymlinkPath(normalizedSource, candidate);
                requireAssetSize(asset.getKey(), candidate);
                selected.put(candidate, asset.getKey());
            }
        } else {
            try (Stream<Path> stream = Files.walk(
                    normalizedSource, limits.getLocalMaxDepth() + 1)) {
                for (Path candidate : stream.toList()) {
                    if (normalizedSource.relativize(candidate).getNameCount()
                            > limits.getLocalMaxDepth()) {
                        throw new IOException("Local model bundle exceeds the depth limit");
                    }
                    if (Files.isSymbolicLink(candidate)) {
                        throw new IOException("Symbolic links are not accepted in local model bundles");
                    }
                    if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Special files are not accepted in local model bundles");
                    }
                    requireAssetSize(TextModelAssetMap.MODEL, candidate);
                    selected.put(candidate, TextModelAssetMap.MODEL);
                }
            }
        }
        if (selected.isEmpty()) {
            throw new IOException("Local model bundle contains no selected regular files");
        }
        if (selected.size() > limits.getLocalMaxFiles()) {
            throw new IOException("Local model bundle exceeds the file-count limit");
        }
        long total = 0L;
        for (Path file : selected.keySet()) {
            total = Math.addExact(total, Files.size(file));
            if (total > limits.getTotalBytes()) {
                throw new IOException("Local model bundle exceeds the total-byte limit");
            }
        }
        return selected;
    }

    private void requireNoSymlinkPath(Path root, Path file) throws IOException {
        Path current = root;
        for (Path segment : root.relativize(file)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not accepted in local model bundles");
            }
        }
    }

    private void requireAssetSize(String key, Path file) throws IOException {
        if (Files.size(file) > limits.maxBytesFor(key)) {
            throw new IOException("Local asset exceeds its byte limit: " + file.getFileName());
        }
    }

    private long sizeOf(Path path, DownloadRequest request) throws IOException {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            requireAssetSize(TextModelAssetMap.MODEL, path);
            return Files.size(path);
        }
        long total = 0L;
        for (Path file : selectedFiles(path, request).keySet()) {
            total = Math.addExact(total, Files.size(file));
        }
        return total;
    }

    private void copyRegularFile(
            Path source,
            Path destination,
            String assetKey,
            StagingCancellation cancellation) throws IOException {
        requireAssetSize(assetKey, source);
        Files.createDirectories(destination.getParent());
        Path pending = destination.resolveSibling(
                "." + destination.getFileName() + ".part-" + UUID.randomUUID());
        long copied = 0L;
        long maximum = limits.maxBytesFor(assetKey);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(pending))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                cancellation.checkpoint();
                copied += count;
                if (copied > maximum) {
                    throw new IOException("Local asset exceeds its byte limit while copying");
                }
                output.write(buffer, 0, count);
            }
        } catch (RuntimeException | IOException failure) {
            Files.deleteIfExists(pending);
            throw failure;
        }
        try {
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private String sha256(Path path) throws Exception {
        return "sha256:" + HashUtils.sha256Hex(path);
    }
}
