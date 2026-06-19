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

package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.DocumentPreprocessor;
import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates the execution of all registered {@link DocumentPreprocessor}
 * beans in order. Filters each preprocessor's input to only applicable
 * documents and tracks per-step progress.
 *
 * <p>This runner is injected into {@code UnifiedCrawlGraphServiceImpl} and
 * called during the PREPROCESSING pipeline phase.</p>
 */
@Service
public class PreprocessingPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PreprocessingPipelineRunner.class);

    private final List<DocumentPreprocessor> preprocessors;

    @Autowired
    public PreprocessingPipelineRunner(
            @Autowired(required = false) List<DocumentPreprocessor> preprocessors) {
        this.preprocessors = preprocessors != null
                ? preprocessors.stream()
                    .sorted(Comparator.comparingInt(DocumentPreprocessor::order))
                    .toList()
                : List.of();
        if (!this.preprocessors.isEmpty()) {
            log.info("Registered {} document preprocessors: {}", this.preprocessors.size(),
                    this.preprocessors.stream()
                            .map(p -> p.id() + "(" + p.order() + ")")
                            .toList());
        }
    }

    /**
     * Run all applicable preprocessors on the given documents.
     *
     * @param documents the documents to preprocess
     * @param config    the preprocessing configuration from the crawl request
     * @param listener  optional listener for per-step progress callbacks
     * @return the preprocessed documents (may be a different list)
     */
    public List<Document> run(List<Document> documents, PreprocessingConfig config,
                               PreprocessingStepListener listener) {
        if (documents == null || documents.isEmpty()) return documents;
        if (config == null || !config.isEnabled() || !config.hasAnyStepEnabled()) {
            log.debug("Preprocessing disabled or no steps enabled — passing through {} documents",
                    documents.size());
            return documents;
        }

        List<Document> current = new ArrayList<>(documents);

        for (DocumentPreprocessor preprocessor : preprocessors) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Preprocessing interrupted before step '{}'", preprocessor.id());
                break;
            }

            // Filter to applicable documents
            List<Document> applicable = new ArrayList<>();
            List<Document> passThrough = new ArrayList<>();
            for (Document doc : current) {
                if (preprocessor.appliesTo(doc, config)) {
                    applicable.add(doc);
                } else {
                    passThrough.add(doc);
                }
            }

            if (applicable.isEmpty()) {
                if (listener != null) {
                    listener.onStepSkipped(preprocessor.id(), preprocessor.displayName(),
                            "No applicable documents");
                }
                log.debug("Preprocessor '{}' skipped — no applicable documents", preprocessor.id());
                continue;
            }

            if (listener != null) {
                listener.onStepStarted(preprocessor.id(), preprocessor.displayName(),
                        applicable.size(), current.size());
            }

            long startMs = System.currentTimeMillis();
            try {
                List<Document> processed = preprocessor.process(applicable, config);
                long elapsedMs = System.currentTimeMillis() - startMs;

                // Reassemble: passthrough + processed
                current = new ArrayList<>(passThrough.size() + processed.size());
                current.addAll(passThrough);
                current.addAll(processed);

                if (listener != null) {
                    listener.onStepCompleted(preprocessor.id(), preprocessor.displayName(),
                            applicable.size(), processed.size(), elapsedMs);
                }

                log.info("Preprocessor '{}' complete: {}/{} documents processed in {}ms (output: {})",
                        preprocessor.id(), applicable.size(), documents.size(),
                        elapsedMs, processed.size());

            } catch (Exception e) {
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.error("Preprocessor '{}' failed after {}ms: {}", preprocessor.id(),
                        elapsedMs, e.getMessage(), e);
                if (listener != null) {
                    listener.onStepFailed(preprocessor.id(), preprocessor.displayName(),
                            e.getMessage());
                }
                // On failure, keep the current documents (passthrough + unprocessed applicable)
                current = new ArrayList<>(passThrough.size() + applicable.size());
                current.addAll(passThrough);
                current.addAll(applicable);
            }
        }

        return current;
    }

    /**
     * Get the list of registered preprocessors (sorted by order).
     */
    public List<DocumentPreprocessor> getPreprocessors() {
        return preprocessors;
    }

    /**
     * Callback interface for tracking preprocessing step progress.
     */
    public interface PreprocessingStepListener {
        void onStepStarted(String stepId, String displayName,
                           int applicableCount, int totalCount);
        void onStepCompleted(String stepId, String displayName,
                              int inputCount, int outputCount, long elapsedMs);
        void onStepFailed(String stepId, String displayName, String errorMessage);
        void onStepSkipped(String stepId, String displayName, String reason);
    }
}
