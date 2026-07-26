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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one place the crawl changes: instead of one prompt, DECOMPOSED mode runs a sequence of
 * bounded passes and hands back the same schema-shaped JSON. These tests drive the orchestrator's
 * seam directly — with the real dispatcher mocked — to prove the substitution is transparent to
 * everything downstream (JSON extraction, validation, {@code toGraph}, merge, persistence).
 */
class GraphExtractionOrchestratorDecomposedSeamTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Analysts believe the deal was overpriced.";

    private static final String PROPOSITIONS = """
            {"propositions":[{"id":"p1","text":"Acme Corp acquired Initech in 2019",
              "subject":"Acme Corp","predicate":"acquired","object":"Initech",
              "polarity":"AFFIRMED","modality":"FACTUAL","timeExpression":"2019",
              "evidence":{"quote":"Acme Corp acquired Initech in 2019","role":"DIRECT_SUPPORT"}}]}
            """;

    private static final String MENTIONS = """
            {"mentions":[
              {"mentionText":"Acme Corp","mentionRole":"SUBJECT","operation":"REUSE_ENTITY",
               "selectedEntityId":"ent-acme","confidence":0.93,
               "evidence":{"quote":"Acme Corp"}},
              {"mentionText":"Initech","mentionRole":"OBJECT","operation":"REUSE_ENTITY",
               "selectedEntityId":"ent-initech","confidence":0.88,
               "evidence":{"quote":"Initech"}}]}
            """;

    private static final String EPISTEMIC = """
            {"classification":{"speechAct":"ASSERTION","certainty":0.95,"reason":"stated directly"}}
            """;

    private static final String RELATION = """
            {"relation":{"operation":"CREATE_CLAIM","type":"ACQUIRED","confidence":0.9,
              "occurredAt":"2019","reason":"explicit acquisition verb",
              "evidence":{"quote":"acquired Initech"}}}
            """;

    // ── fixtures ──────────────────────────────────────────────────────────

    private static Entity entity(String id, String title) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType("ORGANIZATION");
        return entity;
    }

    private static Graph graph() {
        Graph graph = new Graph();
        graph.setId("graph-7");
        graph.setEntities(new ArrayList<>(List.of(
                entity("ent-acme", "Acme Corporation"),
                entity("ent-initech", "Initech"))));
        graph.setRelationships(new ArrayList<>());
        return graph;
    }

    private static GraphExtractionConfig decomposedConfig() {
        return GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .entityTypes(new ArrayList<>(List.of("ORGANIZATION")))
                .relationshipTypes(new ArrayList<>(List.of("ACQUIRED", "PARTNERED_WITH")))
                .validationPolicy(GraphExtractionValidationPolicy.builder()
                        .relationPatterns(new ArrayList<>(
                                List.of("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")))
                        .build())
                .build();
    }

    private static Document chunk() {
        return new Document(SOURCE, Map.of(GraphConstants.META_SOURCE_PATH, "/corpus/acme.txt"));
    }

    private static UnifiedCrawlJob job() {
        return UnifiedCrawlJob.builder().jobId("job-1").build();
    }

    /** Routes each pass prompt to its scripted answer by the output shape the prompt asks for. */
    private static String answerFor(String prompt) {
        if (prompt.contains("{\"propositions\":[{")) {
            return PROPOSITIONS;
        }
        if (prompt.contains("{\"mentions\":[{")) {
            return MENTIONS;
        }
        if (prompt.contains("{\"classification\":{")) {
            return EPISTEMIC;
        }
        if (prompt.contains("{\"relation\":{")) {
            return RELATION;
        }
        return null;
    }

    private record Harness(GraphExtractionOrchestrator orchestrator,
                           CrawlLlmDispatcher dispatcher,
                           List<String> prompts) {
    }

    private static Harness harness() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        List<String> prompts = new CopyOnWriteArrayList<>();
        when(dispatcher.promptWithCapacityFallback(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String prompt = invocation.getArgument(0);
                    prompts.add(prompt);
                    return answerFor(prompt);
                });
        orchestrator.llmDispatcher = dispatcher;
        return new Harness(orchestrator, dispatcher, prompts);
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void theSeamReturnsTheSameSchemaShapedJsonTheSinglePromptPathWouldHave() throws Exception {
        Harness harness = harness();

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        assertNotNull(json, "the seam must produce a response the existing parse path can read");
        ExtractionResult result = GraphExtractionValidator.fromJson(json);
        assertEquals(2, result.entities().size());
        assertEquals(1, result.relations().size());
        assertEquals("ACQUIRED", result.relations().get(0).type());
        assertEquals("ent-acme", result.relations().get(0).source());
        assertEquals("ent-initech", result.relations().get(0).target());
    }

    @Test
    void everyPassGoesThroughTheCrawlsOwnDispatcherOnTheLlmLane() {
        Harness harness = harness();
        UnifiedCrawlJob job = job();

        harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job);

        // Routing, capacity fallback, transcripts and quota accounting stay exactly where they
        // were: the passes are extra calls on the same dispatcher, not a new egress path.
        verify(harness.dispatcher(), org.mockito.Mockito.atLeast(4))
                .promptWithCapacityFallback(anyString(), eq("llm"), eq(job));
        assertTrue(harness.prompts().size() >= 4, "expected one call per pass");
        assertTrue(harness.prompts().stream().allMatch(p -> p.contains("OUTPUT FORMAT")),
                "every dispatched prompt must be a bounded pass prompt, not the one-shot prompt");
    }

    @Test
    void eachPromptIsPinnedToTheChunkDocumentAndGraphRevision() {
        Harness harness = harness();

        harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        for (String prompt : harness.prompts()) {
            assertTrue(prompt.contains("- document: /corpus/acme.txt"), prompt);
            assertTrue(prompt.contains("- graph: graph-7"), prompt);
        }
    }

    @Test
    void theInRunGraphIsWhatTheIdentityPassGetsToChooseFrom() {
        Harness harness = harness();

        harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        String mentionPrompt = harness.prompts().stream()
                .filter(p -> p.contains("{\"mentions\":[{"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the identity pass was never dispatched"));
        assertTrue(mentionPrompt.contains("ent-acme"));
        assertTrue(mentionPrompt.contains("Acme Corporation"));
    }

    @Test
    void theRelationPassOnlySeesTypesTheProjectsOwnSignaturesAdmit() {
        Harness harness = harness();

        harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        String relationPrompt = harness.prompts().stream()
                .filter(p -> p.contains("{\"relation\":{"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the relation pass was never dispatched"));
        assertTrue(relationPrompt.contains("ACQUIRED"));
        assertTrue(relationPrompt.contains("PARTNERED_WITH"));
    }

    @Test
    void aDeadDispatcherIsReportedAsNoResponseSoTheExistingRetryPathApplies() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        when(dispatcher.promptWithCapacityFallback(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("backend unreachable"));
        orchestrator.llmDispatcher = dispatcher;

        String json = orchestrator.extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        assertNull(json, "a null response is what the caller already treats as a bad LLM response");
    }

    @Test
    void aModelThatAnswersNothingYieldsNoResponseRatherThanAnEmptyExtraction() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        when(dispatcher.promptWithCapacityFallback(anyString(), anyString(), any())).thenReturn("");
        orchestrator.llmDispatcher = dispatcher;

        String json = orchestrator.extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        assertNull(json);
    }

    @Test
    void aChunkWithNoIdentifiableFactsIsAnEmptyResultNotAFailure() throws Exception {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        when(dispatcher.promptWithCapacityFallback(anyString(), anyString(), any()))
                .thenReturn("{\"propositions\":[]}");
        orchestrator.llmDispatcher = dispatcher;

        String json = orchestrator.extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), job());

        assertNotNull(json);
        ExtractionResult result = GraphExtractionValidator.fromJson(json);
        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void aDocumentWithoutASourcePathStillRunsAndFallsBackToTheChunkId() {
        Harness harness = harness();
        Document untracked = new Document(SOURCE, Map.of());

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, untracked, decomposedConfig(), graph(), job());

        assertNotNull(json);
        assertFalse(harness.prompts().isEmpty());
    }

    @Test
    void aJoblessInvocationDoesNotThrow() {
        Harness harness = harness();

        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), decomposedConfig(), graph(), null);

        assertNotNull(json, "the seam is usable outside a running job, e.g. from a preview");
    }

    @Test
    void aConfiglessInvocationIsContainedRatherThanPropagated() {
        Harness harness = harness();

        // buildGraphSchema dereferences the config; the seam swallows that rather than failing
        // the chunk in a way the caller has no handler for.
        String json = harness.orchestrator().extractViaDecomposedPasses(
                SOURCE, chunk(), null, graph(), job());

        assertNull(json);
    }
}
