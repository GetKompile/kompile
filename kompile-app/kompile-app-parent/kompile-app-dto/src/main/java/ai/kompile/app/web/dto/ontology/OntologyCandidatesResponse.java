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
package ai.kompile.app.web.dto.ontology;

import java.util.List;

/**
 * Graph-grounded candidates that power step&nbsp;2 of the ontology-derivation wizard.
 *
 * <p>Produced purely from a fact sheet's knowledge-graph statistics and top concepts — <b>no LLM
 * call</b> — so the wizard can show the user real entity-type candidates and relationship signals to
 * select from before the (optionally LLM-backed) generation step.</p>
 *
 * @param factSheetId          the source fact sheet
 * @param factSheetName        its display name
 * @param graphAvailable       false when the fact sheet has no built graph yet (nothing to derive)
 * @param entityCount          number of entity nodes in the graph
 * @param documentCount        number of document nodes in the graph
 * @param distinctConcepts     number of distinct concepts mentioned
 * @param totalNodes           total graph nodes
 * @param totalEdges           total graph edges
 * @param candidateEntityTypes top concepts proposed as seed entity types
 * @param relationshipHints    edge-type signals proposed as seed relationships
 * @param classifications      allowed {@code EntityClassification} values for the wizard UI
 */
public record OntologyCandidatesResponse(
        Long factSheetId,
        String factSheetName,
        boolean graphAvailable,
        long entityCount,
        long documentCount,
        long distinctConcepts,
        long totalNodes,
        long totalEdges,
        List<Candidate> candidateEntityTypes,
        List<RelationshipHint> relationshipHints,
        List<String> classifications
) {

    /**
     * A concept from the crawl graph proposed as a seed entity type.
     *
     * @param concept               the raw concept/label as it appears in the graph
     * @param mentions              how many times it is mentioned (popularity signal)
     * @param suggestedEntityName   a PascalCase entity-type name derived from the concept
     * @param suggestedClassification a default {@code EntityClassification} guess for the UI
     */
    public record Candidate(
            String concept,
            long mentions,
            String suggestedEntityName,
            String suggestedClassification
    ) {
    }

    /**
     * An edge-type signal proposed as a seed relationship.
     *
     * @param type    the graph edge type (e.g. SHARED_ENTITY, HIERARCHICAL)
     * @param count   how many edges of this type exist
     * @param example a representative "source &rarr; target" example, when available
     */
    public record RelationshipHint(
            String type,
            long count,
            String example
    ) {
    }
}
