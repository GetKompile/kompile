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
     * Engine-computed identity facts for one candidate. These are control data, not model
     * judgements and never source evidence.
     *
     * @param canonicalNameExact the normalized mention exactly matches the canonical name
     * @param aliasExact         the normalized mention exactly matches a known alias
     * @param stableIdentifierExact a stable identifier (email, account id, SKU, ...) matches
     * @param typeCompatible     the candidate does not contradict the expected mention type
     * @param sharedTokensOnly   retrieval is based only on weak lexical overlap
     * @param forbiddenMerge     graph state explicitly forbids resolving this mention to the candidate
     * @param matchedIdentifier  stable identifier that matched, when one exists
     * @param constraintReason   compact explanation of an engine-owned identity constraint
     * @param sourceAliasExact   the source explicitly groups the mention with a retrieved variant
     * @param matchedSourceAlias exact source-grounded variant that retrieved this candidate
     */
    public record IdentitySignals(
            boolean canonicalNameExact,
            boolean aliasExact,
            boolean stableIdentifierExact,
            boolean typeCompatible,
            boolean sharedTokensOnly,
            boolean forbiddenMerge,
            String matchedIdentifier,
            String constraintReason,
            boolean sourceAliasExact,
            String matchedSourceAlias) {

        public IdentitySignals {
            matchedIdentifier = blankToNull(matchedIdentifier);
            constraintReason = blankToNull(constraintReason);
            matchedSourceAlias = bound(blankToNull(matchedSourceAlias), 96);
        }

        /** Source-compatible constructor for retrievers predating source-grounded alias signals. */
        public IdentitySignals(boolean canonicalNameExact, boolean aliasExact,
                               boolean stableIdentifierExact, boolean typeCompatible,
                               boolean sharedTokensOnly, boolean forbiddenMerge,
                               String matchedIdentifier, String constraintReason) {
            this(canonicalNameExact, aliasExact, stableIdentifierExact, typeCompatible,
                    sharedTokensOnly, forbiddenMerge, matchedIdentifier, constraintReason,
                    false, null);
        }

        public static IdentitySignals unknown() {
            return new IdentitySignals(false, false, false, true, false, false,
                    null, null, false, null);
        }

        public boolean strongMatch() {
            return canonicalNameExact || aliasExact || stableIdentifierExact || sourceAliasExact;
        }

        public boolean selectable() {
            return typeCompatible && !forbiddenMerge;
        }

        public IdentitySignals asSourceAlias(String sourceAlias) {
            return new IdentitySignals(false, false, stableIdentifierExact, typeCompatible,
                    false, forbiddenMerge, matchedIdentifier, constraintReason,
                    true, sourceAlias);
        }

        public IdentitySignals merge(IdentitySignals other) {
            if (other == null) {
                return this;
            }
            return new IdentitySignals(
                    canonicalNameExact || other.canonicalNameExact,
                    aliasExact || other.aliasExact,
                    stableIdentifierExact || other.stableIdentifierExact,
                    typeCompatible && other.typeCompatible,
                    sharedTokensOnly && other.sharedTokensOnly,
                    forbiddenMerge || other.forbiddenMerge,
                    firstNonBlank(matchedIdentifier, other.matchedIdentifier),
                    firstNonBlank(constraintReason, other.constraintReason),
                    sourceAliasExact || other.sourceAliasExact,
                    firstNonBlank(matchedSourceAlias, other.matchedSourceAlias));
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        private static String firstNonBlank(String first, String second) {
            return first != null && !first.isBlank() ? first : blankToNull(second);
        }

        private static String bound(String value, int limit) {
            return value == null || value.length() <= limit ? value : value.substring(0, limit);
        }
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
     * @param identityContext compact graph-neighborhood state that can distinguish same-name
     *                        candidates; identity context is never source evidence
     * @param identitySignals deterministic match and graph-purity controls computed by the engine
     */
    public record EntityCandidate(
            String id,
            String name,
            String type,
            List<String> aliases,
            double score,
            String provenance,
            String identityContext,
            IdentitySignals identitySignals) {

        public static final int MAX_IDENTITY_CONTEXT_CHARS = 480;

        public EntityCandidate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            identityContext = identityContext == null || identityContext.isBlank()
                    ? null : bound(identityContext);
            identitySignals = identitySignals == null ? IdentitySignals.unknown() : identitySignals;
        }

        /** Source-compatible constructor for candidates created before structured identity facts. */
        public EntityCandidate(String id, String name, String type, List<String> aliases,
                               double score, String provenance, String identityContext) {
            this(id, name, type, aliases, score, provenance, identityContext,
                    IdentitySignals.unknown());
        }

        /** Source-compatible constructor for retrievers that do not expose neighborhood state. */
        public EntityCandidate(String id, String name, String type, List<String> aliases,
                               double score, String provenance) {
            this(id, name, type, aliases, score, provenance, null, IdentitySignals.unknown());
        }

        public static EntityCandidate of(String id, String name, String type, double score) {
            return new EntityCandidate(id, name, type, List.of(), score, "lexical", null,
                    IdentitySignals.unknown());
        }

        private static String bound(String value) {
            String oneLine = value.strip().replace('\n', ' ').replace('\r', ' ');
            return oneLine.length() <= MAX_IDENTITY_CONTEXT_CHARS
                    ? oneLine : oneLine.substring(0, MAX_IDENTITY_CONTEXT_CHARS);
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
     * @param aliases     audited lexical forms, including multilingual surface predicates
     */
    public record RelationCandidate(
            String type,
            String description,
            List<String> domainTypes,
            List<String> rangeTypes,
            double score,
            List<String> aliases) {

        public RelationCandidate {
            domainTypes = domainTypes == null ? List.of() : List.copyOf(domainTypes);
            rangeTypes = rangeTypes == null ? List.of() : List.copyOf(rangeTypes);
            aliases = aliases == null ? List.of() : aliases.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip).distinct().toList();
        }

        /** Binary/source-compatible constructor for candidates without audited lexical aliases. */
        public RelationCandidate(String type, String description, List<String> domainTypes,
                                 List<String> rangeTypes, double score) {
            this(type, description, domainTypes, rangeTypes, score, List.of());
        }

        public static RelationCandidate of(String type, String description) {
            return new RelationCandidate(type, description, List.of(), List.of(), 0.5, List.of());
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
