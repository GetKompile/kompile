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
package ai.kompile.app.web.dto.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Response DTO for {@code GET /api/graph-ontology/owl?factSheetId={id}}.
 *
 * <p>When no ontology is bound to the fact sheet, {@link #isOntologyBound()} is {@code false}
 * and all counts are zero; {@link #isReasonerActive()} is {@code false} and no error is raised.</p>
 *
 * <p>JSON field contract (a frontend agent codes against this shape):</p>
 * <pre>
 * {
 *   "factSheetId":            number,
 *   "ontologyBound":          boolean,
 *   "ontologyName":           string | null,
 *   "classCount":             number,
 *   "objectPropertyCount":    number,
 *   "dataPropertyCount":      number,
 *   "axiomCount":             number,
 *   "entailmentsMaterialized": number,
 *   "inferredTypeCount":      number,
 *   "inferredRelationCount":  number,
 *   "consistent":             boolean,
 *   "inconsistencies":        [{ "description": string }],
 *   "sampleEntailments":      [string],
 *   "reasonerActive":         boolean
 * }
 * </pre>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OwlReasoningResponse {

    /** The fact sheet that was queried. */
    long factSheetId;

    /** Whether this fact sheet has a bound {@link ai.kompile.process.ontology.OntologySchema}. */
    boolean ontologyBound;

    /** Display name of the bound ontology, or {@code null} when none is bound. */
    String ontologyName;

    /** Number of OWL classes mapped from entity type definitions. */
    int classCount;

    /** Number of OWL object properties mapped from relationship type definitions. */
    int objectPropertyCount;

    /** Number of OWL data properties mapped from field definitions. */
    int dataPropertyCount;

    /**
     * Total structural axiom count: classCount + objectPropertyCount + dataPropertyCount +
     * subClassOf arcs derived from the OWL RL pass.
     */
    int axiomCount;

    /**
     * Number of new relations/types materialized by the OWL RL forward-chaining pass
     * (transitive closure edges + inferred type assertions).
     */
    int entailmentsMaterialized;

    /** Number of instance-level inferred type assertions (is-a) from the OWL RL pass. */
    int inferredTypeCount;

    /** Number of inferred transitive-closure relations (has-a) from the OWL RL pass. */
    int inferredRelationCount;

    /** {@code true} if the OWL RL reasoner found no inconsistencies. */
    boolean consistent;

    /** Detected inconsistencies (empty when {@link #isConsistent()} is {@code true}). */
    List<InconsistencyEntry> inconsistencies;

    /**
     * A short sample of human-readable inferred axioms produced by the OWL RL pass,
     * e.g. {@code "Manager ⊑ Employee"}.  At most 10 entries; empty when nothing was inferred.
     */
    List<String> sampleEntailments;

    /**
     * {@code true} when the reasoner actually ran (i.e. an ontology was bound).
     * {@code false} means the response is a no-op default (no ontology bound).
     */
    boolean reasonerActive;

    // ── Nested type ──────────────────────────────────────────────────────────────

    /**
     * A single inconsistency detected by the OWL RL reasoner.
     */
    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InconsistencyEntry {
        /** Human-readable description of the violation. */
        String description;
    }
}
