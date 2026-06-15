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
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves LLM model set IDs to local file paths, analogous to
 * {@link ai.kompile.modelmanager.vlm.VlmModelResolver}.
 *
 * <p>Works with {@link LlmModelSetDownloader} to ensure models are available
 * locally before returning paths. Supports automatic download of missing models.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * LlmModelResolver resolver = LlmModelResolver.getInstance();
 *
 * // Resolve with auto-download
 * ResolvedLlmModel model = resolver.resolve("smollm-135m-instruct");
 * Path decoderPath = model.getComponentPath("decoder");
 * }</pre>
 */
public class LlmModelResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmModelResolver.class);
    private static final LlmModelResolver INSTANCE = new LlmModelResolver();

    private final LlmModelSetDownloader downloader;

    private LlmModelResolver() {
        this.downloader = LlmModelSetDownloader.getInstance();
    }

    public static LlmModelResolver getInstance() {
        return INSTANCE;
    }

    /**
     * Resolve a model set ID to local paths. Does not download missing models.
     *
     * @param modelSetId The model set ID (e.g., "smollm-135m-instruct")
     * @return Resolved model with local paths, or null if model set unknown
     */
    public ResolvedLlmModel resolve(String modelSetId) {
        LlmModelSet modelSet = LlmModelSet.getModelSet(modelSetId);
        if (modelSet == null) {
            log.warn("Unknown LLM model set: {}", modelSetId);
            return null;
        }
        return resolveModelSet(modelSet);
    }

    /**
     * Resolve a model set, downloading missing components if needed.
     */
    public ResolvedLlmModel resolveAndDownload(String modelSetId) throws IOException {
        LlmModelSet modelSet = LlmModelSet.getModelSet(modelSetId);
        if (modelSet == null) {
            throw new IllegalArgumentException("Unknown LLM model set: " + modelSetId);
        }
        ensureDownloaded(modelSet);
        return resolveModelSet(modelSet);
    }

    /**
     * Ensure a model set is downloaded.
     */
    public void ensureDownloaded(LlmModelSet modelSet) throws IOException {
        if (!downloader.isModelSetCached(modelSet)) {
            log.info("Model set not cached, downloading: {}", modelSet.getSetId());
            downloader.downloadModelSet(modelSet, (component, progress) ->
                    log.debug("Downloading {}: {:.0f}%", component.getFileName(), progress * 100));
        }
    }

    /**
     * Resolve for a specific generation configuration, selecting the appropriate model.
     */
    public ResolvedLlmModel resolveForConfig(LlmGenerationConfig config, String defaultModelSetId) {
        String modelSetId = defaultModelSetId;
        Map<String, Object> overrides = config.getModelOverrides();
        if (overrides != null && overrides.containsKey("model_set_id")) {
            modelSetId = (String) overrides.get("model_set_id");
        }
        return resolve(modelSetId);
    }

    private ResolvedLlmModel resolveModelSet(LlmModelSet modelSet) {
        Map<String, Path> componentPaths = new LinkedHashMap<>();
        boolean allCached = true;

        for (LlmModelComponent component : modelSet.getComponents()) {
            Path path = downloader.getComponentPath(modelSet, component);
            componentPaths.put(component.getComponentKey(), path);
            if (!downloader.isComponentCached(modelSet, component)) {
                allCached = false;
            }
        }

        return new ResolvedLlmModel(modelSet, componentPaths, allCached);
    }

    /**
     * A resolved model with local file paths for each component.
     */
    public static class ResolvedLlmModel {
        private final LlmModelSet modelSet;
        private final Map<String, Path> componentPaths;
        private final boolean fullyCached;

        public ResolvedLlmModel(LlmModelSet modelSet, Map<String, Path> componentPaths, boolean fullyCached) {
            this.modelSet = modelSet;
            this.componentPaths = Collections.unmodifiableMap(componentPaths);
            this.fullyCached = fullyCached;
        }

        public LlmModelSet getModelSet() { return modelSet; }
        public Map<String, Path> getComponentPaths() { return componentPaths; }
        public boolean isFullyCached() { return fullyCached; }

        public Path getComponentPath(String componentKey) {
            return componentPaths.get(componentKey);
        }

        public Path getEmbedTokensPath() { return componentPaths.get("embed_tokens"); }
        public Path getDecoderPath() { return componentPaths.get("decoder"); }
        public Path getTokenizerPath() { return componentPaths.get("tokenizer"); }

        @Override
        public String toString() {
            return "ResolvedLlmModel{" +
                    "setId='" + modelSet.getSetId() + '\'' +
                    ", fullyCached=" + fullyCached +
                    ", components=" + componentPaths.keySet() +
                    '}';
        }
    }
}
