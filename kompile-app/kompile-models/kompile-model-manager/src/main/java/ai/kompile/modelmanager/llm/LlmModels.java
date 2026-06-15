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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Convenience facade for LLM model management, analogous to
 * {@link ai.kompile.modelmanager.vlm.VlmModels}.
 *
 * <p>Provides a simplified API for downloading, resolving, and managing
 * LLM model sets. Wraps {@link LlmModelSetDownloader} and {@link LlmModelResolver}.</p>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Download SmolLM
 * LlmModels.download("smollm-135m-instruct");
 *
 * // Resolve to local paths
 * LlmModelResolver.ResolvedLlmModel model = LlmModels.resolve("smollm-135m-instruct");
 *
 * // Check cache status
 * LlmModels.printCacheStatus();
 * }</pre>
 */
public final class LlmModels {

    private static final Logger log = LoggerFactory.getLogger(LlmModels.class);
    private static final LlmModelSetDownloader downloader = LlmModelSetDownloader.getInstance();
    private static final LlmModelResolver resolver = LlmModelResolver.getInstance();

    private LlmModels() {}

    // --- Download ---

    /**
     * Download a model set by ID.
     */
    public static void download(String modelSetId) throws IOException {
        download(modelSetId, null);
    }

    /**
     * Download a model set with progress tracking.
     */
    public static void download(String modelSetId, BiConsumer<LlmModelComponent, Double> progressCallback) throws IOException {
        LlmModelSet modelSet = LlmModelSet.getModelSet(modelSetId);
        if (modelSet == null) {
            throw new IllegalArgumentException("Unknown LLM model set: " + modelSetId);
        }
        downloader.downloadModelSet(modelSet, progressCallback);
    }

    /**
     * Download all known model sets.
     */
    public static void downloadAll(BiConsumer<LlmModelComponent, Double> progressCallback) throws IOException {
        downloader.downloadModelSets(new ArrayList<>(LlmModelSet.getAllModelSets().values()), progressCallback);
    }

    // --- Resolve ---

    /**
     * Resolve a model set to local paths (does not download).
     */
    public static LlmModelResolver.ResolvedLlmModel resolve(String modelSetId) {
        return resolver.resolve(modelSetId);
    }

    /**
     * Resolve a model set, downloading if needed.
     */
    public static LlmModelResolver.ResolvedLlmModel resolveAndDownload(String modelSetId) throws IOException {
        return resolver.resolveAndDownload(modelSetId);
    }

    // --- Cache Status ---

    /**
     * Check if a model set is fully cached.
     */
    public static boolean isCached(String modelSetId) {
        LlmModelSet modelSet = LlmModelSet.getModelSet(modelSetId);
        return modelSet != null && downloader.isModelSetCached(modelSet);
    }

    /**
     * Get cache status for all model sets.
     */
    public static Map<String, Boolean> getCacheStatus() {
        return downloader.getCacheStatus();
    }

    /**
     * Print cache status to log.
     */
    public static void printCacheStatus() {
        Map<String, Boolean> status = getCacheStatus();
        log.info("=== LLM Model Cache Status ===");
        for (Map.Entry<String, Boolean> entry : status.entrySet()) {
            LlmModelSet modelSet = LlmModelSet.getModelSet(entry.getKey());
            String name = modelSet != null ? modelSet.getDisplayName() : entry.getKey();
            log.info("  {} [{}]: {}", name, entry.getKey(), entry.getValue() ? "CACHED" : "NOT CACHED");
        }
    }

    /**
     * Get all available model sets.
     */
    public static Map<String, LlmModelSet> getAllModelSets() {
        return LlmModelSet.getAllModelSets();
    }

    /**
     * Delete a cached model set.
     */
    public static boolean delete(String modelSetId) {
        LlmModelSet modelSet = LlmModelSet.getModelSet(modelSetId);
        if (modelSet == null) return false;
        return downloader.deleteModelSet(modelSet);
    }

    /**
     * Get the list of model set IDs that are fully cached and ready to use.
     */
    public static List<String> getReadyModels() {
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : getCacheStatus().entrySet()) {
            if (entry.getValue()) {
                ready.add(entry.getKey());
            }
        }
        return ready;
    }
}
