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
import java.util.Map;

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
    /**
     * Alternate labels that should resolve to this canonical type. These can include crawl-native
     * names, abbreviations, translated labels, and spelling variants.
     */
    private List<String> aliases;
    /** Optional language-tagged labels, e.g. {@code {"fr": "Vin rouge", "ja": "赤ワイン"}}. */
    private Map<String, String> localizedLabels;
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

    /**
     * Optional is-a parent type name (OWL {@code subClassOf}). When set, this type is a subtype of
     * {@code parentType}, enabling MEBN/SSBN subsumption grounding: an RV declared over the parent
     * grounds over instances of this type too. Bridged into a graph-reasoning {@code TypeHierarchy}
     * by {@code OntologySchemaTypeRegistry}.
     */
    private String parentType;
}
