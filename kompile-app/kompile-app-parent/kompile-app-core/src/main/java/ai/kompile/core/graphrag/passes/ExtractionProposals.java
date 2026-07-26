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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed outputs of the decomposed extraction passes.
 *
 * <p>Each record is a <em>proposal</em>, never a commitment: it names one operation from a closed
 * vocabulary, cites the exact source span it rests on, lists the alternatives it rejected, and may
 * decline outright. Nothing here touches the graph — the engine validates, fuses and promotes.</p>
 *
 * <p>Mirrors the layout convention of
 * {@link ai.kompile.core.graphrag.format.GraphExtractionSchema}: one holder class of nested
 * records so the wire shape of a pass is readable in a single file.</p>
 */
public final class ExtractionProposals {

    private ExtractionProposals() {
    }

    /** Schema version stamped on decomposed runs, distinct from the single-shot schema. */
    public static final String SCHEMA_VERSION = "kompile-extraction-passes/v1";

    /** Whether the source affirmed or denied the proposition. */
    public enum Polarity {
        AFFIRMED, NEGATED, UNKNOWN;

        public static Polarity from(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "AFFIRMED", "AFFIRM", "POSITIVE", "TRUE", "YES" -> AFFIRMED;
                case "NEGATED", "NEGATIVE", "DENIED", "FALSE", "NO", "NOT" -> NEGATED;
                default -> UNKNOWN;
            };
        }
    }

    /** Modal force of the proposition; anything but {@code FACTUAL} blocks direct promotion. */
    public enum Modality {
        FACTUAL, HYPOTHETICAL, OBLIGATION, POSSIBILITY, UNKNOWN;

        public static Modality from(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "FACTUAL", "FACT", "ACTUAL", "REAL" -> FACTUAL;
                case "HYPOTHETICAL", "CONDITIONAL", "IF", "COUNTERFACTUAL" -> HYPOTHETICAL;
                case "OBLIGATION", "OBLIGATORY", "MUST", "REQUIRED", "DEONTIC" -> OBLIGATION;
                case "POSSIBILITY", "POSSIBLE", "MAY", "MIGHT", "EPISTEMIC" -> POSSIBILITY;
                default -> UNKNOWN;
            };
        }

        /** Only unmodalised, actual statements may become unattributed facts. */
        public boolean allowsDirectAssertion() {
            return this == FACTUAL;
        }
    }

    /** What the cited span does for the proposal. */
    public enum EvidenceRole {
        DIRECT_SUPPORT, ATTRIBUTION, OPPOSITION, QUALIFICATION, UNKNOWN;

        public static EvidenceRole from(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
                case "DIRECT_SUPPORT", "SUPPORT", "SUPPORTING", "DIRECT" -> DIRECT_SUPPORT;
                case "ATTRIBUTION", "ATTRIBUTED", "HOLDER", "SOURCE" -> ATTRIBUTION;
                case "OPPOSITION", "OPPOSING", "REFUTES", "CONTRADICTS", "AGAINST" -> OPPOSITION;
                case "QUALIFICATION", "QUALIFIER", "SCOPE", "CONDITION" -> QUALIFICATION;
                default -> UNKNOWN;
            };
        }
    }

    /**
     * An exact reference into the source chunk. Offsets are half-open {@code [start, end)} over
     * {@link PassContext#sourceText()}; {@code quote} is the literal text.
     *
     * <p>Models routinely miscount offsets, so {@link EvidenceSpanValidator} treats the quote as
     * authoritative and repairs the offsets. A span whose quote cannot be found in the source is
     * fabricated evidence and its proposal is dropped.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceSpan(
            String chunkId,
            int start,
            int end,
            String quote,
            EvidenceRole role) {

        public EvidenceSpan {
            quote = quote == null ? "" : quote;
            role = role == null ? EvidenceRole.UNKNOWN : role;
        }

        public static EvidenceSpan ofQuote(String chunkId, String quote) {
            return new EvidenceSpan(chunkId, -1, -1, quote, EvidenceRole.DIRECT_SUPPORT);
        }

        /** True when the offsets form a usable half-open range. */
        public boolean hasOffsets() {
            return start >= 0 && end > start;
        }

        /** True when there is a non-blank quote to verify against the source. */
        public boolean hasQuote() {
            return quote != null && !quote.isBlank();
        }

        public EvidenceSpan withOffsets(int newStart, int newEnd) {
            return new EvidenceSpan(chunkId, newStart, newEnd, quote, role);
        }

        public EvidenceSpan withChunkId(String newChunkId) {
            return new EvidenceSpan(newChunkId, start, end, quote, role);
        }
    }

    /**
     * A candidate the pass considered and rejected. Required on committing operations: it is the
     * only way to tell a confident decision from a blind first-match.
     *
     * @param candidateId the rejected candidate's id or type name
     * @param score       the model's own plausibility for it, in [0,1]
     * @param reason      why it was rejected
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alternative(String candidateId, double score, String reason) {
    }

    /**
     * Pass 1 output: one atomic, self-contained statement lifted from the chunk, with its
     * polarity, modality, time and condition kept attached rather than silently dropped.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropositionProposal(
            String id,
            String text,
            String subject,
            String predicate,
            String object,
            Polarity polarity,
            Modality modality,
            String timeExpression,
            String condition,
            String attributedTo,
            EvidenceSpan evidence) {

        public PropositionProposal {
            polarity = polarity == null ? Polarity.UNKNOWN : polarity;
            modality = modality == null ? Modality.UNKNOWN : modality;
        }

        /** Short rendering used as the "one primary object" line of downstream pass prompts. */
        public String render() {
            if (text != null && !text.isBlank()) {
                return text;
            }
            return String.join(" ",
                    subject == null ? "?" : subject,
                    predicate == null ? "?" : predicate,
                    object == null ? "?" : object);
        }
    }

    /**
     * Pass 2 output: identity decision for one mention within one proposition.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MentionProposal(
            String propositionId,
            String mentionText,
            String mentionRole,
            ProposalOperation operation,
            String selectedEntityId,
            String provisionalName,
            String provisionalType,
            double confidence,
            List<Alternative> alternatives,
            String reason,
            EvidenceSpan evidence) {

        public MentionProposal {
            operation = operation == null ? ProposalOperation.ABSTAIN : operation;
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        public boolean resolved() {
            return !operation.isAbstention();
        }
    }

    /**
     * Pass 3 output: epistemic classification of one proposition — the attribution firewall.
     *
     * @param certainty the source's expressed certainty in [0,1], not the model's own confidence
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EpistemicProposal(
            String propositionId,
            SpeechAct speechAct,
            String holder,
            double certainty,
            String reason,
            EvidenceSpan evidence) {

        public EpistemicProposal {
            speechAct = speechAct == null ? SpeechAct.UNKNOWN : speechAct;
        }

        /** Conservative default when pass 3 produced nothing for a proposition. */
        public static EpistemicProposal unknown(String propositionId) {
            return new EpistemicProposal(propositionId, SpeechAct.UNKNOWN, null, 0.5,
                    "no classification returned", null);
        }
    }

    /**
     * Pass 4 output: relation-type selection over the resolved endpoints of one proposition.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelationProposal(
            String propositionId,
            String sourceEntityId,
            String targetEntityId,
            String type,
            ProposalOperation operation,
            double confidence,
            String occurredAt,
            Map<String, String> qualifiers,
            List<Alternative> alternatives,
            String reason,
            EvidenceSpan evidence) {

        public RelationProposal {
            operation = operation == null ? ProposalOperation.ABSTAIN : operation;
            qualifiers = qualifiers == null ? Map.of() : Map.copyOf(qualifiers);
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        public boolean committing() {
            return !operation.isAbstention() && type != null && !type.isBlank();
        }
    }

    /**
     * Pass 5 output: how one relation proposal relates to claims the KB already holds.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClaimProposal(
            String propositionId,
            String relationKey,
            ProposalOperation operation,
            String matchedAtomKey,
            double confidence,
            List<Alternative> alternatives,
            String reason,
            EvidenceSpan evidence) {

        public ClaimProposal {
            operation = operation == null ? ProposalOperation.ABSTAIN : operation;
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        /** Default when pass 5 is disabled or produced nothing: treat as a fresh claim. */
        public static ClaimProposal newClaim(String propositionId, String relationKey) {
            return new ClaimProposal(propositionId, relationKey, ProposalOperation.CREATE_CLAIM,
                    null, 0.5, List.of(), "no matching claim candidates supplied", null);
        }
    }

    /**
     * All pass outputs for one chunk, kept together so the projection step and the diagnostics
     * see exactly what each pass produced.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PassBundle(
            List<PropositionProposal> propositions,
            List<MentionProposal> mentions,
            List<EpistemicProposal> epistemics,
            List<RelationProposal> relations,
            List<ClaimProposal> claims) {

        public PassBundle {
            propositions = propositions == null ? List.of() : List.copyOf(propositions);
            mentions = mentions == null ? List.of() : List.copyOf(mentions);
            epistemics = epistemics == null ? List.of() : List.copyOf(epistemics);
            relations = relations == null ? List.of() : List.copyOf(relations);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }

        public static PassBundle empty() {
            return new PassBundle(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
