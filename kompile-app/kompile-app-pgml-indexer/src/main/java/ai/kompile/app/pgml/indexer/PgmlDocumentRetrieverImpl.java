/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.app.pgml.indexer;

import ai.kompile.app.pgml.indexer.config.PgmlIndexerProperties;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service("pgmlDocumentRetriever")
public class PgmlDocumentRetrieverImpl implements DocumentRetriever {

    private static final Logger logger = LoggerFactory.getLogger(PgmlDocumentRetrieverImpl.class);
    
    private final PgmlIndexerProperties properties;
    private  VectorStore vectorStore;
    private  IndexerService indexerService;
    
    // Default similarity threshold for searches (can be made configurable)
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.0;

    public PgmlDocumentRetrieverImpl(PgmlIndexerProperties properties,
                                     List<VectorStore> vectorStore,
                                     List<IndexerService> indexerService) {
        this.properties = properties;


        if(vectorStore.size() > 1) {
            for(VectorStore vectorStore1 : vectorStore) {
                if(vectorStore1 instanceof NoOpVectorStoreImpl) {
                    continue;
                } else {
                    this.vectorStore = vectorStore1;
                }
            }

        } else {
            this.vectorStore = vectorStore.get(0);
        }


        if(indexerService.size() > 1) {
            for(IndexerService indexerService1 : indexerService) {
                if(indexerService1 instanceof NoOpIndexerService) {
                    continue;
                } else {
                    this.indexerService = indexerService1;
                }
            }

        } else {
            this.indexerService = indexerService.get(0);
        }


        logger.debug("PgmlDocumentRetrieverImpl constructed with VectorStore: {} and IndexerService: {}",
                    vectorStore.getClass().getName(), indexerService.getClass().getName());
    }

    @PostConstruct
    public void init() {
        logger.info("Attempting to initialize PgmlDocumentRetrieverImpl...");
        
        if (!indexerService.isIndexAvailable()) {
            logger.error("Index is reported as not available by IndexerService. PgmlDocumentRetrieverImpl may not function properly.");
            // Unlike Anserini, we don't fail initialization here since VectorStore might be lazily initialized
            // or the index might become available later
        }
        
        if (vectorStore == null) {
            logger.error("VectorStore is null. PgmlDocumentRetrieverImpl cannot function without a VectorStore.");
            throw new IllegalStateException("VectorStore cannot be null for PgmlDocumentRetrieverImpl");
        }
        
        logger.info("PgmlDocumentRetrieverImpl initialized successfully with VectorStore: {} (expected to use default collection: '{}')",
                vectorStore.getClass().getName(), properties.getDefaultCollectionName());
    }

    private String getEffectiveCollectionName(String collectionNameFromParam) {
        return StringUtils.hasText(collectionNameFromParam) ? collectionNameFromParam : properties.getDefaultCollectionName();
    }

    @Override
    public List<String> retrieve(String query, int maxResults) {
        if (vectorStore == null) {
            logger.error("VectorStore is not initialized. Cannot perform search.");
            return Collections.singletonList("Error: VectorStore not initialized.");
        }
        
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Search query is null or empty.");
            return Collections.emptyList();
        }

        logger.debug("PGML retrieving for query: '{}', maxResults: {}", query, maxResults);
        
        try {
            // Use VectorStore's similarity search with query string
            List<Document> documents = vectorStore.similaritySearch(query, maxResults, DEFAULT_SIMILARITY_THRESHOLD);
            
            if (documents == null || documents.isEmpty()) {
                logger.debug("PGML search returned no results for query: '{}'", query);
                return Collections.emptyList();
            }

            logger.debug("PGML found {} documents for query: '{}'", documents.size(), query);

            return documents.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            logger.error("Error during PGML search for query '{}': {}", query, e.getMessage(), e);
            return Collections.singletonList("Error performing search: " + e.getMessage());
        }
    }

    @Override
    public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
        if (vectorStore == null) {
            logger.error("VectorStore is not initialized. Cannot perform detailed search.");
            return Collections.emptyList();
        }
        
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Search query is null or empty.");
            return Collections.emptyList();
        }

        logger.debug("PGML retrieving with details for query: '{}', maxResults: {}", query, maxResults);
        
        try {
            // Use VectorStore's similarity search with query string
            List<Document> documents = vectorStore.similaritySearch(query, maxResults, DEFAULT_SIMILARITY_THRESHOLD);
            
            if (documents == null || documents.isEmpty()) {
                logger.debug("PGML search returned no results for query: '{}'", query);
                return Collections.emptyList();
            }

            logger.debug("PGML found {} documents for query: '{}'", documents.size(), query);

            return IntStream.range(0, documents.size())
                    .mapToObj(i -> {
                        Document doc = documents.get(i);
                        try {
                            String content = doc.getText();
                            if (content == null) {
                                content = "[Content not available for document " + doc.getId() + "]";
                            }

                            // Extract metadata from Spring AI Document
                            Map<String, Object> metadata = new HashMap<>();
                            if (doc.getMetadata() != null) {
                                metadata.putAll(doc.getMetadata());
                            }
                            
                            // Add search-specific metadata
                            metadata.put("search_rank", i + 1);
                            metadata.put("collection_name", getEffectiveCollectionName(null));
                            
                            // Spring AI Document similarity scores are not always available
                            // We use the rank as a proxy for relevance score (higher rank = lower score)
                            float score = 1.0f / (i + 1);
                            
                            return new RetrievedDoc(doc.getId(), content, score, metadata);
                            
                        } catch (Exception e) {
                            logger.warn("Error processing search result for document {}: {}", doc.getId(), e.getMessage());
                            Map<String, Object> errorMetadata = new HashMap<>();
                            errorMetadata.put("search_rank", i + 1);
                            errorMetadata.put("error", "Error processing result: " + e.getMessage());
                            errorMetadata.put("collection_name", getEffectiveCollectionName(null));
                            
                            return new RetrievedDoc(doc.getId(), "[Error processing result]", 0.0f, errorMetadata);
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            logger.error("Error during PGML detailed search for query '{}': {}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Retrieves documents from a specific collection/table.
     * This method extends the basic interface to support PGML's collection-based architecture.
     * 
     * @param query The search query string
     * @param maxResults Maximum number of results to return
     * @param collectionName The specific collection/table name to search in
     * @return List of document contents
     */
    public List<String> retrieve(String query, int maxResults, String collectionName) {
        String effectiveCollectionName = getEffectiveCollectionName(collectionName);
        logger.debug("PGML retrieving from collection '{}' for query: '{}', maxResults: {}", 
                    effectiveCollectionName, query, maxResults);
        
        // Note: The current VectorStore interface doesn't support collection-specific searches
        // This would need to be implemented in the specific VectorStore implementation
        // For now, we log the collection context and use the default behavior
        logger.warn("Collection-specific search not fully supported by generic VectorStore interface. " +
                   "Using default collection behavior for collection: '{}'", effectiveCollectionName);
        
        return retrieve(query, maxResults);
    }

    /**
     * Retrieves documents with details from a specific collection/table.
     * 
     * @param query The search query string
     * @param maxResults Maximum number of results to return
     * @param collectionName The specific collection/table name to search in
     * @return List of RetrievedDoc objects with detailed information
     */
    public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults, String collectionName) {
        String effectiveCollectionName = getEffectiveCollectionName(collectionName);
        logger.debug("PGML retrieving with details from collection '{}' for query: '{}', maxResults: {}", 
                    effectiveCollectionName, query, maxResults);
        
        // Note: The current VectorStore interface doesn't support collection-specific searches
        logger.warn("Collection-specific detailed search not fully supported by generic VectorStore interface. " +
                   "Using default collection behavior for collection: '{}'", effectiveCollectionName);
        
        return retrieveWithDetails(query, maxResults);
    }
}
