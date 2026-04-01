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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;

/**
 * LLM-based fact extractor that identifies factual statements in document text.
 *
 * <p>This extractor identifies facts in Subject-Predicate-Object (SPO) triple format:</p>
 * <ul>
 *   <li><b>Subject</b> - The entity the fact is about</li>
 *   <li><b>Predicate</b> - The relationship or property</li>
 *   <li><b>Object</b> - The value or related entity</li>
 * </ul>
 *
 * <p>Example facts:</p>
 * <ul>
 *   <li>"The Eiffel Tower" - "is located in" - "Paris"</li>
 *   <li>"Python" - "was created by" - "Guido van Rossum"</li>
 *   <li>"Apple Inc." - "has headquarters in" - "Cupertino, California"</li>
 * </ul>
 */
public class LLMFactExtractor extends AbstractStructuredExtractor {

    private static final String DEFAULT_EXTRACTION_PROMPT = """
            Extract factual statements from the following text as Subject-Predicate-Object triples.
            Return a JSON object with the following structure:
            {
              "facts": [
                {
                  "statement": "The complete factual statement",
                  "subject": "The entity the fact is about",
                  "predicate": "The relationship or property",
                  "object": "The value or related entity",
                  "confidence": 0.0-1.0,
                  "category": "DEFINITION|MEASUREMENT|RELATIONSHIP|TEMPORAL|SPATIAL|CAUSAL|PROCEDURAL"
                }
              ]
            }

            Focus on extracting:
            - Definitions and descriptions
            - Measurements and quantities
            - Relationships between entities
            - Temporal facts (dates, durations)
            - Spatial facts (locations, positions)
            - Causal relationships
            - Procedural facts (how things work)

            Text to analyze:
            ---
            %s
            ---

            Return ONLY the JSON object, no other text.
            """;

    private final Function<String, String> llmInvoker;
    private final ObjectMapper objectMapper;
    private String extractionPrompt = DEFAULT_EXTRACTION_PROMPT;
    private int maxFactsPerDocument = 50;

    /**
     * Creates an LLM fact extractor with the given LLM invoker function.
     *
     * @param llmInvoker Function that takes a prompt and returns the LLM response
     */
    public LLMFactExtractor(Function<String, String> llmInvoker) {
        this.llmInvoker = llmInvoker;
        this.objectMapper = new ObjectMapper();
        this.batchSize = 5;
    }

    @Override
    public String getName() {
        return "llm-fact-extractor";
    }

    @Override
    public ExtractorType getType() {
        return ExtractorType.FACT;
    }

    @Override
    protected List<StructuredItem> doExtract(RetrievedDoc document, Map<String, Object> options) throws Exception {
        String text = document.getText();
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        // Truncate very long text
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

    private List<StructuredItem> parseExtractionResponse(String response, String sourceDocId) {
        List<StructuredItem> items = new ArrayList<>();

        try {
            // Clean response
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf('\n') + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```"));
                }
            }

            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) parsed.get("facts");

            if (facts != null) {
                int count = 0;
                for (Map<String, Object> fact : facts) {
                    if (count >= maxFactsPerDocument) break;

                    String statement = (String) fact.get("statement");
                    String subject = (String) fact.get("subject");
                    String predicate = (String) fact.get("predicate");
                    String object = (String) fact.get("object");
                    double confidence = fact.containsKey("confidence")
                            ? ((Number) fact.get("confidence")).doubleValue()
                            : 0.8;
                    String category = (String) fact.getOrDefault("category", "GENERAL");

                    StructuredItem item = StructuredItem.fact(
                            statement, subject, predicate, object, confidence, sourceDocId);

                    // Add category as additional property
                    StructuredItem.Builder builder = StructuredItem.builder()
                            .id(item.getId())
                            .type(StructuredItem.ItemType.FACT)
                            .content(statement)
                            .confidence(confidence)
                            .sourceDocumentId(sourceDocId)
                            .property("subject", subject)
                            .property("predicate", predicate)
                            .property("object", object)
                            .property("category", category);

                    items.add(builder.build());
                    count++;
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to parse LLM fact extraction response: {}", e.getMessage());
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
        if (options.containsKey("maxFactsPerDocument")) {
            this.maxFactsPerDocument = ((Number) options.get("maxFactsPerDocument")).intValue();
        }
    }

    /**
     * Sets a custom extraction prompt.
     */
    public void setExtractionPrompt(String prompt) {
        this.extractionPrompt = prompt;
    }

    /**
     * Sets the maximum number of facts to extract per document.
     */
    public void setMaxFactsPerDocument(int max) {
        this.maxFactsPerDocument = Math.max(1, max);
    }
}
