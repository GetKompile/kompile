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

package ai.kompile.crawl.graph.passes;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.PropositionAtomizationConfig;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.LlmCaller;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Options;
import ai.kompile.core.graphrag.passes.ExtractionPassPrompts;
import ai.kompile.core.graphrag.passes.ExtractionProjection.Props;
import ai.kompile.core.llm.ModelCapability;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crawl-side wiring of decomposed extraction: the in-run graph supplies the identity and claim
 * ballots, the project schema and validation policy supply the relation vocabulary, and what comes
 * back is the same schema-shaped JSON the monolithic prompt produced — so nothing downstream of the
 * LLM call has to change.
 */
class DecomposedExtractionExecutorTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Analysts believe the deal was overpriced.";

    // ── scripted model answers, one per pass ──────────────────────────────

    private static final String ONE_PROPOSITION = """
            {"propositions":[{"id":"p1","text":"Acme Corp acquired Initech in 2019",
              "subject":"Acme Corp","predicate":"acquired","object":"Initech",
              "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
              "evidence":{"quote":"Acme Corp acquired Initech in 2019","role":"DIRECT_SUPPORT"}}]}
            """;

    private static final String BOTH_MENTIONS_RESOLVED = """
            {"mentions":[
              {"mentionText":"Acme Corp","mentionRole":"SUBJECT","operation":"REUSE_ENTITY",
               "selectedEntityId":"ent-acme","confidence":0.93,"reason":"name matches",
               "evidence":{"quote":"Acme Corp"}},
              {"mentionText":"Initech","mentionRole":"OBJECT","operation":"REUSE_ENTITY",
               "selectedEntityId":"ent-initech","confidence":0.88,
               "evidence":{"quote":"Initech"}}]}
            """;

    private static final String SUBJECT_MENTION_RESOLVED = """
            {"mention":{"decision":"REUSE","candidateOrdinal":1,"confidence":0.93,
              "reason":"name matches"}}
            """;

    private static final String OBJECT_MENTION_RESOLVED = """
            {"mention":{"decision":"REUSE","candidateOrdinal":1,"confidence":0.88,
              "reason":"exact name"}}
            """;

    private static final String ASSERTION = """
            {"classification":{"speechAct":"ASSERTION","certainty":0.95,
              "reason":"stated directly by the document"}}
            """;

    private static final String RELATION_ASSERTED = """
            {"decision":{"assertsRelation":true,"confidence":0.95,
              "reason":"the acquisition is stated directly"}}
            """;

    private static final String ACQUIRED_TYPE_ORDINAL = """
            {"selection":{"candidateOrdinal":1,"schemaGap":false,"confidence":0.9,
              "qualifiers":{},"reason":"the acquisition relation matches"}}
            """;

    private static final String RELATION_DONE = """
            {"selection":{"decision":"DONE","candidateOrdinal":null,"schemaGap":false,
              "confidence":0.9,"qualifiers":{},"reason":"no additional distinct relation is asserted"}}
            """;

    // ── fixtures ──────────────────────────────────────────────────────────

    private static Entity entity(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        return entity;
    }

    private static Graph graph(Relationship... relationships) {
        Graph graph = new Graph();
        graph.setId("graph-7");
        graph.setEntities(new ArrayList<>(List.of(
                entity("ent-acme", "Acme Corporation", "ORGANIZATION"),
                entity("ent-initech", "Initech", "ORGANIZATION"))));
        graph.setRelationships(new ArrayList<>(List.of(relationships)));
        return graph;
    }

    private static Relationship acquisitionEdge() {
        Relationship relationship = new Relationship();
        relationship.setSource("ent-acme");
        relationship.setType("ACQUIRED");
        relationship.setTarget("ent-initech");
        relationship.setOccurredAt("2019");
        return relationship;
    }

    private static GraphExtractionConfig config() {
        return GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .relationshipTypes(new ArrayList<>(List.of("ACQUIRED", "PARTNERED_WITH")))
                .modelName("test-model")
                .build();
    }

    private static GraphSchema schema() {
        GraphSchema schema = new GraphSchema();
        schema.setRelationshipTypes(new ArrayList<>(List.of(
                new RelationshipType("ACQUIRED", "one organization bought another", null),
                new RelationshipType("WORKS_AT", "employment", null))));
        schema.setPatterns(new ArrayList<>(List.of(
                "(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)",
                "(PERSON)-[:WORKS_AT]->(ORGANIZATION)")));
        return schema;
    }

    private static ScriptedCaller happyPath() {
        return new ScriptedCaller()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, ONE_PROPOSITION)
                .on(ExtractionPassPrompts.PASS_MENTIONS,
                        SUBJECT_MENTION_RESOLVED, OBJECT_MENTION_RESOLVED)
                .on(ExtractionPassPrompts.PASS_EPISTEMIC, ASSERTION)
                .on(ExtractionPassPrompts.PASS_RELATIONS,
                        RELATION_ASSERTED, ACQUIRED_TYPE_ORDINAL, RELATION_DONE);
    }

    private static DecomposedExtractionExecutor.Result run(GraphExtractionConfig config,
                                                           Graph graph, LlmCaller caller) {
        return new DecomposedExtractionExecutor().extract(
                SOURCE, "chunk-1", "doc-1", config, schema(),
                GraphExtractionValidationPolicy.defaults(), graph, caller);
    }

    private static ExtractionResult parse(DecomposedExtractionExecutor.Result result) {
        assertTrue(result.usable(), "expected projectable JSON, summary was: " + result.summary());
        try {
            return GraphExtractionValidator.fromJson(result.json());
        } catch (Exception e) {
            throw new AssertionError("emitted JSON is not the crawl's extraction schema: " + e, e);
        }
    }

    // ── mode + options ────────────────────────────────────────────────────

    @Test
    void onlyDecomposedModeEnablesThePasses() {
        assertFalse(DecomposedExtractionExecutor.isEnabled(null));
        assertFalse(DecomposedExtractionExecutor.isEnabled(GraphExtractionConfig.builder().build()),
                "existing projects keep the single-prompt path until they opt in");
        assertTrue(DecomposedExtractionExecutor.isEnabled(config()));
    }

    @Test
    void configuredKnobsBecomePipelineOptions() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .decomposedMaxPropositions(3)
                .decomposedEntityCandidateLimit(4)
                .decomposedSplitMentionsByEndpoint(true)
                .decomposedRelationCandidateLimit(5)
                .decomposedClaimCandidateLimit(6)
                .decomposedRequireEvidenceSpans(false)
                .decomposedClaimMatching(false)
                .decomposedAtomization(PropositionAtomizationConfig.builder()
                        .mode(PropositionAtomizationConfig.Mode.SOURCE_SPANS)
                        .maxEventChars(333)
                        .referenceContextChars(44)
                        .build())
                .build();

        Options options = DecomposedExtractionExecutor.optionsFrom(config);

        assertEquals(3, options.maxPropositions());
        assertEquals(4, options.entityCandidateLimit());
        assertTrue(options.splitMentionsByEndpoint());
        assertTrue(options.inferFocusedMentionOperation(),
                "production focused mentions use the non-redundant derived-operation contract");
        assertEquals(5, options.relationCandidateLimit());
        assertEquals(6, options.claimCandidateLimit());
        assertFalse(options.requireEvidenceSpans());
        assertFalse(options.claimMatchingEnabled());
        assertTrue(options.splitRelationDecision());
        assertTrue(options.splitClaimsByCandidate());
        assertEquals(PropositionAtomizationConfig.Mode.SOURCE_SPANS,
                options.propositionAtomizationMode());
        assertEquals(333, options.propositionMaxEventChars());
        assertEquals(44, options.propositionReferenceContextChars());
    }

    @Test
    void defaultDecomposedCrawlLetsTheModelSurfaceEverySemanticProposition() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .build();

        assertTrue(config.isDecomposedSplitMentionsByEndpoint());
        Options options = DecomposedExtractionExecutor.optionsFrom(config);
        assertTrue(options.splitMentionsByEndpoint(),
                "the measured small-model-safe mention contract must be active without a hidden opt-in");
        assertTrue(options.splitRelationDecision(),
                "relation existence must be decided before the type ballot is exposed");
        assertTrue(options.splitClaimsByCandidate(),
                "claim comparison must receive exactly one engine-owned candidate at a time");
        assertFalse(options.splitPropositionsBySourceEvent(),
                "the proposition model must own semantic atomization by default");
        assertEquals(0, options.maxPropositions(),
                "zero is the explicit unbounded sentinel; prompt tiers must not invent a cap");
        assertEquals(PropositionAtomizationConfig.Mode.WHOLE_CHUNK,
                options.propositionAtomizationMode());
        assertEquals(PropositionAtomizationConfig.DEFAULT_MAX_EVENT_CHARS,
                options.propositionMaxEventChars());
        assertEquals(PropositionAtomizationConfig.DEFAULT_REFERENCE_CONTEXT_CHARS,
                options.propositionReferenceContextChars());
    }

    @Test
    void deterministicAtomizationRemainsAnExplicitProductionOverride() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .decomposedAtomization(PropositionAtomizationConfig.builder()
                        .mode(PropositionAtomizationConfig.Mode.AUTO)
                        .build())
                .build();

        Options options = DecomposedExtractionExecutor.optionsFrom(config);

        assertTrue(options.splitPropositionsBySourceEvent());
        assertEquals(PropositionAtomizationConfig.Mode.AUTO,
                options.propositionAtomizationMode());
    }

    @Test
    void nonPositivePropositionLimitsMeanUnboundedWhileBallotLimitsUseDefaults() {
        Options previewOptions = DecomposedExtractionExecutor.optionsFrom(null);
        assertEquals(Options.defaults().maxPropositions(), previewOptions.maxPropositions());
        assertEquals(Options.defaults().entityCandidateLimit(), previewOptions.entityCandidateLimit());
        assertEquals(Options.defaults().relationCandidateLimit(), previewOptions.relationCandidateLimit());
        assertEquals(Options.defaults().claimCandidateLimit(), previewOptions.claimCandidateLimit());
        assertTrue(previewOptions.splitMentionsByEndpoint());
        assertTrue(previewOptions.splitRelationDecision());
        assertTrue(previewOptions.splitClaimsByCandidate());
        assertFalse(previewOptions.splitPropositionsBySourceEvent());

        Options options = DecomposedExtractionExecutor.optionsFrom(GraphExtractionConfig.builder()
                .decomposedMaxPropositions(0)
                .decomposedEntityCandidateLimit(-1)
                .decomposedRelationCandidateLimit(0)
                .decomposedClaimCandidateLimit(-4)
                .decomposedAtomization(PropositionAtomizationConfig.builder()
                        .maxEventChars(1)
                        .referenceContextChars(-5)
                        .build())
                .build());

        assertEquals(0, options.maxPropositions());
        assertEquals(Options.defaults().entityCandidateLimit(), options.entityCandidateLimit());
        assertEquals(Options.defaults().relationCandidateLimit(), options.relationCandidateLimit());
        assertEquals(Options.defaults().claimCandidateLimit(), options.claimCandidateLimit());
        assertEquals(128, options.propositionMaxEventChars());
        assertEquals(0, options.propositionReferenceContextChars());
    }

    @Test
    void automaticPromptTierFollowsCommonExecutableContextWindows() {
        GraphExtractionConfig config = config();

        assertEquals(DecomposedPromptTier.COMPACT,
                DecomposedExtractionExecutor.promptProfileFrom(config,
                        new ModelCapability("2k", 2_048, 512, true)).tier());
        assertEquals(DecomposedPromptTier.STANDARD,
                DecomposedExtractionExecutor.promptProfileFrom(config,
                        new ModelCapability("4k", 4_096, 1_024, true)).tier());
        assertEquals(DecomposedPromptTier.RICH,
                DecomposedExtractionExecutor.promptProfileFrom(config,
                        new ModelCapability("8k", 8_192, 1_024, true)).tier());
        assertEquals(DecomposedPromptTier.EXPANDED,
                DecomposedExtractionExecutor.promptProfileFrom(config,
                        new ModelCapability("32k", 32_768, 1_024, true)).tier());
    }

    @Test
    void projectBudgetAndTierOverridesAreExplicitAndValidated() {
        GraphExtractionConfig budgeted = config();
        budgeted.setDecomposedPromptBudgetTokens(1_536);
        DecomposedExtractionExecutor.PromptProfile budgetProfile =
                DecomposedExtractionExecutor.promptProfileFrom(budgeted,
                        new ModelCapability("8k", 8_192, 1_024, true));
        assertEquals(DecomposedPromptTier.STANDARD, budgetProfile.tier());
        assertEquals(1_536, budgetProfile.budgetTokens());
        assertEquals("PROJECT_BUDGET", budgetProfile.source());

        GraphExtractionConfig compact = config();
        compact.setDecomposedPromptTier(DecomposedPromptTier.COMPACT);
        compact.setDecomposedPromptBudgetTokens(1_536);
        DecomposedExtractionExecutor.PromptProfile compactProfile =
                DecomposedExtractionExecutor.promptProfileFrom(compact,
                        new ModelCapability("8k", 8_192, 1_024, true));
        assertEquals(DecomposedPromptTier.COMPACT, compactProfile.tier());
        assertEquals("PROJECT_TIER_AND_BUDGET", compactProfile.source());

        GraphExtractionConfig tooRich = config();
        tooRich.setDecomposedPromptTier(DecomposedPromptTier.EXPANDED);
        assertThrows(IllegalArgumentException.class,
                () -> DecomposedExtractionExecutor.promptProfileFrom(tooRich,
                        new ModelCapability("4k", 4_096, 1_024, true)));

        GraphExtractionConfig tooLarge = config();
        tooLarge.setDecomposedPromptBudgetTokens(3_000);
        assertThrows(IllegalArgumentException.class,
                () -> DecomposedExtractionExecutor.promptProfileFrom(tooLarge,
                        new ModelCapability("4k", 4_096, 1_024, true)));
    }

    @Test
    void tiersBoundBallotsAndContextWithoutSuppressingExplicitProposalRecall() {
        GraphExtractionConfig wide = GraphExtractionConfig.builder()
                .decomposedMaxPropositions(40)
                .decomposedEntityCandidateLimit(40)
                .decomposedRelationCandidateLimit(40)
                .decomposedClaimCandidateLimit(40)
                .decomposedAtomization(PropositionAtomizationConfig.builder()
                        .maxEventChars(5_000)
                        .referenceContextChars(5_000)
                        .build())
                .build();
        DecomposedExtractionExecutor.PromptProfile compact =
                DecomposedExtractionExecutor.promptProfileFrom(wide,
                        new ModelCapability("2k", 2_048, 512, true));
        Options options = DecomposedExtractionExecutor.optionsFrom(wide, compact);

        assertEquals(40, options.maxPropositions(),
                "prompt tiers must not silently replace the project's proposition policy");
        assertEquals(4, options.entityCandidateLimit());
        assertEquals(6, options.relationCandidateLimit());
        assertEquals(2, options.claimCandidateLimit());
        assertEquals(480, options.propositionMaxEventChars());
        assertEquals(320, options.propositionReferenceContextChars());
        assertTrue(options.requireEvidenceSpans(),
                "context tiers must never weaken source-grounding validation");
    }

    @Test
    void resolvedPromptProfileIsPinnedIntoEveryDecisionPass() {
        ScriptedCaller caller = happyPath();
        new DecomposedExtractionExecutor().extract(
                SOURCE, "chunk-1", "doc-1", config(), schema(),
                GraphExtractionValidationPolicy.defaults(), graph(), caller, null,
                new ModelCapability("lfm", 4_096, 1_024, true));

        String prompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS);
        assertTrue(prompt.contains("- promptTier: STANDARD"));
        assertTrue(prompt.contains("- promptBudgetTokens: 2560"));
        assertTrue(prompt.contains("- promptBudgetSource: MODEL_CAPABILITY"));
        assertTrue(prompt.contains("- executableContextTokens: 4096"));
    }

    // ── the seam contract ─────────────────────────────────────────────────

    @Test
    void emitsTheSameSchemaShapedJsonTheSinglePromptPathProduces() {
        ExtractionResult result = parse(run(config(), graph(), happyPath()));

        assertEquals(2, result.entities().size());
        assertEquals(1, result.relations().size());
        ExtractedRelation relation = result.relations().get(0);
        assertEquals("ACQUIRED", relation.type());
        assertEquals("ent-acme", relation.source());
        assertEquals("ent-initech", relation.target());
        assertEquals("2019", relation.occurredAt());
        assertEquals(0.9d, relation.confidence(), 1e-9);
        assertEquals("true", relation.properties().get(Props.ASSERTABLE));
    }

    @Test
    void reusedIdsAreTheInRunGraphsOwnIdsSoTheMergeStepLinesUp() {
        ExtractionResult result = parse(run(config(), graph(), happyPath()));

        List<String> ids = result.entities().stream().map(e -> e.id()).sorted().toList();
        assertEquals(List.of("ent-acme", "ent-initech"), ids);
    }

    @Test
    void theIdentityBallotComesFromTheInRunGraphWithoutExposingOpaqueIds() {
        ScriptedCaller caller = happyPath();
        run(config(), graph(), caller);

        String prompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS);
        assertFalse(prompt.contains("ent-acme"),
                "production ballots keep opaque graph ids engine-owned");
        assertTrue(prompt.contains("[1] name=Acme Corporation"));
        assertTrue(prompt.contains("graph-7"), "the graph revision is pinned into the prompt");
    }

    @Test
    void schemaPatternEndpointTypesPopulateTheBoundedProvisionalTypeBallot() {
        ScriptedCaller caller = happyPath();
        run(config(), graph(), caller);

        String prompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS);

        assertTrue(prompt.contains("PROVISIONAL ENTITY TYPE BALLOT"));
        assertTrue(prompt.contains("- [1] ORGANIZATION"));
        assertTrue(prompt.contains("- [2] PERSON"),
                "pattern-only endpoint labels are active schema type declarations");
    }

    @Test
    void incrementalTaskGraphRevisionOverridesTheStableGraphIdInDecisionPrompts() {
        ScriptedCaller caller = happyPath();
        ExtractionTaskContext task = new ExtractionTaskContext(
                "task-1", "partition-acme", "corpus-v1", List.of("Acme Corporation", "Initech"),
                "chunk-1", "SOURCE_EVENT", "bounded source event", 1.0,
                "graph-7:2:1", "bounded graph state");

        new DecomposedExtractionExecutor().extract(
                SOURCE, "chunk-1", "doc-1", config(), schema(),
                GraphExtractionValidationPolicy.defaults(), graph(), caller, task);

        String prompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS);
        assertTrue(prompt.contains("- graph: graph-7:2:1"),
                "prompt identity must advance with the candidate snapshot");
        assertFalse(prompt.contains("- graph: graph-7\n"),
                "the stable graph id must not replace the incremental revision");
    }

    @Test
    void anEmptyGraphOffersNoIdentityCandidatesAtAll() {
        ScriptedCaller caller = happyPath();
        run(config(), new Graph(), caller);

        String prompt = caller.prompt(ExtractionPassPrompts.PASS_MENTIONS);
        assertFalse(prompt.contains("ent-acme"),
                "nothing may be offered that the run has not actually seen");
    }

    // ── vocabulary ────────────────────────────────────────────────────────

    @Test
    void theRelationVocabularyIsTheSchemasAndOnlyForAdmissibleEndpoints() {
        ScriptedCaller caller = happyPath();
        run(config(), graph(), caller);

        String existencePrompt = caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 0);
        String prompt = caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 1);
        assertFalse(existencePrompt.contains("ACQUIRED"),
                "the existence gate must not be biased by a relation-type ballot");
        assertTrue(prompt.contains("ACQUIRED"));
        assertTrue(prompt.contains("PARTNERED_WITH"), "config types widen the vocabulary");
        assertFalse(prompt.contains("WORKS_AT"),
                "(PERSON)-[:WORKS_AT]->(ORGANIZATION) cannot hold between two organizations, so "
                        + "offering it would set the model up to fail validation");
    }

    @Test
    void aTypeThatWasNeverOfferedIsRecordedAsASchemaGapNotAsAFact() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_RELATIONS,
                RELATION_ASSERTED, """
                        {"selection":{"candidateOrdinal":null,"schemaGap":true,"confidence":0.95,
                          "qualifiers":{},"reason":"none of the offered types matches"}}
                        """);
        GraphExtractionConfig partneredOnly = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .relationshipTypes(new ArrayList<>(List.of("PARTNERED_WITH")))
                .modelName("test-model")
                .build();
        GraphSchema partneredOnlySchema = new GraphSchema();
        partneredOnlySchema.setRelationshipTypes(new ArrayList<>(List.of(
                new RelationshipType("PARTNERED_WITH", "two organizations formed a partnership", null))));
        partneredOnlySchema.setPatterns(new ArrayList<>(List.of(
                "(ORGANIZATION)-[:PARTNERED_WITH]->(ORGANIZATION)")));

        DecomposedExtractionExecutor.Result result = new DecomposedExtractionExecutor().extract(
                SOURCE, "chunk-1", "doc-1", partneredOnly, partneredOnlySchema,
                GraphExtractionValidationPolicy.defaults(), graph(), caller);
        ExtractionResult parsed = parse(result);

        ExtractedRelation relation = parsed.relations().get(0);
        assertEquals("true", relation.properties().get(Props.SCHEMA_GAP));
        assertFalse("true".equals(relation.properties().get(Props.ASSERTABLE)),
                "the model does not get to widen the ontology by asserting a new type");
        assertTrue(result.summary().contains("oov:1"), result.summary());
    }

    // ── claims ────────────────────────────────────────────────────────────

    @Test
    void anExistingClaimOverTheSameEndpointsIsPutOnTheBallotAndTheDecisionIsCarried() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS, """
                {"comparison":{"relationship":"SAME","confidence":0.75,
                  "reason":"same acquisition, restated"}}
                """);

        ExtractionResult result = parse(run(config(), graph(acquisitionEdge()), caller));

        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_CLAIMS));
        assertFalse(caller.prompt(ExtractionPassPrompts.PASS_CLAIMS)
                        .contains("ACQUIRED(ent-acme,ent-initech)"),
                "engine-owned atom keys must never be exposed for the model to copy or invent");
        Map<String, String> properties = result.relations().get(0).properties();
        assertEquals("ADD_EVIDENCE", properties.get(Props.CLAIM_OPERATION));
        assertEquals("ACQUIRED(ent-acme,ent-initech)", properties.get(Props.CLAIM_ATOM_KEY));
    }

    @Test
    void anUncertainClaimComparisonWithholdsTheRelationWithoutLosingEntities() {
        ScriptedCaller caller = happyPath().on(ExtractionPassPrompts.PASS_CLAIMS, """
                {"comparison":{"relationship":"UNCERTAIN","confidence":0.51,
                  "reason":"not enough detail to decide whether this is the same acquisition"}}
                """);

        DecomposedExtractionExecutor.Result result = run(config(), graph(acquisitionEdge()), caller);
        ExtractionResult parsed = parse(result);

        assertTrue(parsed.relations().isEmpty(), parsed.relations().toString());
        assertEquals(2, parsed.entities().size(), "the resolved entities are still real");
        assertTrue(result.summary().contains("abstain:"), result.summary());
    }

    @Test
    void turningClaimMatchingOffSkipsThePassEvenWhenClaimsExist() {
        GraphExtractionConfig config = config();
        config.setDecomposedClaimMatching(false);
        ScriptedCaller caller = happyPath();

        ExtractionResult result = parse(run(config, graph(acquisitionEdge()), caller));

        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_CLAIMS));
        assertEquals(1, result.relations().size(), "the relation is still extracted");
    }

    @Test
    void withoutAnyExistingClaimTheClaimPassIsNotCalled() {
        ScriptedCaller caller = happyPath();
        run(config(), graph(), caller);

        assertEquals(0, caller.calls(ExtractionPassPrompts.PASS_CLAIMS),
                "an empty ballot has only one possible answer, so asking is wasted latency");
    }

    // ── degenerate inputs ─────────────────────────────────────────────────

    @Test
    void blankChunkTextIsNotSentToAnyModel() {
        ScriptedCaller caller = happyPath();
        DecomposedExtractionExecutor executor = new DecomposedExtractionExecutor();

        for (String text : new String[]{null, "", "   "}) {
            DecomposedExtractionExecutor.Result result = executor.extract(
                    text, "chunk-1", "doc-1", config(), schema(),
                    GraphExtractionValidationPolicy.defaults(), graph(), caller);
            assertFalse(result.usable());
            assertEquals("no outcome", result.summary());
        }
        assertEquals(0, caller.total());
    }

    @Test
    void aChunkWithNoPropositionsYieldsAnEmptyButValidResult() {
        ScriptedCaller caller = happyPath()
                .on(ExtractionPassPrompts.PASS_PROPOSITIONS, "{\"propositions\":[]}");

        ExtractionResult result = parse(run(config(), graph(), caller));

        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void aFailedDispatchIsReportedUnusableRatherThanAsAnEmptyExtraction() {
        // Left usable-and-empty, a transport failure would be recorded as "this chunk had no
        // facts". Reporting it unusable puts the caller back on its normal retry path.
        ScriptedCaller caller = happyPath()
                .failOn(ExtractionPassPrompts.PASS_PROPOSITIONS, "model unreachable");

        DecomposedExtractionExecutor.Result result = run(config(), graph(), caller);

        assertFalse(result.usable());
        assertNull(result.json());
        assertEquals(1, caller.calls(ExtractionPassPrompts.PASS_PROPOSITIONS),
                "default semantic atomization dispatches the production chunk once");
        assertTrue(result.summary().contains("fail:1"), result.summary());
    }

    @Test
    void aLaterPassFailingStillLeavesTheEarlierWorkProjectable() {
        ScriptedCaller caller = happyPath()
                .failOn(ExtractionPassPrompts.PASS_RELATIONS, "model unreachable");

        ExtractionResult result = parse(run(config(), graph(), caller));

        assertEquals(2, result.entities().size(), "resolved entities survive a failed relation pass");
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void anAbsentSchemaAndPolicyStillRunWithTheConfiguredTypes() {
        ScriptedCaller caller = happyPath();

        DecomposedExtractionExecutor.Result result = new DecomposedExtractionExecutor().extract(
                SOURCE, "chunk-1", "doc-1", config(), null, null, graph(), caller);

        ExtractionResult parsed = parse(result);
        assertEquals("ACQUIRED", parsed.relations().get(0).type());
        assertTrue(caller.prompt(ExtractionPassPrompts.PASS_RELATIONS, 1)
                .contains("PARTNERED_WITH"));
    }

    @Test
    void aNullGraphIsToleratedAndSimplyOffersNothing() {
        ScriptedCaller caller = happyPath();

        DecomposedExtractionExecutor.Result result = new DecomposedExtractionExecutor().extract(
                SOURCE, "chunk-1", "doc-1", config(), schema(),
                GraphExtractionValidationPolicy.defaults(), null, caller);

        // Nothing to reuse, so the model's REUSE answers are rejected and no entity or relation is
        // written from an unavailable ballot ordinal.
        ExtractionResult parsed = parse(result);
        assertTrue(parsed.entities().isEmpty(), result.outcome().notes().toString());
        assertTrue(parsed.relations().isEmpty(), result.outcome().notes().toString());
        assertTrue(result.outcome().stats().stream()
                        .anyMatch(stats -> stats.outOfVocabulary() > 0),
                result.outcome().notes().toString());
    }

    @Test
    void aCallerThatThrowsIsContainedByThePipeline() {
        LlmCaller exploding = (passId, prompt) -> {
            throw new IllegalStateException("boom");
        };

        DecomposedExtractionExecutor.Result result = run(config(), graph(), exploding);

        assertFalse(result.usable(), "a totally dead dispatcher must not look like an empty chunk");
        assertThrows(AssertionError.class, () -> parse(result));
    }

    /** A model stand-in: fixed answers per pass, recording every prompt it was given. */
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
            return queued.size() == 1 ? queued.peek() : queued.poll();
        }

        int calls(String passId) {
            return prompts.getOrDefault(passId, List.of()).size();
        }

        int total() {
            return prompts.values().stream().mapToInt(List::size).sum();
        }

        String prompt(String passId) {
            return prompt(passId, 0);
        }

        String prompt(String passId, int index) {
            List<String> seen = prompts.getOrDefault(passId, List.of());
            assertTrue(index >= 0 && index < seen.size(),
                    "pass " + passId + " was not called at index " + index);
            return seen.get(index);
        }
    }
}
