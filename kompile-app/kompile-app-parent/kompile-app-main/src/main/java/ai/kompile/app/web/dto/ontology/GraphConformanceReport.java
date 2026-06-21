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

import java.util.List;

/**
 * Result of validating a fact sheet's knowledge-graph ENTITY nodes against the OntologySchema bound
 * to that fact sheet. Produced by {@code GraphOntologyBindingService.checkConformance}.
 *
 * <p>{@code ontologyBound=false} means no ontology governs this fact sheet's graph yet (neither an
 * explicit graph-level binding nor a process definition carrying one) — nothing was checked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphConformanceReport(
        Long factSheetId,
        boolean ontologyBound,
        String ontologyId,
        Integer ontologyVersion,
        String ontologyName,
        int entitiesChecked,
        int unknownTypeCount,
        int nonConformantCount,
        // Fraction (0..1) of checked ENTITY nodes that conform; null when nothing is bound/checked.
        Double conformanceScore,
        List<NodeViolation> violations,
        int edgesChecked,
        int nonConformantEdgeCount,
        List<EdgeViolation> edgeViolations,
        String message
) {
    /** A single non-conforming node and the reasons it failed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeViolation(String nodeId, String title, String entityType,
                                boolean unknownType, List<String> messages) {}

    /** A single non-conforming relationship/edge (relation not in the ontology, or cardinality breach). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgeViolation(String edgeId, String relationshipType, String sourceType,
                                String targetType, String reason) {}

    /** No ontology is bound to the fact sheet; nothing was validated. */
    public static GraphConformanceReport notBound(Long factSheetId) {
        return new GraphConformanceReport(factSheetId, false, null, null, null, 0, 0, 0, null, List.of(),
                0, 0, List.of(),
                "No ontology is bound to this fact sheet (no explicit graph binding and no process "
                        + "definition with an ontology). Bind one to enable conformance checking.");
    }
}
