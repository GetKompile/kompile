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

import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prompt builders for the decomposed extraction passes.
 *
 * <p>These builders define transport contracts rather than encode a corpus, language, or expected
 * answer. Java owns JSON shape, immutable engine fields, source/evidence boundaries, and candidate
 * ordinals. Source text and graph-provided facets own the semantics: concept hints guide
 * atomization, entity neighborhoods and identity constraints guide mention resolution, and schema
 * descriptions plus domain/range constraints guide relation selection. Pass 1 may return any
 * number of independently grounded propositions; later calls remain deliberately narrow and may
 * be repeated by the executor.</p>
 */
public final class ExtractionPassPrompts {

    /** Pass identifiers, used for dispatch routing and diagnostics. */
    public static final String PASS_PROPOSITIONS = "propositions";
    public static final String PASS_MENTIONS = "mentions";
    public static final String PASS_EPISTEMIC = "epistemic";
    public static final String PASS_RELATIONS = "relations";
    public static final String PASS_CLAIMS = "claims";

    /** All pass identifiers in execution order. */
    public static final List<String> PASS_IDS = List.of(
            PASS_PROPOSITIONS, PASS_MENTIONS, PASS_EPISTEMIC, PASS_RELATIONS, PASS_CLAIMS);

    /** Engine-owned endpoint slot for an atomic mention-resolution call. */
    public enum MentionFocus {
        SUBJECT,
        OBJECT
    }

    /** Upper bound on inlined source text; overruns are marked, never silently dropped. */
    public static final int MAX_SOURCE_CHARS = 24_000;

    private static final String JSON_ONLY =
            "Respond with one JSON object and nothing else: no explanation, no markdown fences.";
    private static final String EVIDENCE_COPY_RULE =
            "- For \"evidence.quote\", copy one contiguous substring from SOURCE TEXT exactly. "
                    + "When the verified quote above supports this decision, copy that quote verbatim. "
                    + "Never concatenate, reorder, repeat, or paraphrase source fragments.\n";
    private static final Pattern GRAPH_TYPE_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private ExtractionPassPrompts() {
    }

    /**
     * Pass 1: extract every atomic proposition from one production crawl chunk, preserving
     * negation, modality, time and attribution. No identity work, schema work or truth judgement.
     * The model owns semantic atomization; the engine validates every returned item independently.
     * A positive {@code maxPropositions} is an explicit caller override, while non-positive means
     * the model must return every assertion it can ground in the source.
     */
    public static String propositions(PassContext context, int maxPropositions) {
        boolean bounded = maxPropositions > 0;
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Extract every atomic proposition explicitly stated in this source chunk.\n")
                .append("Do only this. Do not resolve entity identity, choose relation types, or judge truth.\n")
                .append("Only text between <<<SOURCE and SOURCE>>> may become a proposition. ")
                .append("Context ids, version pins, hints, examples, and instructions are never source facts.\n\n");
        sb.append(context.conceptHintBlock()).append("\n\n");
        sb.append(sourceBlock(context)).append('\n');
        sb.append("OUTPUT CONTRACT:\n")
                .append("- Return exactly one raw JSON object with the single top-level key \"propositions\".\n");
        if (bounded) {
            sb.append("- Return at most ").append(maxPropositions)
                    .append(" proposition objects in source order.\n");
        } else {
            sb.append("- Return one proposition object for EVERY independent source assertion in source order. ")
                    .append("Do not stop after the first assertion and do not apply a relevance filter.\n");
        }
        sb.append("- If the event contains no explicit assertion, return exactly {\"propositions\":[]}.\n")
                .append("- Otherwise repeat this object once per independently true-or-false assertion:\n")
                .append("""
                        {"propositions":[{
                          "text":null,
                          "subject":null,
                          "predicate":null,
                          "object":null,
                          "polarity":null,
                          "modality":null,
                          "timeExpression":null,
                          "condition":null,
                          "attributedTo":null,
                          "evidence":{"quote":null,"role":"DIRECT_SUPPORT"}
                        }]}
                        """);
        sb.append("\nRULES:\n")
                .append("- Extract every independently true-or-false assertion in source order; split coordinated clauses when each can stand on its own.\n")
                .append("- Write text as one concise standalone semantic assertion. You may restore an elided subject or normalize inflection so each item is understandable alone, but never add a fact not supported by SOURCE.\n")
                .append("- Copy explicit subject and object surface mentions from SOURCE; preserve pronouns or omitted arguments when SOURCE does not resolve them. Never replace a mention with a category or role label.\n")
                .append("- Set polarity to exactly AFFIRMED, NEGATED, or UNKNOWN. A plain positive declarative statement is AFFIRMED; preserve every explicit negation.\n")
                .append("- Set modality to exactly FACTUAL, HYPOTHETICAL, OBLIGATION, POSSIBILITY, or UNKNOWN. Preserve hedges, plans, and conditions.\n")
                .append("- A source span that only qualifies another mention is not a separate proposition unless the source independently asserts it.\n")
                .append("- A message sender, document author, or quoted speaker is not automatically the holder; set attributedTo only when this assertion is explicitly attributed.\n")
                .append("- evidence.quote must be one exact contiguous substring copied character-for-character from SOURCE. A non-source quote is discarded.\n")
                .append("- Use JSON null for an absent optional value. Do not copy the word null into a string.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /**
     * Focused small-model form for an explicitly configured source-segmentation experiment. The
     * engine owns the segment and evidence span; the model decides whether it contains an assertion
     * and, when it does, returns its semantic frame. No language-specific classifier or domain
     * vocabulary is applied before the model sees the segment.
     */
    public static String proposition(PassContext context, String focusEvent) {
        String focus = focusEvent == null ? "" : focusEvent.strip();
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Classify and frame ONE engine-fixed source segment.\n")
                .append("The engine owns the segment text, evidence span, order, and id. Decide only whether ")
                .append("this segment expresses an assertion and, if so, return its semantic frame.\n\n")
                .append("<<<FOCUS_SEGMENT\n").append(focus).append("\nFOCUS_SEGMENT>>>\n\n");
        sb.append(SourceGroundedConceptFacets.promptBlock(context, focus)).append("\n\n");
        sb.append("REFERENCE SOURCE (bounded context for resolving an omitted or referring mention; it cannot add another assertion):\n")
                .append(sourceBlock(context)).append('\n');
        sb.append("OUTPUT CONTRACT:\n")
                .append("- Return exactly one flat JSON object and nothing else. Never use a proposition wrapper or array.\n")
                .append("- Use exactly these fields in order: asserted, subject, predicate, object, polarity, modality, timeExpression, condition, attributedTo.\n")
                .append("- Choose exactly one of the two structure-only shapes below. Values shown between __...__ are placeholders and must never be copied.\n\n")
                .append("ASSERTED SHAPE:\n")
                .append("{\"asserted\":true,\"subject\":\"__SOURCE_SUBJECT__\",\"predicate\":\"__SOURCE_PREDICATE__\",\"object\":\"__SOURCE_OBJECT__\",\"polarity\":\"__POLARITY_ENUM__\",\"modality\":\"__MODALITY_ENUM__\",\"timeExpression\":null,\"condition\":null,\"attributedTo\":null}\n")
                .append("NON-ASSERTIVE SHAPE:\n")
                .append("{\"asserted\":false,\"subject\":null,\"predicate\":null,\"object\":null,\"polarity\":null,\"modality\":null,\"timeExpression\":null,\"condition\":null,\"attributedTo\":null}\n\n")
                .append("FRAME RULES:\n")
                .append("- Set asserted from the meaning of FOCUS_SEGMENT, not from a Java word list or graph expectation.\n")
                .append("- For asserted=true, copy complete subject and object surface mentions when explicit; use JSON null when an endpoint is omitted or unresolved.\n")
                .append("- Keep predicate concise and source-grounded. Do not choose a graph relation type in this pass.\n")
                .append("- Source-grounded concept facets expose exact aligned surfaces plus graph/pre-pass metadata. Interpret their categories from the supplied graph, schema, and domain; Java assigns no semantic role from category words.\n")
                .append("- Polarity is AFFIRMED, NEGATED, or UNKNOWN. Modality is FACTUAL, HYPOTHETICAL, OBLIGATION, POSSIBILITY, or UNKNOWN.\n")
                .append("- Preserve source-stated time, condition, attribution, negation, and uncertainty. The engine attaches evidence.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /** One bounded correction after a focused proposition violates its source or assertion contract. */
    public static String propositionCorrection(PassContext context, String focusEvent,
                                               String rejectedResponse) {
        return propositionCorrection(context, focusEvent, rejectedResponse,
                "empty, null, malformed, or not source-grounded");
    }

    /** Bounded semantic correction with the engine validation reason kept out of source evidence. */
    public static String propositionCorrection(PassContext context, String focusEvent,
                                               String rejectedResponse, String validationReason) {
        return "VALIDATION CORRECTION: The previous frame was rejected by the engine: "
                + boundedDiagnostic(context, validationReason) + ". Re-evaluate the exact FOCUS_SEGMENT. "
                + "Correct the JSON branch or source-grounding violation without inheriting a semantic "
                + "decision from the rejected response. Return either complete structure-only shape and use "
                + "only source-grounded values. Never copy a placeholder, non-source value, or "
                + "__...__ token.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(context, rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + proposition(context, focusEvent);
    }

    /**
     * Pass 2: resolve the mentions of one proposition against a bounded candidate set.
     */
    public static String mentions(PassContext context, PropositionProposal proposition,
                                  List<EntityCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Decide which existing entity each mention in ONE proposition refers to.\n")
                .append("Do only this. Do not add propositions, relations or judgements.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("PROPOSITION (the only one you are working on):\n")
                .append("- id: ").append(nullSafe(proposition.id())).append('\n')
                .append("- text: ").append(proposition.render()).append('\n')
                .append("- subject mention: ").append(nullSafe(proposition.subject())).append('\n')
                .append("- object mention: ").append(nullSafe(proposition.object())).append("\n\n");
        sb.append(verifiedEvidenceBlock(proposition.evidence())).append('\n');
        sb.append(SourceGroundedConceptFacets.promptBlock(context)).append("\n\n");
        sb.append(identityContextPolicy()).append("\n\n");
        sb.append(context.retrievalFacetBlock()).append("\n\n");
        sb.append(sourceBlock(context)).append('\n');
        sb.append(entityCandidateBlock(candidates)).append('\n');
        sb.append("OUTPUT FORMAT:\n")
                .append("""
                        {"mentions":[{
                          "propositionId":"<the id above>",
                          "mentionText":"surface form as written",
                          "mentionRole":"SUBJECT|OBJECT|OTHER",
                          "operation":"REUSE_ENTITY|CREATE_PROVISIONAL_ENTITY|UNRESOLVED",
                          "selectedEntityId":"candidate id, or null",
                          "provisionalName":"name for a new entity, or null",
                          "provisionalType":"type for a new entity, or null",
                          "confidence":0.0,
                          "alternatives":[{"candidateId":"id","score":0.0,"reason":"why rejected"}],
                          "reason":"why this decision",
                          "evidence":{"quote":"verbatim source text","role":"DIRECT_SUPPORT"}
                        }]}
                        """);
        sb.append("\nRULES:\n")
                .append("- The candidate list is a bounded retrieval ballot, not a complete inventory of the graph. ")
                .append("Copy \"selectedEntityId\" verbatim from the ballot; never invent an id.\n")
                .append("- Choose REUSE_ENTITY only when the candidate is the SAME real-world ")
                .append("thing. A shared word, surname or industry is not identity.\n")
                .append("- If two candidates are equally plausible, answer UNRESOLVED and list ")
                .append("both under \"alternatives\". Guessing is worse than abstaining.\n")
                .append("- If no candidate is the same thing, answer CREATE_PROVISIONAL_ENTITY ")
                .append("and supply \"provisionalName\" and \"provisionalType\".\n")
                .append("- Every candidate you considered and rejected must appear in ")
                .append("\"alternatives\" with a reason.\n")
                .append(EVIDENCE_COPY_RULE).append('\n')
                .append(JSON_ONLY);
        return sb.toString();
    }

    /**
     * Pass 2 small-model form: resolve one engine-selected endpoint. The endpoint surface, role and
     * source evidence are fixed inputs, so the model returns only the identity decision instead of
     * repeating fields the engine already owns.
     */
    public static String mention(PassContext context, PropositionProposal proposition,
                                 MentionFocus focus, List<EntityCandidate> candidates) {
        return mention(context, proposition, focus, null, candidates, true);
    }

    /**
     * Renders the focused mention contract. {@code inferOperationFromFields=false} retains the
     * former redundant operation field solely so accuracy experiments can isolate this seam.
     */
    public static String mention(PassContext context, PropositionProposal proposition,
                                 MentionFocus focus, List<EntityCandidate> candidates,
                                 boolean inferOperationFromFields) {
        return mention(context, proposition, focus, null, candidates, inferOperationFromFields);
    }

    /** Focused mention form with an optional caller-supplied graph/schema entity-type constraint. */
    public static String mention(PassContext context, PropositionProposal proposition,
                                 MentionFocus focus, String expectedType,
                                 List<EntityCandidate> candidates) {
        return mention(context, proposition, focus, expectedType, candidates, true);
    }

    /**
     * Production uses a discriminated, ordinal contract whose shape changes with the available
     * engine state. The legacy field-inference contract remains available only for ablations.
     */
    public static String mention(PassContext context, PropositionProposal proposition,
                                 MentionFocus focus, String expectedType,
                                 List<EntityCandidate> candidates,
                                 boolean inferOperationFromFields) {
        MentionFocus effectiveFocus = focus == null ? MentionFocus.SUBJECT : focus;
        String surface = effectiveFocus == MentionFocus.SUBJECT
                ? proposition.subject() : proposition.object();
        boolean hasCandidates = candidates != null && !candidates.isEmpty();
        boolean hasExpectedType = expectedType != null && !expectedType.isBlank();
        List<String> provisionalTypeBallot = hasExpectedType
                ? List.of() : provisionalTypeBallot(context, surface, candidates);
        boolean hasProvisionalTypeBallot = !provisionalTypeBallot.isEmpty();
        boolean hasSelectableCandidate = hasCandidates && candidates.stream()
                .anyMatch(candidate -> candidate.identitySignals().selectable());
        boolean weakOnlyBallot = hasSelectableCandidate && candidates.stream()
                .filter(candidate -> candidate.identitySignals().selectable())
                .allMatch(candidate -> candidate.identitySignals().sharedTokensOnly()
                        && !candidate.identitySignals().strongMatch());
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Resolve ONE fixed mention to an existing entity or a provisional entity.\n")
                .append("Do only this identity decision. Do not extract another endpoint, proposition, relation or judgement.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("FIXED ENDPOINT (engine-owned; do not change):\n")
                .append("- propositionId: ").append(nullSafe(proposition.id())).append('\n')
                .append("- mentionRole: ").append(effectiveFocus).append('\n')
                .append("- mentionText: ").append(nullSafe(surface)).append('\n')
                .append("- schemaEntityTypeConstraint: ")
                .append(hasExpectedType ? expectedType : "NONE").append("\n\n");
        sb.append("PROPOSITION CONTEXT:\n- ").append(proposition.render()).append("\n\n");
        sb.append(verifiedEvidenceBlock(proposition.evidence())).append('\n');
        sb.append(SourceGroundedConceptFacets.promptBlock(context, surface)).append("\n\n");
        sb.append(identityContextPolicy()).append("\n\n");
        sb.append(context.retrievalFacetBlock()).append("\n\n");
        sb.append(sourceBlock(context)).append('\n');
        sb.append(inferOperationFromFields
                ? entityCandidateOrdinalBlock(candidates) : entityCandidateBlock(candidates)).append('\n');
        if (inferOperationFromFields && !hasExpectedType) {
            sb.append(provisionalTypeBallotBlock(provisionalTypeBallot)).append('\n');
        }
        sb.append(inferOperationFromFields ? "OUTPUT SHAPES (choose exactly one):\n" : "OUTPUT FORMAT:\n");
        if (inferOperationFromFields) {
            sb.append("CREATE_PROVISIONAL:\n")
                    .append("{\"mention\":{\"decision\":\"CREATE_PROVISIONAL\",");
            if (!hasExpectedType) {
                if (hasProvisionalTypeBallot) {
                    sb.append("\"provisionalTypeOrdinal\":1,");
                } else {
                    sb.append("\"provisionalType\":\"\",");
                }
            }
            sb.append("\"confidence\":0.0,\"reason\":\"cite why the current mention is a new identity\"}}\n")
                    .append("UNRESOLVED:\n")
                    .append("{\"mention\":{\"decision\":\"UNRESOLVED\",\"confidence\":0.0,")
                    .append("\"reason\":\"cite the unresolved identity ambiguity\"}}\n");
            if (hasCandidates) {
                sb.append("REUSE (candidateOrdinal must never be null; use a 1-based integer):\n")
                        .append("{\"mention\":{\"decision\":\"REUSE\",\"candidateOrdinal\":1,")
                        .append("\"confidence\":0.0,\"reason\":\"cite the exact identity signal for the selected candidate\"}}");
            }
        } else {
            sb.append("""
                    {"mention":{
                      "operation":"REUSE_ENTITY|CREATE_PROVISIONAL_ENTITY|UNRESOLVED",
                      "selectedEntityId":null,
                      "provisionalName":null,
                      "provisionalType":null,
                      "confidence":0.0,
                      "reason":"why this identity decision"
                    }}
                    """);
        }
        sb.append("\nRULES:\n")
                .append("- Return exactly one root JSON object containing exactly one key: \"mention\". Do not add sibling keys.\n")
                .append("- The engine supplies mentionText, mentionRole and evidence. Do not repeat or change them.\n");
        if (inferOperationFromFields) {
            sb.append("- decision is the discriminator. Copy exactly one complete branch shape and change only its values; never combine branches.\n")
                    .append("- Inside \"mention\", use only decision, candidateOrdinal when shown, provisionalTypeOrdinal when shown, provisionalType when shown, confidence, and reason. Never output selectedEntityId, provisionalName, operation, evidence, alternatives, mentionText, or mentionRole.\n");
            if (hasCandidates) {
                sb.append("- REUSE: candidateOrdinal must be exactly one 1-based [number] from the ballot and never null. The engine maps it to the opaque entity id.\n")
                        .append("- CREATE_PROVISIONAL or UNRESOLVED: omit candidateOrdinal entirely. It belongs only to REUSE.\n")
                        .append("- Choose REUSE only for the SAME real-world identity. TYPE_MISMATCH, WEAK_LEXICAL_ONLY, NO_STRONG_IDENTITY_SIGNAL, or NOT_SELECTABLE is never sufficient.\n")
                        .append("- SOURCE_ALIAS_EXACT means the current source explicitly attached matchedSourceAlias to mentionText and that alias exactly retrieved this candidate. It is strong recall evidence, but TYPE_MISMATCH, NOT_SELECTABLE, and conflicting stable identifiers still win.\n");
            } else {
                sb.append("- No candidate exists, so REUSE and candidateOrdinal are not part of this contract.\n");
            }
            sb.append("- CREATE_PROVISIONAL when no candidate is the same identity; the engine uses mentionText as the provisional name.\n")
                    .append("- UNRESOLVED when the evidence cannot distinguish identities. Abstention is safer than a merge.\n");
            if (hasExpectedType) {
                sb.append("- The caller supplied graph/schema type constraint ").append(expectedType)
                        .append(". The engine owns that field; do not output provisionalType.\n");
            } else if (hasProvisionalTypeBallot) {
                sb.append("- CREATE_PROVISIONAL: provisionalTypeOrdinal must be exactly one 1-based [number] from the provisional type ballot. The engine maps it to the graph/schema label. Do not output provisionalType.\n")
                        .append("- REUSE or UNRESOLVED: omit provisionalTypeOrdinal entirely.\n");
            } else {
                sb.append("- CREATE_PROVISIONAL: replace the empty provisionalType string with the most specific concrete entity class supported by the source and graph/schema context. It must match [A-Z][A-Z0-9_]* and must not be a template token.\n")
                        .append("- REUSE or UNRESOLVED: omit provisionalType entirely.\n");
            }
        } else {
            sb.append("- For REUSE_ENTITY, replace selectedEntityId with only the candidate id value after \"id=\"; never include the \"id=\" prefix.\n")
                    .append("- Choose REUSE_ENTITY only when the candidate is the SAME real-world thing.\n")
                    .append("- If candidates are equally plausible, answer UNRESOLVED and explain the ambiguity in reason.\n")
                    .append("- If no candidate is the same thing, answer CREATE_PROVISIONAL_ENTITY with a name and type.\n");
        }
        if (inferOperationFromFields && hasCandidates && !hasSelectableCandidate) {
            sb.append("ENGINE IDENTITY ROUTE FOR THIS BALLOT: no displayed candidate is selectable. ")
                    .append("REUSE is invalid; choose CREATE_PROVISIONAL or UNRESOLVED.\n");
        } else if (inferOperationFromFields && weakOnlyBallot) {
            sb.append("ENGINE IDENTITY ROUTE FOR THIS BALLOT: every selectable candidate has only ")
                    .append("WEAK_LEXICAL_ONLY overlap and no exact identity signal. REUSE is invalid; ")
                    .append("choose CREATE_PROVISIONAL or UNRESOLVED.\n");
        }
        sb.append(JSON_ONLY);
        return sb.toString();
    }

    /** One bounded repair after a focused mention violates its branch or graph-purity contract. */
    public static String mentionCorrection(PassContext context, PropositionProposal proposition,
                                           MentionFocus focus, String expectedType,
                                           List<EntityCandidate> candidates,
                                           String rejectedResponse, String validationReason) {
        return "VALIDATION CORRECTION: The previous identity answer was rejected by the engine. "
                + "Correct the decision itself, not just its formatting. The rejection was: "
                + boundedDiagnostic(validationReason) + ". Graph identity constraints and the "
                + "caller-supplied graph/schema type constraint are hard controls; when they prevent a safe reuse, choose "
                + "CREATE_PROVISIONAL or UNRESOLVED. candidateOrdinal appears only with REUSE; "
                + "delete it entirely for CREATE_PROVISIONAL or UNRESOLVED.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + mention(context, proposition, focus, expectedType, candidates, true);
    }

    /**
     * Pass 3: classify the epistemic status of one proposition. The allowed labels are the output
     * vocabulary; the proposition, attribution fields, and source determine the label.
     */
    public static String epistemic(PassContext context, PropositionProposal proposition) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Classify what KIND of statement ONE proposition is.\n")
                .append("Do only this. Do not decide whether it is true, and do not extract ")
                .append("anything new.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("PROPOSITION (the only one you are working on):\n")
                .append("- id: ").append(nullSafe(proposition.id())).append('\n')
                .append("- text: ").append(proposition.render()).append('\n')
                .append("- modality from pass 1: ").append(proposition.modality()).append('\n')
                .append("- attributed to (from pass 1, may be null): ")
                .append(nullSafe(proposition.attributedTo())).append("\n\n");
        sb.append(verifiedEvidenceBlock(proposition.evidence())).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append("OUTPUT CONTRACT:\n")
                .append("- Begin exactly with {\"classification\":{\"speechAct\":\" and return exactly speechAct, holder, certainty, and reason.\n")
                .append("- speechAct is exactly one of ASSERTION, OPERATIONAL_RECORD, OPINION, REQUEST, COMMITMENT, WARNING, PREDICTION, QUESTION, or UNKNOWN.\n")
                .append("- holder is the explicit source holder as a JSON string, or JSON null when no holder is expressed.\n")
                .append("- certainty is a JSON number from 0.0 through 1.0 representing certainty expressed by the source.\n")
                .append("- reason cites the current proposition words that decide the class. Never copy contract descriptions as values.\n\n");
        sb.append("RULES:\n")
                .append("- Decide from the proposition frame, its explicit attribution fields, and verified source. No language-specific cue list or expected graph outcome is supplied.\n")
                .append("- Use ASSERTION when the source presents the proposition as its own claim, OPERATIONAL_RECORD when the source presents it as a record, and OPINION when it presents an attributed stance. Use the other labels according to their ordinary meanings.\n")
                .append("- Use UNKNOWN only when the available source does not support a more specific label.\n")
                .append("- The engine already owns the proposition id and verified evidence shown above. ")
                .append("Output no propositionId, evidence, quote, role, chunk id, or source offsets.\n");
        sb.append(JSON_ONLY);
        return sb.toString();
    }

    /** One bounded repair when the response violates the epistemic JSON contract. */
    public static String epistemicCorrection(PassContext context, PropositionProposal proposition,
                                             String rejectedResponse, String validationReason) {
        return "VALIDATION CORRECTION: The previous epistemic answer was rejected by the engine: "
                + boundedDiagnostic(validationReason) + ". Reclassify the same fixed proposition; do not "
                + "copy allowed-value lists, placeholder reasons, or default numbers. The correction "
                + "constrains structure only; infer the label from the supplied proposition and source.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + epistemic(context, proposition);
    }

    /**
     * Pass 4 legacy form: select one schema-admissible relation for one proposition. Production's
     * split form repeats a single-choice ballot until the model returns DONE, so a proposition may
     * surface any number of distinct graph-supported relations.
     */
    public static String relations(PassContext context, PropositionProposal proposition,
                                   String sourceLabel, String targetLabel,
                                   List<RelationCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Choose the ONE relation type that this proposition asserts between two ")
                .append("already-resolved entities.\n")
                .append("Do only this. Do not re-resolve the entities and do not invent types.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("RESOLVED ENTITIES (fixed — do not change):\n")
                .append("- source: ").append(nullSafe(sourceLabel)).append('\n')
                .append("- target: ").append(nullSafe(targetLabel)).append("\n\n");
        sb.append("PROPOSITION (the only one you are working on):\n")
                .append("- id: ").append(nullSafe(proposition.id())).append('\n')
                .append("- text: ").append(proposition.render()).append('\n')
                .append("- polarity: ").append(proposition.polarity()).append('\n')
                .append("- modality: ").append(proposition.modality()).append("\n\n");
        sb.append(verifiedEvidenceBlock(proposition.evidence())).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append(relationCandidateBlock(candidates)).append('\n');
        sb.append("OUTPUT FORMAT:\n")
                .append("""
                        {"relation":{
                          "propositionId":"<the id above>",
                          "operation":"CREATE_CLAIM|PROPOSE_SCHEMA_GAP|ABSTAIN",
                          "type":"a permitted type name, or null",
                          "confidence":0.0,
                          "occurredAt":"ISO-8601 or the text's own time expression, or null",
                          "qualifiers":{"schemaQualifier":"source-stated value"},
                          "alternatives":[{"candidateId":"type","score":0.0,"reason":"why rejected"}],
                          "reason":"why this type",
                          "evidence":{"quote":"verbatim source text","role":"DIRECT_SUPPORT"}
                        }}
                        """);
        sb.append("\nRULES:\n")
                .append("- Use CREATE_CLAIM with a \"type\" copied verbatim from the permitted ")
                .append("list above.\n")
                .append("- If the proposition asserts a relation that NO permitted type ")
                .append("expresses, answer PROPOSE_SCHEMA_GAP and describe the missing type in ")
                .append("\"reason\". Do not force the nearest type.\n")
                .append("- If the proposition asserts no relation between these two entities, ")
                .append("answer ABSTAIN.\n")
                .append("- A NEGATED proposition does not assert the relation: answer ABSTAIN ")
                .append("unless a permitted type expresses the negation itself.\n")
                .append("- Put source-stated values that qualify the claim into \"qualifiers\" using schema-defined keys. Dropping a supported qualifier is an error.\n")
                .append(EVIDENCE_COPY_RULE).append('\n')
                .append(JSON_ONLY);
        return sb.toString();
    }

    /**
     * Small-model relation gate: decide only whether the proposition asserts a positive relation
     * between the fixed endpoints. No schema vocabulary is shown until this decision is complete.
     */
    public static String relationExistence(PassContext context, PropositionProposal proposition,
                                           String sourceLabel, String targetLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Decide one thing: does this proposition explicitly assert a relation between the two fixed entities?\n")
                .append("Do not name or choose a relation type. Do not change the endpoints.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("FIXED ENDPOINTS (engine-owned):\n")
                .append("- source: ").append(nullSafe(sourceLabel)).append('\n')
                .append("- target: ").append(nullSafe(targetLabel)).append("\n\n");
        sb.append("ENGINE-PARSED PROPOSITION FRAME:\n")
                .append("- text: ").append(proposition.render()).append('\n')
                .append("- subject mention: ").append(nullSafe(proposition.subject())).append('\n')
                .append("- predicate: ").append(nullSafe(proposition.predicate())).append('\n')
                .append("- object mention: ").append(nullSafe(proposition.object())).append('\n')
                .append("- polarity: ").append(proposition.polarity()).append('\n')
                .append("- modality: ").append(proposition.modality()).append("\n\n");
        sb.append(verifiedEvidenceBlock(proposition.evidence())).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append("DECISION SEMANTICS:\n")
                .append("- Decide from the engine-parsed proposition frame, endpoint identities, polarity, modality, and verified source.\n")
                .append("- ASSERTED means the proposition positively connects these fixed endpoints; NOT_ASSERTED means it does not; UNCERTAIN means the supplied information cannot decide.\n")
                .append("- The engine provides no language-specific syntax rule or expected verdict. Graph state supplies endpoint identity, while SOURCE supplies the new relation.\n\n")
                .append("OUTPUT CONTRACT:\n")
                .append("- Begin exactly with {\"decision\":{\"verdict\":\" and return verdict, confidence, and reason.\n")
                .append("- verdict is exactly ASSERTED when the proposition positively connects source to target.\n")
                .append("- verdict is exactly NOT_ASSERTED for description-only co-mentions, questions, unrelated clauses, or a negated relation.\n")
                .append("- verdict is exactly UNCERTAIN only when the source itself is ambiguous; the engine treats it as abstention.\n")
                .append("- confidence is a JSON number from 0.0 through 1.0. reason identifies the deciding source words.\n")
                .append("- Output no boolean, type, candidate, endpoint id, time, qualifier, or evidence object.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /** One bounded correction when a relation-existence response violates its JSON contract. */
    public static String relationExistenceCorrection(PassContext context,
                                                     PropositionProposal proposition,
                                                     String sourceLabel, String targetLabel,
                                                     String rejectedResponse) {
        return "VALIDATION CORRECTION: The previous verdict violated the relation-existence "
                + "contract. Re-evaluate the same engine-parsed proposition, fixed endpoints, and "
                + "verified source, then return one complete decision object. No lexical route or "
                + "expected semantic answer is supplied by the engine.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + relationExistence(context, proposition, sourceLabel, targetLabel);
    }

    /**
     * Small-model relation type decision after the existence gate passed. The model selects a
     * 1-based ordinal; the engine owns the canonical type string and all source evidence.
     */
    public static String relationType(PassContext context, PropositionProposal proposition,
                                      String sourceLabel, String targetLabel,
                                      List<RelationCandidate> candidates) {
        return relationType(context, proposition, sourceLabel, targetLabel, candidates, List.of());
    }

    static String relationType(PassContext context, PropositionProposal proposition,
                               String sourceLabel, String targetLabel,
                               List<RelationCandidate> candidates,
                               List<String> alreadySelectedTypes) {
        boolean additionalRound = alreadySelectedTypes != null && !alreadySelectedTypes.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append(additionalRound
                        ? "TASK: Decide whether this proposition expresses one ADDITIONAL semantically distinct permitted relation type.\n"
                        : "TASK: Select the one permitted relation type expressed by this already-confirmed relation.\n")
                .append("The existence decision is complete. Do not reconsider endpoints or emit a type name.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("FIXED ENDPOINTS (engine-owned):\n")
                .append("- source: ").append(nullSafe(sourceLabel)).append('\n')
                .append("- target: ").append(nullSafe(targetLabel)).append("\n\n");
        sb.append("ENGINE-PARSED PROPOSITION FRAME:\n")
                .append("- text: ").append(proposition.render()).append('\n')
                .append("- predicate to match: ").append(nullSafe(proposition.predicate())).append('\n')
                .append("- subject mention: ").append(nullSafe(proposition.subject())).append('\n')
                .append("- object mention: ").append(nullSafe(proposition.object())).append("\n\n");
        sb.append(verifiedEvidenceBlock(proposition.evidence())).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append(relationCandidateOrdinalBlock(candidates)).append('\n');
        if (additionalRound) {
            sb.append("ALREADY VALIDATED TYPES (engine-owned; never select a synonym or broader/narrower restatement):\n");
            alreadySelectedTypes.forEach(type -> sb.append("- ").append(type).append('\n'));
            sb.append("Select another ordinal only when the source independently asserts that distinct meaning. "
                    + "Otherwise return decision=DONE, candidateOrdinal=null, and schemaGap=false.\n\n");
        }
        sb.append("DECISION SEMANTICS:\n")
                .append("- Compare the complete source-grounded proposition meaning and endpoint direction with each graph-supplied candidate type, description, audited alias, and logical facet.\n")
                .append("- The engine has already applied hard schema domain/range constraints. Treat displayed logical facets as admissibility control data and embeddings or similarity as recall/ranking signals, never as source evidence.\n")
                .append("- Select at most one ordinal in this small decision. The engine will remove it and ask again when other distinct candidates remain; never turn candidate names into qualifier keys or boolean flags.\n")
                .append(additionalRound
                        ? "- A remaining candidate is not automatically an additional fact. When the source does not independently assert another meaning, decision=DONE is the correct answer.\n\n"
                        : "\n")
                .append("OUTPUT CONTRACT:\n")
                .append("- Begin exactly with {\"selection\":{\"decision\": and return decision, candidateOrdinal, schemaGap, confidence, qualifiers, and reason.\n")
                .append("- selection MUST be a JSON object. Never set selection to null and never put its fields beside it at the top level.\n")
                .append("- decision is SELECT, SCHEMA_GAP, ABSTAIN")
                .append(additionalRound ? ", or DONE. DONE is the explicit no-more-relations branch.\n" : ". DONE is invalid in the first round.\n")
                .append("- candidateOrdinal is a JSON integer containing the 1-based [number] beside the exact permitted type, or JSON null. Never output the type text.\n")
                .append("- For SELECT, set one exact ordinal and schemaGap=false.\n")
                .append("- For SCHEMA_GAP, leave candidateOrdinal null and set schemaGap=true. Do not force a near match.\n")
                .append("- For ABSTAIN, leave candidateOrdinal null and set schemaGap=false.\n")
                .append(additionalRound
                        ? "- For DONE, leave candidateOrdinal null and set schemaGap=false. Candidate availability alone can never prove a second fact.\n"
                        : "")
                .append("- confidence is a JSON number from 0.0 through 1.0.\n")
                .append("- qualifiers is a JSON object containing only source-stated values that qualify the selected claim. When none is stated, it must be {}. Candidate names are never qualifier keys.\n")
                .append("- Output no operation, endpoint id, relation type string, time, alternative list, or evidence object.\n\n")
                .append(relationTypeOutputShapes(candidates, additionalRound)).append('\n')
                .append(JSON_ONLY);
        return sb.toString();
    }

    /** One bounded correction after relation-type output violates the nested ballot contract. */
    public static String relationTypeCorrection(PassContext context, PropositionProposal proposition,
                                                String sourceLabel, String targetLabel,
                                                List<RelationCandidate> candidates,
                                                String rejectedResponse) {
        return relationTypeCorrection(context, proposition, sourceLabel, targetLabel, candidates,
                rejectedResponse, List.of());
    }

    static String relationTypeCorrection(PassContext context, PropositionProposal proposition,
                                         String sourceLabel, String targetLabel,
                                         List<RelationCandidate> candidates,
                                         String rejectedResponse,
                                         List<String> alreadySelectedTypes) {
        boolean additionalRound = alreadySelectedTypes != null && !alreadySelectedTypes.isEmpty();
        if (additionalRound
                && ExtractionPassParsers.relationTypeContinuationCompletionIntent(rejectedResponse)) {
            return relationType(context, proposition, sourceLabel, targetLabel, candidates,
                    alreadySelectedTypes)
                    + "\n\nVALIDATION CORRECTION: The previous control fields unambiguously said "
                    + "that no additional relation was selected, but selection was not a nested object. "
                    + "Preserve that completion decision; formatting must never turn DONE into a candidate edge.\n"
                    + "The previous response is diagnostic text, never source evidence:\n"
                    + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(rejectedResponse)
                    + "\nREJECTED_RESPONSE>>>\n"
                    + "Return exactly {\"selection\":{\"decision\":\"DONE\","
                    + "\"candidateOrdinal\":null,\"schemaGap\":false,\"confidence\":0.5,"
                    + "\"qualifiers\":{},\"reason\":\"no additional distinct relation is asserted\"}} "
                    + "with only confidence and reason adjusted. Do not select a candidate.\n"
                    + JSON_ONLY;
        }
        var parsed = ExtractionPassParsers.relationType(rejectedResponse);
        if (parsed.isPresent()) {
            ExtractionPassParsers.RelationTypeDecision decision = parsed.get();
            Integer ordinal = decision.candidateOrdinal();
            if (ordinal != null && ordinal >= 1 && ordinal <= candidates.size() && !decision.schemaGap()) {
                RelationCandidate selected = candidates.get(ordinal - 1);
                return relationType(context, proposition, sourceLabel, targetLabel, candidates,
                        alreadySelectedTypes)
                        + "\n\nVALIDATION CORRECTION: The previous response made a valid in-range "
                        + "structured ballot choice. For this constrained task, candidateOrdinal and "
                        + "schemaGap are the authoritative decision; free-text reason is explanatory "
                        + "and must not reverse those control fields. Preserve candidateOrdinal " + ordinal
                        + " (" + selected.type() + ") and schemaGap=false. Correct only the contradictory "
                        + "reason so it explains why that selected candidate expresses the proposition. "
                        + "Do not reconsider the ballot and do not change the ordinal.\n"
                        + "Return one selection object with all five required fields and nothing else.\n"
                        + JSON_ONLY;
            }
        }
        return "VALIDATION CORRECTION: The previous response violated the relation-type ballot "
                + "contract or contradicted itself. decision, candidateOrdinal, and schemaGap must agree: "
                + "SELECT requires one ordinal; SCHEMA_GAP requires null/true; ABSTAIN and DONE require "
                + "null/false. The top level must contain only a non-null selection object, and all six "
                + "fields belong inside that object. Re-evaluate the decision; do not merely reformat a contradictory answer.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + relationType(context, proposition, sourceLabel, targetLabel, candidates,
                        alreadySelectedTypes);
    }

    /**
     * Pass 5: match one proposed relation against claims the KB already holds. The model may flag
     * a conflict but is explicitly forbidden from resolving one.
     */
    public static String claims(PassContext context, RelationProposal relation,
                                String relationSummary, List<ClaimCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Decide how ONE newly proposed relation relates to claims the knowledge ")
                .append("base already holds.\n")
                .append("Do only this. You may flag a conflict; you must NOT resolve one.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("NEW RELATION (the only one you are working on):\n")
                .append("- ").append(nullSafe(relationSummary)).append('\n')
                .append("- from proposition: ").append(nullSafe(relation.propositionId()))
                .append("\n\n");
        sb.append(verifiedEvidenceBlock(relation.evidence())).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append(claimCandidateBlock(candidates)).append('\n');
        sb.append("OUTPUT FORMAT:\n")
                .append("""
                        {"decision":{
                          "operation":"ADD_EVIDENCE|CREATE_CLAIM|FLAG_CONTRADICTION|ABSTAIN",
                          "matchedCandidateOrdinal":1,
                          "confidence":0.0,
                          "reason":"why this decision"
                        }}
                        """);
        sb.append("\nRULES:\n")
                .append("- ADD_EVIDENCE when the new relation says the same thing as an existing ")
                .append("claim. Attach to it; do not create a duplicate.\n")
                .append("- FLAG_CONTRADICTION when it is incompatible with an existing claim. ")
                .append("You are only reporting the conflict. Do not say which side is right and ")
                .append("do not retract anything — that decision is not yours.\n")
                .append("- For ADD_EVIDENCE or FLAG_CONTRADICTION, set matchedCandidateOrdinal to the ")
                .append("1-based [number] printed beside that existing claim. Do not copy or rewrite its atomKey.\n")
                .append("- For CREATE_CLAIM or ABSTAIN, set matchedCandidateOrdinal to null.\n")
                .append("- CREATE_CLAIM when no existing claim covers it.\n")
                .append("- ABSTAIN when you cannot tell. Abstaining is expected on partial text.\n")
                .append("- Do not output proposition ids, atom keys, alternatives, or evidence. The engine owns ")
                .append("those fields, maps the ordinal to the exact key, and attaches the already verified relation evidence.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /**
     * Small-model claim decision for exactly one existing candidate. The engine knows which
     * candidate is being judged, so the model never sees or reproduces its opaque atom key.
     */
    public static String claimCandidate(PassContext context, RelationProposal relation,
                                        String relationSummary, ClaimCandidate candidate,
                                        int ordinal, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Compare one new relation with exactly one existing claim.\n")
                .append("Do not search other claims, choose an id, or decide which claim is true.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append("NEW RELATION:\n- ").append(nullSafe(relationSummary)).append('\n')
                .append("- subject: ").append(nullSafe(relation == null ? null : relation.sourceEntityId())).append('\n')
                .append("- predicate: ").append(nullSafe(relation == null ? null : relation.type())).append('\n')
                .append("- object: ").append(nullSafe(relation == null ? null : relation.targetEntityId())).append("\n\n");
        sb.append(verifiedEvidenceBlock(relation.evidence())).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append("ONE EXISTING CLAIM (candidate ").append(ordinal).append(" of ")
                .append(total).append("; identity held by engine):\n")
                .append("- claim: ").append(nullSafe(candidate == null ? null : candidate.summary())).append('\n');
        if (candidate != null) {
            sb.append("- subject: ").append(nullSafe(candidate.subject())).append('\n')
                    .append("- predicate: ").append(nullSafe(candidate.predicate())).append('\n')
                    .append("- object: ").append(nullSafe(candidate.object())).append('\n');
        }
        sb.append('\n').append(claimDeltaBlock(relation, candidate)).append('\n');
        sb.append("\nOUTPUT FORMAT:\n")
                .append("""
                        {"comparison":{
                          "relationship":"SAME|CONTRADICTS|DIFFERENT|UNCERTAIN",
                          "confidence":0.0,
                          "reason":"why this pair has that relationship"
                        }}
                        """);
        sb.append("\nDECISION PROCEDURE:\n")
                .append("- First obey the ENGINE-COMPUTED STRUCTURAL DELTA. A known MISMATCH makes SAME illegal.\n")
                .append("- Then compare wording, polarity and scope only where the structural delta leaves a semantic choice.\n")
                .append("- SAME only when the two statements make the same scoped claim and every known atom field matches.\n")
                .append("- CONTRADICTS only when the graph/schema semantics make the two scoped claims mutually exclusive. Different atom fields alone do not prove a contradiction.\n")
                .append("- Treat graph-provided logic and scope facets as controls; do not infer domain rules from hardcoded vocabulary.\n")
                .append("- When subject and predicate match but object differs, the claims are DIFFERENT because both may be true.\n")
                .append("- DIFFERENT when both may be true but concern distinct facts.\n")
                .append("- UNCERTAIN when the available wording or scope is insufficient.\n")
                .append("- Output no operation, candidate number, atom key, proposition id, alternative list, or evidence object.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /** One bounded repair after a claim comparison contradicts the engine-computed atom delta. */
    public static String claimCandidateCorrection(PassContext context, RelationProposal relation,
                                                  String relationSummary, ClaimCandidate candidate,
                                                  int ordinal, int total,
                                                  String rejectedResponse,
                                                  String validationReason) {
        return "VALIDATION CORRECTION: The previous claim comparison was rejected because "
                + boundedDiagnostic(validationReason) + ". The engine-computed subject, predicate, "
                + "and object delta is authoritative control data. Re-evaluate the semantic label; "
                + "do not merely reformat the rejected label.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedDiagnostic(rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + claimCandidate(context, relation, relationSummary, candidate, ordinal, total);
    }

    static String claimDeltaBlock(RelationProposal relation, ClaimCandidate candidate) {
        String subject = matchState(relation == null ? null : relation.sourceEntityId(),
                candidate == null ? null : candidate.subject());
        String predicate = matchState(relation == null ? null : relation.type(),
                candidate == null ? null : candidate.predicate());
        String object = matchState(relation == null ? null : relation.targetEntityId(),
                candidate == null ? null : candidate.object());
        String structuralCase;
        if ("MATCH".equals(subject) && "MATCH".equals(predicate) && "MATCH".equals(object)) {
            structuralCase = "EXACT_ENDPOINT_ATOM";
        } else if ("MATCH".equals(subject) && "MATCH".equals(predicate)
                && "MISMATCH".equals(object)) {
            structuralCase = "SAME_SUBJECT_PREDICATE_DIFFERENT_OBJECT";
        } else if ("MATCH".equals(subject) && "MISMATCH".equals(predicate)
                && "MATCH".equals(object)) {
            structuralCase = "SAME_ENDPOINTS_DIFFERENT_PREDICATE";
        } else if ("MISMATCH".equals(subject) || "MISMATCH".equals(predicate)
                || "MISMATCH".equals(object)) {
            structuralCase = "DISTINCT_ATOM_FIELDS";
        } else {
            structuralCase = "INCOMPLETE_STORED_ATOM";
        }
        String guard = structuralCase.equals("EXACT_ENDPOINT_ATOM")
                ? "SAME is structurally possible; still verify scope and polarity"
                : structuralCase.equals("INCOMPLETE_STORED_ATOM")
                ? "No deterministic SAME conclusion; use wording and scope"
                : "SAME is forbidden by a known atom-field mismatch";
        return "ENGINE-COMPUTED STRUCTURAL DELTA (control facts; not source evidence):\n"
                + "- subject: " + subject + "\n"
                + "- predicate: " + predicate + "\n"
                + "- object: " + object + "\n"
                + "- structuralCase: " + structuralCase + "\n"
                + "- guard: " + guard;
    }

    private static String matchState(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return "UNKNOWN";
        }
        String normalizedLeft = left.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        String normalizedRight = right.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return normalizedLeft.equals(normalizedRight) ? "MATCH" : "MISMATCH";
    }

    // ---------------------------------------------------------------------------------------
    // shared blocks
    // ---------------------------------------------------------------------------------------

    static String verifiedEvidenceBlock(EvidenceSpan evidence) {
        if (evidence == null || !evidence.hasQuote()) {
            return "VERIFIED EVIDENCE FROM THE PREVIOUS PASS: none. Select one exact contiguous "
                    + "substring from SOURCE TEXT; do not reconstruct a quote.";
        }
        return "VERIFIED EVIDENCE FROM THE PREVIOUS PASS (engine-checked; copy, do not rewrite):\n"
                + "<<<VERIFIED_EVIDENCE\n" + evidence.quote().strip()
                + "\nVERIFIED_EVIDENCE>>>";
    }

    static String sourceBlock(PassContext context) {
        String text = context.sourceText();
        int sourceLimit = Math.min(MAX_SOURCE_CHARS, context.promptSourceCharLimit());
        StringBuilder sb = new StringBuilder("SOURCE TEXT (the only permitted source of content");
        if (text.length() > sourceLimit) {
            sb.append("; TRUNCATED to the first ").append(sourceLimit).append(" of ")
                    .append(text.length()).append(" characters");
            text = text.substring(0, sourceLimit);
        }
        sb.append("):\n<<<SOURCE\n").append(text).append("\nSOURCE>>>\n");
        return sb.toString();
    }

    private static String boundedDiagnostic(String response) {
        String value = response == null ? "<null>" : response.strip();
        return value.length() <= 2_000 ? value : value.substring(0, 2_000) + "\n[TRUNCATED]";
    }

    private static String boundedDiagnostic(PassContext context, String response) {
        String value = response == null ? "<null>" : response.strip();
        int limit = context == null ? 2_000 : context.promptDiagnosticCharLimit();
        return value.length() <= limit ? value : value.substring(0, limit) + "\n[TRUNCATED]";
    }

    static String identityContextPolicy() {
        return "IDENTITY CONTEXT POLICY: Existing graph state has already been used by the engine "
                + "to retrieve and rank the bounded candidate ballot below. Only compact neighborhoods "
                + "attached to those candidates are shown; raw graph prose, rule programs, and vectors are "
                + "not repeated. logic= facets report engine-derived constraints with rule/version provenance. "
                + "embedding/similarity/graphScore facets are soft ranking evidence, never identity proof. The "
                + "ballot is the complete selectable candidate set; choose only a displayed ordinal. The engine "
                + "owns canonical graph ids, and graph state is never source evidence.";
    }

    static String entityCandidateBlock(List<EntityCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return """
                    CANDIDATE ENTITIES: none were retrieved for this proposition.
                    Because the candidate set is empty, REUSE_ENTITY is not available: either
                    propose a provisional entity or answer UNRESOLVED.
                    """;
        }
        StringBuilder sb = new StringBuilder(
                "CANDIDATE ENTITIES (bounded ballot retrieved for this exact mention; it may not "
                        + "contain every entity in the graph):\n");
        for (EntityCandidate c : candidates) {
            sb.append("- id=").append(c.id())
                    .append(" | name=").append(nullSafe(c.name()))
                    .append(" | type=").append(nullSafe(c.type()));
            if (!c.aliases().isEmpty()) {
                sb.append(" | aliases=").append(String.join(", ", c.aliases()));
            }
            sb.append(" | retrievalScore=").append(round(c.score()));
            if (c.provenance() != null && !c.provenance().isBlank()) {
                sb.append(" (").append(c.provenance()).append(')');
            }
            if (c.identityContext() != null && !c.identityContext().isBlank()) {
                sb.append(" | graphIdentityContext=").append(c.identityContext());
            }
            sb.append('\n');
        }
        sb.append("The retrieval score is provider-specific similarity. It is a ranking ")
                .append("hint only — a high score is not evidence of identity. graphIdentityContext is ")
                .append("prior identity/reconciliation state only; it cannot support a new source fact.\n");
        return sb.toString();
    }

    /**
     * Focused production ballot. Opaque graph ids stay engine-owned; the model selects only a
     * compact ordinal and receives deterministic identity signals separately from similarity.
     */
    static String entityCandidateOrdinalBlock(List<EntityCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "CANDIDATE ENTITIES: none. The production contract therefore has no REUSE branch.";
        }
        StringBuilder sb = new StringBuilder(
                "CANDIDATE ENTITIES (bounded ordinal ballot for this exact mention):\n");
        for (int index = 0; index < candidates.size(); index++) {
            EntityCandidate candidate = candidates.get(index);
            sb.append("- [").append(index + 1).append("] name=")
                    .append(nullSafe(candidate.name()))
                    .append(" | type=").append(nullSafe(candidate.type()));
            if (!candidate.aliases().isEmpty()) {
                sb.append(" | aliases=").append(String.join(", ", candidate.aliases()));
            }
            sb.append(" | retrievalScore=").append(round(candidate.score()))
                    .append(" | engineMatchSignals=")
                    .append(identitySignals(candidate));
            if (candidate.provenance() != null && !candidate.provenance().isBlank()) {
                sb.append(" | retrievalProvider=").append(candidate.provenance());
            }
            if (candidate.identitySignals().constraintReason() != null) {
                sb.append(" | engineConstraint=")
                        .append(candidate.identitySignals().constraintReason());
            }
            if (candidate.identityContext() != null && !candidate.identityContext().isBlank()) {
                sb.append(" | graphIdentityContext=").append(candidate.identityContext());
            }
            sb.append('\n');
        }
        sb.append("Ordinals are temporary ballot handles; the engine owns entity ids. Retrieval score "
                + "ranks recall only. Engine match signals and constraints are deterministic control "
                + "facts, not source evidence. A high similarity score never overrides a type or "
                + "MUST_NOT_MERGE constraint.\n");
        return sb.toString();
    }

    /**
     * Bounded type vocabulary for a new endpoint. Values come only from source-aligned graph/pre-pass
     * facets, types already attached to retrieved graph candidates, and the active schema. Java does
     * not infer a type from source words; it only validates and orders labels supplied by those
     * structural inputs so the model can select one without inventing transport data.
     */
    static List<String> provisionalTypeBallot(PassContext context, String surface,
                                              List<EntityCandidate> candidates) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (context != null) {
            SourceGroundedConceptFacets.from(context, surface).stream()
                    .map(SourceGroundedConceptFacets.Facet::category)
                    .filter(ExtractionPassPrompts::validGraphType)
                    .forEach(values::add);
        }
        if (candidates != null) {
            candidates.stream()
                    .map(EntityCandidate::type)
                    .filter(ExtractionPassPrompts::validGraphType)
                    .forEach(values::add);
        }
        if (context != null) {
            context.schemaEntityTypes().stream()
                    .filter(ExtractionPassPrompts::validGraphType)
                    .forEach(values::add);
        }
        int limit = context == null ? 16 : context.promptEntityTypeLimit();
        return values.stream().limit(limit).toList();
    }

    static String provisionalTypeBallotBlock(List<String> types) {
        if (types == null || types.isEmpty()) {
            return "PROVISIONAL ENTITY TYPE BALLOT: none was supplied by source-aligned graph facets, "
                    + "retrieved graph entities, or the active schema.";
        }
        StringBuilder sb = new StringBuilder(
                "PROVISIONAL ENTITY TYPE BALLOT (bounded labels supplied by graph/pre-pass/schema state):\n");
        for (int index = 0; index < types.size(); index++) {
            sb.append("- [").append(index + 1).append("] ").append(types.get(index)).append('\n');
        }
        sb.append("The ordinal is a temporary handle. Choose the label that describes the entire fixed "
                + "mention in this source; the engine owns and validates the actual graph type.");
        return sb.toString();
    }

    private static boolean validGraphType(String value) {
        return value != null && GRAPH_TYPE_NAME.matcher(value.strip()).matches();
    }

    private static String identitySignals(EntityCandidate candidate) {
        var signals = candidate.identitySignals();
        StringBuilder rendered = new StringBuilder();
        appendSignal(rendered, signals.canonicalNameExact(), "CANONICAL_EXACT");
        appendSignal(rendered, signals.aliasExact(), "ALIAS_EXACT");
        appendSignal(rendered, signals.stableIdentifierExact(), "IDENTIFIER_EXACT");
        appendSignal(rendered, signals.sourceAliasExact(), "SOURCE_ALIAS_EXACT");
        appendSignal(rendered, signals.typeCompatible(), "TYPE_COMPATIBLE");
        appendSignal(rendered, !signals.typeCompatible(), "TYPE_MISMATCH");
        appendSignal(rendered, signals.sharedTokensOnly(), "WEAK_LEXICAL_ONLY");
        appendSignal(rendered, signals.forbiddenMerge(), "NOT_SELECTABLE");
        if (signals.matchedIdentifier() != null) {
            if (!rendered.isEmpty()) {
                rendered.append(',');
            }
            rendered.append("matchedIdentifier=").append(signals.matchedIdentifier());
        }
        if (signals.matchedSourceAlias() != null) {
            if (!rendered.isEmpty()) {
                rendered.append(',');
            }
            rendered.append("matchedSourceAlias=").append(signals.matchedSourceAlias());
        }
        return rendered.isEmpty() ? "NO_STRONG_IDENTITY_SIGNAL" : rendered.toString();
    }

    private static void appendSignal(StringBuilder rendered, boolean present, String signal) {
        if (!present) {
            return;
        }
        if (!rendered.isEmpty()) {
            rendered.append(',');
        }
        rendered.append(signal);
    }

    static String relationCandidateBlock(List<RelationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return """
                    PERMITTED RELATION TYPES: none are defined for these entity types.
                    Because the permitted set is empty, answer PROPOSE_SCHEMA_GAP if the text
                    asserts a relation, otherwise ABSTAIN.
                    """;
        }
        StringBuilder sb = new StringBuilder(
                "PERMITTED RELATION TYPES (the complete set admissible here — choose at most "
                        + "one, copied verbatim):\n");
        for (RelationCandidate c : candidates) {
            sb.append("- ").append(c.type());
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" — ").append(c.description());
            }
            if (!c.domainTypes().isEmpty()) {
                sb.append(" | domain: ").append(String.join("/", c.domainTypes()));
            }
            if (!c.rangeTypes().isEmpty()) {
                sb.append(" | range: ").append(String.join("/", c.rangeTypes()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String relationCandidateOrdinalBlock(List<RelationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "PERMITTED RELATION TYPES: none.";
        }
        StringBuilder sb = new StringBuilder(
                "PERMITTED RELATION TYPES (engine maps the selected ordinal to the canonical type):\n");
        for (int index = 0; index < candidates.size(); index++) {
            RelationCandidate candidate = candidates.get(index);
            sb.append("- [").append(index + 1).append("] ").append(candidate.type());
            if (candidate.description() != null && !candidate.description().isBlank()) {
                sb.append(" — ").append(candidate.description());
            }
            if (!candidate.aliases().isEmpty()) {
                sb.append(" | audited aliases: ")
                        .append(String.join(" / ", candidate.aliases()));
            }
            if (!candidate.domainTypes().isEmpty()) {
                sb.append(" | domain: ").append(String.join("/", candidate.domainTypes()));
            }
            if (!candidate.rangeTypes().isEmpty()) {
                sb.append(" | range: ").append(String.join("/", candidate.rangeTypes()));
            }
            if (!candidate.domainTypes().isEmpty() || !candidate.rangeTypes().isEmpty()) {
                sb.append(" | logicalFacet=ADMITTED_BY_SCHEMA_DOMAIN_RANGE");
            } else {
                sb.append(" | logicalFacet=SCHEMA_TYPE_WITHOUT_ENDPOINT_SIGNATURE");
            }
            sb.append('\n');
        }
        sb.append("Logical facets are engine-owned hard controls. Schema domain/range declarations have ")
                .append("the same role as type constraints in the graph reasoning layer: disallowed endpoint ")
                .append("pairs are omitted before the model sees this ballot. They are not source evidence.\n");
        return sb.toString();
    }

    /**
     * Complete, symmetric branch templates for small models. Every permitted ordinal receives the
     * same nested JSON shape, so format guidance cannot imply which candidate is correct.
     */
    static String relationTypeOutputShapes(
            List<RelationCandidate> candidates, boolean additionalRound) {
        StringBuilder sb = new StringBuilder(
                "VALID COMPLETE OUTPUT SHAPES (choose exactly one whole object):\n");
        if (candidates != null) {
            for (int index = 0; index < candidates.size(); index++) {
                int ordinal = index + 1;
                sb.append("- choose candidate [").append(ordinal).append("]: ")
                        .append("{\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":").append(ordinal)
                        .append(",\"schemaGap\":false,\"confidence\":0.5,\"qualifiers\":{},")
                        .append("\"reason\":\"candidate [").append(ordinal)
                        .append("] matches the source predicate and direction\"}}\n");
            }
        }
        if (additionalRound) {
            sb.append("- no additional distinct relation is asserted (stop successfully): ")
                    .append("{\"selection\":{\"decision\":\"DONE\",\"candidateOrdinal\":null,")
                    .append("\"schemaGap\":false,\"confidence\":0.5,\"qualifiers\":{},")
                    .append("\"reason\":\"no additional distinct relation is asserted\"}}\n");
        }
        sb.append("- a distinct source relation is asserted but no permitted candidate expresses it: ")
                .append("{\"selection\":{\"decision\":\"SCHEMA_GAP\",\"candidateOrdinal\":null,\"schemaGap\":true,")
                .append("\"confidence\":0.5,\"qualifiers\":{},")
                .append("\"reason\":\"no permitted candidate expresses the source relation\"}}\n")
                .append("- the source genuinely cannot distinguish the candidates: ")
                .append("{\"selection\":{\"decision\":\"ABSTAIN\",\"candidateOrdinal\":null,\"schemaGap\":false,")
                .append("\"confidence\":0.5,\"qualifiers\":{},")
                .append("\"reason\":\"the source does not distinguish the candidate meanings\"}}\n")
                .append("Every candidate branch is format-equivalent and none is marked as the answer. ")
                .append("Copy exactly one complete object, then update confidence, qualifiers, and reason. ")
                .append("Never return selection:null or move selection fields to the top level.\n");
        return sb.toString();
    }

    static String claimCandidateBlock(List<ClaimCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return """
                    EXISTING CLAIMS: none were retrieved for this relation.
                    Because the candidate set is empty, ADD_EVIDENCE and FLAG_CONTRADICTION are
                    not available: answer CREATE_CLAIM or ABSTAIN.
                    """;
        }
        StringBuilder sb = new StringBuilder(
                "EXISTING CLAIMS (retrieved from the knowledge base at the pinned revision):\n");
        for (int index = 0; index < candidates.size(); index++) {
            ClaimCandidate c = candidates.get(index);
            sb.append("- [").append(index + 1).append("] atomKey=").append(c.atomKey())
                    .append(" | claim: ").append(nullSafe(c.summary()))
                    .append(" | kbConfidence=").append(round(c.currentConfidence()))
                    .append(" | supporting=").append(c.supporting())
                    .append(" | refuting=").append(c.refuting())
                    .append('\n');
        }
        return sb.toString();
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "null" : value;
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
