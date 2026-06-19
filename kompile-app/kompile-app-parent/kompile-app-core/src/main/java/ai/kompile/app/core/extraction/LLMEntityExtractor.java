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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;

/**
 * LLM-based entity extractor that uses a language model to identify
 * named entities and their relationships in document text.
 *
 * <p>This extractor can identify:</p>
 * <ul>
 *   <li>PERSON - People mentioned in the text</li>
 *   <li>ORGANIZATION - Companies, institutions, groups</li>
 *   <li>LOCATION - Places, addresses, geographic features</li>
 *   <li>DATE - Dates, time periods, temporal references</li>
 *   <li>PRODUCT - Products, services, technologies</li>
 *   <li>EVENT - Events, meetings, occurrences</li>
 *   <li>CONCEPT - Abstract concepts, theories, ideas</li>
 * </ul>
 *
 * <p>The extractor also identifies relationships between entities.</p>
 */
public class LLMEntityExtractor extends AbstractStructuredExtractor {

    private static final String DEFAULT_EXTRACTION_PROMPT = """
            Extract named entities and their relationships from the following text.
            Return a JSON object with the following structure:
            {
              "entities": [
                {
                  "name": "entity name",
                  "type": "PERSON|ORGANIZATION|LOCATION|DATE|PRODUCT|EVENT|CONCEPT",
                  "description": "brief description or context",
                  "confidence": 0.0-1.0
                }
              ],
              "relationships": [
                {
                  "source": "source entity name",
                  "target": "target entity name",
                  "type": "relationship type (e.g., WORKS_AT, LOCATED_IN, PART_OF)",
                  "description": "brief description of the relationship",
                  "confidence": 0.0-1.0
                }
              ]
            }

            Text to analyze:
            ---
            %s
            ---

            Return ONLY the JSON object, no other text.
            """;

    private final Function<String, String> llmInvoker;
    private final ObjectMapper objectMapper;
    private String extractionPrompt = DEFAULT_EXTRACTION_PROMPT;

    /**
     * Creates an LLM entity extractor with the given LLM invoker function.
     *
     * @param llmInvoker Function that takes a prompt and returns the LLM response
     */
    public LLMEntityExtractor(Function<String, String> llmInvoker) {
        this.llmInvoker = llmInvoker;
        this.objectMapper = JsonUtils.standardMapper();
        this.batchSize = 5; // LLM calls are expensive, smaller batches
    }

    @Override
    public String getName() {
        return "llm-entity-extractor";
    }

    @Override
    public ExtractorType getType() {
        return ExtractorType.ENTITY;
    }

    @Override
    protected List<StructuredItem> doExtract(RetrievedDoc document, Map<String, Object> options) throws Exception {
        String text = document.getText();
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        // Truncate very long text to avoid LLM token limits
        int maxLength = options.containsKey("maxTextLength")
                ? ((Number) options.get("maxTextLength")).intValue()
                : 8000;
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength) + "...";
        }

        // Create prompt and invoke LLM
        String prompt = String.format(extractionPrompt, text);
        String response = llmInvoker.apply(prompt);

        // Parse response
        return parseExtractionResponse(response, document.getId());
    }

    @Override
    protected List<StructuredItem> doExtractBatch(List<RetrievedDoc> documents, Map<String, Object> options)
            throws Exception {
        // For LLM extraction, batch by combining texts (if supported)
        // Default implementation processes one at a time
        return super.doExtractBatch(documents, options);
    }

    private List<StructuredItem> parseExtractionResponse(String response, String sourceDocId) {
        List<StructuredItem> items = new ArrayList<>();

        try {
            // Clean response - remove markdown code blocks if present
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf('\n') + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```"));
                }
            }

            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            // Parse entities
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entities = (List<Map<String, Object>>) parsed.get("entities");
            if (entities != null) {
                for (Map<String, Object> entity : entities) {
                    String name = (String) entity.get("name");
                    String type = (String) entity.get("type");
                    String description = (String) entity.get("description");
                    double confidence = entity.containsKey("confidence")
                            ? ((Number) entity.get("confidence")).doubleValue()
                            : 0.8;

                    items.add(StructuredItem.entity(name, type, description, confidence, sourceDocId));
                }
            }

            // Parse relationships
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relationships = (List<Map<String, Object>>) parsed.get("relationships");
            if (relationships != null) {
                for (Map<String, Object> rel : relationships) {
                    String source = (String) rel.get("source");
                    String target = (String) rel.get("target");
                    String type = (String) rel.get("type");
                    String description = (String) rel.get("description");
                    double confidence = rel.containsKey("confidence")
                            ? ((Number) rel.get("confidence")).doubleValue()
                            : 0.7;

                    items.add(StructuredItem.relationship(source, target, type, description, confidence, sourceDocId));
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to parse LLM extraction response: {}", e.getMessage());
            logger.debug("Raw response: {}", response);
        }

        return filterByConfidence(items);
    }

    @Override
    public void configure(Map<String, Object> options) {
        super.configure(options);

        if (options.containsKey("extractionPrompt")) {
            this.extractionPrompt = (String) options.get("extractionPrompt");
        }
    }

    /**
     * Sets a custom extraction prompt.
     * The prompt should contain a %s placeholder for the document text.
     */
    public void setExtractionPrompt(String prompt) {
        this.extractionPrompt = prompt;
    }
}
