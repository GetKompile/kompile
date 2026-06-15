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

package ai.kompile.app.core.extraction;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Base class for structured content extractors.
 *
 * <p>Provides common functionality for extractors that produce structured items
 * (entities, relationships, concepts, etc.) from document text. Subclasses
 * implement the actual extraction logic.</p>
 */
public abstract class AbstractStructuredExtractor implements ContentExtractor {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected boolean enabled = true;
    protected int priority = 0;
    protected int batchSize = 10;
    protected double minConfidence = 0.5;
    protected final AtomicBoolean cancelled = new AtomicBoolean(false);

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public ExtractionResult extract(RetrievedDoc document, Map<String, Object> options) throws Exception {
        return extract(document, options, null);
    }

    @Override
    public ExtractionResult extract(
            RetrievedDoc document,
            Map<String, Object> options,
            Consumer<ExtractionProgress> progressCallback) throws Exception {

        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Extraction cancelled");
        }

        String text = document.getText();
        if (text == null || text.trim().isEmpty()) {
            return ExtractionResult.builder()
                    .sourceDocumentId(document.getId())
                    .extractorType(getType())
                    .extractorName(getName())
                    .success(true)
                    .build();
        }

        if (progressCallback != null) {
            progressCallback.accept(ExtractionProgress.starting(getName(), getType(), 1));
        }

        long startTime = System.currentTimeMillis();

        try {
            List<StructuredItem> items = doExtract(document, options);

            long elapsedTime = System.currentTimeMillis() - startTime;

            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.complete(getName(), getType(), items.size()));
            }

            return ExtractionResult.ofStructuredItems(
                    document.getId(),
                    getType(),
                    getName(),
                    items,
                    elapsedTime
            );

        } catch (Exception e) {
            logger.warn("Extraction failed for document {}: {}", document.getId(), e.getMessage());
            return ExtractionResult.failed(document.getId(), e.getMessage());
        }
    }

    @Override
    public List<ExtractionResult> extractBatch(
            List<RetrievedDoc> documents,
            Map<String, Object> options) throws Exception {

        List<ExtractionResult> results = new ArrayList<>();

        // Process in batches for efficiency
        for (int i = 0; i < documents.size(); i += batchSize) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                break;
            }

            int end = Math.min(i + batchSize, documents.size());
            List<RetrievedDoc> batch = documents.subList(i, end);

            // Process batch - subclasses can override for batch-optimized extraction
            List<StructuredItem> batchItems = doExtractBatch(batch, options);

            // Create a combined result for the batch
            if (!batchItems.isEmpty()) {
                results.add(ExtractionResult.ofStructuredItems(
                        batch.get(0).getId() + "-batch",
                        getType(),
                        getName(),
                        batchItems,
                        0
                ));
            }
        }

        return results;
    }

    /**
     * Performs the actual extraction from a single document.
     * Subclasses must implement this method.
     *
     * @param document The document to extract from
     * @param options  Extraction options
     * @return List of extracted structured items
     */
    protected abstract List<StructuredItem> doExtract(RetrievedDoc document, Map<String, Object> options)
            throws Exception;

    /**
     * Performs batch extraction. Default implementation calls doExtract for each document.
     * Subclasses can override for batch-optimized extraction (e.g., LLM batching).
     *
     * @param documents The documents to extract from
     * @param options   Extraction options
     * @return List of extracted structured items from all documents
     */
    protected List<StructuredItem> doExtractBatch(List<RetrievedDoc> documents, Map<String, Object> options)
            throws Exception {
        List<StructuredItem> allItems = new ArrayList<>();
        for (RetrievedDoc doc : documents) {
            if (cancelled.get()) break;
            allItems.addAll(doExtract(doc, options));
        }
        return allItems;
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("enabled")) {
            this.enabled = (Boolean) options.get("enabled");
        }
        if (options.containsKey("priority")) {
            this.priority = ((Number) options.get("priority")).intValue();
        }
        if (options.containsKey("batchSize")) {
            this.batchSize = ((Number) options.get("batchSize")).intValue();
        }
        if (options.containsKey("minConfidence")) {
            this.minConfidence = ((Number) options.get("minConfidence")).doubleValue();
        }
    }

    @Override
    public void reset() {
        cancelled.set(false);
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Filters items by minimum confidence threshold.
     */
    protected List<StructuredItem> filterByConfidence(List<StructuredItem> items) {
        return items.stream()
                .filter(item -> item.getConfidence() >= minConfidence)
                .toList();
    }
}
