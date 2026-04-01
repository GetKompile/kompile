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

package ai.kompile.anserini;

import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.anserini.config.AnseriniConfig;
import ai.kompile.core.indexers.IndexerService;

import io.anserini.search.SimpleSearcher;
import io.anserini.search.ScoredDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.index.IndexableField;
// We will use the fully qualified name for org.apache.lucene.document.Document to avoid import clashes
// import org.springframework.ai.document.Document; // Not directly used in this class's method signatures

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service("anseriniDocumentRetriever")
public class AnseriniDocumentRetrieverImpl implements DocumentRetriever {

    private static final Logger logger = LoggerFactory.getLogger(AnseriniDocumentRetrieverImpl.class);
    private  AnseriniConfig anseriniConfig;
    private SimpleSearcher searcher;
    private IndexerService indexerService;

    public AnseriniDocumentRetrieverImpl(AnseriniConfig anseriniConfig,
                                         List<IndexerService> indexerService) {
        this.anseriniConfig = anseriniConfig;
        if(indexerService.size() > 1)  {
            for(IndexerService indexerService1 : indexerService) {
                if(indexerService1 instanceof NoOpIndexerService) {
                    continue;
                } else {
                    this.indexerService = indexerService1;
                    break;
                }
            }

        } else {
            this.indexerService = indexerService.get(0);
        }

        logger.debug("AnseriniDocumentRetrieverImpl constructed.");
    }

    @PostConstruct
    public void init() {
        logger.info("Attempting to initialize AnseriniDocumentRetrieverImpl's SimpleSearcher...");
        if (!indexerService.isIndexAvailable()) {
            logger.error("Index is reported as not available by IndexerService. AnseriniDocumentRetrieverImpl cannot initialize searcher.");
            // This state means the application might not be able to perform retrieval.
            // For now, it will log and searcher will be null.
            return;
        }

        try {
            Path indexPath = Paths.get(anseriniConfig.getIndexPath());
            if (!Files.exists(indexPath) || !Files.isDirectory(indexPath) || isEmpty(indexPath)) {
                logger.error("Anserini index path {} does not exist, is not a directory, or is empty, despite IndexerService reporting it as available. This is unexpected.", indexPath);
                throw new IllegalStateException("Anserini index at " + indexPath + " is not valid for SimpleSearcher initialization, even after IndexerService check.");
            }
            this.searcher = new SimpleSearcher(anseriniConfig.getIndexPath());
            logger.info("Anserini SimpleSearcher initialized successfully for index path: {}", anseriniConfig.getIndexPath());
        } catch (IOException e) {
            logger.error("Failed to initialize Anserini SimpleSearcher at path {}: {}", anseriniConfig.getIndexPath(), e.getMessage(), e);
            throw new IllegalStateException("Could not initialize AnseriniRetriever due to IOException: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("An unexpected error occurred during Anserini SimpleSearcher initialization: {}", e.getMessage(), e);
            throw new IllegalStateException("Unexpected error initializing AnseriniRetriever", e);
        }
    }

    private boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return !entries.findFirst().isPresent();
            }
        }
        return true;
    }

    @Override
    public List<String> retrieve(String query, int maxResults) {
        // Use the detailed retrieval and extract text content for backward compatibility
        List<RetrievedDoc> detailedResults = retrieveWithDetails(query, maxResults);

        return detailedResults.stream()
                .map(RetrievedDoc::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
        if (this.searcher == null) {
            logger.error("Anserini SimpleSearcher is not initialized. Cannot perform detailed search. Indexing might have failed or index is unavailable.");
            return Collections.emptyList();
        }
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Search query is null or empty.");
            return Collections.emptyList();
        }

        logger.debug("Anserini retrieving with details for query: '{}', maxResults: {}", query, maxResults);
        try {
            ScoredDoc[] hits = searcher.search(query, maxResults);

            if (hits == null) {
                logger.warn("Anserini search returned null for query: {}", query);
                return Collections.emptyList();
            }

            logger.debug("Anserini found {} hits for query: '{}'", hits.length, query);

            return Arrays.stream(hits)
                    .map(hit -> createRetrievedDoc(hit))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("IOException during Anserini detailed search for query '{}': {}", query, e.getMessage(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Unexpected error during Anserini detailed search for query '{}': {}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Creates a RetrievedDoc from an Anserini ScoredDoc hit.
     *
     * @param hit The ScoredDoc from Anserini search results
     * @return A RetrievedDoc containing the document content and metadata, or null if document cannot be retrieved
     */
    private RetrievedDoc createRetrievedDoc(ScoredDoc hit) {
        try {
            // Use fully qualified name for org.apache.lucene.document.Document
            org.apache.lucene.document.Document luceneDoc = retrieveLuceneDocument(hit);

            if (luceneDoc == null) {
                logger.warn("Could not retrieve Lucene document for external_id: {}, lucene_id: {}", hit.docid, hit.lucene_docid);
                return createErrorRetrievedDoc(hit, "Could not retrieve document");
            }

            String content = extractContent(luceneDoc, hit.docid);
            Map<String, Object> metadata = extractMetadata(luceneDoc, hit);

            return RetrievedDoc.builder()
                    .id(hit.docid)
                    .text(content)
                    .score((double) hit.score)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            logger.warn("Error processing search hit for docid {}: {}", hit.docid, e.getMessage());
            return createErrorRetrievedDoc(hit, "Error processing result: " + e.getMessage());
        }
    }

    /**
     * Retrieves the Lucene document for a given ScoredDoc hit.
     * Tries internal lucene_docid first, then falls back to external docid.
     *
     * @param hit The ScoredDoc hit
     * @return The Lucene Document or null if not found
     */
    private org.apache.lucene.document.Document retrieveLuceneDocument(ScoredDoc hit) {
        try {
            org.apache.lucene.document.Document luceneDoc = searcher.doc(hit.lucene_docid);
            if (luceneDoc == null) {
                logger.debug("Could not retrieve Lucene document by internal luceneDocid: {}. Trying external docid: {}", hit.lucene_docid, hit.docid);
                luceneDoc = searcher.doc(hit.docid);
            }
            return luceneDoc;
        } catch (Exception e) {
            logger.warn("Exception retrieving Lucene document for docid {}: {}", hit.docid, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts content from a Lucene document.
     * Prefers 'raw' field, falls back to 'contents' field.
     *
     * @param luceneDoc The Lucene document
     * @param docId The document ID for error messages
     * @return The extracted content or an error message
     */
    private String extractContent(org.apache.lucene.document.Document luceneDoc, String docId) {
        String content = luceneDoc.get("raw");
        if (content != null) {
            return content;
        }

        content = luceneDoc.get("contents");
        if (content != null) {
            logger.trace("Retrieved from 'contents' field for docid: {}", docId);
            return content;
        }

        logger.warn("Neither 'raw' nor 'contents' field found for Lucene doc (external id: {})", docId);
        return "[Content not available in stored fields for doc " + docId + "]";
    }

    /**
     * Extracts metadata from a Lucene document and ScoredDoc hit.
     *
     * @param luceneDoc The Lucene document
     * @param hit The ScoredDoc hit
     * @return A map containing the extracted metadata
     */
    private Map<String, Object> extractMetadata(org.apache.lucene.document.Document luceneDoc, ScoredDoc hit) {
        Map<String, Object> metadata = new HashMap<>();

        // Extract metadata from all document fields
        for (IndexableField field : luceneDoc.getFields()) {
            String fieldName = field.name();
            String fieldValue = field.stringValue();

            // Skip content fields and null values
            if (shouldIncludeFieldInMetadata(fieldName, fieldValue)) {
                metadata.put(fieldName, fieldValue);
            }
        }

        // Add search-specific metadata
        addSearchMetadata(metadata, hit);

        return metadata;
    }

    /**
     * Determines whether a field should be included in metadata.
     *
     * @param fieldName The field name
     * @param fieldValue The field value
     * @return true if the field should be included in metadata
     */
    private boolean shouldIncludeFieldInMetadata(String fieldName, String fieldValue) {
        return fieldValue != null
                && !"raw".equals(fieldName)
                && !"contents".equals(fieldName);
    }

    /**
     * Adds search-specific metadata to the metadata map.
     *
     * @param metadata The metadata map to add to
     * @param hit The ScoredDoc hit
     */
    private void addSearchMetadata(Map<String, Object> metadata, ScoredDoc hit) {
        metadata.put("lucene_internal_id", hit.lucene_docid);
        metadata.put("search_score", hit.score);
        metadata.put("retriever_type", "anserini");
        metadata.put("index_path", anseriniConfig.getIndexPath());
    }

    /**
     * Creates an error RetrievedDoc for cases where document processing fails.
     *
     * @param hit The ScoredDoc hit
     * @param errorMessage The error message
     * @return A RetrievedDoc containing error information
     */
    private RetrievedDoc createErrorRetrievedDoc(ScoredDoc hit, String errorMessage) {
        Map<String, Object> errorMetadata = new HashMap<>();
        errorMetadata.put("lucene_internal_id", hit.lucene_docid);
        errorMetadata.put("search_score", hit.score);
        errorMetadata.put("error", errorMessage);
        errorMetadata.put("retriever_type", "anserini");
        errorMetadata.put("index_path", anseriniConfig.getIndexPath());

        return RetrievedDoc.builder()
                .id(hit.docid)
                .text("[" + errorMessage + " " + hit.docid + "]")
                .score((double) hit.score)
                .metadata(errorMetadata)
                .build();
    }
}