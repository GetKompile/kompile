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
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
import ai.kompile.core.graphrag.passes.ExtractionProposals.RelationProposal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that every pass prompt carries the five guarantees a bounded semantic operation needs:
 * one objective, one object, a bounded candidate set, a version pin, and a way out.
 */
class ExtractionPassPromptsTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Bob said Initech was overvalued.";

    private static PassContext context() {
        return PassContext.forChunk("chunk-1", "doc-1", SOURCE)
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model")
                .withPin("ruleSet", "rules-42");
    }

    private static PropositionProposal proposition() {
        return new PropositionProposal("p1", "Acme Corp acquired Initech in 2019", "Acme Corp",
                "acquired", "Initech", Polarity.AFFIRMED, Modality.FACTUAL, "2019", null, null,
                null);
    }

    @Test
    void everyPassPinsTheContextVersionAndForbidsProse() {
        List<String> prompts = List.of(
                ExtractionPassPrompts.propositions(context(), 10),
                ExtractionPassPrompts.mentions(context(), proposition(), List.of()),
                ExtractionPassPrompts.epistemic(context(), proposition()),
                ExtractionPassPrompts.relations(context(), proposition(), "id=a", "id=b",
                        List.of()),
                ExtractionPassPrompts.claims(context(),
                        new RelationProposal("p1", "a", "b", "ACQUIRED",
                                ProposalOperation.CREATE_CLAIM, 0.8, null, Map.of(), List.of(),
                                null, null),
                        "a -[ACQUIRED]-> b", List.of()));

        for (String prompt : prompts) {
            assertTrue(prompt.contains("graph: graph-7"), "missing graph pin:\n" + prompt);
            assertTrue(prompt.contains("chunk: chunk-1"), "missing chunk pin");
            assertTrue(prompt.contains("ruleSet: rules-42"), "missing custom pin");
            assertTrue(prompt.contains("no markdown fences"), "missing JSON-only instruction");
            assertTrue(prompt.startsWith("TASK: "), "prompt must open with one objective");
        }
    }

    @Test
    void propositionPassAsksOnlyForSegmentationAndCapsOutput() {
        String prompt = ExtractionPassPrompts.propositions(context(), 7);

        assertTrue(prompt.contains("Do not resolve entity identity"));
        assertTrue(prompt.contains("at most 7 propositions"));
        assertTrue(prompt.contains("\"polarity\""));
        assertTrue(prompt.contains("<<<SOURCE"));
        assertTrue(prompt.contains("{\"propositions\":[]}"), "must offer an empty answer");
    }

    @Test
    void mentionPassBoundsTheCandidateSetAndOffersAbstention() {
        List<EntityCandidate> candidates = List.of(
                new EntityCandidate("ent-acme", "Acme Corporation", "ORGANIZATION",
                        List.of("Acme", "Acme Corp"), 0.91, "lexical"),
                EntityCandidate.of("ent-acme-holdings", "Acme Holdings", "ORGANIZATION", 0.62));

        String prompt = ExtractionPassPrompts.mentions(context(), proposition(), candidates);

        assertTrue(prompt.contains("id=ent-acme "), "candidate id must be quotable verbatim");
        assertTrue(prompt.contains("aliases=Acme, Acme Corp"));
        assertTrue(prompt.contains("complete set of entities"));
        assertTrue(prompt.contains("UNRESOLVED"), "must offer a way out");
        assertTrue(prompt.contains("never invent an id"));
        assertTrue(prompt.contains("alternatives"), "must require rejected alternatives");
        // One primary object: the prompt talks about exactly one proposition.
        assertTrue(prompt.contains("PROPOSITION (the only one you are working on)"));
    }

    @Test
    void mentionPassWithoutCandidatesRemovesReuseFromTheVocabulary() {
        String prompt = ExtractionPassPrompts.mentions(context(), proposition(), List.of());

        assertTrue(prompt.contains("REUSE_ENTITY is not available"));
    }

    @Test
    void epistemicPassSeparatesAttributionFromAssertion() {
        String prompt = ExtractionPassPrompts.epistemic(context(), proposition());

        assertTrue(prompt.contains("Do not decide whether it is true"));
        assertTrue(prompt.contains("is an OPINION held"));
        assertTrue(prompt.contains("UNKNOWN is a"));
        assertFalse(prompt.contains("CANDIDATE ENTITIES"), "epistemic pass needs no candidates");
    }

    @Test
    void relationPassOffersOnlyPermittedTypesAndASchemaGapEscape() {
        List<RelationCandidate> candidates = List.of(
                new RelationCandidate("ACQUIRED", "one organization bought another",
                        List.of("ORGANIZATION"), List.of("ORGANIZATION"), 0.9),
                RelationCandidate.of("PARTNERED_WITH", "joint venture"));

        String prompt = ExtractionPassPrompts.relations(context(), proposition(),
                "id=ent-acme | type=ORGANIZATION", "id=ent-initech | type=ORGANIZATION",
                candidates);

        assertTrue(prompt.contains("- ACQUIRED — one organization bought another"));
        assertTrue(prompt.contains("domain: ORGANIZATION"));
        assertTrue(prompt.contains("PROPOSE_SCHEMA_GAP"));
        assertTrue(prompt.contains("Do not force the nearest type"));
        assertTrue(prompt.contains("qualifiers"), "scope must be captured, not dropped");
        assertTrue(prompt.contains("RESOLVED ENTITIES (fixed"));
    }

    @Test
    void relationPassWithoutPermittedTypesSaysSo() {
        String prompt =
                ExtractionPassPrompts.relations(context(), proposition(), "a", "b", List.of());

        assertTrue(prompt.contains("PERMITTED RELATION TYPES: none are defined"));
    }

    @Test
    void claimPassForbidsResolvingTheConflictItFlags() {
        List<ClaimCandidate> candidates = List.of(
                new ClaimCandidate("atom-1", "ent-acme", "ACQUIRED", "ent-initech", 0.72, 3, 1,
                        "Acme acquired Initech"));
        RelationProposal relation = new RelationProposal("p1", "ent-acme", "ent-initech",
                "ACQUIRED", ProposalOperation.CREATE_CLAIM, 0.8, "2019", Map.of(), List.of(), null,
                null);

        String prompt = ExtractionPassPrompts.claims(context(), relation,
                "ent-acme -[ACQUIRED]-> ent-initech", candidates);

        assertTrue(prompt.contains("atomKey=atom-1"));
        assertTrue(prompt.contains("kbConfidence=0.72"));
        assertTrue(prompt.contains("supporting=3"));
        assertTrue(prompt.contains("Do not say which side is right"));
        assertTrue(prompt.contains("ADD_EVIDENCE"));
        assertTrue(prompt.contains("ABSTAIN"));
    }

    @Test
    void claimPassWithoutCandidatesRemovesMatchOperations() {
        RelationProposal relation = new RelationProposal("p1", "a", "b", "ACQUIRED",
                ProposalOperation.CREATE_CLAIM, 0.8, null, Map.of(), List.of(), null, null);

        String prompt = ExtractionPassPrompts.claims(context(), relation, "a -> b", List.of());

        assertTrue(prompt.contains("ADD_EVIDENCE and FLAG_CONTRADICTION are"));
    }

    @Test
    void oversizedSourceIsTruncatedVisiblyRatherThanSilently() {
        String huge = "x".repeat(ExtractionPassPrompts.MAX_SOURCE_CHARS + 500);
        PassContext big = PassContext.forChunk("chunk-1", "doc-1", huge);

        String prompt = ExtractionPassPrompts.propositions(big, 5);

        assertTrue(prompt.contains("TRUNCATED to the first "
                + ExtractionPassPrompts.MAX_SOURCE_CHARS));
    }
}
