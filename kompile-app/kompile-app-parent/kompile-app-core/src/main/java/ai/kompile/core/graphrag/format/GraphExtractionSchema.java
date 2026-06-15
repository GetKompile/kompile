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

package ai.kompile.core.graphrag.format;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Standardized JSON schema for graph extraction results.
 * This format is used for LLM entity/relation extraction and for import/export of graph data.
 *
 * Schema version: kompile-graph-extraction/v1
 */
public final class GraphExtractionSchema {

    private GraphExtractionSchema() {
    }

    public static final String SCHEMA_VERSION = "kompile-graph-extraction/v1";

    /**
     * Top-level extraction result containing entities, relations, and metadata.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtractionResult(
            @JsonProperty("$schema") String schema,
            @JsonProperty("entities") List<ExtractedEntity> entities,
            @JsonProperty("relations") List<ExtractedRelation> relations,
            @JsonProperty("metadata") ExtractionMetadata metadata
    ) {
        public ExtractionResult {
            if (schema == null) schema = SCHEMA_VERSION;
            if (entities == null) entities = List.of();
            if (relations == null) relations = List.of();
        }

        public static ExtractionResult of(List<ExtractedEntity> entities,
                                           List<ExtractedRelation> relations,
                                           ExtractionMetadata metadata) {
            return new ExtractionResult(SCHEMA_VERSION, entities, relations, metadata);
        }
    }

    /**
     * An extracted entity with aliases, confidence, and typed properties.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtractedEntity(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("aliases") List<String> aliases,
            @JsonProperty("description") String description,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("properties") Map<String, String> properties
    ) {
        public ExtractedEntity {
            if (aliases == null) aliases = List.of();
            if (confidence == null) confidence = 1.0;
            if (properties == null) properties = Map.of();
        }
    }

    /**
     * An extracted relation between two entities.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtractedRelation(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target,
            @JsonProperty("type") String type,
            @JsonProperty("description") String description,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("properties") Map<String, String> properties,
            @JsonProperty("occurredAt") String occurredAt
    ) {
        public ExtractedRelation {
            if (confidence == null) confidence = 1.0;
            if (properties == null) properties = Map.of();
        }

        /**
         * Backwards-compatible constructor without occurredAt.
         */
        public ExtractedRelation(String source, String target, String type,
                                  String description, Double confidence,
                                  Map<String, String> properties) {
            this(source, target, type, description, confidence, properties, null);
        }
    }

    /**
     * Metadata about the extraction process.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtractionMetadata(
            @JsonProperty("sourceChunkId") String sourceChunkId,
            @JsonProperty("sourceDocumentId") String sourceDocumentId,
            @JsonProperty("extractionModel") String extractionModel,
            @JsonProperty("extractionTimestamp") String extractionTimestamp,
            @JsonProperty("graphId") String graphId,
            @JsonProperty("parentGraphId") String parentGraphId
    ) {
        public ExtractionMetadata(String sourceChunkId, String sourceDocumentId,
                                  String extractionModel, String extractionTimestamp) {
            this(sourceChunkId, sourceDocumentId, extractionModel, extractionTimestamp, null, null);
        }

        public static ExtractionMetadata forChunk(String chunkId, String documentId, String model) {
            return new ExtractionMetadata(chunkId, documentId, model, Instant.now().toString(), null, null);
        }

        public static ExtractionMetadata forChunkInGraph(String chunkId, String documentId,
                                                          String model, String graphId, String parentGraphId) {
            return new ExtractionMetadata(chunkId, documentId, model, Instant.now().toString(), graphId, parentGraphId);
        }
    }
}
