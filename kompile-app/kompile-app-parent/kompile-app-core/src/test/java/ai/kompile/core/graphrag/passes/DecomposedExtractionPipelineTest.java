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

import ai.kompile.core.crawl.graph.PropositionAtomizationConfig.Mode;
import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.SourceSpan;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.LlmCaller;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Options;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Outcome;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.PassStats;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.IdentitySignals;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Modality;
import ai.kompile.core.graphrag.passes.ExtractionProposals.Polarity;
import ai.kompile.core.graphrag.passes.ExtractionProposals.PropositionProposal;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String SUBJECT_MENTION_RESOLVED = """
            {"mention":{"decision":"REUSE","candidateOrdinal":1,
              "confidence":0.93,"reason":"same organization"}}
            """;

    private static final String OBJECT_MENTION_RESOLVED = """
            {"mention":{"decision":"REUSE","candidateOrdinal":1,
              "confidence":0.88,"reason":"exact name"}}
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

    private static final String RELATION_ASSERTED = """
            {"decision":{"assertsRelation":true,"confidence":0.85,
              "reason":"acquired explicitly connects the endpoints"}}
            """;

    private static final String RELATION_NOT_ASSERTED = """
            {"decision":{"assertsRelation":false,"confidence":0.9,
              "reason":"the endpoints are only co-mentioned"}}
            """;

    private static final String ACQUIRED_TYPE_ORDINAL = """
            {"selection":{"candidateOrdinal":1,"schemaGap":false,"confidence":0.8,
              "qualifiers":{},"reason":"candidate one exactly means acquisition"}}
            """;

    private static final String RELATION_DONE = """
            {"selection":{"decision":"DONE","candidateOrdinal":null,"schemaGap":false,
              "confidence":0.9,"qualifiers":{},"reason":"no additional distinct relation is asserted"}}
            """;

    // ------------------------------------------------------------------------------------
    // tests
    // ------------------------------------------------------------------------------------

    @Test
    void runsEveryPassAndProjectsAnAssertableRelation() {
        ScriptedCaller caller = happyPath();
        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(2, outcome.result().entities().size());
        ExtractedEntity acme = outcome.result().entities().stream()
                .filter(entity -> "ent-acme".equals(entity.id())).findFirst().orElseThrow();
        ExtractedEntity initech = outcome.result().entities().stream()
                .filter(entity -> "ent-initech".equals(entity.id())).findFirst().orElseThrow();
        assertEquals("Acme Corporation", acme.name());
        assertEquals("ORGANIZATION", acme.type());
        assertNotNull(acme.description());
        assertEquals("ORGANIZATION", initech.type(),
                "reused candidate metadata is engine state, not something the model must repeat");
        assertNotNull(initech.description());
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
    void sourceEventSplitKeepsEveryModelDecisionLocalAndRebasesEvidenceToTheChunk() {
        String secondSource = "Acme Corp acquired Initech again in 2020.";
        String source = "Acme Corp acquired Initech in 2019.\n\n" + secondSource;
        PassContext context = PassContext.forChunk("chunk-events", "doc-events", source)
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model");
        String secondProposition = """
                {"propositions":[{"id":"p1","text":"Acme Corp acquired Initech again in 2020",
                  "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2020",
                  "evidence":{"quote":"Acme Corp acquired Initech again in 2020.",
                              "role":"DIRECT_SUPPORT"}}]}
                """;
        ScriptedCaller caller = new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, ONE_PROPOSITION, secondProposition)
                .on(ExtractionPassPrompts.PASS_MENTIONS,
                        SUBJECT_MENTION_RESOLVED, OBJECT_MENTION_RESOLVED,
                        SUBJECT_MENTION_RESOLVED, OBJECT_MENTION_RESOLVED)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, ASSERTION)
                .on(ExtractionPassPrompts.PASS_RELATIONS,
                        RELATION_ASSERTED, ACQUIRED_TYPE_ORDINAL, RELATION_DONE,
                        RELATION_ASSERTED, ACQUIRED_TYPE_ORDINAL, RELATION_DONE);
        Options options = Options.defaults()
                .withSplitPropositionsBySourceEvent(true)
                .withSplitMentionsByEndpoint(true)
                .withSplitRelationDecision(true);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(), options);

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertEquals(4, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_EPISTEMIC));
        assertEquals(6, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        String firstAtomization = caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 0);
        String secondAtomization = caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 1);
        assertTrue(firstAtomization.contains("<<<FOCUS_SEGMENT\nAcme Corp acquired Initech in 2019."));
        assertFalse(firstAtomization.contains("2020"), "future source must never leak backward");
        assertTrue(secondAtomization.contains("<<<FOCUS_SEGMENT\n" + secondSource));
        assertTrue(secondAtomization.contains("2019"),
                "bounded preceding source must remain available for pronouns and omitted subjects");
        assertTrue(caller.prompts(ExtractionPassPrompts.PASS_PROPOSITIONS).stream()
                .allMatch(prompt -> prompt.contains("\"asserted\":true")
                        && !prompt.contains("{\"proposition\":")));
        assertEquals(List.of("p1", "p2"), outcome.bundle().propositions().stream()
                .map(proposition -> proposition.id()).toList());
        assertEquals(source.indexOf(secondSource),
                outcome.bundle().propositions().get(1).evidence().start());
        assertEquals(source.indexOf(secondSource) + secondSource.length(),
                outcome.bundle().propositions().get(1).evidence().end());
    }

    @Test
    void focusedMalformedPropositionGetsOneBoundedContractCorrection() {
        String source = "Acme Corp acquired Initech in 2019.";
        PassContext context = PassContext.forChunk("chunk-correction", "doc-correction", source);
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":\"Acme Corp acquired Initech\"}",
                "{\"asserted\":true,\"subject\":\"Acme Corp\",\"predicate\":\"acquired\","
                        + "\"object\":\"Initech\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\",\"timeExpression\":\"2019\","
                        + "\"condition\":null,\"attributedTo\":null}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 1)
                .contains("VALIDATION CORRECTION"));
        assertEquals(1, outcome.bundle().propositions().size());
        assertEquals("Acme Corp", outcome.bundle().propositions().get(0).subject());
    }

    @Test
    void focusedExplicitNullIsAValidModelSemanticDecision() {
        String source = "Maya exported the forecast.";
        PassContext context = PassContext.forChunk("chunk-null-correction", "doc-null-correction", source);
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":null}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertTrue(outcome.bundle().propositions().isEmpty());
    }

    @Test
    void malformedSplitRelationTypeGetsOneBoundedContractCorrection() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED,
                "{\"selection\":null,\"schemaGap\":true,\"confidence\":0.9}",
                ACQUIRED_TYPE_ORDINAL, RELATION_DONE);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(4, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 2)
                .contains("VALIDATION CORRECTION"));
        assertEquals("ACQUIRED", single(outcome).type());
    }

    @Test
    void malformedAdditionalCompletionCannotBeCorrectedIntoANewEdge() {
        List<RelationCandidate> candidates = List.of(
                RelationCandidate.of("ACQUIRED", "acquired ownership of another organization"),
                RelationCandidate.of("CONTROLLED", "acquired operational control of another organization"));
        RelationCandidateProvider provider = (sourceType, targetType, context, limit) -> candidates;
        String malformedDone = """
                {"selection":null,"schemaGap":false,"confidence":0.5,"qualifiers":{},
                 "reason":"No additional semantically distinct relation type was identified."}
                """;
        String forcedCandidate = """
                {"selection":{"decision":"SELECT","candidateOrdinal":1,"schemaGap":false,
                 "confidence":0.7,"qualifiers":{},"reason":"candidate one is related"}}
                """;
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED,
                """
                {"selection":{"decision":"SELECT","candidateOrdinal":1,"schemaGap":false,
                 "confidence":0.8,"qualifiers":{},"reason":"acquired is explicit"}}
                """,
                malformedDone, forcedCandidate, forcedCandidate);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), provider, ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(5, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 3)
                .contains("Preserve that completion decision"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 4)
                .contains("Do not select a candidate"));
        assertEquals("ACQUIRED", single(outcome).type());
    }

    @Test
    void validatedIterationCanKeepMoreThanOneDistinctRelationBeforeDone() {
        String source = "Acme Corp acquired and controlled Initech in 2019.";
        PassContext context = PassContext.forChunk("chunk-multi-relation", "doc-multi-relation", source);
        String proposition = """
                {"propositions":[{"id":"p1","text":"Acme Corp acquired and controlled Initech in 2019",
                  "subject":"Acme Corp","predicate":"acquired and controlled","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
                  "evidence":{"quote":"Acme Corp acquired and controlled Initech in 2019",
                              "role":"DIRECT_SUPPORT"}}]}
                """;
        List<RelationCandidate> candidates = List.of(
                RelationCandidate.of("ACQUIRED", "ownership changed hands"),
                RelationCandidate.of("CONTROLLED", "one organization controls another"),
                RelationCandidate.of("PARTNERED_WITH", "commercial partnership"));
        RelationCandidateProvider provider = (sourceType, targetType, ignored, limit) -> candidates;
        String selectFirst = """
                {"selection":{"decision":"SELECT","candidateOrdinal":1,"schemaGap":false,
                 "confidence":0.9,"qualifiers":{},"reason":"the source asserts acquisition"}}
                """;
        String selectNext = """
                {"selection":{"decision":"SELECT","candidateOrdinal":1,"schemaGap":false,
                 "confidence":0.9,"qualifiers":{},"reason":"the source separately asserts control"}}
                """;
        String done = """
                {"selection":{"decision":"DONE","candidateOrdinal":null,"schemaGap":false,
                 "confidence":0.9,"qualifiers":{},"reason":"no third relation is asserted"}}
                """;
        ScriptedCaller caller = new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, proposition)
                .on(ExtractionPassPrompts.PASS_MENTIONS, BOTH_MENTIONS_RESOLVED)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, ASSERTION)
                .on(ExtractionPassPrompts.PASS_RELATIONS,
                        RELATION_ASSERTED, selectFirst, selectNext, done);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), provider, ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(4, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(2, outcome.result().relations().size());
        assertEquals(List.of("ACQUIRED", "CONTROLLED"), outcome.result().relations().stream()
                .map(ExtractedRelation::type).toList());
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 3)
                .contains("ALREADY VALIDATED TYPES"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 3)
                .contains("decision=DONE"));
    }

    @Test
    void structuredRelationChoiceIsNotOverruledByEnglishWordsInItsReason() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED,
                "{\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":2,"
                        + "\"schemaGap\":false,\"confidence\":1.0,\"qualifiers\":{},"
                        + "\"reason\":\"free-form explanation in any language\"}}",
                "{\"selection\":{\"decision\":\"DONE\",\"candidateOrdinal\":null,"
                        + "\"schemaGap\":false,\"confidence\":1.0,\"qualifiers\":{},"
                        + "\"reason\":\"complete\"}}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals("PARTNERED_WITH", single(outcome).type());
        assertFalse(caller.prompts(ExtractionPassPrompts.PASS_RELATIONS).stream()
                .anyMatch(prompt -> prompt.contains("ENGINE LEXICAL")));
    }

    @Test
    void splitRelationTypeAggressivelySurfacesEveryModelSelectedGraphCandidate() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED,
                "{\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":1,\"schemaGap\":false,"
                        + "\"confidence\":1.0,\"qualifiers\":{},"
                        + "\"reason\":\"first graph candidate\"}}",
                "{\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":1,\"schemaGap\":false,"
                        + "\"confidence\":1.0,\"qualifiers\":{},"
                        + "\"reason\":\"remaining graph candidate\"}}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(List.of("ACQUIRED", "PARTNERED_WITH"), outcome.result().relations().stream()
                .map(ExtractedRelation::type).toList());
    }

    @Test
    void graphBallotStillRejectsAnOutOfRangeOrdinalAfterBoundedFormatCorrections() {
        String outsideGraphBallot = "{\"selection\":{\"decision\":\"SELECT\",\"candidateOrdinal\":9,"
                + "\"schemaGap\":false,\"confidence\":1.0,\"qualifiers\":{},"
                + "\"reason\":\"free-form explanation\"}}";
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED, outsideGraphBallot, outsideGraphBallot, outsideGraphBallot);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(4, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 2)
                .contains("VALIDATION CORRECTION"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 3)
                .contains("Re-evaluate the decision"));
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void autoAtomizationUsesACompleteUpstreamSpanPlan() {
        String source = "Finance selected option A. Next step begins Monday.";
        PassContext context = PassContext.forChunk("chunk-plan", "doc-plan", source)
                .withSourceSpans(List.of(new SourceSpan(0, source.length(), "PARSED_MESSAGE")));
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":null}", "{\"proposition\":null}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        pipeline.run(context, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 0)
                .contains("<<<FOCUS_SEGMENT\n" + source));
    }

    @Test
    void autoAtomizationFallsBackWhenAnUpstreamSpanPlanIsIncomplete() {
        String source = "First fact. Second fact.";
        int firstEnd = source.indexOf(" Second");
        PassContext context = PassContext.forChunk("chunk-fallback", "doc-fallback", source)
                .withSourceSpans(List.of(new SourceSpan(0, firstEnd, "PARTIAL")));
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":null}", "{\"proposition\":null}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        pipeline.run(context, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
    }

    @Test
    void configuredReferenceWindowCanRemoveEarlierEventText() {
        String source = "First fact. Second fact.";
        PassContext context = PassContext.forChunk("chunk-reference", "doc-reference", source);
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":null}", "{\"proposition\":null}");
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(), Options.defaults()
                .withPropositionAtomization(Mode.HEURISTIC, 512, 0));

        pipeline.run(context, caller);

        String secondPrompt = caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 1);
        assertTrue(secondPrompt.contains("<<<FOCUS_SEGMENT\nSecond fact."));
        assertFalse(secondPrompt.contains("First fact."));
    }

    @Test
    void structuredNumericFactsUseIndependentSingletonFramesWithBoundedReference() {
        String source = "HYD-110 (the new sleep serum) launches july 1. "
                + "i have it at conservative phasing — 4200 units in jul, 6800 in aug.";
        PassContext context = PassContext.forChunk("chunk-hyd", "doc-hyd", source);
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":{\"subject\":\"HYD-110\",\"predicate\":\"launches\","
                        + "\"object\":\"july 1\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\",\"timeExpression\":\"july 1\"}}",
                "{\"proposition\":{\"subject\":\"HYD-110\",\"predicate\":\"have at\","
                        + "\"object\":\"conservative phasing\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\"}}",
                "{\"proposition\":{\"subject\":\"HYD-110\",\"predicate\":\"phased at\","
                        + "\"object\":\"4200 units\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\",\"timeExpression\":\"jul\"}}",
                "{\"proposition\":{\"subject\":\"HYD-110\",\"predicate\":\"phased at\","
                        + "\"object\":\"6800\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\",\"timeExpression\":\"aug\"}}"
        );
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(4, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertEquals(List.of(
                        "HYD-110 (the new sleep serum) launches july 1.",
                        "i have it at conservative phasing",
                        "4200 units in jul",
                        "6800 in aug."),
                outcome.bundle().propositions().stream().map(PropositionProposal::text).toList());
        assertTrue(outcome.bundle().propositions().stream()
                .allMatch(proposition -> "HYD-110".equals(proposition.subject())));
        for (PropositionProposal proposition : outcome.bundle().propositions()) {
            assertEquals(source.indexOf(proposition.text()), proposition.evidence().start());
            assertEquals(proposition.evidence().start() + proposition.text().length(),
                    proposition.evidence().end());
        }
        String finalPrompt = caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 3);
        assertTrue(finalPrompt.contains("<<<FOCUS_SEGMENT\n6800 in aug."));
        assertTrue(finalPrompt.contains("HYD-110"),
                "the engine must preserve bounded preceding reference for the omitted subject");
    }

    @Test
    void malformedFocusedEventCannotEraseAValidLaterSibling() {
        String second = "Acme Corp acquired Initech in 2020.";
        String source = "This first event is malformed. " + second;
        PassContext context = PassContext.forChunk("chunk-siblings", "doc-siblings", source);
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "not json",
                "{\"proposition\":null}",
                "{\"proposition\":{\"subject\":\"Acme Corp\",\"predicate\":\"acquired\","
                        + "\"object\":\"Initech\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\",\"timeExpression\":\"2020\"}}"
        );
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 1)
                .contains("VALIDATION CORRECTION"));
        assertEquals(1, outcome.bundle().propositions().size());
        assertEquals(second, outcome.bundle().propositions().get(0).text());
        assertEquals(source.indexOf(second), outcome.bundle().propositions().get(0).evidence().start());
    }

    @Test
    void copiedPropositionExampleValuesTriggerOneSourceGroundingCorrection() {
        String source = "Channel mismatches always escalate to M. Chen.";
        PassContext context = PassContext.forChunk("chunk-grounding", "doc-grounding", source);
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":{\"subject\":\"Nia\",\"predicate\":\"sent\","
                        + "\"object\":\"the invoice\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\"}}",
                "{\"proposition\":{\"subject\":\"Channel mismatches\","
                        + "\"predicate\":\"always escalate to\",\"object\":\"M. Chen\","
                        + "\"polarity\":\"AFFIRMED\",\"modality\":\"FACTUAL\"}}"
        );
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS, 1)
                .contains("subject/object were not exact surface mentions"));
        assertEquals("Channel mismatches", outcome.bundle().propositions().get(0).subject());
        assertEquals("M. Chen", outcome.bundle().propositions().get(0).object());
        assertFalse(outcome.bundle().propositions().stream()
                .anyMatch(value -> "Nia".equals(value.subject()) || "the invoice".equals(value.object())));
    }

    @Test
    void graphFacetCategoryDoesNotTriggerJavaSemanticRepair() {
        String source = "M. Chen is VP, Planning.";
        PassContext context = PassContext.forChunk("chunk-frame-boundary", "doc-frame-boundary", source)
                .withConceptHints(List.of(
                        new ConceptHint("M. Chen", "PERSON", "unified-corpus", null),
                        new ConceptHint("VP Planning", "ROLE", "deterministic-prepass", null)));
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS,
                "{\"proposition\":{\"subject\":\"M. Chen\",\"predicate\":\"is VP\","
                        + "\"object\":\"Planning\",\"polarity\":\"AFFIRMED\","
                        + "\"modality\":\"FACTUAL\"}}"
        );
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertFalse(outcome.notes().stream().anyMatch(note -> note.contains("deterministically repaired")));
        assertEquals("is VP", outcome.bundle().propositions().get(0).predicate());
        assertEquals("Planning", outcome.bundle().propositions().get(0).object());
    }

    @Test
    void arbitraryFacetCategoryDoesNotSilentlyRejectAModelFrame() {
        String source = "M. Chen manages Planning operations.";
        PassContext context = PassContext.forChunk("chunk-frame-purity", "doc-frame-purity", source)
                .withConceptHints(List.of(
                        new ConceptHint("M Chen", "PERSON", "unified-corpus", null),
                        new ConceptHint("Planning operations", "BUSINESS_UNIT",
                                "deterministic-prepass", null)));
        String split = "{\"proposition\":{\"subject\":\"M. Chen\","
                + "\"predicate\":\"manages Planning\",\"object\":\"operations\","
                + "\"polarity\":\"AFFIRMED\","
                + "\"modality\":\"FACTUAL\"}}";
        ScriptedCaller caller = new ScriptedCaller().on(
                ExtractionPassPrompts.PASS_PROPOSITIONS, split);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                EntityCandidateProvider.none(), RelationCandidateProvider.none(),
                ClaimCandidateProvider.none(),
                Options.defaults().withSplitPropositionsBySourceEvent(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS));
        assertEquals(1, outcome.bundle().propositions().size());
        assertEquals("manages Planning", outcome.bundle().propositions().get(0).predicate());
        assertFalse(outcome.notes().stream().anyMatch(note -> note.contains("deterministically repaired")));
    }

    @Test
    void focusedValidationDoesNotUseHardcodedPronounOrReferenceWordLists() {
        PassContext context = PassContext.forChunk("chunk-focus", "doc-focus",
                "Acme approved the plan. Approved yesterday.");
        PropositionProposal copied = new PropositionProposal("p1", "Approved yesterday.",
                "Acme", "Approved", "the plan", Polarity.AFFIRMED, Modality.FACTUAL,
                null, null, null, EvidenceSpan.ofQuote("chunk-focus", "Approved yesterday."));
        PropositionProposal resolvedPronoun = new PropositionProposal("p2", "It was approved.",
                "the plan", "approved", null, Polarity.AFFIRMED, Modality.FACTUAL,
                null, null, null, EvidenceSpan.ofQuote("chunk-focus", "It was approved."));

        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                copied, context, "Approved yesterday."));
        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                resolvedPronoun,
                PassContext.forChunk("chunk-pronoun", "doc-pronoun",
                        "The plan was discussed. It was approved."),
                "It was approved."));
    }

    @Test
    void predicateMeaningIsDeferredToGraphEvaluationWithoutLanguageSpecificStemming() {
        String source = "HYD-110: i have it at conservative phasing — 4200 units in Jul.";
        String focus = "4200 units in Jul.";
        PassContext context = PassContext.forChunk("chunk-scalar", "doc-scalar", source);
        PropositionProposal supported = new PropositionProposal("p1", focus,
                "HYD-110", "phased at", "4200 units", Polarity.AFFIRMED, Modality.FACTUAL,
                "Jul", null, null, EvidenceSpan.ofQuote("chunk-scalar", focus));
        PropositionProposal invented = new PropositionProposal("p2", focus,
                "HYD-110", "forecasted at", "4200 units", Polarity.AFFIRMED, Modality.FACTUAL,
                "Jul", null, null, EvidenceSpan.ofQuote("chunk-scalar", focus));
        PropositionProposal nonLatin = new PropositionProposal("p3", focus,
                "HYD-110", "段階化する", "4200 units", Polarity.UNKNOWN, Modality.UNKNOWN,
                "Jul", null, null, EvidenceSpan.ofQuote("chunk-scalar", focus));

        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                supported, context, focus));
        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                invented, context, focus));
        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                nonLatin, context, focus));
    }

    @Test
    void conceptFacetCategoriesDoNotActAsJavaArgumentRoleRules() {
        String roleFocus = "M. Chen is VP, Planning.";
        PassContext roleContext = PassContext.forChunk("chunk-role-purity", "doc-role-purity", roleFocus)
                .withConceptHints(List.of(
                        new ConceptHint("M Chen", "PERSON", "unified-corpus", null),
                        new ConceptHint("VP Planning", "ROLE", "deterministic-prepass", null)));
        PropositionProposal swallowed = new PropositionProposal("p1", roleFocus,
                "M. Chen", "is VP, Planning", null, Polarity.AFFIRMED, Modality.FACTUAL,
                null, null, null, EvidenceSpan.ofQuote("chunk-role-purity", roleFocus));
        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                swallowed, roleContext, roleFocus));

        String escalationFocus = "Channel mismatch must always be escalated to M. Chen.";
        PassContext escalationContext = PassContext.forChunk(
                        "chunk-order-purity", "doc-order-purity", escalationFocus)
                .withConceptHints(List.of(
                        new ConceptHint("Channel mismatch", "VARIANCE_TRIAGE", "unified-corpus", null),
                        new ConceptHint("M. Chen", "PERSON", "unified-corpus", null)));
        PropositionProposal reversed = new PropositionProposal("p2", escalationFocus,
                "M. Chen", "must always be escalated", "Channel mismatch",
                Polarity.AFFIRMED, Modality.OBLIGATION, null, null, null,
                EvidenceSpan.ofQuote("chunk-order-purity", escalationFocus));
        PropositionProposal reused = new PropositionProposal("p3", escalationFocus,
                "M. Chen", "must always be escalated to", "M. Chen",
                Polarity.AFFIRMED, Modality.OBLIGATION, null, null, null,
                EvidenceSpan.ofQuote("chunk-order-purity", escalationFocus));
        PropositionProposal ordered = new PropositionProposal("p4", escalationFocus,
                "Channel mismatch", "must always be escalated to", "M. Chen",
                Polarity.AFFIRMED, Modality.OBLIGATION, null, null, null,
                EvidenceSpan.ofQuote("chunk-order-purity", escalationFocus));

        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                reversed, escalationContext, escalationFocus));
        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                reused, escalationContext, escalationFocus));
        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                ordered, escalationContext, escalationFocus));
    }

    @Test
    void unknownPolarityRemainsAProposalForDownstreamGraphCalibration() {
        String focus = "05a_AMER_Forecast_Q3_v3_FINAL_v2.xlsx contains Sheet1.";
        PropositionProposal unknown = new PropositionProposal("p1", focus,
                "05a_AMER_Forecast_Q3_v3_FINAL_v2.xlsx", "contains", "Sheet1",
                Polarity.UNKNOWN, Modality.FACTUAL, null, null, null,
                EvidenceSpan.ofQuote("chunk-polarity", focus));

        assertEquals(null, DecomposedExtractionPipeline.propositionValidationError(
                unknown, PassContext.forChunk("chunk-polarity", "doc-polarity", focus), focus));
    }

    @Test
    void endpointSplitMakesRoleSurfaceAndEvidenceEngineOwned() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS,
                SUBJECT_MENTION_RESOLVED, OBJECT_MENTION_RESOLVED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("mentionRole: SUBJECT"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("mentionRole: OBJECT"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("[1] name=Acme Corporation"));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                        .contains("[1] name=Initech"),
                "the subject call must not inherit the object's candidate ballot");
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("[1] name=Initech"));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                        .contains("Acme Corporation"),
                "the object call must not inherit the subject's candidate ballot");
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0).contains("ent-acme"),
                "focused production prompts expose ordinals, not opaque graph ids");
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("\"operation\""));
        assertEquals(2, outcome.bundle().mentions().size());
        assertEquals("ent-acme", outcome.bundle().mentions().get(0).selectedEntityId(),
                "the engine maps candidate ordinal one to its exact opaque id");
        assertEquals("Acme Corp", outcome.bundle().mentions().get(0).mentionText());
        assertEquals("SUBJECT", outcome.bundle().mentions().get(0).mentionRole());
        assertEquals("Acme Corp", outcome.bundle().mentions().get(0).evidence().quote());
        assertEquals(1, outcome.result().relations().size());
    }

    @Test
    void endpointSplitRejectsAnIdThatWasOnlyOfferedToTheOtherEndpoint() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mention":{"decision":"REUSE","candidateOrdinal":2,
                  "confidence":0.93,"reason":"copied the other endpoint"}}
                """, """
                {"mention":{"decision":"REUSE","candidateOrdinal":2,
                  "confidence":0.93,"reason":"still outside the ballot"}}
                """, OBJECT_MENTION_RESOLVED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_MENTIONS).outOfVocabulary());
        assertEquals(ProposalOperation.UNRESOLVED,
                outcome.bundle().mentions().get(0).operation());
        assertTrue(outcome.notes().stream().anyMatch(note ->
                note.contains("candidateOrdinal") && note.contains("outside the offered ballot")));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("VALIDATION CORRECTION"));
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void unifiedCorpusConceptCategoriesInformTheModelWithoutBecomingJavaTypeRules() {
        PassContext typed = CONTEXT.withConceptHints(List.of(
                new ConceptHint("Acme", "ORGANIZATION", "unified-corpus", null),
                new ConceptHint("Initech", "PRODUCT", "deterministic-prepass", null)));
        List<String> typeHints = new ArrayList<>();
        EntityCandidateProvider provider = (mention, typeHint, context, limit) -> {
            typeHints.add(typeHint);
            return mention.contains("Acme") ? List.of(ACME) : List.of(INITECH);
        };
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS,
                SUBJECT_MENTION_RESOLVED, OBJECT_MENTION_RESOLVED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                provider, relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true));

        pipeline.run(typed, caller);

        assertEquals(2, typeHints.size());
        assertEquals(null, typeHints.get(0));
        assertEquals(null, typeHints.get(1));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("schemaEntityTypeConstraint: NONE"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("category=ORGANIZATION"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("category=PRODUCT"));
    }

    @Test
    void copiedTypeTemplateIsCorrectedThroughSourceGroundedTypeBallotsBeforeProjection() {
        String source = "The inventory identifies M. Chen as VP, Planning.";
        PassContext context = PassContext.forChunk("chunk-type-repair", "doc-type-repair", source)
                .withConceptHints(List.of(
                        new ConceptHint("M Chen", "PERSON", "deterministic-prepass", null),
                        new ConceptHint("VP Planning", "ROLE", "deterministic-prepass", null)));
        String proposition = """
                {"propositions":[{"id":"p1","text":"M. Chen is VP, Planning",
                  "subject":"M. Chen","predicate":"is","object":"VP, Planning",
                  "polarity":"AFFIRMED","modality":"FACTUAL",
                  "evidence":{"quote":"M. Chen as VP, Planning","role":"DIRECT_SUPPORT"}}]}
                """;
        String copiedTemplate = """
                {"mention":{"decision":"CREATE_PROVISIONAL",
                  "provisionalType":"__SOURCE_OR_SCHEMA_TYPE__","confidence":0.0,
                  "reason":"cite why the current mention is a new identity"}}
                """;
        String selectTypeOne = """
                {"mention":{"decision":"CREATE_PROVISIONAL","provisionalTypeOrdinal":1,
                  "confidence":0.9,"reason":"the source-aligned type ballot describes this endpoint"}}
                """;
        EntityCandidateProvider emptyEntities = (mention, typeHint, ignored, limit) -> List.of();
        ScriptedCaller caller = new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, proposition)
                .on(ExtractionPassPrompts.PASS_MENTIONS,
                        copiedTemplate, selectTypeOne, selectTypeOne)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, ASSERTION)
                .on(ExtractionPassPrompts.PASS_RELATIONS, RELATION_NOT_ASSERTED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                emptyEntities, relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true)
                        .withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("- [1] PERSON"));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("__SOURCE_OR_SCHEMA_TYPE__"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("VALIDATION CORRECTION"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 2)
                .contains("- [1] ROLE"));
        assertEquals(List.of("PERSON", "ROLE"), outcome.result().entities().stream()
                .map(ExtractedEntity::type).toList());
        assertEquals(0, stat(outcome, ExtractionPassPrompts.PASS_MENTIONS).outOfVocabulary());
        assertTrue(outcome.notes().stream().anyMatch(note ->
                note.contains("focused mention SUBJECT rejected")));
    }

    @Test
    void sourceAttachedAliasExpandsRecallWithoutCreatingADuplicateIdentity() {
        String source = "田中綾子 (Ayako Tanaka, A. Tanaka, "
                + "ayako.tanaka@northstargoods.com) submitted APAC June Forecast.";
        PassContext context = PassContext.forChunk("chunk-alias", "doc-alias", source)
                .withGraph("graph-9", "graph-8")
                .withSchema("kompile-graph-extraction/v1", "test-model")
                .withConceptHints(List.of(
                        new ConceptHint("田中綾子", "PERSON", "unified-corpus", null),
                        new ConceptHint("APAC June Forecast", "REGIONAL_FORECAST",
                                "deterministic-prepass", null)));
        String proposition = """
                {"propositions":[{"id":"p1","text":"田中綾子 (Ayako Tanaka, A. Tanaka, ayako.tanaka@northstargoods.com) submitted APAC June Forecast.",
                  "subject":"田中綾子","predicate":"submitted","object":"APAC June Forecast",
                  "polarity":"AFFIRMED","modality":"FACTUAL",
                  "evidence":{"quote":"田中綾子 (Ayako Tanaka, A. Tanaka, ayako.tanaka@northstargoods.com) submitted APAC June Forecast.",
                              "role":"DIRECT_SUPPORT"}}]}
                """;
        String createForecast = """
                {"mention":{"decision":"CREATE_PROVISIONAL","provisionalTypeOrdinal":1,
                  "confidence":0.9,
                  "reason":"no existing forecast candidate was retrieved"}}
                """;
        EntityCandidate ayako = new EntityCandidate("person-ayako", "Ayako Tanaka", "PERSON",
                List.of("A. Tanaka", "ayako.tanaka@northstargoods.com"), 1.0, "exact", null,
                new IdentitySignals(true, false, false, true, false, false, null, null));
        List<String> probes = new ArrayList<>();
        List<String> typeHints = new ArrayList<>();
        EntityCandidateProvider provider = (mention, typeHint, ignored, limit) -> {
            probes.add(mention);
            typeHints.add(typeHint);
            return "Ayako Tanaka".equals(mention) ? List.of(ayako) : List.of();
        };
        ScriptedCaller caller = new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, proposition)
                .on(ExtractionPassPrompts.PASS_MENTIONS,
                        SUBJECT_MENTION_RESOLVED, createForecast)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, ASSERTION)
                .on(ExtractionPassPrompts.PASS_RELATIONS, RELATION_NOT_ASSERTED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                provider, relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true)
                        .withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(List.of("田中綾子", "Ayako Tanaka", "A. Tanaka",
                "ayako.tanaka@northstargoods.com", "APAC June Forecast"), probes,
                () -> "pipeline notes: " + outcome.notes());
        assertEquals(5, typeHints.size());
        assertTrue(typeHints.stream().allMatch(value -> value == null),
                "concept categories are model context, not Java-inferred entity constraints");
        String subjectPrompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0);
        assertTrue(subjectPrompt.contains("[1] name=Ayako Tanaka"));
        assertTrue(subjectPrompt.contains("SOURCE_ALIAS_EXACT"));
        assertTrue(subjectPrompt.contains("matchedSourceAlias=Ayako Tanaka"));
        assertFalse(subjectPrompt.contains("person-ayako"), "opaque graph ids stay engine-owned");
        ExtractedEntity person = outcome.result().entities().stream()
                .filter(entity -> "person-ayako".equals(entity.id())).findFirst().orElseThrow();
        assertEquals("Ayako Tanaka", person.name());
        assertTrue(person.aliases().contains("田中綾子"),
                "the exact observed surface must remain attached to the reused canonical entity");
        assertEquals(1, outcome.result().entities().stream()
                .filter(entity -> "PERSON".equals(entity.type())).count());
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void weakCandidateCannotOverrideAUniqueExactIdentityMatchAndGetsOneCorrection() {
        EntityCandidate exact = new EntityCandidate("ent-acme", "Acme Corporation", "ORGANIZATION",
                List.of(), 0.94, "test", null,
                new IdentitySignals(true, false, false, true, false, false, null, null));
        EntityCandidate weak = new EntityCandidate("ent-acme-holdings", "Acme Holdings",
                "ORGANIZATION", List.of(), 0.90, "test", null,
                new IdentitySignals(false, false, false, true, true, false, null, null));
        EntityCandidateProvider provider = (mention, typeHint, context, limit) ->
                mention.contains("Acme") ? List.of(exact, weak) : List.of(INITECH);
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mention":{"decision":"REUSE","candidateOrdinal":2,"confidence":0.9}}
                """, """
                {"mention":{"decision":"REUSE","candidateOrdinal":1,"confidence":0.9}}
                """, OBJECT_MENTION_RESOLVED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                provider, relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals("ent-acme", outcome.bundle().mentions().get(0).selectedEntityId());
        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("weak lexical overlap over the unique exact identity match"));
        assertEquals(1, outcome.result().relations().size());
    }

    @Test
    void weakOnlyReuseIsCorrectedToAProvisionalEntityInsteadOfPollutingIdentity() {
        EntityCandidate weak = new EntityCandidate("ent-acme-holdings", "Acme Holdings",
                "ORGANIZATION", List.of(), 0.90, "test", null,
                new IdentitySignals(false, false, false, true, true, false, null, null));
        EntityCandidateProvider provider = (mention, typeHint, context, limit) ->
                mention.contains("Acme") ? List.of(weak) : List.of(INITECH);
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mention":{"decision":"REUSE","candidateOrdinal":1,"confidence":0.9}}
                """, """
                {"mention":{"decision":"CREATE_PROVISIONAL","candidateOrdinal":1,
                  "provisionalTypeOrdinal":1,"confidence":0.8,
                  "reason":"names identify different organizations"}}
                """, OBJECT_MENTION_RESOLVED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                provider, relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 0)
                .contains("every selectable candidate has only WEAK_LEXICAL_ONLY"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("only WEAK_LEXICAL_ONLY overlap and no exact identity signal"));
        assertEquals(ProposalOperation.CREATE_PROVISIONAL_ENTITY,
                outcome.bundle().mentions().get(0).operation());
        assertEquals("Acme Corp", outcome.bundle().mentions().get(0).provisionalName());
        assertEquals("ORGANIZATION", outcome.bundle().mentions().get(0).provisionalType());
        assertFalse(outcome.bundle().mentions().stream()
                .anyMatch(value -> "ent-acme-holdings".equals(value.selectedEntityId())));
        assertEquals(1, outcome.result().relations().size(),
                "non-merging ordinal noise must not destroy an otherwise grounded endpoint");
    }

    @Test
    void focusedMentionExtraRootFieldsTriggerOneExactShapeCorrection() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_MENTIONS, """
                {"mention":{"decision":"REUSE","candidateOrdinal":1,"confidence":0.9},
                 "mentionText":"Acme Corp"}
                """, SUBJECT_MENTION_RESOLVED, OBJECT_MENTION_RESOLVED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitMentionsByEndpoint(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals("ent-acme", outcome.bundle().mentions().get(0).selectedEntityId());
        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_MENTIONS, 1)
                .contains("decision fields do not match the selected mention branch"));
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
    void semanticPropositionTextMayNormalizeSourceWhileEvidenceRemainsVerbatim() {
        String semanticText = "In 2019 Acme Corp completed its acquisition of Initech";
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[{"text":"In 2019 Acme Corp completed its acquisition of Initech",
                  "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
                  "evidence":{"quote":"Acme Corp acquired Initech in 2019"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).accepted());
        assertEquals(semanticText, outcome.bundle().propositions().get(0).text());
        assertEquals("Acme Corp acquired Initech in 2019",
                outcome.bundle().propositions().get(0).evidence().quote());
    }

    @Test
    void aGraphOnlyPropositionCannotBorrowARealSourceQuote() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[{"text":"Acme Corp secretly controls Globex",
                  "subject":"Acme Corp","predicate":"controls","object":"Globex",
                  "evidence":{"quote":"Acme Corp acquired Initech in 2019"}}]}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).spanRejected());
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertTrue(outcome.notes().stream()
                .anyMatch(note -> note.contains("ENDPOINT_NOT_IN_SOURCE")));
        assertTrue(outcome.result().entities().isEmpty());
        assertTrue(outcome.result().relations().isEmpty());
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
    void epistemicDecisionReusesTheEngineVerifiedPropositionEvidence() {
        Outcome outcome = pipeline().run(CONTEXT, happyPath());

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_EPISTEMIC).accepted());
        assertEquals(0, stat(outcome, ExtractionPassPrompts.PASS_EPISTEMIC).spanRejected());
        assertFalse(outcome.notes().stream()
                .anyMatch(note -> note.contains("epistemic p1: no evidence quote supplied")));
        assertEquals("ASSERTION", single(outcome).properties().get(Props.EPISTEMIC));
    }

    @Test
    void modelEpistemicDecisionIsNotOverriddenByAJavaLexicalRoute() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_EPISTEMIC, """
                {"classification":{"speechAct":"UNKNOWN","holder":null,"certainty":0.0,
                  "reason":"which words in the text decided it"}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_EPISTEMIC));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_EPISTEMIC, 0)
                .contains("ENGINE SPEECH-ACT ROUTE"));
        assertEquals("UNKNOWN", single(outcome).properties().get(Props.EPISTEMIC));
        assertEquals("false", single(outcome).properties().get(Props.ASSERTABLE));
    }

    @Test
    void anOpinionIsAttributedRatherThanPromoted() {
        String attributedProposition = """
                {"propositions":[{"text":"Acme Corp acquired Initech in 2019",
                  "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
                  "attributedTo":"Analysts",
                  "evidence":{"quote":"Acme Corp acquired Initech in 2019","role":"DIRECT_SUPPORT"}}]}
                """;
        ScriptedCaller caller = happyPath()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, attributedProposition)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, """
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
    void relationTimeFallsBackToTheVerifiedPropositionWhenTheModelOmitsIt() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS, """
                {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.9,
                  "reason":"explicit acquisition verb",
                  "evidence":{"quote":"acquired Initech"}}}
                """);

        Outcome outcome = pipeline().run(CONTEXT, caller);

        assertEquals("2019", outcome.bundle().relations().get(0).occurredAt());
        assertEquals("2019", single(outcome).occurredAt(),
                "time is proposition state and must not be lost in relation selection");
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
    void claimCandidateOrdinalMapsToTheExactEngineKeyAndInheritsRelationEvidence() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS, """
                {"decision":{"operation":"FLAG_CONTRADICTION","matchedCandidateOrdinal":1,
                  "confidence":0.7,"reason":"the existing claim conflicts"}}
                """);

        Outcome outcome = pipelineWithClaims().run(CONTEXT, caller);

        var claim = outcome.bundle().claims().get(0);
        assertEquals("atom-1", claim.matchedAtomKey());
        assertNotNull(claim.evidence());
        assertEquals("acquired Initech", claim.evidence().quote(),
                "the engine must carry forward the verified relation evidence");
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_CLAIMS)
                .contains("[1] atomKey=atom-1"));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_CLAIMS)
                .contains("matchedAtomKey"));
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
        String source = "Acme Corp did not acquire Initech.";
        PassContext negativeContext = PassContext.forChunk("chunk-negative", "doc-negative", source)
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model");
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[{"id":"p1","text":"Acme Corp did not acquire Initech",
                  "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                  "polarity":"NEGATED","modality":"FACTUAL",
                  "evidence":{"quote":"Acme Corp did not acquire Initech"}}]}
                """).on(ExtractionPassPrompts.PASS_RELATIONS, """
                {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.9,
                  "reason":"explicit acquisition verb",
                  "evidence":{"quote":"acquire Initech"}}}
                """);

        Outcome outcome = pipeline().run(negativeContext, caller);

        assertTrue(outcome.result().relations().isEmpty());
        assertTrue(outcome.notes().stream().anyMatch(n -> n.contains("source negates it")));
        assertEquals(2, outcome.result().entities().size());
    }

    @Test
    void eachPropositionGetsItsOwnBoundedCallPerPass() {
        String source = "Acme Corp acquired Initech in 2019 and Acme Corp retained Initech in 2020.";
        PassContext twoFactContext = PassContext.forChunk("chunk-two-facts", "doc-two-facts", source)
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model");
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                {"propositions":[
                  {"id":"p1","text":"Acme Corp acquired Initech in 2019","subject":"Acme Corp",
                   "predicate":"acquired","object":"Initech","polarity":"AFFIRMED",
                   "modality":"FACTUAL","evidence":{"quote":"Acme Corp acquired Initech"}},
                  {"id":"p2","text":"Acme Corp retained Initech in 2020","subject":"Acme Corp",
                   "predicate":"retained","object":"Initech","polarity":"AFFIRMED",
                   "modality":"FACTUAL","evidence":{"quote":"Acme Corp retained Initech"}}]}
                """);

        Outcome outcome = pipeline().run(twoFactContext, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS),
                "the chunk is enumerated once");
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_EPISTEMIC));
        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(2, stat(outcome, ExtractionPassPrompts.PASS_EPISTEMIC).calls());
        assertEquals(2, stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).accepted());
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
    void unboundedPropositionPolicyRetainsEverySourceGroundedModelProposal() {
        String item = """
                {"text":"Acme Corp acquired Initech in 2019","subject":"Acme Corp",
                 "predicate":"acquired","object":"Initech","polarity":"AFFIRMED",
                 "modality":"FACTUAL","evidence":{"quote":"Acme Corp acquired Initech"}}
                """.strip();
        int proposalCount = 20;
        String response = "{\"propositions\":["
                + String.join(",", java.util.Collections.nCopies(proposalCount, item)) + "]}";
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_PROPOSITIONS, response);

        Outcome outcome = new DecomposedExtractionPipeline(entityProvider(), relationProvider(),
                ClaimCandidateProvider.none(), Options.defaults().withMaxPropositions(0))
                .run(CONTEXT, caller);

        assertEquals(proposalCount,
                stat(outcome, ExtractionPassPrompts.PASS_PROPOSITIONS).accepted());
        assertEquals(proposalCount, outcome.bundle().propositions().size());
        assertEquals(proposalCount, caller.calls(ExtractionPassPrompts.PASS_MENTIONS));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_PROPOSITIONS)
                .contains("Return at most"));
    }

    @Test
    void aFailingCandidateProviderPropagatesAsInfrastructureFailure() {
        EntityCandidateProvider broken = (mention, typeHint, context, limit) -> {
            throw new IllegalStateException("index offline");
        };
        ScriptedCaller caller = happyPath();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DecomposedExtractionPipeline(broken, relationProvider(),
                        ClaimCandidateProvider.none(), Options.defaults()).run(CONTEXT, caller));

        assertEquals("index offline", failure.getMessage());
        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_MENTIONS),
                "the model must not receive a fake empty ballot after retrieval infrastructure fails");
    }

    @Test
    void splitRelationChecksExistenceBeforeShowingTheTypeBallot() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED, ACQUIRED_TYPE_ORDINAL, RELATION_DONE);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        String existencePrompt = caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 0);
        String typePrompt = caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 1);
        String completionPrompt = caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 2);
        assertTrue(existencePrompt.contains("does this proposition explicitly assert a relation"));
        assertFalse(existencePrompt.contains("PARTNERED_WITH"),
                "type glosses must not bias the existence decision");
        assertTrue(typePrompt.contains("[1] ACQUIRED"));
        assertTrue(typePrompt.contains("[2] PARTNERED_WITH"));
        assertTrue(typePrompt.contains("Never output the type text"));
        assertTrue(completionPrompt.contains("ADDITIONAL semantically distinct"));
        assertEquals("ACQUIRED", single(outcome).type(),
                "the engine maps the ordinal to the schema's canonical type");
        assertEquals(0.8d, single(outcome).confidence(), 1e-9,
                "composed confidence is bounded by both independent decisions");
        assertEquals(3, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).proposed());
    }

    @Test
    void affirmedFrameDoesNotCauseJavaToOverrideTheModelVerdict() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_NOT_ASSERTED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 0)
                .contains("polarity-specific route"));
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void uncertainRelationExistenceSkipsCandidateRetrievalAndTypeSelection() {
        RelationCandidateProvider shouldNotRun = (source, target, context, limit) -> {
            throw new AssertionError("type candidates must be retrieved only after existence passes");
        };
        ScriptedCaller caller = happyPath()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                        {"propositions":[{"id":"p1","text":"Acme Corp acquired Initech in 2019",
                          "subject":"Acme Corp","predicate":"acquired","object":"Initech",
                          "polarity":"UNKNOWN","modality":"FACTUAL","timeExpression":"2019",
                          "evidence":{"quote":"Acme Corp acquired Initech in 2019"}}]}
                        """)
                .on(ExtractionPassPrompts.PASS_RELATIONS, RELATION_NOT_ASSERTED);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), shouldNotRun, ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).abstained());
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void negatedProposalCanBeSurfacedButProjectionPreservesGraphPurity() {
        String source = "Acme Corp did not acquire Initech.";
        PassContext context = PassContext.forChunk("chunk-negated-split", "doc-negated-split", source);
        ScriptedCaller caller = happyPath()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, """
                        {"propositions":[{"subject":"Acme Corp","predicate":"acquire",
                          "object":"Initech","polarity":"NEGATED","modality":"FACTUAL",
                          "text":"Acme Corp did not acquire Initech.",
                          "evidence":{"quote":"Acme Corp did not acquire Initech."}}]}
                        """)
                .on(ExtractionPassPrompts.PASS_RELATIONS,
                        RELATION_ASSERTED, ACQUIRED_TYPE_ORDINAL, RELATION_DONE);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertEquals(1, outcome.bundle().relations().stream()
                .filter(value -> value.operation() == ProposalOperation.CREATE_CLAIM).count());
        assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("source negates it")));
        assertTrue(outcome.result().relations().isEmpty());
    }

    @Test
    void splitRelationKicksAnOrdinalOutsideTheEngineBallotBackForCorrection() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED,
                "{\"selection\":{\"candidateOrdinal\":99,\"schemaGap\":false,\"confidence\":0.99}}",
                ACQUIRED_TYPE_ORDINAL, RELATION_DONE);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), relationProvider(), ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_RELATIONS).outOfVocabulary());
        assertEquals(4, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 2)
                .contains("VALIDATION CORRECTION"));
        assertEquals("ACQUIRED", single(outcome).type());
    }

    @Test
    void splitRelationIteratesAOneChoiceBallotForMultipleDistinctExplicitTypes() {
        String source = "Acme Corp acquired and controls Initech.";
        PassContext context = PassContext.forChunk("chunk-multi-relation", "doc-multi-relation", source)
                .withGraph("graph-7", "graph-6")
                .withSchema("kompile-graph-extraction/v1", "test-model");
        String proposition = """
                {"propositions":[{"text":"Acme Corp acquired and controls Initech",
                  "subject":"Acme Corp","predicate":"acquired and controls","object":"Initech",
                  "polarity":"AFFIRMED","modality":"FACTUAL",
                  "evidence":{"quote":"Acme Corp acquired and controls Initech."}}]}
                """;
        String firstType = """
                {"selection":{"candidateOrdinal":1,"schemaGap":false,"confidence":0.9,
                  "qualifiers":{},"reason":"acquired is explicit"}}
                """;
        String secondType = """
                {"selection":{"candidateOrdinal":1,"schemaGap":false,"confidence":0.88,
                  "qualifiers":{},"reason":"controls is independently explicit"}}
                """;
        RelationCandidateProvider provider = (sourceType, targetType, ignored, limit) -> List.of(
                RelationCandidate.of("ACQUIRED", "one organization acquired another"),
                RelationCandidate.of("CONTROLLED", "one organization controls another"));
        ScriptedCaller caller = happyPath()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, proposition)
                .on(ExtractionPassPrompts.PASS_RELATIONS,
                        RELATION_ASSERTED, firstType, secondType);
        DecomposedExtractionPipeline pipeline = new DecomposedExtractionPipeline(
                entityProvider(), provider, ClaimCandidateProvider.none(),
                Options.defaults().withSplitRelationDecision(true));

        Outcome outcome = pipeline.run(context, caller);

        assertEquals(3, caller.calls(ExtractionPassPrompts.PASS_RELATIONS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 2)
                .contains("one ADDITIONAL semantically distinct permitted relation type"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 2)
                .contains("- ACQUIRED"));
        assertEquals(List.of("ACQUIRED", "CONTROLLED"), outcome.result().relations().stream()
                .map(ExtractedRelation::type).sorted().toList());
    }

    @Test
    void splitClaimsComparesOneCandidatePerCallWithoutExposingAtomKeys() {
        List<ClaimCandidate> candidates = List.of(
                ClaimCandidate.of("atom-1", "legacy candidate one", 0.6),
                ClaimCandidate.of("atom-2", "matching candidate two", 0.8));
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS,
                "{\"comparison\":{\"relationship\":\"DIFFERENT\",\"confidence\":0.9,\"reason\":\"different buyer\"}}",
                "{\"comparison\":{\"relationship\":\"SAME\",\"confidence\":0.95,\"reason\":\"same scoped acquisition\"}}");
        DecomposedExtractionPipeline pipeline = pipelineWithClaims(candidates,
                Options.defaults().withSplitClaimsByCandidate(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_CLAIMS));
        String firstPrompt = caller.prompt(ExtractionPassPrompts.PASS_CLAIMS, 0);
        String secondPrompt = caller.prompt(ExtractionPassPrompts.PASS_CLAIMS, 1);
        assertTrue(firstPrompt.contains("legacy candidate one"));
        assertFalse(firstPrompt.contains("matching candidate two"));
        assertTrue(secondPrompt.contains("matching candidate two"));
        assertFalse(secondPrompt.contains("legacy candidate one"));
        assertFalse(firstPrompt.contains("atom-1"));
        assertFalse(secondPrompt.contains("atom-2"));
        assertEquals("atom-2", outcome.bundle().claims().get(0).matchedAtomKey());
        assertEquals(ProposalOperation.ADD_EVIDENCE,
                outcome.bundle().claims().get(0).operation());
        assertEquals("ADD_EVIDENCE", single(outcome).properties().get(Props.CLAIM_OPERATION));
    }

    @Test
    void uncertainPerCandidateComparisonSafelyAbstainsInsteadOfMintingADuplicateClaim() {
        List<ClaimCandidate> candidates = List.of(
                ClaimCandidate.of("atom-1", "legacy candidate one", 0.6),
                ClaimCandidate.of("atom-2", "matching candidate two", 0.8));
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS,
                "{\"comparison\":{\"relationship\":\"DIFFERENT\",\"confidence\":0.9}}",
                "{\"comparison\":{\"relationship\":\"UNCERTAIN\",\"confidence\":0.4}}");
        DecomposedExtractionPipeline pipeline = pipelineWithClaims(candidates,
                Options.defaults().withSplitClaimsByCandidate(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(ProposalOperation.ABSTAIN, outcome.bundle().claims().get(0).operation());
        assertEquals(1, stat(outcome, ExtractionPassPrompts.PASS_CLAIMS).abstained());
        assertTrue(outcome.result().relations().isEmpty(),
                "an uncertain match must not create a duplicate or silently attach evidence");
    }

    @Test
    void structuredClaimMismatchRejectsSameThenAcceptsCorrectedDifferent() {
        List<ClaimCandidate> candidates = List.of(new ClaimCandidate(
                "atom-1", "ent-globex", "ACQUIRED", "ent-initech", 0.7, 1, 0,
                "Globex acquired Initech"));
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS,
                "{\"comparison\":{\"relationship\":\"SAME\",\"confidence\":0.9}}",
                "{\"comparison\":{\"relationship\":\"DIFFERENT\",\"confidence\":0.9}}");
        DecomposedExtractionPipeline pipeline = pipelineWithClaims(candidates,
                Options.defaults().withSplitClaimsByCandidate(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_CLAIMS));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_CLAIMS, 1)
                .contains("VALIDATION CORRECTION"));
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_CLAIMS, 1)
                .contains("subject: MISMATCH"));
        assertEquals(ProposalOperation.CREATE_CLAIM,
                outcome.bundle().claims().get(0).operation());
    }

    @Test
    void repeatedSameAgainstKnownMismatchAbstainsForGraphPurity() {
        List<ClaimCandidate> candidates = List.of(new ClaimCandidate(
                "atom-1", "ent-globex", "ACQUIRED", "ent-initech", 0.7, 1, 0,
                "Globex acquired Initech"));
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS,
                "{\"comparison\":{\"relationship\":\"SAME\",\"confidence\":0.9}}");
        DecomposedExtractionPipeline pipeline = pipelineWithClaims(candidates,
                Options.defaults().withSplitClaimsByCandidate(true));

        Outcome outcome = pipeline.run(CONTEXT, caller);

        assertEquals(2, caller.calls(ExtractionPassPrompts.PASS_CLAIMS));
        assertEquals(ProposalOperation.ABSTAIN, outcome.bundle().claims().get(0).operation());
        assertTrue(outcome.result().relations().isEmpty());
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

    private static DecomposedExtractionPipeline pipelineWithClaims(
            List<ClaimCandidate> candidates, Options options) {
        ClaimCandidateProvider claims = (subject, predicate, object, context, limit) -> candidates;
        return new DecomposedExtractionPipeline(entityProvider(), relationProvider(), claims,
                options);
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

        List<String> prompts(String passId) {
            return List.copyOf(prompts.getOrDefault(passId, List.of()));
        }

        String prompt(String passId) {
            return prompt(passId, 0);
        }

        String prompt(String passId, int index) {
            List<String> seen = prompts.getOrDefault(passId, List.of());
            assertTrue(index >= 0 && index < seen.size(),
                    "pass " + passId + " has no prompt at index " + index);
            return seen.get(index);
        }
    }
}
