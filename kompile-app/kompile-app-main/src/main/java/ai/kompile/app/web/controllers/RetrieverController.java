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

package ai.kompile.app.web.controllers;

import ai.kompile.core.retrievers.DocumentRetriever; // From core-abstractions
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retriever")
public class RetrieverController {

    private static final Logger logger = LoggerFactory.getLogger(RetrieverController.class);
    private final DocumentRetriever documentRetriever;

    @Autowired
    public RetrieverController(DocumentRetriever documentRetriever) {
        // We inject the specific DocumentRetriever if multiple exist,
        // or just DocumentRetriever if AnseriniDocumentRetrieverImpl is the only one or @Primary
        this.documentRetriever = documentRetriever;
        logger.info("RetrieverController initialized with DocumentRetriever: {}", documentRetriever.getClass().getSimpleName());
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchDocuments(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int maxResults) { // Changed default to 5

        if (query == null || query.trim().isEmpty()) {
            logger.warn("Received direct search request with empty or null query.");
            return ResponseEntity.badRequest().body(Map.of("error", "Query cannot be empty."));
        }
        if (maxResults <= 0 || maxResults > 50) { // Added upper bound for direct searches
            logger.warn("Received direct search request with invalid maxResults: {}", maxResults);
            return ResponseEntity.badRequest().body(Map.of("error", "maxResults must be between 1 and 50."));
        }

        try {
            logger.info("RetrieverController received direct search: '{}', maxResults: {}", query, maxResults);
            List<String> results = documentRetriever.retrieve(query, maxResults);

            if (results == null) {
                logger.error("DocumentRetriever returned null for query [{}]", query);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("query", query, "error", "Retriever returned null results."));
            }

            if (!results.isEmpty() && results.get(0) != null && results.get(0).startsWith("Error:")) {
                logger.warn("DocumentRetriever indicated an error for query [{}]: {}", query, results.get(0));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("query", query, "error", results.get(0)));
            }

            logger.info("RetrieverController successfully processed direct search for query: {}", query);
            return ResponseEntity.ok(Map.of("query", query, "maxResults", maxResults, "hits", results));
        } catch (Exception e) {
            logger.error("Error during direct document retrieval via controller for query [{}]: {}", query, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Direct document retrieval failed: " + e.getMessage()));
        }
    }
}