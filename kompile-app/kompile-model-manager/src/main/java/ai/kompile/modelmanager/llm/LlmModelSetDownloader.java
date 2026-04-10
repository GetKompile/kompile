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

package ai.kompile.modelmanager.llm;

import ai.kompile.modelmanager.ModelConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Downloads and caches LLM model sets, analogous to
 * {@link ai.kompile.modelmanager.vlm.VlmModelSetDownloader}.
 *
 * <p>Downloads model components to {@code ~/.kompile/models/samediff-llm/<model-id>/}
 * with progress tracking and parallel download support.</p>
 */
public class LlmModelSetDownloader {

    private static final Logger log = LoggerFactory.getLogger(LlmModelSetDownloader.class);
    private static final LlmModelSetDownloader INSTANCE = new LlmModelSetDownloader();
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 120_000;

    private final Path cacheDirectory;

    private LlmModelSetDownloader() {
        String envDir = System.getenv(ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR);
        if (envDir != null && !envDir.isEmpty()) {
            this.cacheDirectory = Paths.get(envDir);
        } else {
            this.cacheDirectory = Paths.get(System.getProperty("user.home"),
                    ModelConstants.DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR);
        }
    }

    public static LlmModelSetDownloader getInstance() {
        return INSTANCE;
    }

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    /**
     * Get the cache directory for a specific model set.
     */
    public Path getModelSetDirectory(LlmModelSet modelSet) {
        return cacheDirectory.resolve("samediff-llm").resolve(modelSet.getSetId());
    }

    /**
     * Get the local path for a specific model component.
     */
    public Path getComponentPath(LlmModelSet modelSet, LlmModelComponent component) {
        return getModelSetDirectory(modelSet).resolve(component.getFileName());
    }

    /**
     * Check if a model set is fully cached (all components downloaded).
     */
    public boolean isModelSetCached(LlmModelSet modelSet) {
        for (LlmModelComponent component : modelSet.getComponents()) {
            Path path = getComponentPath(modelSet, component);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a specific component is cached.
     */
    public boolean isComponentCached(LlmModelSet modelSet, LlmModelComponent component) {
        Path path = getComponentPath(modelSet, component);
        return Files.exists(path) && Files.isRegularFile(path);
    }

    /**
     * Get cache status for all known model sets.
     */
    public Map<String, Boolean> getCacheStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (LlmModelSet modelSet : LlmModelSet.getAllModelSets().values()) {
            status.put(modelSet.getSetId(), isModelSetCached(modelSet));
        }
        return status;
    }

    /**
     * Download a model set with progress callback.
     *
     * @param modelSet The model set to download
     * @param progressCallback Called with (component, progress 0.0-1.0) for each component
     * @throws IOException If download fails
     */
    public void downloadModelSet(LlmModelSet modelSet,
                                  BiConsumer<LlmModelComponent, Double> progressCallback) throws IOException {
        Path modelDir = getModelSetDirectory(modelSet);
        Files.createDirectories(modelDir);

        log.info("Downloading LLM model set: {} ({} components)", modelSet.getDisplayName(),
                modelSet.getComponents().size());

        for (LlmModelComponent component : modelSet.getComponents()) {
            if (isComponentCached(modelSet, component)) {
                log.info("Component already cached: {}", component.getFileName());
                if (progressCallback != null) {
                    progressCallback.accept(component, 1.0);
                }
                continue;
            }

            downloadComponent(modelSet, component, progressCallback);
        }

        log.info("LLM model set download complete: {}", modelSet.getDisplayName());
    }

    /**
     * Download model sets in parallel.
     */
    public void downloadModelSets(List<LlmModelSet> modelSets,
                                   BiConsumer<LlmModelComponent, Double> progressCallback) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(modelSets.size(), Runtime.getRuntime().availableProcessors()));
        List<Future<?>> futures = new ArrayList<>();

        for (LlmModelSet modelSet : modelSets) {
            futures.add(executor.submit(() -> {
                try {
                    downloadModelSet(modelSet, progressCallback);
                } catch (IOException e) {
                    log.error("Failed to download model set: {}", modelSet.getSetId(), e);
                    throw new UncheckedIOException(e);
                }
            }));
        }

        executor.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw new IOException("Download failed", e.getCause());
            }
        }
    }

    /**
     * Delete a cached model set.
     */
    public boolean deleteModelSet(LlmModelSet modelSet) {
        Path modelDir = getModelSetDirectory(modelSet);
        if (!Files.exists(modelDir)) {
            return false;
        }

        try {
            Files.walk(modelDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });
            return true;
        } catch (IOException e) {
            log.error("Failed to delete model set directory: {}", modelDir, e);
            return false;
        }
    }

    private void downloadComponent(LlmModelSet modelSet, LlmModelComponent component,
                                    BiConsumer<LlmModelComponent, Double> progressCallback) throws IOException {
        String downloadUrl = component.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            log.warn("No download URL for component: {}", component.getComponentKey());
            return;
        }

        Path targetPath = getComponentPath(modelSet, component);
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");

        log.info("Downloading component: {} -> {}", component.getFileName(), targetPath);

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "Kompile-ModelManager/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("HTTP " + responseCode + " downloading " + downloadUrl);
            }

            long totalSize = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempPath))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    if (progressCallback != null && totalSize > 0) {
                        progressCallback.accept(component, (double) downloaded / totalSize);
                    }
                }
            }

            // Atomic move temp -> target
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Downloaded component: {} ({} bytes)", component.getFileName(), downloaded);

        } catch (IOException e) {
            // Clean up temp file on failure
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            throw e;
        }
    }
}
