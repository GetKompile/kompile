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
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;

import java.util.List;
import java.util.Locale;

/**
 * Prompt builders for the decomposed extraction passes.
 *
 * <p>Each builder produces a task with the same five guarantees: one semantic objective, one
 * primary object, an explicitly bounded candidate set, the version pin the answer is attributable
 * to, and a way out. Nothing asks the model to remember an earlier pass, to search, or to decide
 * what the graph should ultimately hold.</p>
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

    /** Upper bound on inlined source text; overruns are marked, never silently dropped. */
    public static final int MAX_SOURCE_CHARS = 24_000;

    private static final String JSON_ONLY =
            "Respond with one JSON object and nothing else: no explanation, no markdown fences.";

    private ExtractionPassPrompts() {
    }

    /**
     * Pass 1: segment the chunk into atomic propositions, preserving negation, modality, time and
     * attribution. No identity work, no schema work, no truth judgement.
     */
    public static String propositions(PassContext context, int maxPropositions) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: Split the source text into atomic propositions.\n")
                .append("Do only this. Do not resolve entity identity, do not choose relation ")
                .append("types, do not judge whether anything is true.\n\n");
        sb.append(context.pinBlock()).append('\n');
        sb.append(sourceBlock(context)).append('\n');
        sb.append("OUTPUT FORMAT:\n")
                .append("""
                        {"propositions":[{
                          "id":"p1",
                          "text":"one self-contained statement",
                          "subject":"the thing the statement is about",
                          "predicate":"what is said about it",
                          "object":"the other participant, or null",
                          "polarity":"AFFIRMED|NEGATED|UNKNOWN",
                          "modality":"FACTUAL|HYPOTHETICAL|OBLIGATION|POSSIBILITY|UNKNOWN",
                          "timeExpression":"as written in the text, or null",
                          "condition":"the 'if' part, or null",
                          "attributedTo":"who the text says asserts it, or null",
                          "evidence":{"quote":"verbatim source text","role":"DIRECT_SUPPORT"}
                        }]}
                        """);
        sb.append("\nRULES:\n")
                .append("- One statement per proposition. Split conjunctions into separate items.\n")
                .append("- \"quote\" must be copied character-for-character from the source text ")
                .append("above. A quote that does not appear there causes the item to be discarded.\n")
                .append("- Never drop a negation: put it in \"polarity\", not in the text.\n")
                .append("- Never turn a hedge, plan or condition into a plain fact: put it in ")
                .append("\"modality\" and \"condition\".\n")
                .append("- If the text says who asserts something, record them in \"attributedTo\".\n")
                .append("- Return at most ").append(Math.max(1, maxPropositions))
                .append(" propositions, most substantive first.\n")
                .append("- If the text asserts nothing, return {\"propositions\":[]}.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
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
                .append("- The candidate list above is the complete set of entities that exist. ")
                .append("Copy \"selectedEntityId\" verbatim from it; never invent an id.\n")
                .append("- Choose REUSE_ENTITY only when the candidate is the SAME real-world ")
                .append("thing. A shared word, surname or industry is not identity.\n")
                .append("- If two candidates are equally plausible, answer UNRESOLVED and list ")
                .append("both under \"alternatives\". Guessing is worse than abstaining.\n")
                .append("- If no candidate is the same thing, answer CREATE_PROVISIONAL_ENTITY ")
                .append("and supply \"provisionalName\" and \"provisionalType\".\n")
                .append("- Every candidate you considered and rejected must appear in ")
                .append("\"alternatives\" with a reason.\n")
                .append("- \"quote\" must be copied verbatim from the source text.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /**
     * Pass 3: classify the epistemic status of one proposition. This is the attribution firewall —
     * it runs on its own so a stance is never silently converted into a business fact.
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
                .append("- attributed to (from pass 1, may be null): ")
                .append(nullSafe(proposition.attributedTo())).append("\n\n");
        sb.append(sourceBlock(context)).append('\n');
        sb.append("OUTPUT FORMAT:\n")
                .append("""
                        {"classification":{
                          "propositionId":"<the id above>",
                          "speechAct":"ASSERTION|OPERATIONAL_RECORD|OPINION|REQUEST|COMMITMENT|WARNING|PREDICTION|QUESTION|UNKNOWN",
                          "holder":"who holds the opinion/commitment, or null",
                          "certainty":0.0,
                          "reason":"which words in the text decided it",
                          "evidence":{"quote":"verbatim source text","role":"ATTRIBUTION"}
                        }}
                        """);
        sb.append("\nRULES:\n")
                .append("- \"X said / thinks / believes / estimates that Y\" is an OPINION held ")
                .append("by X. It is never a bare ASSERTION of Y.\n")
                .append("- ASSERTION is for what the document itself states as fact.\n")
                .append("- OPERATIONAL_RECORD is for a logged event or transaction.\n")
                .append("- \"certainty\" is the certainty the SOURCE expresses (\"probably\" is ")
                .append("about 0.6, \"definitely\" about 0.95), not your own confidence.\n")
                .append("- If the text does not make the kind clear, answer UNKNOWN. UNKNOWN is a ")
                .append("correct answer, not a failure.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    /**
     * Pass 4: pick at most one relation type from the schema-admissible set for one proposition
     * whose endpoints are already resolved.
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
                          "qualifiers":{"scope":"as-of date, unit, jurisdiction, degree"},
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
                .append("- Put every scope condition (as-of date, unit, currency, jurisdiction, ")
                .append("degree) into \"qualifiers\". Dropping scope is an error.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
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
        sb.append(sourceBlock(context)).append('\n');
        sb.append(claimCandidateBlock(candidates)).append('\n');
        sb.append("OUTPUT FORMAT:\n")
                .append("""
                        {"decision":{
                          "propositionId":"<the proposition id above>",
                          "operation":"ADD_EVIDENCE|CREATE_CLAIM|FLAG_CONTRADICTION|ABSTAIN",
                          "matchedAtomKey":"an existing claim key, or null",
                          "confidence":0.0,
                          "alternatives":[{"candidateId":"atomKey","score":0.0,"reason":"why rejected"}],
                          "reason":"why this decision",
                          "evidence":{"quote":"verbatim source text","role":"DIRECT_SUPPORT"}
                        }}
                        """);
        sb.append("\nRULES:\n")
                .append("- ADD_EVIDENCE when the new relation says the same thing as an existing ")
                .append("claim. Attach to it; do not create a duplicate.\n")
                .append("- FLAG_CONTRADICTION when it is incompatible with an existing claim. ")
                .append("You are only reporting the conflict. Do not say which side is right and ")
                .append("do not retract anything — that decision is not yours.\n")
                .append("- CREATE_CLAIM when no existing claim covers it.\n")
                .append("- ABSTAIN when you cannot tell. Abstaining is expected on partial text.\n")
                .append("- \"matchedAtomKey\" must be copied verbatim from the list above.\n\n")
                .append(JSON_ONLY);
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------
    // shared blocks
    // ---------------------------------------------------------------------------------------

    static String sourceBlock(PassContext context) {
        String text = context.sourceText();
        StringBuilder sb = new StringBuilder("SOURCE TEXT (the only permitted source of content");
        if (text.length() > MAX_SOURCE_CHARS) {
            sb.append("; TRUNCATED to the first ").append(MAX_SOURCE_CHARS).append(" of ")
                    .append(text.length()).append(" characters");
            text = text.substring(0, MAX_SOURCE_CHARS);
        }
        sb.append("):\n<<<SOURCE\n").append(text).append("\nSOURCE>>>\n");
        return sb.toString();
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
                "CANDIDATE ENTITIES (retrieved by the graph index — this is the complete set of "
                        + "entities available to you):\n");
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
            sb.append('\n');
        }
        sb.append("The retrieval score is the index's lexical/vector similarity. It is a ranking ")
                .append("hint only — a high score is not evidence of identity.\n");
        return sb.toString();
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
        for (ClaimCandidate c : candidates) {
            sb.append("- atomKey=").append(c.atomKey())
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
