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

package ai.kompile.tool.rag; // New package

import ai.kompile.core.retrievers.DocumentRetriever; // Import from core abstractions
// Assuming RagQueryInput will be defined here or in core DTOs.
// If RagQueryInput is very specific to this tool, defining it here is fine.
// If it's a general query structure, it could be in core.
// For now, keeping it as an inner record as in your original code.

import com.fasterxml.jackson.databind.ObjectMapper; // Still needed if you manually work with JSON arguments for other tools
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component // Makes it a Spring bean, discoverable if this module is on classpath
public class RagToolImpl {

    private static final Logger logger = LoggerFactory.getLogger(RagToolImpl.class);
    private final DocumentRetriever documentRetriever; // Using the interface
    private final ObjectMapper objectMapper; // Kept if other tools might need it, or for complex argument handling

    // Define a record or class for structured input if not already in core.
    // Spring AI will use this to generate the input schema for the tool.
    public record RagQueryInput(String query, Integer maxResults) {}

    @Autowired
    public RagToolImpl(DocumentRetriever documentRetriever, ObjectMapper objectMapper) {
        this.documentRetriever = documentRetriever;
        this.objectMapper = objectMapper; // ObjectMapper can be useful, but not strictly used in this method's current form
        logger.debug("RagToolImpl constructed with DocumentRetriever: {}", documentRetriever.getClass().getSimpleName());
    }

    /**
     * This method is exposed as an MCP tool.
     * It takes a query and retrieves relevant document snippets.
     */
    @Tool(name = "rag_query",
            description = "Queries the document corpus using the configured retriever and returns relevant information snippets. Optionally, provide maxResults to limit document count (default 3, max 10).")
    public Map<String, Object> executeRagQuery(RagQueryInput input) {
        logger.info("RagTool: Executing RAG Query with input: {}", input);

        if (input.query() == null || input.query().trim().isEmpty()) {
            logger.warn("RagTool: Query is empty.");
            return Map.of("error", "Query cannot be empty.", "query", input.query(), "retrieved_documents", Collections.emptyList());
        }

        int maxDocs = (input.maxResults() != null && input.maxResults() > 0 && input.maxResults() <= 10) ? input.maxResults() : 3;

        try {
            List<String> retrievedDocs = documentRetriever.retrieve(input.query(), maxDocs);

            if (retrievedDocs.isEmpty() || (retrievedDocs.size() == 1 && retrievedDocs.get(0).startsWith("Error:"))) {
                logger.warn("RagTool: No documents found or error in retrieval for query: {}", input.query());
                return Map.of("query", input.query(), "status", "No relevant documents found or error in retrieval.", "retrieved_documents", retrievedDocs);
            }

            logger.info("RagTool: Successfully retrieved {} documents for query: {}", retrievedDocs.size(), input.query());
            return Map.of("query", input.query(), "status", "Successfully retrieved documents.", "retrieved_documents", retrievedDocs);

        } catch (Exception e) {
            logger.error("RagTool: Error during document retrieval for query [{}]: {}", input.query(), e.getMessage(), e);
            return Map.of("query", input.query(), "error", "Failed during document retrieval: " + e.getMessage(), "retrieved_documents", Collections.emptyList());
        }
    }
}