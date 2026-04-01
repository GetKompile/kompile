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

package ai.kompile.core.loaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;

/**
 * A composite document loader that tries multiple PDF loaders and selects the one
 * that extracts the most content. This is useful for handling different types of PDFs
 * (text-based vs image-based/scanned) where different loaders may perform better.
 *
 * <p>The loader tests each available PDF loader in sequence and compares the total
 * content length extracted. The result from the loader that extracts the most text
 * is returned, along with metadata about which loader was selected and why.</p>
 *
 * <p>Comparison metrics include:
 * <ul>
 *   <li>Total character count across all documents</li>
 *   <li>Word count (for tie-breaking)</li>
 *   <li>Number of documents extracted</li>
 * </ul>
 */
public class CompositePdfLoader implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(CompositePdfLoader.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    private final List<DocumentLoader> pdfLoaders;
    private final boolean returnComparisonMetadata;

    /**
     * Result of loading a PDF with multiple loaders.
     */
    public static class LoaderComparisonResult {
        private final DocumentLoader selectedLoader;
        private final List<Document> documents;
        private final Map<String, LoaderStats> loaderStats;
        private final String selectionReason;

        public LoaderComparisonResult(DocumentLoader selectedLoader, List<Document> documents,
                                       Map<String, LoaderStats> loaderStats, String selectionReason) {
            this.selectedLoader = selectedLoader;
            this.documents = documents;
            this.loaderStats = loaderStats;
            this.selectionReason = selectionReason;
        }

        public DocumentLoader getSelectedLoader() { return selectedLoader; }
        public List<Document> getDocuments() { return documents; }
        public Map<String, LoaderStats> getLoaderStats() { return loaderStats; }
        public String getSelectionReason() { return selectionReason; }
    }

    /**
     * Statistics for a single loader's extraction attempt.
     */
    public static class LoaderStats {
        private final String loaderName;
        private final String loaderClassName;
        private final int documentCount;
        private final long totalCharacters;
        private final long totalWords;
        private final boolean hadError;
        private final String errorMessage;
        private final long processingTimeMs;

        public LoaderStats(String loaderName, String loaderClassName, int documentCount,
                          long totalCharacters, long totalWords, boolean hadError,
                          String errorMessage, long processingTimeMs) {
            this.loaderName = loaderName;
            this.loaderClassName = loaderClassName;
            this.documentCount = documentCount;
            this.totalCharacters = totalCharacters;
            this.totalWords = totalWords;
            this.hadError = hadError;
            this.errorMessage = errorMessage;
            this.processingTimeMs = processingTimeMs;
        }

        public String getLoaderName() { return loaderName; }
        public String getLoaderClassName() { return loaderClassName; }
        public int getDocumentCount() { return documentCount; }
        public long getTotalCharacters() { return totalCharacters; }
        public long getTotalWords() { return totalWords; }
        public boolean isHadError() { return hadError; }
        public String getErrorMessage() { return errorMessage; }
        public long getProcessingTimeMs() { return processingTimeMs; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("loaderName", loaderName);
            map.put("loaderClassName", loaderClassName);
            map.put("documentCount", documentCount);
            map.put("totalCharacters", totalCharacters);
            map.put("totalWords", totalWords);
            map.put("hadError", hadError);
            if (errorMessage != null) {
                map.put("errorMessage", errorMessage);
            }
            map.put("processingTimeMs", processingTimeMs);
            return map;
        }
    }

    /**
     * Creates a composite PDF loader with the given list of PDF loaders.
     *
     * @param pdfLoaders the list of PDF loaders to try
     */
    public CompositePdfLoader(List<DocumentLoader> pdfLoaders) {
        this(pdfLoaders, true);
    }

    /**
     * Creates a composite PDF loader with the given list of PDF loaders.
     *
     * @param pdfLoaders the list of PDF loaders to try
     * @param returnComparisonMetadata whether to include comparison metadata in returned documents
     */
    public CompositePdfLoader(List<DocumentLoader> pdfLoaders, boolean returnComparisonMetadata) {
        this.pdfLoaders = new ArrayList<>(pdfLoaders);
        this.returnComparisonMetadata = returnComparisonMetadata;
    }

    @Override
    public String getName() {
        return "Composite PDF Loader (Auto-Select Best)";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            return false;
        }
        String path = sourceDescriptor.getPathOrUrl() != null ? sourceDescriptor.getPathOrUrl().toLowerCase() : "";
        return SUPPORTED_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        LoaderComparisonResult result = loadWithComparison(sourceDescriptor);
        return result.getDocuments();
    }

    /**
     * Loads the document using all available PDF loaders, compares results, and returns
     * the best result along with comparison metadata.
     *
     * @param sourceDescriptor the document source descriptor
     * @return comparison result with selected loader, documents, and stats
     * @throws Exception if all loaders fail
     */
    public LoaderComparisonResult loadWithComparison(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (pdfLoaders.isEmpty()) {
            throw new IllegalStateException("No PDF loaders available for composite loading");
        }

        logger.info("Composite PDF Loader: Testing {} loaders for file: {}",
                   pdfLoaders.size(), sourceDescriptor.getPathOrUrl());

        Map<String, LoaderStats> allStats = new LinkedHashMap<>();
        Map<DocumentLoader, List<Document>> results = new LinkedHashMap<>();

        // Try each loader
        for (DocumentLoader loader : pdfLoaders) {
            if (!loader.supports(sourceDescriptor)) {
                logger.debug("Loader {} does not support this file, skipping", loader.getName());
                continue;
            }

            long startTime = System.currentTimeMillis();
            try {
                List<Document> docs = loader.load(sourceDescriptor);
                long processingTime = System.currentTimeMillis() - startTime;

                LoaderStats stats = calculateStats(loader, docs, processingTime);
                allStats.put(loader.getName(), stats);
                results.put(loader, docs);

                logger.info("Loader '{}' extracted {} characters from {} documents in {}ms",
                           loader.getName(), stats.getTotalCharacters(),
                           stats.getDocumentCount(), processingTime);

            } catch (Exception e) {
                long processingTime = System.currentTimeMillis() - startTime;
                logger.warn("Loader '{}' failed with error: {}", loader.getName(), e.getMessage());

                LoaderStats errorStats = new LoaderStats(
                    loader.getName(),
                    loader.getClass().getName(),
                    0, 0, 0,
                    true,
                    e.getMessage(),
                    processingTime
                );
                allStats.put(loader.getName(), errorStats);
            }
        }

        if (results.isEmpty()) {
            throw new RuntimeException("All PDF loaders failed to extract content from: " +
                                      sourceDescriptor.getPathOrUrl());
        }

        // Select the best loader based on content extraction
        DocumentLoader bestLoader = selectBestLoader(results, allStats);
        List<Document> bestDocs = results.get(bestLoader);
        LoaderStats bestStats = allStats.get(bestLoader.getName());

        String selectionReason = buildSelectionReason(bestLoader, bestStats, allStats);

        logger.info("Selected loader '{}' with {} characters extracted. Reason: {}",
                   bestLoader.getName(), bestStats.getTotalCharacters(), selectionReason);

        // Add comparison metadata to documents if enabled
        if (returnComparisonMetadata) {
            addComparisonMetadata(bestDocs, bestLoader, allStats, selectionReason);
        }

        return new LoaderComparisonResult(bestLoader, bestDocs, allStats, selectionReason);
    }

    /**
     * Calculate statistics for a loader's extraction result.
     */
    private LoaderStats calculateStats(DocumentLoader loader, List<Document> docs, long processingTime) {
        long totalChars = 0;
        long totalWords = 0;

        for (Document doc : docs) {
            String content = doc.getText();
            if (content != null) {
                totalChars += content.length();
                // Count words (split by whitespace)
                totalWords += content.split("\\s+").length;
            }
        }

        return new LoaderStats(
            loader.getName(),
            loader.getClass().getName(),
            docs.size(),
            totalChars,
            totalWords,
            false,
            null,
            processingTime
        );
    }

    /**
     * Select the best loader based on extraction results.
     * Primary criterion is total characters extracted.
     * Secondary criterion is word count (for tie-breaking).
     */
    private DocumentLoader selectBestLoader(Map<DocumentLoader, List<Document>> results,
                                            Map<String, LoaderStats> allStats) {
        DocumentLoader best = null;
        long bestScore = -1;

        for (Map.Entry<DocumentLoader, List<Document>> entry : results.entrySet()) {
            DocumentLoader loader = entry.getKey();
            LoaderStats stats = allStats.get(loader.getName());

            if (stats == null || stats.isHadError()) {
                continue;
            }

            // Score is primarily based on character count, with word count as tie-breaker
            // Use character count directly - higher is better
            long score = stats.getTotalCharacters();

            // Check if this result looks like an error document
            boolean isErrorDocument = false;
            for (Document doc : entry.getValue()) {
                String text = doc.getText();
                if (text != null && (text.startsWith("[Error:") ||
                    Boolean.TRUE.equals(doc.getMetadata().get("parseError")))) {
                    isErrorDocument = true;
                    break;
                }
            }

            if (isErrorDocument) {
                // Heavily penalize error documents
                score = 0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = loader;
            } else if (score == bestScore && best != null) {
                // Tie-breaker: prefer loader with more words
                LoaderStats currentBestStats = allStats.get(best.getName());
                if (stats.getTotalWords() > currentBestStats.getTotalWords()) {
                    best = loader;
                }
            }
        }

        // Fallback to first result if no clear winner
        if (best == null) {
            best = results.keySet().iterator().next();
        }

        return best;
    }

    /**
     * Build a human-readable reason for why a loader was selected.
     */
    private String buildSelectionReason(DocumentLoader selected, LoaderStats selectedStats,
                                        Map<String, LoaderStats> allStats) {
        StringBuilder reason = new StringBuilder();
        reason.append(selected.getName()).append(" extracted the most content (");
        reason.append(selectedStats.getTotalCharacters()).append(" characters, ");
        reason.append(selectedStats.getTotalWords()).append(" words");

        if (allStats.size() > 1) {
            reason.append("). Compared against: ");
            List<String> comparisons = new ArrayList<>();
            for (Map.Entry<String, LoaderStats> entry : allStats.entrySet()) {
                if (!entry.getKey().equals(selected.getName())) {
                    LoaderStats stats = entry.getValue();
                    if (stats.isHadError()) {
                        comparisons.add(entry.getKey() + " (failed: " + stats.getErrorMessage() + ")");
                    } else {
                        comparisons.add(entry.getKey() + " (" + stats.getTotalCharacters() + " chars)");
                    }
                }
            }
            reason.append(String.join(", ", comparisons));
        }

        reason.append(")");
        return reason.toString();
    }

    /**
     * Add comparison metadata to the documents.
     */
    private void addComparisonMetadata(List<Document> docs, DocumentLoader selectedLoader,
                                       Map<String, LoaderStats> allStats, String selectionReason) {
        Map<String, Object> comparisonData = new LinkedHashMap<>();
        comparisonData.put("compositeLoaderUsed", true);
        comparisonData.put("selectedLoader", selectedLoader.getName());
        comparisonData.put("selectionReason", selectionReason);
        comparisonData.put("loadersCompared", allStats.size());

        // Add per-loader stats
        Map<String, Object> statsMap = new LinkedHashMap<>();
        for (Map.Entry<String, LoaderStats> entry : allStats.entrySet()) {
            statsMap.put(entry.getKey(), entry.getValue().toMap());
        }
        comparisonData.put("loaderStats", statsMap);

        // Add metadata to all documents
        for (Document doc : docs) {
            doc.getMetadata().put("compositeLoader", comparisonData);
        }
    }

    /**
     * Get the list of PDF loaders being used by this composite loader.
     */
    public List<DocumentLoader> getPdfLoaders() {
        return Collections.unmodifiableList(pdfLoaders);
    }
}
