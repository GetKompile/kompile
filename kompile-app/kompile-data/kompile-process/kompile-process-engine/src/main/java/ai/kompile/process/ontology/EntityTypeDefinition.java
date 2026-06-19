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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Defines an entity type in the ontology (e.g., RegionalForecast, ChannelTaxonomy, SKUMaster).
 * Supports typed fields with constraints, classification, and provenance.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityTypeDefinition {

    /** Entity type name, e.g., "ChannelTaxonomy". */
    private String name;
    private String description;
    /** Classification bucket: REFERENCE, TRANSACTIONAL, PATTERN, CONTROL, METRIC, ACTOR. */
    private EntityClassification classification;
    /** Which cross-customer template seeded this entity type. */
    private String templateSource;
    private int templateVersion;
    private double confidence;
    private List<FieldDefinition> fields;
    private List<ValidationRule> rules;
    private List<ProvenanceCitation> provenance;
    private List<ChangeRecord> changeHistory;
}
