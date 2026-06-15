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

package ai.kompile.process.ontology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A versioned ontology schema that defines entity types, relationship types,
 * and validation rules for a specific domain process.
 * Generalization of the FP&amp;A semantic layer — works for any domain.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OntologySchema {

    private String id;
    private String name;
    private int version;
    /** Cross-customer template this derives from (e.g., "FP&A_CPG_Channel v3.1"). */
    private String templateId;
    private Instant createdAt;
    private Instant updatedAt;
    private String updatedBy;
    private List<EntityTypeDefinition> entityTypes;
    private List<RelationshipTypeDefinition> relationshipTypes;
    private List<ValidationRule> globalRules;
    private Map<String, Object> metadata;
}
