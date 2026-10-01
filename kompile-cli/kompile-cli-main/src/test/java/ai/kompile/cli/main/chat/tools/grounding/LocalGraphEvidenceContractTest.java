package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tiny archive fixtures: no indexing, provider calls, or learning subprocess is needed. */
class LocalGraphEvidenceContractTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);

    private ToolContext context() {
        return new ToolContext("evidence-contract", AgentConfig.builder("evidence-contract")
                .enabledTools(Set.of("*")).build(), new PermissionService(), root, new ToolRegistry(mapper));
    }

    private ObjectNode request() { return mapper.createObjectNode().put("knowledgeBase", "fixture"); }

    private UnifiedGraph graph() {
        UnifiedGraph graph = new UnifiedGraph().graphId("fixture");
        for (String id : Set.of("a", "b", "c", "d")) {
            graph.addEntity(GraphEntity.builder(id).type("CLASS").label(id).build());
        }
        return graph;
    }

    private Path archive() { return root.resolve("data/crawls/fixture/graph.kgraph"); }

    private void save(UnifiedGraph graph) throws Exception { save(graph, true); }

    /** A compact archive takes the journal path; a portable one is loaded whole for its first mutation. */
    private void save(UnifiedGraph graph, boolean compact) throws Exception {
        Files.createDirectories(archive().getParent());
        if (compact) graph.saveCompact(archive());
        else graph.save(archive());
    }

    private List<GraphRelation> storedEdges(String from, String to) throws Exception {
        return UnifiedGraph.load(archive()).relations().stream()
                .filter(relation -> relation.sourceId().equals(from) && relation.targetId().equals(to))
                .toList();
    }

    private void edge(UnifiedGraph graph, String id, String from, String to, double confidence) {
        graph.addRelation(GraphRelation.builder(id, from, to).type("CALLS")
                .confidence(confidence).directed(true).build());
    }

    private JsonNode execute(String tool, ObjectNode request) throws Exception {
        ToolResult result = backend.executeOfflineTool(tool, request, context());
        assertFalse(result.isError(), result.getOutput());
        return mapper.readTree(result.getOutput());
    }

    @Test void boundedReasoningResolvesNamesFromJournalAndCompactedArchive() throws Exception {
        save(graph());
        execute("ask_graph_assert", request().put("atom", "RELATED_TO(Alex, Morgan)").put("value", 1.0));
        ObjectNode query = request().put("operation", "VERIFY").put("entityId", "Alex").put("targetId", "Morgan");
        query.putArray("relationTypes").add("RELATED_TO");
        assertEquals("SUPPORTED", new LocalProjectGraphBackend(mapper).reasoningQuery(query, context())
                .path("status").asText());
        UnifiedGraphMutationJournal.compact(archive());
        assertEquals("SUPPORTED", new LocalProjectGraphBackend(mapper).reasoningQuery(query, context())
                .path("status").asText());
    }

    @Test void boundedReasoningResolvesFqnAndRejectsAmbiguousLabels() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("first").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "one.Service").build());
        graph.addEntity(GraphEntity.builder("second").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "two.Service").build());
        edge(graph, "edge", "first", "b", 1.0);
        save(graph);
        ObjectNode query = request().put("operation", "VERIFY").put("entityId", "one.Service").put("targetId", "b");
        query.putArray("relationTypes").add("CALLS");
        assertEquals("SUPPORTED", backend.reasoningQuery(query, context()).path("status").asText());
        query.put("entityId", "Service");
        assertThrows(IllegalArgumentException.class, () -> backend.reasoningQuery(query, context()));
    }

    @Test void claimUsesVerificationWithoutInventingFusion() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(1.0)
                .attribute("_kompileProjectionOwner", "local-code-index").build());
        edge(graph, "bc", "b", "c", 0.4);
        edge(graph, "cd", "c", "d", 0.0);
        save(graph);
        for (String[] pair : new String[][]{{"a", "b"}, {"b", "c"}, {"c", "d"}, {"d", "a"}}) {
            JsonNode verify = execute("ask_graph_verify", request()
                    .put("atom", "CALLS(" + pair[0] + ", " + pair[1] + ")"));
            JsonNode claim = execute("ask_graph_claim", request().put("subject", pair[0])
                    .put("predicate", "CALLS").put("object", pair[1]));
            assertEquals(verify.path("verdict"), claim.path("verdict"));
            assertEquals(verify.path("confidence"), claim.path("confidence"));
            assertTrue(claim.path("fusedScore").isNull());
            assertFalse(claim.path("fusionPerformed").asBoolean());
        }
    }

    @Test void unsupportedAnalysisDoesNotCreateFakeRunsOrReadAGraph() throws Exception {
        for (String action : Set.of("scenarios", "create_run", "runs", "run", "step", "play",
                "pause", "promote", "delete", "reason", "ground_truth")) {
            ToolResult result = backend.executeOfflineTool("graph_simulate", request().put("action", action)
                    .put("scenario_id", "fixture").put("run_id", "fake"), context());
            assertTrue(result.isError(), action);
            assertTrue(result.getOutput().contains("not supported"), result.getOutput());
        }
        for (String action : Set.of("discover", "discover_all", "entailment", "conformance",
                "declare", "bpmn", "suggestions", "suggestion")) {
            ToolResult result = backend.executeOfflineTool("process_mining", request().put("action", action), context());
            assertTrue(result.isError(), action);
            assertTrue(result.getOutput().contains("not supported"), result.getOutput());
        }
        assertFalse(Files.exists(root.resolve("data")));
    }

    @Test void reviseRejectsBeforeMutationAndRetractionDisclosesLimits() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 1.0);
        save(graph);
        ToolResult revision = backend.executeOfflineTool("ask_graph_retract", request()
                .put("atomKey", "CALLS(a, b)").put("mode", "revise"), context());
        assertTrue(revision.isError());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"))
                .path("verdict").asText());
        JsonNode retraction = execute("ask_graph_retract", request().put("atomKey", "CALLS(a, b)"));
        assertTrue(retraction.path("dependentAtomsUnsupported").isNull());
        assertFalse(retraction.path("dependencyAnalysisPerformed").asBoolean());
        assertFalse(retraction.path("cascadeTriggered").asBoolean());
        assertTrue(retraction.path("caveat").asText().startsWith("Stored edges matching the fact"),
                retraction.toString());
        assertFalse(retraction.has("contradictionCheckPerformed"));
    }

    @Test void synthesisIdentifiesCandidatesAsUnverifiedRetrieval() throws Exception {
        save(graph());
        JsonNode result = execute("ask_graph_synthesize", request().put("query", "CLASS"));
        assertEquals("RETRIEVAL_ONLY", result.path("status").asText());
        assertFalse(result.path("verificationPerformed").asBoolean());
        assertFalse(result.path("answers").isEmpty());
        assertTrue(result.path("answers").get(0).has("retrievalScore"));
        assertFalse(result.path("answers").get(0).has("likelihood"));
    }

    @Test void verifyHonorsThresholdAndDoesNotClaimHistoricalSupport() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.4);
        save(graph);
        assertEquals("UNKNOWN", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"))
                .path("verdict").asText());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)")
                .put("minConfidence", 0.3)).path("verdict").asText());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "calls(a, b)")
                .put("minConfidence", 0.3)).path("verdict").asText());
        for (String tool : Set.of("ask_graph_verify", "ask_graph_query")) {
            ToolResult result = backend.executeOfflineTool(tool,
                    request().put("asOf", "2020-01-01T00:00:00Z").put("atom", "CALLS(a, b)"), context());
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("asOf"), result.getOutput());
        }
    }

    @Test void predicateSpellingsMatchStoredTypesAndFindThePosteriorsLearnedForThem() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        graph.addRelation(GraphRelation.builder("bc", "b", "c").type("calls-into")
                .confidence(0.8).directed(true).build());
        // Learning keys each target by the stored relation's spelling, not the caller's.
        graph.putModel("reasoning/consensus-targets.bin",
                new HashMap<>(Map.of("CALLS(a,b)", 0.99, "calls_into(b,c)", 0.7)));
        save(graph);
        for (String predicate : new String[]{"CALLS", "calls", "Calls"}) {
            JsonNode result = execute("ask_graph_verify", request().put("atom", predicate + "(a, b)"));
            assertEquals("SUPPORTED", result.path("verdict").asText(), predicate);
            assertEquals("CALLS(a, b)", result.path("evidenceAtoms").get(0).asText(), predicate);
            assertEquals(0.99, result.path("learnedPosterior").asDouble(), 1e-9, predicate);
        }
        JsonNode camel = execute("ask_graph_verify", request().put("atom", "callsInto(b, c)"));
        assertEquals("SUPPORTED", camel.path("verdict").asText());
        assertEquals(0.7, camel.path("learnedPosterior").asDouble(), 1e-9);
        ObjectNode query = request();
        query.putArray("conjuncts").addObject().put("predicate", "CallsInto")
                .putArray("args").add("?source").add("c");
        assertEquals("b", execute("ask_graph_query", query).path("bindings").get(0)
                .path("variables").path("source").asText());
    }

    @Test void unknownPredicateIsToldApartFromMissingFactsAndNamesSpellingsTheGraphUses() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        save(graph);
        JsonNode misspelled = execute("ask_graph_verify", request().put("atom", "CALL(a, b)"));
        assertEquals("UNKNOWN", misspelled.path("verdict").asText());
        assertEquals("unknown-predicate", misspelled.path("unknownReason").asText());
        assertEquals("[\"CALLS\"]", misspelled.path("didYouMean").toString());
        JsonNode unrelated = execute("ask_graph_verify", request().put("atom", "employs(a, b)"));
        assertEquals("unknown-predicate", unrelated.path("unknownReason").asText());
        assertTrue(unrelated.path("didYouMean").isArray());
        assertTrue(unrelated.path("didYouMean").isEmpty());
        JsonNode missingFact = execute("ask_graph_verify", request().put("atom", "calls(b, a)"));
        assertEquals("no-evidence", missingFact.path("unknownReason").asText());
        assertFalse(missingFact.has("didYouMean"));
        // A missing entity is the reason; the misspelled predicate is still named.
        JsonNode missingEntity = execute("ask_graph_verify", request().put("atom", "CALL(a, nobody)"));
        assertEquals("entity-not-in-graph", missingEntity.path("unknownReason").asText());
        assertEquals("[\"CALLS\"]", missingEntity.path("didYouMean").toString());
    }

    @Test void emptyQueryNamesEachPredicateTheGraphNeverUses() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        save(graph);
        ObjectNode misspelled = request();
        misspelled.putArray("conjuncts").addObject().put("predicate", "call")
                .putArray("args").add("a").add("?target");
        JsonNode unknown = execute("ask_graph_query", misspelled).path("unknownPredicates");
        assertEquals(1, unknown.size());
        assertEquals("call", unknown.get(0).path("predicate").asText());
        assertEquals("[\"CALLS\"]", unknown.get(0).path("didYouMean").toString());
        ObjectNode chain = request();
        var conjuncts = chain.putArray("conjuncts");
        conjuncts.addObject().put("predicate", "calls").putArray("args").add("a").add("?mid");
        conjuncts.addObject().put("predicate", "locatedIn").putArray("args").add("?mid").add("?place");
        JsonNode chained = execute("ask_graph_query", chain);
        assertEquals(0, chained.path("bindings").size());
        assertEquals(1, chained.path("unknownPredicates").size());
        assertEquals("locatedIn", chained.path("unknownPredicates").get(0).path("predicate").asText());
        ObjectNode unmatched = request();
        unmatched.putArray("conjuncts").addObject().put("predicate", "CALLS")
                .putArray("args").add("b").add("?target");
        JsonNode noFacts = execute("ask_graph_query", unmatched);
        assertEquals(0, noFacts.path("bindings").size());
        assertFalse(noFacts.has("unknownPredicates"));
    }

    @Test void retractionMatchesAnyPredicateSpellingOnTheCompactArchive() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        edge(graph, "bc", "b", "c", 0.8);
        save(graph);
        assertEquals("RETRACTED", execute("ask_graph_retract", request().put("atomKey", "calls(a, b)"))
                .path("status").asText());
        JsonNode retracted = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertEquals("UNKNOWN", retracted.path("verdict").asText());
        assertEquals("no-evidence", retracted.path("unknownReason").asText());
        assertTrue(retracted.path("meta").path("stale").asBoolean());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(b, c)"))
                .path("verdict").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void retractingATypesLastEdgeLeavesTheFactMissingNotThePredicateUnknown(boolean compact) throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        save(graph, compact);
        execute("ask_graph_retract", request().put("atomKey", "CALLS(a, b)"));
        assertTrue(UnifiedGraph.load(archive()).retractedRelationTypes().contains("CALLS"));
        for (int pass = 0; pass < 2; pass++) {
            JsonNode verified = execute("ask_graph_verify", request().put("atom", "calls(a, b)"));
            assertEquals("UNKNOWN", verified.path("verdict").asText(), verified.toString());
            assertEquals("no-evidence", verified.path("unknownReason").asText(), verified.toString());
            assertFalse(verified.has("didYouMean"), verified.toString());
            ObjectNode query = request();
            query.putArray("conjuncts").addObject().put("predicate", "Calls")
                    .putArray("args").add("a").add("?target");
            JsonNode queried = execute("ask_graph_query", query);
            assertEquals(0, queried.path("bindings").size());
            assertFalse(queried.has("unknownPredicates"), queried.toString());
            // The vocabulary survives folding the journal into the archive.
            if (compact && pass == 0) assertTrue(UnifiedGraphMutationJournal.compact(archive()));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void reassertingUnderAnotherSpellingLeavesOneEdge(boolean compact) throws Exception {
        save(graph(), compact);
        execute("ask_graph_assert", request().put("atom", "worksFor(a, b)").put("value", 0.9));
        JsonNode second = execute("ask_graph_assert", request().put("atom", "WORKS_FOR(a, b)").put("value", 0.6));
        assertFalse(second.has("replacedAtoms"), second.toString());
        List<GraphRelation> stored = storedEdges("a", "b");
        assertEquals(1, stored.size(), stored.toString());
        assertEquals("WORKS_FOR", stored.get(0).type());
        assertEquals(0.6, stored.get(0).confidence(), 1e-9);
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "works_for(a, b)")
                .put("minConfidence", 0.5)).path("verdict").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void anEarlierAssertionUnderAnotherSpellingIsReplacedButCrawledEdgesAreKept(boolean compact) throws Exception {
        UnifiedGraph graph = graph();
        // Stored before assertion ids were keyed by the predicate key, so its id differs.
        graph.addRelation(GraphRelation.builder("asserted:legacy", "a", "b").type("worksFor")
                .confidence(0.9).directed(true).attribute("source", "stdio-local").build());
        graph.addRelation(GraphRelation.builder("crawled", "a", "b").type("works_for").confidence(0.7)
                .directed(true).attribute("provenance", "unified-corpus-extraction").build());
        save(graph, compact);
        JsonNode asserted = execute("ask_graph_assert", request().put("atom", "WORKS_FOR(a, b)").put("value", 0.6));
        JsonNode replaced = asserted.path("replacedAtoms");
        assertEquals(1, replaced.size(), asserted.toString());
        assertEquals("worksFor(a, b)", replaced.get(0).path("atom").asText());
        assertEquals("asserted:legacy", replaced.get(0).path("relationId").asText());
        assertEquals("stdio-local", replaced.get(0).path("origin").asText());
        assertTrue(replaced.get(0).path("asserted").asBoolean());
        assertTrue(asserted.path("caveat").asText().contains("replacedAtoms"), asserted.toString());
        List<GraphRelation> stored = storedEdges("a", "b");
        assertEquals(2, stored.size(), stored.toString());
        GraphRelation kept = stored.stream().filter(relation -> relation.id().equals("crawled")).findFirst()
                .orElseThrow();
        assertEquals("works_for", kept.type());
        GraphRelation fresh = stored.stream().filter(relation -> !relation.id().equals("crawled")).findFirst()
                .orElseThrow();
        assertTrue(fresh.id().startsWith("asserted:"), fresh.id());
        assertNotEquals("asserted:legacy", fresh.id());
        assertEquals("WORKS_FOR", fresh.type());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void retractionListsEachRemovedEdgeWithItsOrigin(boolean compact) throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("crawled", "a", "b").type("works_for").confidence(0.7)
                .directed(true).attribute("provenance", "unified-corpus-extraction").build());
        graph.addRelation(GraphRelation.builder("projected", "a", "b").type("WORKS_FOR").confidence(1.0)
                .directed(true).attribute("_kompileProjectionOwner", "local-code-index").build());
        graph.addRelation(GraphRelation.builder("asserted:x", "a", "b").type("worksFor").confidence(0.9)
                .directed(true).attribute("source", "stdio-local").build());
        edge(graph, "ab", "a", "b", 0.8);
        save(graph, compact);
        JsonNode retracted = execute("ask_graph_retract", request().put("atomKey", "WorksFor(a, b)"));
        assertEquals("RETRACTED", retracted.path("status").asText());
        assertEquals(3, retracted.path("removed").asInt());
        Map<String, JsonNode> byId = new HashMap<>();
        retracted.path("removedAtoms").forEach(entry -> byId.put(entry.path("relationId").asText(), entry));
        assertEquals(Set.of("crawled", "projected", "asserted:x"), byId.keySet(), retracted.toString());
        assertEquals("works_for(a, b)", byId.get("crawled").path("atom").asText());
        assertEquals("unified-corpus-extraction", byId.get("crawled").path("origin").asText());
        assertFalse(byId.get("crawled").path("asserted").asBoolean());
        assertEquals("local-code-index", byId.get("projected").path("origin").asText());
        assertEquals("stdio-local", byId.get("asserted:x").path("origin").asText());
        assertTrue(byId.get("asserted:x").path("asserted").asBoolean());
        assertTrue(retracted.path("caveat").asText().startsWith("Stored edges matching the fact"),
                retracted.toString());
        assertEquals(List.of("ab"), storedEdges("a", "b").stream().map(GraphRelation::id).toList());
        JsonNode again = execute("ask_graph_retract", request().put("atomKey", "WorksFor(a, b)"));
        assertEquals("NOT_FOUND", again.path("status").asText());
        assertEquals(0, again.path("removedAtoms").size());
        assertTrue(again.path("caveat").asText().startsWith("No stored edge matched"), again.toString());
    }

    @Test void assertAndRetractRejectVariablesWithoutChangingTheGraph() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        save(graph);
        ToolResult asserted = backend.executeOfflineTool("ask_graph_assert",
                request().put("atom", "CALLS(?x, b)").put("value", 0.9), context());
        ToolResult retracted = backend.executeOfflineTool("ask_graph_retract",
                request().put("atomKey", "CALLS(a, ?target)"), context());
        for (ToolResult result : List.of(asserted, retracted)) {
            assertTrue(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("must be ground"), result.getOutput());
        }
        assertFalse(Files.exists(UnifiedGraphMutationJournal.pathFor(archive())));
        UnifiedGraph stored = UnifiedGraph.load(archive());
        assertEquals(4, stored.entityCount());
        assertEquals(List.of("ab"), stored.relations().stream().map(GraphRelation::id).toList());
    }

    @Test void aValidTimeWindowWithoutATypesEdgesIsMissingFactsNotAnUnknownPredicate() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(0.8)
                .attribute("validFrom", "2020-01-01T00:00:00Z")
                .attribute("validUntil", "2021-01-01T00:00:00Z").build());
        save(graph);
        String before = "2019-06-01T00:00:00Z";
        JsonNode verified = execute("ask_graph_verify", request().put("atom", "calls(a, b)").put("validAt", before));
        assertEquals("UNKNOWN", verified.path("verdict").asText(), verified.toString());
        assertEquals("no-evidence", verified.path("unknownReason").asText(), verified.toString());
        assertFalse(verified.has("didYouMean"), verified.toString());
        ObjectNode query = request().put("validAt", before);
        query.putArray("conjuncts").addObject().put("predicate", "calls")
                .putArray("args").add("a").add("?target");
        JsonNode queried = execute("ask_graph_query", query);
        assertEquals(0, queried.path("bindings").size());
        assertFalse(queried.has("unknownPredicates"), queried.toString());
        // A misspelling is still named, with the spellings the graph uses outside the window.
        JsonNode misspelled = execute("ask_graph_verify", request().put("atom", "CALL(a, b)").put("validAt", before));
        assertEquals("unknown-predicate", misspelled.path("unknownReason").asText(), misspelled.toString());
        assertEquals("[\"CALLS\"]", misspelled.path("didYouMean").toString());
    }

    @Test void owlEntailmentAcceptsAnySpellingOfTheDeclaredProperty() throws Exception {
        UnifiedGraph graph = graph();
        graph.putArtifactText("ontology.ttl", "@prefix ex: <urn:test#> .\n"
                + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "ex:PART_OF a owl:ObjectProperty , owl:TransitiveProperty .\n");
        for (String[] hop : new String[][]{{"a", "b"}, {"b", "c"}}) {
            graph.addRelation(GraphRelation.builder(hop[0] + hop[1], hop[0], hop[1]).type("PART_OF")
                    .confidence(1.0).directed(true).build());
        }
        save(graph);
        for (String predicate : new String[]{"partOf", "part-of"}) {
            JsonNode result = execute("ask_graph_verify", request().put("atom", predicate + "(a, c)"));
            assertEquals("SUPPORTED", result.path("verdict").asText(), result.toString());
            assertTrue(result.path("activatedRules").toString().contains("prp-trp"), result.toString());
        }
    }

    @Test void validTimeUsesHalfOpenIntervalsAndDoesNotLeakFullGraphPosterior() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(0.8)
                .attribute("validFrom", "2020-01-01T00:00:00Z")
                .attribute("validUntil", "2021-01-01T00:00:00Z").build());
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(a,b)", 0.99)));
        save(graph);
        ObjectNode query = request();
        query.putArray("conjuncts").addObject().put("predicate", "CALLS")
                .putArray("args").add("a").add("?target");
        for (String instant : new String[]{"2019-12-31T23:59:59Z", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"}) {
            boolean included = instant.startsWith("2020-");
            JsonNode verified = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)").put("validAt", instant));
            assertEquals(included ? "SUPPORTED" : "UNKNOWN", verified.path("verdict").asText());
            assertFalse(verified.has("learnedPosterior"));
            assertEquals("valid-time", verified.path("temporal").path("axis").asText());
            assertFalse(verified.path("temporal").path("historicalReconstruction").asBoolean(true));
            assertEquals(included ? 1 : 0, execute("ask_graph_query", query.put("validAt", instant)).path("bindings").size());
        }
    }

    @Test void validTimeHonorsEndpointValidityAndPointTimestampsButIncludesTimelessFacts() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("b").type("CLASS").label("b")
                .attribute("validFrom", "2021-01-01T00:00:00Z").build());
        edge(graph, "ab", "a", "b", 1.0);
        graph.addRelation(GraphRelation.builder("ac", "a", "c").type("CALLS").confidence(1.0)
                .timestamp(Instant.parse("2021-01-01T00:00:00Z")).build());
        edge(graph, "ad", "a", "d", 1.0);
        save(graph);
        for (String target : new String[]{"b", "c", "d"}) {
            JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, " + target + ")")
                    .put("validAt", "2020-01-01T00:00:00Z"));
            assertEquals("d".equals(target) ? "SUPPORTED" : "UNKNOWN", result.path("verdict").asText());
        }
        execute("ask_graph_retract", request().put("atomKey", "CALLS(a, d)"));
        assertEquals("UNKNOWN", execute("ask_graph_verify", request().put("atom", "CALLS(a, d)")
                .put("validAt", "2020-01-01T00:00:00Z")).path("verdict").asText());
    }

    @Test void temporalInputsFailExplicitlyAndSchemasAdvertiseValidAt() throws Exception {
        save(graph());
        ToolResult invalid = backend.executeOfflineTool("ask_graph_verify",
                request().put("atom", "CALLS(a, b)").put("validAt", "not-an-instant"), context());
        assertTrue(invalid.isError());
        assertTrue(invalid.getOutput().contains("validAt"));
        ToolResult mixed = backend.executeOfflineTool("ask_graph_verify", request().put("atom", "CALLS(a, b)")
                .put("validAt", "2020-01-01T00:00:00Z").put("asOf", "2020-01-01T00:00:00Z"), context());
        assertTrue(mixed.isError());
        assertTrue(new AskGraphVerifyTool((String) null, mapper).parameterSchema().path("properties").has("validAt"));
        assertTrue(new AskGraphQueryTool((String) null, mapper).parameterSchema().path("properties").has("validAt"));
    }

    @Test void remoteRoutesRejectLocalOnlyControlsBeforeSendingRequests() throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("ask_graph_verify", PermissionService.PermissionLevel.ALLOW);
        permissions.setUserOverride("ask_graph_query", PermissionService.PermissionLevel.ALLOW);
        ToolContext allowed = new ToolContext("remote-guards", AgentConfig.builder("remote-guards")
                .enabledTools(Set.of("*")).build(), permissions, root, new ToolRegistry(mapper));
        var verify = new AskGraphVerifyTool("http://127.0.0.1:1", mapper);
        var query = new AskGraphQueryTool("http://127.0.0.1:1", mapper);
        ToolResult rejected = verify.execute(request().put("atom", "CALLS(a, b)")
                .put("validAt", "2020-01-01T00:00:00Z"), allowed);
        assertTrue(rejected.isError());
        assertTrue(rejected.getOutput().contains("no remote request was sent"));
        for (String control : new String[]{"validAt", "maxWork"}) {
            ObjectNode request = request();
            request.putArray("conjuncts").addObject().put("predicate", "CALLS")
                    .putArray("args").add("a").add("?b");
            if (control.equals("validAt")) request.put(control, "2020-01-01T00:00:00Z");
            else request.put(control, 1);
            rejected = query.execute(request, allowed);
            assertTrue(rejected.isError());
            assertTrue(rejected.getOutput().contains("no remote request was sent"));
        }
    }

    @Test void oversizedConjunctionIsRejectedBeforeGraphAccess() {
        ObjectNode query = request();
        var conjuncts = query.putArray("conjuncts");
        for (int i = 0; i < 65; i++) {
            conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?a").add("?b");
        }
        ToolResult rejected = backend.executeOfflineTool("ask_graph_query", query, context());
        assertTrue(rejected.isError());
        assertTrue(rejected.getOutput().contains("64 conjuncts"));
        assertFalse(Files.exists(root.resolve("data")));
    }

    @Test void cartesianQueriesFailWithinBudgetRatherThanSilentlyTruncateJoins() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 1.0);
        edge(graph, "ac", "a", "c", 1.0);
        edge(graph, "ad", "a", "d", 1.0);
        save(graph);
        ObjectNode query = request().put("maxResults", 1).put("maxWork", 4);
        var conjuncts = query.putArray("conjuncts");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?a").add("?b");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?c").add("?d");
        ToolResult blocked = backend.executeOfflineTool("ask_graph_query", query, context());
        assertTrue(blocked.isError());
        assertTrue(blocked.getOutput().contains("maxWork=4"), blocked.getOutput());
        JsonNode allowed = execute("ask_graph_query", query.put("maxWork", 12));
        assertEquals(1, allowed.path("bindings").size());
        assertTrue(allowed.path("truncated").asBoolean());
        assertEquals(12, allowed.path("work").asInt());
    }

    @Test void conjunctionUsesLukasiewiczConfidenceAndExcludesRefutations() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        edge(graph, "bc", "b", "c", 0.7);
        edge(graph, "bd", "b", "d", 0.0);
        save(graph);
        ObjectNode request = request().put("minConfidence", 0.4);
        var conjuncts = request.putArray("conjuncts");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("a").add("?mid");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?mid").add("?target");
        JsonNode rows = execute("ask_graph_query", request).path("bindings");
        assertEquals(1, rows.size());
        assertEquals(0.5, rows.get(0).path("confidence").asDouble(), 1e-9);
        assertEquals("c", rows.get(0).path("variables").path("target").asText());
        request.put("minConfidence", 0.6);
        assertEquals(0, execute("ask_graph_query", request).path("bindings").size());
    }

    @Test void heuristicCodeEdgesAreEvidenceNotVerifiedCalls() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(1.0)
                .attribute("_kompileProjectionOwner", "local-code-index")
                .attribute("evidenceKind", "heuristic-source-pattern").build());
        save(graph);
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertEquals("UNKNOWN", result.path("verdict").asText());
        assertEquals("heuristic-code-evidence", result.path("unknownReason").asText());
        ObjectNode query = request();
        query.putArray("conjuncts").addObject().put("predicate", "CALLS")
                .putArray("args").add("a").add("?target");
        assertTrue(execute("ask_graph_query", query).path("bindings").get(0)
                .path("heuristicEvidence").asBoolean());
    }

    @Test void heuristicConfidenceCannotBorrowAuthorityFromARefutation() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("heuristic", "a", "b").type("CALLS").confidence(1.0)
                .attribute("_kompileProjectionOwner", "local-code-index").build());
        edge(graph, "refutation", "a", "b", 0.0);
        save(graph);
        assertEquals("REFUTED", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"))
                .path("verdict").asText());
    }

    @Test void archiveMutationsUseFqnAndInvalidateLearningThroughCompaction() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("s1").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "one.Service").build());
        graph.addEntity(GraphEntity.builder("s2").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "two.Service").build());
        graph.meta("codeIndexGeneration.p", "same").meta("codeLearningGeneration.p", "same");
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(s1,b)", 0.99)));
        save(graph);
        ToolResult ambiguous = backend.executeOfflineTool("ask_graph_assert",
                request().put("atom", "CALLS(Service, b)").put("value", 0.4), context());
        assertTrue(ambiguous.isError());
        JsonNode asserted = execute("ask_graph_assert",
                request().put("atom", "CALLS(one.Service, b)").put("value", 0.4));
        assertTrue(asserted.path("contradictionCheckPerformed").isBoolean(), asserted.toString());
        assertFalse(asserted.path("contradictionCheckPerformed").asBoolean());
        assertTrue(asserted.path("caveat").asText().startsWith("The fact was stored"), asserted.toString());
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(s1, b)"));
        assertTrue(result.path("meta").path("stale").asBoolean());
        assertFalse(result.has("learnedPosterior"));
        Path archive = archive();
        assertTrue(Files.isRegularFile(UnifiedGraphMutationJournal.pathFor(archive)));
        assertTrue(UnifiedGraphMutationJournal.compact(archive));
        result = execute("ask_graph_verify", request().put("atom", "CALLS(s1, b)"));
        assertTrue(result.path("meta").path("stale").asBoolean());
        assertFalse(result.has("learnedPosterior"));
        assertEquals(6, UnifiedGraph.load(archive).entityCount());
        assertEquals("RETRACTED", execute("ask_graph_retract", request()
                .put("atomKey", "CALLS(one.Service, b)")).path("status").asText());
    }

    @Test void staleLearnedPosteriorIsNotReusedAndStalenessIsReported() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        graph.meta("codeIndexGeneration.p", "new").meta("codeLearningGeneration.p", "old");
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(a,b)", 0.1)));
        save(graph);
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertTrue(result.path("meta").path("stale").asBoolean());
        assertEquals("graph-changes-versus-last-learning-pass; projected-code-versus-learning-generation; "
                + "source-tree-not-checked", result.path("meta").path("freshnessBasis").asText());
        assertFalse(result.has("learnedPosterior"));
        assertFalse(result.has("learnedScore"));
        assertEquals(0.8, result.path("calibratedConfidence").asDouble(), 1e-9);
        assertEquals("SUPPORTED", result.path("verdict").asText());
        assertEquals("direct-evidence", result.path("verdictBasis").asText());
        graph.meta("codeLearningGeneration.p", "new");
        save(graph);
        JsonNode refreshed = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertEquals(0.1, refreshed.path("learnedPosterior").asDouble(), 1e-9);
        assertEquals(0.1, refreshed.path("learnedScore").asDouble(), 1e-9);
        // A fresh learned value is reported but does not decide: the edge recorded at 0.8 still supports.
        assertEquals("SUPPORTED", refreshed.path("verdict").asText(), refreshed.toString());
        assertEquals("direct-evidence", refreshed.path("verdictBasis").asText());
        assertEquals(0.8, refreshed.path("confidence").asDouble(), 1e-9);
        assertEquals(refreshed.path("confidence").asDouble(), refreshed.path("calibratedConfidence").asDouble(), 1e-12);
    }

    @Test void storedRelationOpinionIsReportedAsALearnedScoreUntilLearningGoesStale() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        graph.putRelationOpinion("ab", Opinion.fromSoftTruth(0.1));
        save(graph);
        JsonNode fresh = execute("ask_graph_verify", request().put("atom", "calls(a, b)"));
        // The recorded edge decides; the low learned opinion is only reported beside it.
        assertEquals("SUPPORTED", fresh.path("verdict").asText(), fresh.toString());
        assertEquals("direct-evidence", fresh.path("verdictBasis").asText());
        assertEquals(0.8, fresh.path("confidence").asDouble(), 1e-9);
        assertEquals(Opinion.fromSoftTruth(0.1).expectation(), fresh.path("learnedScore").asDouble(), 1e-9);
        assertEquals("CALLS(a, b)", fresh.path("relationOpinion").path("relation").asText());
        assertEquals(0.8, fresh.path("recordedEvidenceConfidence").asDouble(), 1e-9);
        assertTrue(fresh.path("confidenceBasis").asText().contains("does not decide"), fresh.toString());
        JsonNode claim = execute("ask_graph_claim", request().put("subject", "a")
                .put("predicate", "CALLS").put("object", "b"));
        assertEquals("SUPPORTED", claim.path("verdict").asText());
        assertEquals(fresh.path("confidence"), claim.path("confidence"));
        assertTrue(claim.path("caveat").asText().contains("learnedScore"), claim.toString());
        // Once the graph changes after learning, the stored opinion is no longer reported.
        graph.meta("learning.reasoningStale", true);
        save(graph);
        JsonNode stale = execute("ask_graph_verify", request().put("atom", "calls(a, b)"));
        assertTrue(stale.path("meta").path("stale").asBoolean(), stale.toString());
        // No code is projected here, so staleness is judged on graph changes alone.
        assertEquals("graph-changes-versus-last-learning-pass",
                stale.path("meta").path("freshnessBasis").asText());
        assertEquals("SUPPORTED", stale.path("verdict").asText(), stale.toString());
        assertEquals("direct-evidence", stale.path("verdictBasis").asText());
        assertEquals(0.8, stale.path("confidence").asDouble(), 1e-9);
        assertFalse(stale.has("relationOpinion"));
        assertFalse(stale.has("learnedScore"));
    }

    @Test void aWellEvidencedRelationBetweenLowRankedEntitiesStaysSupported() throws Exception {
        // Learning stores the PSL/MEBN consensus target as the relation's opinion. It blends the
        // recorded confidence with endpoint centrality, so c = 0.6 between peripheral entities came
        // out near 0.234, which used to refute a relation the graph records at 0.6.
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.6);
        graph.putRelationOpinion("ab", Opinion.fromSoftTruth(0.234));
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(a,b)", 0.234)));
        save(graph);
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertEquals("SUPPORTED", result.path("verdict").asText(), result.toString());
        assertEquals("direct-evidence", result.path("verdictBasis").asText());
        assertEquals(0.6, result.path("confidence").asDouble(), 1e-9);
        assertEquals(0.6, result.path("calibratedConfidence").asDouble(), 1e-9);
        assertEquals(Opinion.fromSoftTruth(0.234).expectation(), result.path("learnedScore").asDouble(), 1e-9);
        assertFalse(result.has("unknownReason"), result.toString());
        JsonNode claim = execute("ask_graph_claim", request().put("subject", "a")
                .put("predicate", "CALLS").put("object", "b"));
        assertEquals("SUPPORTED", claim.path("verdict").asText(), claim.toString());
        assertEquals(0.6, claim.path("confidence").asDouble(), 1e-9);
    }

    @Test void learnedResultsDoNotDecideOverAHeuristicCodeCall() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(1.0)
                .attribute("_kompileProjectionOwner", "local-code-index").build());
        graph.putRelationOpinion("ab", Opinion.fromSoftTruth(0.99));
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(a,b)", 0.99)));
        save(graph);
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertEquals("UNKNOWN", result.path("verdict").asText(), result.toString());
        assertEquals("heuristic-code-evidence", result.path("unknownReason").asText());
        assertEquals("direct-evidence", result.path("verdictBasis").asText());
        assertEquals(0.0, result.path("confidence").asDouble());
        assertFalse(result.has("relationOpinion"));
        // The learned value is still reported, labelled as a score that does not decide.
        assertEquals(0.99, result.path("learnedScore").asDouble(), 1e-9);
    }

    @Test void explainReturnsTheVerdictTraceAndDescribesBareIds() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        graph.putRelationOpinion("ab", Opinion.fromSoftTruth(0.9));
        save(graph);
        ToolResult explained = backend.executeOfflineTool("ask_graph_explain",
                request().put("atom", "CALLS(a, b)"), context());
        assertFalse(explained.isError(), explained.getOutput());
        assertTrue(explained.getOutput().contains("Reasoning trace"), explained.getOutput());
        assertTrue(explained.getOutput().contains("Basis: direct-evidence"), explained.getOutput());
        assertTrue(explained.getOutput().contains("learned score"), explained.getOutput());
        assertEquals("SUPPORTED", explained.getMetadata().get("verdict"));
        assertEquals("direct-evidence", explained.getMetadata().get("verdictBasis"));
        JsonNode trace = (JsonNode) explained.getMetadata().get("trace");
        assertEquals(2, trace.path("size").asInt(), trace.toString());
        JsonNode root = trace.path("conclusion");
        assertEquals("INFERENCE", root.path("kind").asText());
        assertEquals("direct-evidence", root.path("operation").asText());
        assertTrue(root.path("opinion").isMissingNode() || root.path("opinion").isNull(), root.toString());
        assertEquals(Opinion.fromSoftTruth(0.9).expectation(),
                Double.parseDouble(root.path("meta").path("learnedScore").asText()), 1e-3);
        JsonNode fact = root.path("premises").get(0);
        assertEquals("CALLS(a, b)", fact.path("conclusion").asText());
        assertEquals("ab", fact.path("source").asText());
        // The learned opinion is labelled in meta, never attached as the recorded fact's opinion.
        assertTrue(fact.path("opinion").isMissingNode() || fact.path("opinion").isNull(), fact.toString());
        assertEquals(Opinion.fromSoftTruth(0.9).expectation(),
                Double.parseDouble(fact.path("meta").path("learnedScore").asText()), 1e-3);
        ToolResult described = backend.executeOfflineTool("ask_graph_explain", request().put("target", "a"), context());
        assertFalse(described.isError(), described.getOutput());
        assertEquals("DESCRIBE", described.getMetadata().get("inferenceMode"));
    }

    @Test void presentButStaleVectorsAreNotUsed() throws Exception {
        UnifiedGraph graph = graph();
        graph.meta("codeIndexGeneration.p", "new").meta("codeKgeGeneration.p", "old");
        graph.putVectorLayer(new VectorLayer("kge", VectorLayer.Target.ENTITY, 2, Dtype.F64)
                .put("a", new double[]{1, 0}));
        graph.putVectorLayer(new VectorLayer("kge-relations", VectorLayer.Target.RELATION, 2, Dtype.F64)
                .put("CALLS", new double[]{1, 0}));
        graph.putArtifactText("models/kge.json", "{\"algorithm\":\"TRANSE\",\"embeddingDim\":2}");
        save(graph);
        ToolResult result = backend.embeddings(request().put("action", "similar").put("entity_name", "a"), context());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("stale"), result.getOutput());
    }

    @Test void scopedSearchDoesNotLoseMatchesBehindOtherProjects() throws Exception {
        UnifiedGraph graph = graph();
        for (int i = 0; i < 150; i++) {
            graph.addEntity(GraphEntity.builder("other-" + i).type("CLASS").label("Service")
                    .attribute("codeProjectId", "other").build());
        }
        graph.addEntity(GraphEntity.builder("wanted").type("CLASS").label("Service")
                .attribute("codeProjectId", "wanted-project").build());
        save(graph);
        JsonNode result = execute("graph_search", request().put("query", "Service")
                .put("code_project_id", "wanted-project").put("entity_type", "CLASS").put("max_results", 1));
        assertEquals(1, result.path("entities").size());
        assertEquals("wanted", result.path("entities").get(0).path("id").asText());
    }

    @Test void ambiguousLabelsFailButFqnAndExactIdsResolve() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("s1").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "one.Service").build());
        graph.addEntity(GraphEntity.builder("s2").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "two.Service").build());
        edge(graph, "s1b", "s1", "b", 1.0);
        save(graph);
        ToolResult ambiguous = backend.executeOfflineTool("ask_graph_verify",
                request().put("atom", "CALLS(Service, b)"), context());
        assertTrue(ambiguous.isError());
        assertTrue(ambiguous.getOutput().contains("Ambiguous"), ambiguous.getOutput());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(one.Service, b)"))
                .path("verdict").asText());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(s1, b)"))
                .path("verdict").asText());
    }
}
