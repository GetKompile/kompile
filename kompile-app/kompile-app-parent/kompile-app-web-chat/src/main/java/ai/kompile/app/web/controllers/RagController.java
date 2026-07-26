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

package ai.kompile.app.web.controllers; // New package for controllers in the main app

import ai.kompile.core.citation.CitationDto;
import ai.kompile.core.rag.RagQuery;    // Import DTO from kompile-app-core
import ai.kompile.core.rag.RagResult;
import ai.kompile.core.rag.RagService;    // Import interface from kompile-app-core
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.citation.CitationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger logger = LoggerFactory.getLogger(RagController.class);
    private final RagService ragService; // Injecting the interface

    @Autowired // Optional on constructors from Spring 4.3+ if only one constructor
    public RagController( RagService ragService) {
        this.ragService = ragService; // Spring will inject RagServiceImpl from this module
    }

    @PostMapping("/query")
    public ResponseEntity<?> queryRAG(@RequestBody RagQuery query) {
        if (query == null || query.getQuery() == null || query.getQuery().trim().isEmpty()) {
            logger.warn("Received RAG query with empty or null query string.");
            return ResponseEntity.badRequest().body(Map.of("error", "Query cannot be empty."));
        }
        try {
            String safeQuery = query.getQuery().replace('\n', ' ').replace('\r', ' ');
            logger.info("RagController received RAG query: '{}', useToolCalling: {}", safeQuery, query.isUseToolCalling());
            RagResult answer = ragService.answerQuery(query); // Calls the interface method

            if (answer == null) { // Handle case where service might return null
                logger.error("RagService returned a null answer for query: {}", safeQuery);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("query", query.getQuery(), "error", "Received null answer from RAG service."));
            }

            if (answer.getAnswer().startsWith("Error:")) {
                logger.warn("RagService indicated an error for query [{}]: {}", safeQuery, answer);
                // Consider if all errors from service should be 500, or if some are user errors (4xx)
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("query", query.getQuery(), "error", answer));
            }
            logger.info("RagController successfully processed query: {}", safeQuery);

            // Build per-doc citation list (getMetadata() is the store-agnostic seam;
            // source accessor methods are @JsonIgnore so we extract here in the controller layer)
            List<Map<String, Object>> citations = new ArrayList<>();
            if (answer.getRetrievedDocs() != null) {
                for (RetrievedDoc doc : answer.getRetrievedDocs()) {
                    CitationDto cit = CitationSupport.from(doc.getMetadata(), doc.getScore(), null);
                    Map<String, Object> citEntry = new LinkedHashMap<>();
                    citEntry.put("docId", doc.getId());
                    citEntry.put("citation", cit);
                    citations.add(citEntry);
                }
            }

            Map<String, Object> responseBody = new LinkedHashMap<>();
            responseBody.put("query", query.getQuery());
            responseBody.put("answer", answer);
            if (!citations.isEmpty()) {
                responseBody.put("citations", citations);
            }
            return ResponseEntity.ok(responseBody);
        } catch (Exception e) {
            logger.error("Unexpected error processing RAG query in RagController: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process RAG query due to an unexpected internal error."));
        }
    }
}