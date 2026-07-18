/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INFRALESS scenarios proving that {@link ExtractionToUnifiedGraph} produces reasoning-ready
 * graphs straight from canonical extraction results: no crawl pipeline, no matrix store, no
 * Spring context. Each scenario is shaped like real extractor output (email, employment,
 * multi-document merge, document mentions) and drives the FOL / contradiction machinery on the
 * projected {@link UnifiedGraph}.
 */
class ExtractionScenarioReasoningTest {

    // ── fixture helpers (shaped like validated LLM extraction output) ────────────

    private static ExtractedEntity entity(String id, String name, String type, Double confidence) {
        return new ExtractedEntity(id, name, type, null, null, confidence, null);
    }

    private static ExtractedRelation rel(String source, String target, String type, Double confidence) {
        return new ExtractedRelation(source, target, type, null, confidence, null, null);
    }

    private static Map<String, List<List<String>>> edb(ReasoningGraph graph) {
        Map<String, List<List<String>>> edb = new HashMap<>();
        for (GraphRelation r : graph.relations()) {
            edb.computeIfAbsent(r.type(), k -> new ArrayList<>())
                    .add(List.of(r.sourceId(), r.targetId()));
        }
        return edb;
    }

    private static RecursiveQueryEngine.FixpointResult runFol(ReasoningGraph graph,
                                                              List<DatalogRule> rules) {
        Map<String, List<List<String>>> edb = edb(graph);
        RecursiveQueryEngine.EdbProvider provider = pred -> edb.getOrDefault(pred, List.of());
        return RecursiveQueryEngine.evaluate(rules, provider,
                RecursiveQueryEngine.DEFAULT_MAX_ROUNDS, RecursiveQueryEngine.DEFAULT_MAX_DERIVED_FACTS);
    }

    /** Employment domain, as one extraction chunk: alice works at acme, acme located in nyc. */
    private static ExtractionResult employmentChunk() {
        return ExtractionResult.of(
                List.of(entity("alice", "Alice", "PERSON", 0.9),
                        entity("acme", "Acme Corp", "ORGANIZATION", 0.9),
                        entity("nyc", "New York", "LOCATION", 0.9)),
                List.of(rel("alice", "acme", "WORKS_AT", 0.9),
                        rel("acme", "nyc", "LOCATED_IN", 0.9)),
                ExtractionMetadata.forChunk("chunk-1", "doc-employment", "model-a"));
    }

    // ── 1. email extraction → FOL contact derivation ─────────────────────────────

    @Test
    void emailSentByAndSentToDeriveContacted() {
        ExtractionResult email = ExtractionResult.of(
                List.of(entity("m1", "Q3 forecast mail", "EMAIL_MESSAGE", 0.95),
                        entity("alice", "Alice", "PERSON", 0.9),
                        entity("bob", "Bob", "PERSON", 0.9)),
                List.of(rel("m1", "alice", "SENT_BY", 0.9),
                        rel("m1", "bob", "SENT_TO", 0.9)),
                null);

        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(email);
        assertEquals(3, graph.entityCount());
        assertEquals(2, graph.relationCount());

        DatalogRule contacted = new DatalogRule("contacted", List.of("?S", "?R"),
                List.of(RuleAtom.pos("SENT_BY", "?M", "?S"),
                        RuleAtom.pos("SENT_TO", "?M", "?R")));
        Set<List<String>> derived = runFol(graph, List.of(contacted)).derivedFacts().get("contacted");
        assertTrue(derived.contains(List.of("alice", "bob")),
                "sender contacted recipient via the shared message");
        assertFalse(derived.contains(List.of("bob", "alice")), "contact derivation is directed");
    }

    // ── 2. employment extraction → FOL residency derivation ──────────────────────

    @Test
    void worksAtAndLocatedInDeriveBasedIn() {
        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(employmentChunk());

        DatalogRule basedIn = new DatalogRule("basedIn", List.of("?P", "?C"),
                List.of(RuleAtom.pos("WORKS_AT", "?P", "?O"),
                        RuleAtom.pos("LOCATED_IN", "?O", "?C")));
        Set<List<String>> derived = runFol(graph, List.of(basedIn)).derivedFacts().get("basedIn");
        assertEquals(Set.of(List.of("alice", "nyc")), derived,
                "employment at an NYC-based org entails NYC residency, and nothing else");
    }

    // ── 3. multi-document merge + functional contradiction ───────────────────────

    @Test
    void multiDocumentMergeSurfacesFunctionalContradiction() {
        ExtractionResult docOne = ExtractionResult.of(
                List.of(new ExtractedEntity("alice", "Alice", "PERSON",
                                List.of("Alice R."), "engineer", 0.8, null),
                        entity("acme", "Acme Corp", "ORGANIZATION", 0.9),
                        entity("nyc", "New York", "LOCATION", 0.9)),
                List.of(rel("alice", "acme", "WORKS_AT", 0.9),
                        rel("acme", "nyc", "LOCATED_IN", 0.9)),
                ExtractionMetadata.forChunk("chunk-1", "doc-1", "model-a"));
        // A second document re-extracts alice (new alias, higher confidence) and claims the
        // company is London-based — a functional clash with document one.
        ExtractionResult docTwo = ExtractionResult.of(
                List.of(new ExtractedEntity("alice", "Alice", "PERSON",
                                List.of("A. Rodriguez"), "senior engineer at Acme", 0.9, null),
                        entity("london", "London", "LOCATION", 0.9)),
                List.of(rel("acme", "london", "LOCATED_IN", 0.85)),
                ExtractionMetadata.forChunk("chunk-2", "doc-2", "model-a"));

        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(List.of(docOne, docTwo));

        assertEquals(4, graph.entityCount(), "alice merged across documents, not duplicated");
        GraphEntity alice = graph.entity("alice").orElseThrow();
        assertEquals(0.9, alice.confidence(), 1e-9, "merge keeps the highest confidence");
        Object aliases = alice.attributes().get(ExtractionToUnifiedGraph.ATTR_ALIASES);
        assertTrue(aliases instanceof List<?> list
                        && list.contains("Alice R.") && list.contains("A. Rodriguez"),
                "aliases union across documents");
        assertTrue(String.valueOf(alice.attributes().get(ExtractionToUnifiedGraph.ATTR_DESCRIPTION))
                .contains("senior"), "longer description wins the merge");
        assertEquals(List.of("doc-1", "doc-2"),
                graph.meta().get(ExtractionToUnifiedGraph.META_SOURCE_DOCUMENT_IDS));

        DatalogRule basedIn = new DatalogRule("basedIn", List.of("?P", "?C"),
                List.of(RuleAtom.pos("WORKS_AT", "?P", "?O"),
                        RuleAtom.pos("LOCATED_IN", "?O", "?C")));
        Set<List<String>> derived = runFol(graph, List.of(basedIn)).derivedFacts().get("basedIn");
        assertTrue(derived.contains(List.of("alice", "nyc")));
        assertTrue(derived.contains(List.of("alice", "london")));

        FactStore store = new FactStore();
        for (List<String> tuple : derived) {
            store.assertFact(Fact.observed(
                    "basedIn(" + tuple.get(0) + ", " + tuple.get(1) + ")", "fol-derivation"));
        }
        List<ContradictionDetector.Pair<Fact, Fact>> clashes =
                ContradictionDetector.findFactContradictions(store, Set.of("basedIn"));
        assertEquals(1, clashes.size(),
                "residency is functional — the two derived cities for alice clash");
    }

    // ── 4. .kgraph round-trip fidelity ────────────────────────────────────────────

    @Test
    void kgraphRoundTripPreservesGraphAndDerivations() throws IOException {
        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(employmentChunk());
        DatalogRule basedIn = new DatalogRule("basedIn", List.of("?P", "?C"),
                List.of(RuleAtom.pos("WORKS_AT", "?P", "?O"),
                        RuleAtom.pos("LOCATED_IN", "?O", "?C")));
        Set<List<String>> before = runFol(graph, List.of(basedIn)).derivedFacts().get("basedIn");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        graph.save(bytes, Dtype.F64);
        UnifiedGraph reloaded = UnifiedGraph.load(new ByteArrayInputStream(bytes.toByteArray()));

        assertEquals(graph.entityCount(), reloaded.entityCount());
        assertEquals(graph.relationCount(), reloaded.relationCount());
        assertEquals(before, runFol(reloaded, List.of(basedIn)).derivedFacts().get("basedIn"),
                "the reloaded graph derives the identical residency facts");
        GraphEntity alice = reloaded.entity("alice").orElseThrow();
        assertEquals(0.9, alice.weight(), 1e-9, "confidence→weight survives the round trip");
    }

    // ── 5. confidence and attribute fidelity ─────────────────────────────────────

    @Test
    void confidenceAndAttributesMapWithFullFidelity() {
        ExtractionResult chunk = ExtractionResult.of(
                List.of(new ExtractedEntity("carol", "Carol Chen", "PERSON",
                                List.of("C. Chen"), "Chief Financial Officer", 0.85,
                                Map.of("role", "CFO")),
                        entity("acme", "Acme Corp", "ORGANIZATION", null)),
                List.of(new ExtractedRelation("carol", "acme", "WORKS_AT",
                        "employment relation", 0.6,
                        Map.of("seniority", "executive"), "2026-01-15T10:00:00Z")),
                null);

        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(chunk);

        GraphEntity carol = graph.entity("carol").orElseThrow();
        assertEquals(0.85, carol.confidence(), 1e-9);
        assertEquals(0.85, carol.weight(), 1e-9, "extraction confidence feeds reasoning weight");
        assertEquals("Chief Financial Officer",
                carol.attributes().get(ExtractionToUnifiedGraph.ATTR_DESCRIPTION));
        assertEquals("CFO", carol.attributes().get("role"));
        assertTrue(((List<?>) carol.attributes().get(ExtractionToUnifiedGraph.ATTR_ALIASES))
                .contains("C. Chen"));

        GraphEntity acme = graph.entity("acme").orElseThrow();
        assertEquals(0.7, acme.confidence(), 1e-9,
                "omitted entity confidence falls back to the schema default");

        GraphRelation employment = graph.outgoing("carol").get(0);
        assertEquals(0.6, employment.confidence(), 1e-9);
        assertEquals(0.6, employment.weight(), 1e-9);
        assertEquals("executive", employment.attributes().get("seniority"));
        assertEquals("employment relation",
                employment.attributes().get(ExtractionToUnifiedGraph.ATTR_DESCRIPTION));
        assertEquals("2026-01-15T10:00:00Z",
                employment.attributes().get(ExtractionToUnifiedGraph.ATTR_OCCURRED_AT));
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), employment.timestamp(),
                "parseable occurredAt becomes the relation timestamp");
    }

    // ── 6. document MENTIONS → co-mention derivation ─────────────────────────────

    @Test
    void documentMentionsDeriveCoMentions() {
        ExtractionResult chunk = ExtractionResult.of(
                List.of(entity("doc1", "Board minutes", "DOCUMENT", 0.95),
                        entity("alice", "Alice", "PERSON", 0.9),
                        entity("acme", "Acme Corp", "ORGANIZATION", 0.9)),
                List.of(rel("doc1", "alice", "MENTIONS", 0.8),
                        rel("doc1", "acme", "MENTIONS", 0.8)),
                null);

        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(chunk);
        assertTrue(graph.facts().stream().map(Fact::atomKey)
                        .anyMatch("MENTIONS(doc1, alice)"::equals),
                "the facts() view exposes the mention as a binary atom");

        DatalogRule coMentioned = new DatalogRule("coMentioned", List.of("?A", "?B"),
                List.of(RuleAtom.pos("MENTIONS", "?D", "?A"),
                        RuleAtom.pos("MENTIONS", "?D", "?B")));
        Set<List<String>> derived = runFol(graph, List.of(coMentioned)).derivedFacts().get("coMentioned");
        assertTrue(derived.contains(List.of("alice", "acme")),
                "entities mentioned by the same document are co-mentioned");
    }

    // ── 7. heterogeneous types enumerate through the ontology views ──────────────

    @Test
    void heterogeneousTypesEnumerateAcrossTheGraph() {
        // Deliberately messy type spellings, as sloppier models produce them.
        ExtractionResult chunk = ExtractionResult.of(
                List.of(entity("alice", "Alice", "person", 0.9),
                        entity("bob", "Bob", "Person", 0.9),
                        entity("acme", "Acme Corp", "organization", 0.9),
                        entity("m1", "Kickoff mail", "email message", 0.9),
                        entity("doc1", "Contract", "DOCUMENT", 0.9)),
                List.of(rel("m1", "alice", "SENT_BY", 0.9),
                        rel("doc1", "acme", "MENTIONS", 0.8)),
                null);

        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(chunk);

        Set<String> types = graph.types();
        assertTrue(types.containsAll(Set.of("PERSON", "ORGANIZATION", "EMAIL_MESSAGE", "DOCUMENT")),
                "entity types normalize to the UPPER_SNAKE convention: " + types);
        assertEquals(2, graph.entitiesOfType("person").size(),
                "type membership lookup is case-insensitive");
        assertEquals(1, graph.entitiesOfType("EMAIL_MESSAGE").size());

        long typeAtoms = graph.facts().stream()
                .filter(fact -> "graph:type".equals(fact.sourceId())).count();
        long relationAtoms = graph.facts().stream()
                .filter(fact -> "graph:relation".equals(fact.sourceId())).count();
        assertEquals(5, typeAtoms, "one unary type atom per entity membership");
        assertEquals(2, relationAtoms, "one binary atom per extracted relation");
    }

    // ── 8. chunk idempotence, relation dedupe, stub upgrade ──────────────────────

    @Test
    void reappliedChunksDedupeRelationsAndUpgradeStubEndpoints() {
        // A relation whose endpoints no chunk has declared yet → stub entities.
        ExtractionResult relationOnly = ExtractionResult.of(
                List.of(),
                List.of(rel("dave", "initech", "WORKS_AT", 0.5)),
                null);
        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(relationOnly);

        GraphEntity stub = graph.entity("dave").orElseThrow();
        assertEquals(ExtractionToUnifiedGraph.STUB_TYPE, stub.type());
        assertEquals(Boolean.TRUE, stub.attributes().get(ExtractionToUnifiedGraph.ATTR_STUB));

        // Re-applying the identical chunk must not duplicate the relation.
        ExtractionToUnifiedGraph.apply(graph, relationOnly);
        assertEquals(1, graph.relationCount(), "identical (type, source, target) dedupes");

        // A later chunk declares the real entity and a stronger relation claim.
        ExtractionResult declaring = ExtractionResult.of(
                List.of(entity("dave", "Dave", "PERSON", 0.9)),
                List.of(rel("dave", "initech", "WORKS_AT", 0.8)),
                null);
        ExtractionToUnifiedGraph.apply(graph, declaring);

        GraphEntity dave = graph.entity("dave").orElseThrow();
        assertEquals("PERSON", dave.type(), "the stub upgrades to the declared type");
        assertEquals("Dave", dave.label());
        assertNull(dave.attributes().get(ExtractionToUnifiedGraph.ATTR_STUB),
                "the stub marker clears once the entity is declared");
        assertEquals(1, graph.relationCount());
        GraphRelation employment = graph.outgoing("dave").get(0);
        assertEquals(0.8, employment.confidence(), 1e-9, "dedupe keeps the strongest claim");
    }
}
