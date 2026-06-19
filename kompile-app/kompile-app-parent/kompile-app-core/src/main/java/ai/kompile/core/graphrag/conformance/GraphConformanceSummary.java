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
package ai.kompile.core.graphrag.conformance;

/**
 * Store-agnostic, ontology-model-free summary of how a fact sheet's knowledge graph conforms to its
 * governing ontology. Lives in {@code kompile-app-core} so lower layers (knowledge-graph maintenance
 * and write paths) can consume conformance results via {@link GraphConformanceChecker} without
 * depending on the OntologySchema model (which lives in a sibling module they cannot see).
 */
public record GraphConformanceSummary(
        Long factSheetId,
        boolean ontologyBound,
        String ontologyName,
        int entitiesChecked,
        int unknownTypeCount,
        int nonConformantCount,
        String message
) {
    /** No ontology governs this fact sheet's graph; nothing was checked. */
    public static GraphConformanceSummary notBound(Long factSheetId) {
        return new GraphConformanceSummary(factSheetId, false, null, 0, 0, 0,
                "No ontology is bound to this fact sheet.");
    }
}
