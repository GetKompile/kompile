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

package ai.kompile.app.web.controllers; // New package for controllers in the main app

import ai.kompile.core.rag.RagQuery;    // Import DTO from kompile-app-core
import ai.kompile.core.rag.RagService;    // Import interface from kompile-app-core
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
            logger.info("RagController received RAG query: '{}', useToolCalling: {}", query.getQuery(), query.isUseToolCalling());
            String answer = ragService.answerQuery(query); // Calls the interface method

            if (answer == null) { // Handle case where service might return null
                logger.error("RagService returned a null answer for query: {}", query.getQuery());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("query", query.getQuery(), "error", "Received null answer from RAG service."));
            }

            if (answer.startsWith("Error:")) {
                logger.warn("RagService indicated an error for query [{}]: {}", query.getQuery(), answer);
                // Consider if all errors from service should be 500, or if some are user errors (4xx)
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("query", query.getQuery(), "error", answer));
            }
            logger.info("RagController successfully processed query: {}", query.getQuery());
            return ResponseEntity.ok(Map.of("query", query.getQuery(), "answer", answer));
        } catch (Exception e) {
            logger.error("Unexpected error processing RAG query [{}] in RagController: {}", query.getQuery(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process RAG query due to an unexpected internal error."));
        }
    }
}