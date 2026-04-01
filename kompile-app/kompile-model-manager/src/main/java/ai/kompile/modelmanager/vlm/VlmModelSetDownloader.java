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

package ai.kompile.modelmanager.vlm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Downloads complete VLM model sets with progress tracking.
 *
 * <h2>Usage Examples</h2>
 *
 * <pre>
 * // Download a complete model set
 * VlmModelSetDownloader downloader = new VlmModelSetDownloader();
 * DownloadSetResult result = downloader.downloadModelSet(VlmModelSet.SMOLDOCLING_256M);
 *
 * // Check what was downloaded
 * for (DownloadComponentResult comp : result.getComponentResults()) {
 *     System.out.println(comp.getComponent().getFileName() + " -> " + comp.getLocalPath());
 * }
 *
 * // Download with progress callback
 * downloader.downloadModelSet(VlmModelSet.SMOLDOCLING_256M, (component, progress) -> {
 *     System.out.printf("%s: %.1f%%\n", component.getFileName(), progress * 100);
 * });
 *
 * // Download multiple model sets in parallel
 * List&lt;DownloadSetResult&gt; results = downloader.downloadModelSets(
 *     Arrays.asList(VlmModelSet.SMOLDOCLING_256M, VlmModelSet.SIGLIP_VISION),
 *     4  // parallelism
 * );
 * </pre>
 *
 * @author Kompile Inc.
 */
public class VlmModelSetDownloader {

    private static final Logger log = LoggerFactory.getLogger(VlmModelSetDownloader.class);

    private static final String DEFAULT_CACHE_DIR = System.getProperty("user.home") + "/.kompile/models/vlm";
    private static final String CACHE_DIR_PROPERTY = "kompile.vlm.model.cache.dir";
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 60000;

    private final Path cacheDir;

    public VlmModelSetDownloader() {
        this(getCacheDirectory());
    }

    public VlmModelSetDownloader(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory: " + cacheDir, e);
        }
    }

    /**
     * Get the cache directory for VLM models.
     */
    public static Path getCacheDirectory() {
        String dir = System.getProperty(CACHE_DIR_PROPERTY, DEFAULT_CACHE_DIR);
        return Paths.get(dir);
    }

    // =====================================================================
    // DOWNLOAD METHODS
    // =====================================================================

    /**
     * Download all components of a model set.
     *
     * @param modelSet the model set to download
     * @return result containing paths to all downloaded components
     */
    public DownloadSetResult downloadModelSet(VlmModelSet modelSet) {
        return downloadModelSet(modelSet, null);
    }

    /**
     * Download all components of a model set with progress callback.
     *
     * @param modelSet the model set to download
     * @param progressCallback callback receiving (component, progress 0-1) updates
     * @return result containing paths to all downloaded components
     */
    public DownloadSetResult downloadModelSet(VlmModelSet modelSet,
                                               BiConsumer<VlmModelComponent, Double> progressCallback) {
        log.info("Downloading model set: {} ({} components)",
            modelSet.getDisplayName(), modelSet.getComponents().size());

        Path setDir = cacheDir.resolve(modelSet.getSetId());
        try {
            Files.createDirectories(setDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create model set directory: " + setDir, e);
        }

        List<DownloadComponentResult> componentResults = new ArrayList<>();
        int totalComponents = modelSet.getComponents().size();
        int completedComponents = 0;

        for (VlmModelComponent component : modelSet.getComponents()) {
            try {
                DownloadComponentResult result = downloadComponent(component, setDir, progressCallback);
                componentResults.add(result);
                completedComponents++;
                log.info("Downloaded {}/{}: {} ({})",
                    completedComponents, totalComponents,
                    component.getFileName(),
                    result.isFromCache() ? "cached" : formatBytes(result.getDownloadedBytes()));
            } catch (Exception e) {
                log.error("Failed to download component: {}", component.getFileName(), e);
                componentResults.add(DownloadComponentResult.failed(component, e.getMessage()));
            }
        }

        // Write manifest file
        writeManifest(modelSet, setDir, componentResults);

        return new DownloadSetResult(modelSet, setDir, componentResults);
    }

    /**
     * Download multiple model sets in parallel.
     *
     * @param modelSets the model sets to download
     * @param parallelism number of concurrent downloads
     * @return results for each model set
     */
    public List<DownloadSetResult> downloadModelSets(List<VlmModelSet> modelSets, int parallelism) {
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<DownloadSetResult>> futures = new ArrayList<>();
            for (VlmModelSet set : modelSets) {
                futures.add(executor.submit(() -> downloadModelSet(set)));
            }

            List<DownloadSetResult> results = new ArrayList<>();
            for (Future<DownloadSetResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    log.error("Failed to download model set", e);
                }
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Download a single component.
     */
    public DownloadComponentResult downloadComponent(VlmModelComponent component, Path targetDir,
                                                      BiConsumer<VlmModelComponent, Double> progressCallback)
            throws IOException {
        Path targetFile = targetDir.resolve(component.getFileName());

        // Check if already cached
        if (Files.exists(targetFile)) {
            long size = Files.size(targetFile);
            // Verify checksum if provided
            if (component.getChecksum() != null && !component.getChecksum().isEmpty()) {
                String actualChecksum = computeSha256(targetFile);
                String expectedChecksum = component.getChecksum().replace("sha256:", "");
                if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
                    log.warn("Checksum mismatch for cached {}: expected {} but got {}. Re-downloading.",
                            component.getFileName(), expectedChecksum, actualChecksum);
                    Files.delete(targetFile);
                    // Fall through to download below
                } else {
                    log.debug("Using cached (checksum verified): {} ({} bytes)", component.getFileName(), size);
                    if (progressCallback != null) {
                        progressCallback.accept(component, 1.0);
                    }
                    return DownloadComponentResult.cached(component, targetFile, size);
                }
            } else {
                log.debug("Using cached: {} ({} bytes)", component.getFileName(), size);
                if (progressCallback != null) {
                    progressCallback.accept(component, 1.0);
                }
                return DownloadComponentResult.cached(component, targetFile, size);
            }
        }

        // Download
        log.info("Downloading: {} from {}", component.getFileName(), component.getDownloadUrl());
        long downloadedBytes = downloadFile(component.getDownloadUrl(), targetFile,
            progressCallback != null ? progress -> progressCallback.accept(component, progress) : null);

        // Verify checksum if provided
        if (component.getChecksum() != null && !component.getChecksum().isEmpty()) {
            String actualChecksum = computeSha256(targetFile);
            String expectedChecksum = component.getChecksum().replace("sha256:", "");
            if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
                Files.delete(targetFile);
                throw new IOException("Checksum mismatch for " + component.getFileName() +
                    ": expected " + expectedChecksum + " but got " + actualChecksum);
            }
            log.debug("Checksum verified for {}", component.getFileName());
        }

        return DownloadComponentResult.downloaded(component, targetFile, downloadedBytes);
    }

    /**
     * Check if a model set is fully cached.
     */
    public boolean isModelSetCached(VlmModelSet modelSet) {
        Path setDir = cacheDir.resolve(modelSet.getSetId());
        if (!Files.exists(setDir)) {
            return false;
        }

        for (VlmModelComponent component : modelSet.getComponents()) {
            Path componentFile = setDir.resolve(component.getFileName());
            if (!Files.exists(componentFile)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the local path for a model set.
     */
    public Path getModelSetPath(VlmModelSet modelSet) {
        return cacheDir.resolve(modelSet.getSetId());
    }

    /**
     * Get the local path for a specific component.
     */
    public Path getComponentPath(VlmModelSet modelSet, String componentKey) {
        return modelSet.getComponent(componentKey)
            .map(c -> cacheDir.resolve(modelSet.getSetId()).resolve(c.getFileName()))
            .orElse(null);
    }

    /**
     * List all cached model sets.
     */
    public List<String> listCachedModelSets() {
        List<String> cached = new ArrayList<>();
        try {
            Files.list(cacheDir)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    String setId = dir.getFileName().toString();
                    VlmModelSet set = VlmModelSet.getModelSet(setId);
                    if (set != null && isModelSetCached(set)) {
                        cached.add(setId);
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to list cached model sets", e);
        }
        return cached;
    }

    /**
     * Delete a cached model set.
     */
    public void deleteModelSet(VlmModelSet modelSet) throws IOException {
        Path setDir = cacheDir.resolve(modelSet.getSetId());
        if (Files.exists(setDir)) {
            Files.walk(setDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete: {}", path, e);
                    }
                });
            log.info("Deleted model set: {}", modelSet.getSetId());
        }
    }

    // =====================================================================
    // INTERNAL METHODS
    // =====================================================================

    private long downloadFile(String urlString, Path outputFile,
                              java.util.function.DoubleConsumer progressCallback) throws IOException {
        // Download to temp file first
        Path tempFile = Files.createTempFile("kompile-download-", ".tmp");

        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("User-Agent", "Kompile-VLM-Downloader/1.0");

            // Handle redirects
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308) {
                String newUrl = connection.getHeaderField("Location");
                log.debug("Redirecting to: {}", newUrl);
                connection.disconnect();
                return downloadFile(newUrl, outputFile, progressCallback);
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode + " from " + urlString);
            }

            long contentLength = connection.getContentLengthLong();
            long totalRead = 0;

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long lastProgressUpdate = System.currentTimeMillis();

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    // Update progress every 100ms
                    long now = System.currentTimeMillis();
                    if (progressCallback != null && (now - lastProgressUpdate > 100)) {
                        double progress = contentLength > 0 ?
                            (double) totalRead / contentLength : 0.5;
                        progressCallback.accept(Math.min(progress, 0.99));
                        lastProgressUpdate = now;
                    }
                }
            }

            connection.disconnect();

            // Move temp file to target
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);

            if (progressCallback != null) {
                progressCallback.accept(1.0);
            }

            return totalRead;

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void writeManifest(VlmModelSet modelSet, Path setDir,
                               List<DownloadComponentResult> results) {
        Path manifestFile = setDir.resolve("manifest.json");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(manifestFile))) {
            writer.println("{");
            writer.println("  \"model_set_id\": \"" + modelSet.getSetId() + "\",");
            writer.println("  \"display_name\": \"" + modelSet.getDisplayName() + "\",");
            writer.println("  \"huggingface_repo\": \"" + modelSet.getHuggingFaceRepo() + "\",");
            writer.println("  \"downloaded_at\": \"" + java.time.Instant.now() + "\",");
            writer.println("  \"components\": [");

            for (int i = 0; i < results.size(); i++) {
                DownloadComponentResult r = results.get(i);
                writer.println("    {");
                writer.println("      \"key\": \"" + r.getComponent().getComponentKey() + "\",");
                writer.println("      \"file\": \"" + r.getComponent().getFileName() + "\",");
                writer.println("      \"stage\": \"" +
                    (r.getComponent().getPipelineStage() != null ?
                        r.getComponent().getPipelineStage().name() : "null") + "\",");
                writer.println("      \"success\": " + r.isSuccess() + ",");
                writer.println("      \"size_bytes\": " + r.getDownloadedBytes());
                writer.println("    }" + (i < results.size() - 1 ? "," : ""));
            }

            writer.println("  ]");
            writer.println("}");
        } catch (IOException e) {
            log.warn("Failed to write manifest: {}", manifestFile, e);
        }
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // =====================================================================
    // RESULT CLASSES
    // =====================================================================

    /**
     * Result of downloading a complete model set.
     */
    public static class DownloadSetResult {
        private final VlmModelSet modelSet;
        private final Path localPath;
        private final List<DownloadComponentResult> componentResults;

        public DownloadSetResult(VlmModelSet modelSet, Path localPath,
                                 List<DownloadComponentResult> componentResults) {
            this.modelSet = modelSet;
            this.localPath = localPath;
            this.componentResults = Collections.unmodifiableList(componentResults);
        }

        public VlmModelSet getModelSet() {
            return modelSet;
        }

        public Path getLocalPath() {
            return localPath;
        }

        public List<DownloadComponentResult> getComponentResults() {
            return componentResults;
        }

        public boolean isFullySuccessful() {
            return componentResults.stream().allMatch(DownloadComponentResult::isSuccess);
        }

        /**
         * Alias for isFullySuccessful() for API compatibility.
         */
        public boolean isSuccess() {
            return isFullySuccessful();
        }

        /**
         * Get list of failed component names.
         */
        public List<String> getFailures() {
            return componentResults.stream()
                .filter(r -> !r.isSuccess())
                .map(r -> r.getComponent().getFileName())
                .toList();
        }

        public long getTotalBytes() {
            return componentResults.stream()
                .mapToLong(DownloadComponentResult::getDownloadedBytes)
                .sum();
        }

        public Optional<Path> getComponentPath(String componentKey) {
            return componentResults.stream()
                .filter(r -> r.getComponent().getComponentKey().equals(componentKey))
                .filter(DownloadComponentResult::isSuccess)
                .map(DownloadComponentResult::getLocalPath)
                .findFirst();
        }

        /**
         * Get the path to the vision encoder model.
         */
        public Optional<Path> getVisionEncoderPath() {
            return getComponentPath("vision_encoder");
        }

        /**
         * Get the path to the decoder model.
         */
        public Optional<Path> getDecoderPath() {
            return getComponentPath("decoder");
        }

        /**
         * Get the path to the embed_tokens model.
         */
        public Optional<Path> getEmbedTokensPath() {
            return getComponentPath("embed_tokens");
        }

        /**
         * Get the path to the tokenizer.
         */
        public Optional<Path> getTokenizerPath() {
            return getComponentPath("tokenizer");
        }
    }

    /**
     * Result of downloading a single component.
     */
    public static class DownloadComponentResult {
        private final VlmModelComponent component;
        private final Path localPath;
        private final long downloadedBytes;
        private final boolean success;
        private final boolean fromCache;
        private final String errorMessage;

        private DownloadComponentResult(VlmModelComponent component, Path localPath,
                                        long downloadedBytes, boolean success,
                                        boolean fromCache, String errorMessage) {
            this.component = component;
            this.localPath = localPath;
            this.downloadedBytes = downloadedBytes;
            this.success = success;
            this.fromCache = fromCache;
            this.errorMessage = errorMessage;
        }

        public static DownloadComponentResult downloaded(VlmModelComponent component,
                                                          Path localPath, long bytes) {
            return new DownloadComponentResult(component, localPath, bytes, true, false, null);
        }

        public static DownloadComponentResult cached(VlmModelComponent component,
                                                      Path localPath, long bytes) {
            return new DownloadComponentResult(component, localPath, bytes, true, true, null);
        }

        public static DownloadComponentResult failed(VlmModelComponent component, String error) {
            return new DownloadComponentResult(component, null, 0, false, false, error);
        }

        public VlmModelComponent getComponent() {
            return component;
        }

        public Path getLocalPath() {
            return localPath;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isFromCache() {
            return fromCache;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
