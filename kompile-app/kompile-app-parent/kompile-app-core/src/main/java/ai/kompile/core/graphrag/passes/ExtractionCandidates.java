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

package ai.kompile.core.graphrag.passes;

import java.util.List;

/**
 * Bounded candidate sets handed to a pass.
 *
 * <p>A pass never searches the graph itself. Retrieval is an engine operation: deterministic
 * indexes and scorers produce a small, ranked, explicitly-scoped candidate list, and the model
 * only chooses among what it was given (or abstains). That keeps recall the engine's
 * responsibility and precision the model's.</p>
 */
public final class ExtractionCandidates {

    private ExtractionCandidates() {
    }

    /**
     * An existing graph entity offered as a resolution target for a mention.
     *
     * @param id         graph entity id the model must echo back verbatim when reusing it
     * @param name       canonical display name
     * @param type       entity type
     * @param aliases    known surface forms, so the model can see why it was proposed
     * @param score      retrieval score in [0,1]; ranking is the engine's judgement, not the model's
     * @param provenance which retriever produced the candidate (lexical, embedding, gtin, ...)
     */
    public record EntityCandidate(
            String id,
            String name,
            String type,
            List<String> aliases,
            double score,
            String provenance) {

        public EntityCandidate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public static EntityCandidate of(String id, String name, String type, double score) {
            return new EntityCandidate(id, name, type, List.of(), score, "lexical");
        }
    }

    /**
     * A relation type the schema permits between the resolved endpoints.
     *
     * @param type        relation type name the model must echo back verbatim
     * @param description short gloss so the model can distinguish near-synonyms
     * @param domainTypes permitted source entity types (empty means unconstrained)
     * @param rangeTypes  permitted target entity types (empty means unconstrained)
     * @param score       schema/profile prior in [0,1]
     */
    public record RelationCandidate(
            String type,
            String description,
            List<String> domainTypes,
            List<String> rangeTypes,
            double score) {

        public RelationCandidate {
            domainTypes = domainTypes == null ? List.of() : List.copyOf(domainTypes);
            rangeTypes = rangeTypes == null ? List.of() : List.copyOf(rangeTypes);
        }

        public static RelationCandidate of(String type, String description) {
            return new RelationCandidate(type, description, List.of(), List.of(), 0.5);
        }
    }

    /**
     * An existing claim the new proposition may support, contradict or duplicate.
     *
     * @param atomKey           stable key of the claim atom the model must echo back
     * @param subject           claim subject as stored
     * @param predicate         claim predicate as stored
     * @param object            claim object as stored
     * @param currentConfidence fused confidence currently held by the KB
     * @param supporting        number of supporting evidence items already recorded
     * @param refuting          number of refuting evidence items already recorded
     * @param summary           short human-readable rendering of the claim
     */
    public record ClaimCandidate(
            String atomKey,
            String subject,
            String predicate,
            String object,
            double currentConfidence,
            int supporting,
            int refuting,
            String summary) {

        public static ClaimCandidate of(String atomKey, String summary, double confidence) {
            return new ClaimCandidate(atomKey, null, null, null, confidence, 0, 0, summary);
        }
    }
}
