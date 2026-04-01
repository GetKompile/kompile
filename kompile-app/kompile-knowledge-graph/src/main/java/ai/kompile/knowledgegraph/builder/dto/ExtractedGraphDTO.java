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
 *  limitations under the License.
 */

package ai.kompile.knowledgegraph.builder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTOs for LLM-based graph extraction results.
 * Used to parse JSON responses from LLM when extracting entities and relationships from text.
 */
public final class ExtractedGraphDTO {

    private ExtractedGraphDTO() {
    }

    @Data
    public static class ExtractedGraph {
        private List<ExtractedEntity> entities;
        private List<ExtractedRelationship> relationships;
    }

    @Data
    public static class ExtractedEntity {
        private String id;
        private String title;
        @JsonProperty("label")
        private String nodeLabel;
        private String description;
        private Map<String, Object> metadata;
    }

    @Data
    public static class ExtractedRelationship {
        private String source;
        private String target;
        @JsonProperty("type")
        private String relationshipType;
        private String description;
        private Double weight;
        private Double confidence;
        private Map<String, Object> metadata;
    }
}
