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
 * LLM-based concept extractor that identifies key concepts and themes in document text.
 *
 * <p>This extractor identifies:</p>
 * <ul>
 *   <li><b>TOPIC</b> - Main subjects or topics of the text</li>
 *   <li><b>THEME</b> - Recurring themes or motifs</li>
 *   <li><b>KEYWORD</b> - Important keywords and phrases</li>
 *   <li><b>TECHNICAL_TERM</b> - Domain-specific terminology</li>
 *   <li><b>METHODOLOGY</b> - Methods, processes, or approaches</li>
 *   <li><b>THEORY</b> - Theoretical concepts and frameworks</li>
 * </ul>
 */
public class LLMConceptExtractor extends AbstractStructuredExtractor {

    private static final String DEFAULT_EXTRACTION_PROMPT = """
            Extract key concepts and themes from the following text.
            Return a JSON object with the following structure:
            {
              "concepts": [
                {
                  "name": "concept name",
                  "category": "TOPIC|THEME|KEYWORD|TECHNICAL_TERM|METHODOLOGY|THEORY",
                  "description": "brief explanation of the concept",
                  "importance": 0.0-1.0,
                  "frequency": "number of times this concept appears or is referenced"
                }
              ]
            }

            Focus on extracting:
            - Main topics and subjects being discussed
            - Key themes that run through the text
            - Important keywords and phrases
            - Technical terminology specific to the domain
            - Methodologies, processes, or approaches mentioned
            - Theoretical concepts and frameworks

            Text to analyze:
            ---
            %s
            ---

            Return ONLY the JSON object, no other text.
            """;

    private final Function<String, String> llmInvoker;
    private final ObjectMapper objectMapper;
    private String extractionPrompt = DEFAULT_EXTRACTION_PROMPT;
    private int maxConceptsPerDocument = 30;

    /**
     * Creates an LLM concept extractor with the given LLM invoker function.
     *
     * @param llmInvoker Function that takes a prompt and returns the LLM response
     */
    public LLMConceptExtractor(Function<String, String> llmInvoker) {
        this.llmInvoker = llmInvoker;
        this.objectMapper = JsonUtils.standardMapper();
        this.batchSize = 5;
    }

    @Override
    public String getName() {
        return "llm-concept-extractor";
    }

    @Override
    public ExtractorType getType() {
        return ExtractorType.CONCEPT;
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
            List<Map<String, Object>> concepts = (List<Map<String, Object>>) parsed.get("concepts");

            if (concepts != null) {
                int count = 0;
                for (Map<String, Object> concept : concepts) {
                    if (count >= maxConceptsPerDocument) break;

                    String name = (String) concept.get("name");
                    String category = (String) concept.getOrDefault("category", "KEYWORD");
                    String description = (String) concept.get("description");
                    double importance = concept.containsKey("importance")
                            ? ((Number) concept.get("importance")).doubleValue()
                            : 0.8;
                    int frequency = concept.containsKey("frequency")
                            ? ((Number) concept.get("frequency")).intValue()
                            : 1;

                    StructuredItem item = StructuredItem.builder()
                            .type(StructuredItem.ItemType.CONCEPT)
                            .title(name)
                            .content(description)
                            .confidence(importance)
                            .sourceDocumentId(sourceDocId)
                            .property("category", category)
                            .property("frequency", frequency)
                            .build();

                    items.add(item);
                    count++;
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to parse LLM concept extraction response: {}", e.getMessage());
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
        if (options.containsKey("maxConceptsPerDocument")) {
            this.maxConceptsPerDocument = ((Number) options.get("maxConceptsPerDocument")).intValue();
        }
    }

    /**
     * Sets a custom extraction prompt.
     */
    public void setExtractionPrompt(String prompt) {
        this.extractionPrompt = prompt;
    }

    /**
     * Sets the maximum number of concepts to extract per document.
     */
    public void setMaxConceptsPerDocument(int max) {
        this.maxConceptsPerDocument = Math.max(1, max);
    }
}
