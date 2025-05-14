/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.anserini;

import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.anserini.config.AnseriniConfig;
import ai.kompile.core.indexers.IndexerService;

import io.anserini.search.SimpleSearcher;
import io.anserini.search.ScoredDoc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service("anseriniDocumentRetriever")
public class AnseriniDocumentRetrieverImpl implements DocumentRetriever {

    private static final Logger logger = LogManager.getLogger(AnseriniDocumentRetrieverImpl.class);
    private final AnseriniConfig anseriniConfig;
    private SimpleSearcher searcher;
    private final IndexerService indexerService;

    public AnseriniDocumentRetrieverImpl(AnseriniConfig anseriniConfig,
                                         IndexerService indexerService) {
        this.anseriniConfig = anseriniConfig;
        this.indexerService = indexerService;
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
        if (this.searcher == null) {
            logger.error("Anserini SimpleSearcher is not initialized. Cannot perform search. Indexing might have failed or index is unavailable.");
            return Collections.singletonList("Error: Searcher not initialized or Anserini index is missing/corrupt.");
        }
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Search query is null or empty.");
            return Collections.emptyList();
        }

        logger.debug("Anserini retrieving for query: '{}', maxResults: {}", query, maxResults);
        try {
            ScoredDoc[] hits = searcher.search(query, maxResults);

            if (hits == null) {
                logger.warn("Anserini search returned null for query: {}", query);
                return Collections.emptyList();
            }

            logger.debug("Anserini found {} hits for query: '{}'", hits.length, query);

            return Arrays.stream(hits)
                    .map(hit -> {
                        // Use fully qualified name for org.apache.lucene.document.Document
                        org.apache.lucene.document.Document luceneDoc = searcher.doc(hit.lucene_docid);
                        if (luceneDoc == null) {
                            logger.warn("Could not retrieve Lucene document by internal luceneDocid: {}. Trying external docid: {}", hit.lucene_docid, hit.docid);
                            luceneDoc = searcher.doc(hit.docid);
                        }

                        if (luceneDoc != null) {
                            String rawContent = luceneDoc.get("raw");
                            if (rawContent != null) {
                                return rawContent;
                            } else {
                                String contentsField = luceneDoc.get("contents");
                                if (contentsField != null) {
                                    logger.trace("Retrieved from 'contents' field for docid: {}", hit.docid);
                                    return contentsField;
                                }
                            }
                            logger.warn("Neither 'raw' nor 'contents' field found for Lucene doc (external id: {} / internal id: {})", hit.docid, hit.lucene_docid);
                            return "[Content not available in stored fields for doc " + hit.docid + "]";
                        } else {
                            logger.warn("Could not retrieve Lucene document for external_id: {}, lucene_id: {}", hit.docid, hit.lucene_docid);
                            return "[Could not retrieve document " + hit.docid + "]";
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("IOException during Anserini search for query '{}': {}", query, e.getMessage(), e);
            return Collections.singletonList("Error performing search: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during Anserini search for query '{}': {}", query, e.getMessage(), e);
            return Collections.singletonList("Unexpected error during search: " + e.getMessage());
        }
    }
}