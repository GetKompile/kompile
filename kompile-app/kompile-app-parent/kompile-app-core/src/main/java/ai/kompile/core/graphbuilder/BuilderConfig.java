/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphbuilder;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a knowledge graph builder.
 *
 * @param modelProvider LLM provider for LLM-based builders
 * @param modelName specific model to use
 * @param temperature sampling temperature for LLM calls
 * @param maxTokens maximum tokens for LLM responses
 * @param entityTypes types of entities to extract
 * @param relationshipTypes types of relationships to extract
 * @param minConfidence minimum confidence threshold for proposals
 * @param autoAccept automatically accept proposals above this threshold
 * @param autoAcceptThreshold threshold for auto-acceptance
 * @param customPrompt custom extraction prompt template
 * @param additionalOptions additional builder-specific options
 */
public record BuilderConfig(
        String modelProvider,
        String modelName,
        Double temperature,
        Integer maxTokens,
        List<String> entityTypes,
        List<String> relationshipTypes,
        Double minConfidence,
        Boolean autoAccept,
        Double autoAcceptThreshold,
        String customPrompt,
        Map<String, Object> additionalOptions
) {
    /**
     * Create a default configuration.
     */
    public static BuilderConfig defaults() {
        return new BuilderConfig(
                null,
                null,
                0.0,
                4096,
                List.of("PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT"),
                List.of("WORKS_AT", "LOCATED_IN", "RELATED_TO", "PART_OF", "CREATED_BY"),
                0.6,
                false,
                0.9,
                null,
                Map.of()
        );
    }

    /**
     * Create a builder config with custom entity types.
     */
    public BuilderConfig withEntityTypes(List<String> entityTypes) {
        return new BuilderConfig(
                modelProvider, modelName, temperature, maxTokens,
                entityTypes, relationshipTypes, minConfidence,
                autoAccept, autoAcceptThreshold, customPrompt, additionalOptions
        );
    }

    /**
     * Create a builder config with a custom model.
     */
    public BuilderConfig withModel(String provider, String model) {
        return new BuilderConfig(
                provider, model, temperature, maxTokens,
                entityTypes, relationshipTypes, minConfidence,
                autoAccept, autoAcceptThreshold, customPrompt, additionalOptions
        );
    }
}
