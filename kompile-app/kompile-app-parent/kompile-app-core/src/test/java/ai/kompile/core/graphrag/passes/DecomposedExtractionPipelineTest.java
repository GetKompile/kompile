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

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.LlmCaller;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Options;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Outcome;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.PassStats;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionProjection.Props;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link DecomposedExtractionPipeline} driven by a scripted model.
 *
 * <p>Every test states what the model returned and asserts what the engine let through. The
 * point of the decomposition is that a wrong or invented answer at any pass is caught by the
 * engine rather than written to the graph.</p>
 */
class DecomposedExtractionPipelineTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Analysts believe the deal was overpriced.";

    private static final PassContext CONTEXT = PassContext
            .forChunk("chunk-1", "doc-1", SOURCE)
            .withGraph("graph-7", "graph-6")
            .withSchema("kompile-graph-extraction/v1", "test-model");

    private static final EntityCandidate ACME =
            EntityCandidate.of("ent-acme", "Acme Corporation", "ORGANIZATION", 0.92);
    private static final EntityCandidate INITECH =
            EntityCandidate.of("ent-initech", "Initech", "ORGANIZATION", 0.81);

    private static final List<RelationCandidate> RELATION_TYPES = List.of(
            RelationCandidate.of("ACQUIRED", "one organization bought another"),
            RelationCandidate.of("PARTNERED_WITH", "a non-ownership commercial relationship"));

    // ------------------------------------------------------------------------------------
    // scripted model responses
    // ------------------------------------------------------------------------------------

    private static final String ONE_PROPOSITION = """
            {"propositions":[{"id":"p1","text":"Acme Corp acquired Initech in 2019",
              "subject":"Acme Corp","predicate":"acquired","object":"Initech",
              "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
              "evidence":{"quote":"Acme Corp acquired Initech in 2019","role":"DIRECT_SUPPORT"}}]}
            """;

    private static final String BOTH_MENTIONS_RESOLVED = """
            {"mentions":[
              {"mentionText":"Acme Corp","mentionRole":"SUBJECT","operation":"REUSE_ENTITY",
               "selectedEntityId":"ent-acme","confidence":0.93,
               "reason":"same organization, name matches",
               "evidence":{"quote":"Acme Corp"}},
              {"mentionText":"Initech","mentionRole":"OBJECT","operation":"REUSE_ENTITY",
               "selectedEntityId":"ent-initech","confidence":0.88,
               "evidence":{"quote":"Initech"}}]}
            """;

    private static final String ASSERTION = """
            {"classification":{"speechAct":"ASSERTION","certainty":0.95,
              "reason":"stated directly by the document"}}
            """;

    private static final String ACQUIRED_RELATION = """
            {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.9,
              "occurredAt":"2019","reason":"explicit acquisition verb",
              "evidence":{"quote":"acquired Initech"}}}
            """;

    // ------------------------------------------------------------------------------------
    // tests
    // ------------------------------------------------------------------------------------

    @Test
    void runsEveryPassAndProjectsAnAssertableRelation() {
        ScriptedCaller caller = happyPath();
        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(2, outcome.result().entities().size());
        ExtractedRelation relation = single(outcome);
        assertEquals("ACQUIRED", relation.type());
        assertEquals("ent-acme", relation.source());
        assertEquals("ent-initech", relation.target());
        assertEquals("true", relation.properties().get(Props.ASSERTABLE));
        assertEquals(0.9d, relation.confidence(), 1e-9);
        assertEquals("2019", relation.occurredAt());

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_EPISTEMIC));
        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
    }

    @Test
    void claimPassIsSkippedEntirelyWhenNoClaimCandidatesExist() {
        ScriptedCaller caller = happyPath();
        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_CLAIMS),
                "no existing claims to match against means the answer is already known");
        assertEquals(0, stat(outcome, ExtractionPassPrompts.PASS_CLAIMS).calls());
        assertFalse(outcome.result().relations().isEmpty());
    }

    @Test
    void promptsCarryTheBoundedCandidateSetAndThePinnedGraphRevision() {
        ScriptedCaller caller = happyPath();
        pipeline().run(CONTEXT, caller);

        String mentionPrompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS);
        assertTrue(mentionPrompt.contains("ent-acme"), "candidate ids must be offered verbatim");
        assertTrue(mentionPrompt.contains("Acme Corporation"));
        assertTrue(mentionPrompt.contains("graph-7"), "the graph revision must be pinned");

        String relationPrompt = caller.prompt(ExtractionPassPrompts.PASS_RELATIONS);
        assertTrue(relationPrompt.contains("ACQUIRED"));
        assertTrue(relationPrompt.contains("PARTNERED_WITH"));
        assertTrue(relationPrompt.contains("ent-acme"));
    }

    @Test
    void selectingAnEntityIdThatWasNeverOfferedIsDowngradedNotAccepted() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mentions":[
                  {"mentionText":"Acme Corp","mentionRole":"SUBJECT","operation":"REUSE_ENTITY",
                   "selectedEntityId":"ent-fabricated","confidence":0.99,
                   "evidence":{"quote":"Acme Corp"}},
                  {"mentionText":"Initech","mentionRole":"OBJECT","operation":"REUSE_ENTITY",
                   "selectedEntityId":"ent-initech","confidence":0.88,
                   "evidence":{"quote":"Initech"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_MENTIONS).outOfVocabulary());
        assertTrue(outcome.notes().stream()
                .anyMatch(n -> n.contains("ent-fabricated")
                        && n.contains("not among the offered candidates")));
        assertTrue(outcome.result().relations().isEmpty(),
                "an unresolved endpoint must not produce an edge");
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_RELATIONS),
                "no point asking for a relation between endpoints that did not resolve");
        assertTrue(outcome.notes().stream()
                .anyMatch(n -> n.contains("endpoints not both resolved")));
    }

    @Test
    void relationTypeOutsideTheSchemaIsRecordedAsASchemaGapNotAnEdgeTheGraphTrusts() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS, """
                {"relation":{"operation":"CREATE_CLAIM","type":"SECRETLY_CONTROLS",
                  "confidence":0.97,"evidence":{"quote":"acquired Initech"}}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).outOfVocabulary());
        assertTrue(outcome.notes().stream()
                .anyMatch(n -> n.contains("SECRETLY_CONTROLS") && n.contains("schema gap")));

        ExtractedRelation relation = single(outcome);
        assertEquals("true", relation.properties().get(Props.SCHEMA_GAP));
        assertEquals("false", relation.properties().get(Props.ASSERTABLE));
        assertTrue(relation.confidence() <= ExtractionProjection.ATTRIBUTED_CONFIDENCE_CEILING);
    }

    @Test
    void aPermittedTypeIsRewrittenToTheSchemasOwnSpelling() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS, """
                {"relation":{"operation":"CREATE_CLAIM","type":"acquired","confidence":0.8,
                  "evidence":{"quote":"acquired Initech"}}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals("ACQUIRED", single(outcome).type());
        assertEquals(0, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).outOfVocabulary());
    }

    @Test
    void aFabricatedQuoteInPassOneDropsThePropositionBeforeAnyFurtherCalls() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[{"id":"p1","text":"Acme Corp sold Initech",
                  "subject":"Acme Corp","predicate":"sold","object":"Initech",
                  "evidence":{"quote":"Acme Corp sold Initech to Globex"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).spanRejected());
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(outcome.result().entities().isEmpty());
        assertTrue(outcome.result().relations().isEmpty());
        assertTrue(outcome.notes().stream().anyMatch(n -> n.startsWith("proposition dropped")));
    }

    @Test
    void aFabricatedQuoteOnTheRelationDropsOnlyTheRelation() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS, """
                {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.9,
                  "evidence":{"quote":"the board unanimously approved the merger"}}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).spanRejected());
        assertTrue(outcome.result().relations().isEmpty());
        assertEquals(2, outcome.result().entities().size(),
                "identity work already verified against the source survives");
    }

    @Test
    void anOmittedQuoteInALaterPassIsToleratedAndRecorded() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS, """
                {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.9}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(0, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).spanRejected());
        assertEquals("ACQUIRED", single(outcome).type());
        assertTrue(outcome.notes().stream()
                .anyMatch(n -> n.contains("no evidence quote supplied")));
    }

    @Test
    void anOpinionIsAttributedRatherThanPromoted() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_EPISTEMIC, """
                {"classification":{"speechAct":"OPINION","holder":"Analysts","certainty":0.6,
                  "reason":"believe marks it as a belief report",
                  "evidence":{"quote":"Analysts believe"}}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        ExtractedRelation relation = single(outcome);
        assertEquals("OPINION", relation.properties().get(Props.EPISTEMIC));
        assertEquals("Analysts", relation.properties().get(Props.EPISTEMIC_HOLDER));
        assertEquals("false", relation.properties().get(Props.ASSERTABLE));
        assertTrue(relation.confidence() <= ExtractionProjection.ATTRIBUTED_CONFIDENCE_CEILING);
    }

    @Test
    void aFailingPassIsContainedCountedAndDowngradedToUnknown() {
        ScriptedCaller caller = happyPath().failOn(ExtractionPassPrompts.PASS_EPISTEMIC,
                "model unreachable");

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_EPISTEMIC).failures());
        assertTrue(outcome.notes().stream().anyMatch(n -> n.contains("model unreachable")));
        ExtractedRelation relation = single(outcome);
        assertEquals("UNKNOWN", relation.properties().get(Props.EPISTEMIC));
        assertEquals("false", relation.properties().get(Props.ASSERTABLE),
                "an unclassified statement is never promoted");
    }

    @Test
    void anEmptyFirstPassEndsTheRunWithAnEmptyResult() {
        ScriptedCaller caller = new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, "   ");

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).failures());
        assertEquals(0, outcome.projectedItems());
        assertNotNull(outcome.result().metadata());
        assertEquals("chunk-1", outcome.result().metadata().sourceChunkId());
    }

    @Test
    void whenEveryMentionAbstainsNothingIsWritten() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mentions":[
                  {"mentionText":"Acme Corp","mentionRole":"SUBJECT","operation":"UNRESOLVED",
                   "reason":"two candidates are equally plausible"},
                  {"mentionText":"Initech","mentionRole":"OBJECT","operation":"UNRESOLVED",
                   "reason":"no candidate matched"}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(2, stat(outcome, ExtractionPassPrompts.PASS_MENTIONS).abstained());
        assertEquals(0, outcome.projectedItems());
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
    }

    @Test
    void anUnmatchedMentionBecomesAProvisionalEntityWithADeterministicId() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mentions":[
                  {"mentionText":"Acme Corp","mentionRole":"SUBJECT","operation":"REUSE_ENTITY",
                   "selectedEntityId":"ent-acme","confidence":0.93,
                   "evidence":{"quote":"Acme Corp"}},
                  {"mentionText":"Initech","mentionRole":"OBJECT",
                   "operation":"CREATE_PROVISIONAL_ENTITY","provisionalName":"Initech Inc",
                   "provisionalType":"ORGANIZATION","confidence":0.7,
                   "evidence":{"quote":"Initech"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        List<String> ids = outcome.result().entities().stream()
                .map(ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity::id)
                .toList();
        assertEquals(List.of("ent-acme", "initech_inc"), ids);
        assertEquals("initech_inc", single(outcome).target());
    }

    @Test
    void claimMatchingRunsWhenCandidatesExistAndAContradictionIsFlaggedNotResolved() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS, """
                {"decision":{"operation":"FLAG_CONTRADICTION","matchedAtomKey":"atom-1",
                  "confidence":0.7,"reason":"the graph says Globex acquired Initech",
                  "evidence":{"quote":"acquired Initech"}}}
                """);

        Outcome outcome = pipelineWithClaims().run(CONTEXT, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_CLAIMS));
        ExtractedRelation relation = single(outcome);
        assertEquals("FLAG_CONTRADICTION", relation.properties().get(Props.CLAIM_OPERATION));
        assertEquals("atom-1", relation.properties().get(Props.CLAIM_ATOM_KEY));
        assertEquals("false", relation.properties().get(Props.ASSERTABLE),
                "resolving the conflict is the truth-maintenance system's job");
    }

    @Test
    void aClaimDecisionCitingAnUnknownAtomAbstainsAndWithholdsTheRelation() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS, """
                {"decision":{"operation":"ADD_EVIDENCE","matchedAtomKey":"atom-999",
                  "confidence":0.9,"evidence":{"quote":"acquired Initech"}}}
                """);

        Outcome outcome = pipelineWithClaims().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_CLAIMS).outOfVocabulary());
        assertTrue(outcome.notes().stream().anyMatch(n -> n.contains("atom-999")));
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void aNegatedPropositionIsNeverProjectedAsAPositiveEdge() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[{"id":"p1","text":"Acme Corp did not acquire Initech",
                  "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"NEGATED","modality":"FACTUAL",
                  "evidence":{"quote":"Acme Corp acquired Initech"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertTrue(outcome.result().relations().isEmpty());
        assertTrue(outcome.notes().stream().anyMatch(n -> n.contains("source negates it")));
        assertEquals(2, outcome.result().entities().size());
    }

    @Test
    void eachPropositionGetsItsOwnBoundedCallPerPass() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[
                  {"id":"p1","text":"Acme Corp acquired Initech in 2019","subject":"Acme Corp",
                   "predicate":"acquired","object":"Initech","polarity":"AFFIRMED",
                   "modality":"FACTUAL","evidence":{"quote":"Acme Corp acquired Initech"}},
                  {"id":"p2","text":"Initech became part of Acme Corp","subject":"Initech",
                   "predicate":"became part of","object":"Acme Corp","polarity":"AFFIRMED",
                   "modality":"FACTUAL","evidence":{"quote":"acquired Initech in 2019"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS),
                "the chunk is enumerated once");
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_EPISTEMIC));
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(2, stat(outcome, ExtractionPassPrompts.PASS_EPISTEMIC).calls());
    }

    @Test
    void aPropositionWhoseEndpointsRetrieveNoCandidatesNeverReachesTheRelationPass() {
        // The scripted model insists on reusing ent-acme/ent-initech for every proposition, but
        // retrieval offered nothing for "Analysts"/"the deal", so both reuses are invention.
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[{"id":"p1","text":"Analysts believe the deal was overpriced",
                  "subject":"Analysts","predicate":"believe","object":"the deal",
                  "polarity":"AFFIRMED","modality":"FACTUAL",
                  "evidence":{"quote":"Analysts believe the deal was overpriced"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_EPISTEMIC));
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(2, stat(outcome, ExtractionPassPrompts.PASS_MENTIONS).outOfVocabulary());
        assertEquals(0, outcome.projectedItems());
    }

    @Test
    void thePropositionCapBoundsTheWorkTheModelCanCreateForItself() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[
                  {"id":"p1","text":"Acme Corp acquired Initech in 2019","subject":"Acme Corp",
                   "object":"Initech","evidence":{"quote":"Acme Corp acquired Initech"}},
                  {"id":"p2","text":"Analysts believe the deal was overpriced",
                   "subject":"Analysts","object":"the deal",
                   "evidence":{"quote":"Analysts believe"}}]}
                """);

        Outcome outcome = new DecomposedExtractionPipeline(entityProvider(), relationProvider(),
                ClaimCandidateProvider.none(), Options.defaults().withMaxPropositions(1))
                .run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).accepted());
        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
    }

    @Test
    void aFailingCandidateProviderDegradesToAnUnboundedFreeChoiceNotACrash() {
        EntityCandidateProvider broken = (mention, typeHint, context, limit) -> {
            throw new IllegalStateException("index offline");
        };
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mentions":[
                  {"mentionText":"Acme Corp","mentionRole":"SUBJECT",
                   "operation":"CREATE_PROVISIONAL_ENTITY","provisionalName":"Acme Corp",
                   "provisionalType":"ORGANIZATION","confidence":0.6,
                   "evidence":{"quote":"Acme Corp"}},
                  {"mentionText":"Initech","mentionRole":"OBJECT",
                   "operation":"CREATE_PROVISIONAL_ENTITY","provisionalName":"Initech",
                   "provisionalType":"ORGANIZATION","confidence":0.6,
                   "evidence":{"quote":"Initech"}}]}
                """);

        Outcome outcome = new DecomposedExtractionPipeline(broken, relationProvider(),
                ClaimCandidateProvider.none(), Options.defaults()).run(CONTEXT, caller);

        assertEquals(2, outcome.result().entities().size());
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS)
                .contains("REUSE_ENTITY is not available"),
                "with no candidates the reuse operation is removed from the vocabulary");
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    private static DecomposedExtractionPipeline pipeline() {
        return new DecomposedExtractionPipeline(entityProvider(), relationProvider(),
                ClaimCandidateProvider.none(), Options.defaults());
    }

    private static DecomposedExtractionPipeline pipelineWithClaims() {
        ClaimCandidateProvider claims = (subject, predicate, object, context, limit) ->
                List.of(ClaimCandidate.of("atom-1", "Globex ACQUIRED Initech", 0.6));
        return new DecomposedExtractionPipeline(entityProvider(), relationProvider(), claims,
                Options.defaults());
    }

    private static EntityCandidateProvider entityProvider() {
        return (mention, typeHint, context, limit) -> {
            String probe = mention == null ? "" : mention.toLowerCase(java.util.Locale.ROOT);
            if (probe.contains("acme")) {
                return List.of(ACME);
            }
            if (probe.contains("initech")) {
                return List.of(INITECH);
            }
            return List.of();
        };
    }

    private static RelationCandidateProvider relationProvider() {
        return (sourceType, targetType, context, limit) -> RELATION_TYPES;
    }

    private static ScriptedCaller happyPath() {
        return new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, ONE_PROPOSITION)
                .on(ExtractionPassPrompts.PASS_MENTIONS, BOTH_MENTIONS_RESOLVED)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, ASSERTION)
                .on(ExtractionPassPrompts.PASS_RELATIONS, ACQUIRED_RELATION);
    }

    private static ExtractedRelation single(Outcome outcome) {
        assertEquals(1, outcome.result().relations().size(),
                "expected exactly one projected relation, got " + outcome.result().relations());
        return outcome.result().relations().get(0);
    }

    private static PassStats stat(Outcome outcome, String passId) {
        return outcome.stats().stream()
                .filter(s -> s.passId().equals(passId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stats recorded for pass " + passId));
    }

    /**
     * A model stand-in: fixed answers per pass, with the option to fail one pass. Also records
     * every prompt so tests can assert on what the engine actually put in front of the model.
     */
    private static final class ScriptedCaller implements LlmCaller {

        private final Map<String, Deque<String>> responses = new HashMap<>();
        private final Map<String, String> failures = new HashMap<>();
        private final Map<String, List<String>> prompts = new HashMap<>();

        ScriptedCaller on(String passId, String... values) {
            responses.put(passId, new ArrayDeque<>(List.of(values)));
            failures.remove(passId);
            return this;
        }

        ScriptedCaller failOn(String passId, String message) {
            failures.put(passId, message);
            return this;
        }

        @Override
        public String call(String passId, String prompt) {
            prompts.computeIfAbsent(passId, key -> new ArrayList<>()).add(prompt);
            String failure = failures.get(passId);
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            Deque<String> queued = responses.get(passId);
            if (queued == null || queued.isEmpty()) {
                return null;
            }
            // A single scripted answer repeats for every call of that pass; several are
            // consumed in order.
            return queued.size() == 1 ? queued.peek() : queued.poll();
        }

        int calls(String passId) {
            return prompts.getOrDefault(passId, List.of()).size();
        }

        String prompt(String passId) {
            List<String> seen = prompts.getOrDefault(passId, List.of());
            assertFalse(seen.isEmpty(), "pass " + passId + " was never called");
            return seen.get(0);
        }
    }
}
