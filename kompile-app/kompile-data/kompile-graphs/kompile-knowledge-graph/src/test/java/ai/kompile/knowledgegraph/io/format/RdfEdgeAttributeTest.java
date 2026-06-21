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
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for serialization audit gaps:
 *
 * <ul>
 *   <li><b>L-2</b>: RDF exporters (N-Triples, Turtle) must represent edge weight and confidence
 *       via RDF reification — an {@code rdf:Statement} node annotated with
 *       {@code https://kompile.ai/kg/weight} and {@code https://kompile.ai/kg/confidence}
 *       typed literals.</li>
 *   <li><b>L-5</b>: JSON-LD exporter must use {@code @context} IRIs under
 *       {@code https://kompile.ai/kg/} — the same namespace minted by the N-Triples and Turtle
 *       exporters — rather than {@code http://schema.org/*}.</li>
 * </ul>
 */
class RdfEdgeAttributeTest {

    // ─── shared fixtures ─────────────────────────────────────────────────────

    /** Two nodes + one edge that carries both weight AND confidence. */
    private static PortableGraph edgeWithWeightAndConfidence() {
        PortableNode alice = new PortableNode("alice", "Alice", null, "PERSON", null);
        PortableNode bob   = new PortableNode("bob",   "Bob",   null, "PERSON", null);
        // 7-arg compact ctor: from, to, type, weight, description, provenance, confidence
        PortableEdge edge  = new PortableEdge("alice", "bob", "KNOWS", 0.75, null, null, 0.9);
        return new PortableGraph(List.of(alice, bob), List.of(edge));
    }

    /** Edge with weight but no confidence. */
    private static PortableGraph edgeWithWeightOnly() {
        PortableNode a = new PortableNode("a", "A", null, "ENTITY", null);
        PortableNode b = new PortableNode("b", "B", null, "ENTITY", null);
        PortableEdge e = new PortableEdge("a", "b", "LINKED", 0.5, null, null, null);
        return new PortableGraph(List.of(a, b), List.of(e));
    }

    /** Edge with confidence but no weight. */
    private static PortableGraph edgeWithConfidenceOnly() {
        PortableNode a = new PortableNode("a", "A", null, "ENTITY", null);
        PortableNode b = new PortableNode("b", "B", null, "ENTITY", null);
        PortableEdge e = new PortableEdge("a", "b", "SCORED", null, null, null, 0.6);
        return new PortableGraph(List.of(a, b), List.of(e));
    }

    /** Edge with neither weight nor confidence — must NOT produce a reification block. */
    private static PortableGraph edgeWithNoAttributes() {
        PortableNode a = new PortableNode("a", "A", null, "ENTITY", null);
        PortableNode b = new PortableNode("b", "B", null, "ENTITY", null);
        PortableEdge e = new PortableEdge("a", "b", "PLAIN", null, null, null, null);
        return new PortableGraph(List.of(a, b), List.of(e));
    }

    // ─── L-2: N-Triples reification ──────────────────────────────────────────

    @Test
    void ntriples_edgeWeight_emitsReificationBlock() {
        String nt = ntOf(edgeWithWeightAndConfidence());
        // The reification stmt IRI
        assertTrue(nt.contains("https://kompile.ai/kg/stmt/alice/KNOWS/bob"),
                "reification subject IRI must appear: " + nt);
        // rdf:Statement type triple
        assertTrue(nt.contains("<http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement>"),
                "rdf:Statement type must be emitted: " + nt);
        // weight predicate
        assertTrue(nt.contains("<https://kompile.ai/kg/weight>"),
                "kompile:weight predicate must appear: " + nt);
        // weight value
        assertTrue(nt.contains("\"0.75\"^^<http://www.w3.org/2001/XMLSchema#double>"),
                "weight typed literal must appear: " + nt);
    }

    @Test
    void ntriples_edgeConfidence_emitsReificationBlock() {
        String nt = ntOf(edgeWithWeightAndConfidence());
        assertTrue(nt.contains("<https://kompile.ai/kg/confidence>"),
                "kompile:confidence predicate must appear: " + nt);
        assertTrue(nt.contains("\"0.9\"^^<http://www.w3.org/2001/XMLSchema#double>"),
                "confidence typed literal must appear: " + nt);
    }

    @Test
    void ntriples_reification_containsSubjectPredicateObject() {
        String nt = ntOf(edgeWithWeightAndConfidence());
        // rdf:subject, rdf:predicate, rdf:object must link back to the original triple
        assertTrue(nt.contains("<http://www.w3.org/1999/02/22-rdf-syntax-ns#subject>"),
                "rdf:subject must be present: " + nt);
        assertTrue(nt.contains("<http://www.w3.org/1999/02/22-rdf-syntax-ns#predicate>"),
                "rdf:predicate must be present: " + nt);
        assertTrue(nt.contains("<http://www.w3.org/1999/02/22-rdf-syntax-ns#object>"),
                "rdf:object must be present: " + nt);
    }

    @Test
    void ntriples_weightOnly_reificationEmitted() {
        String nt = ntOf(edgeWithWeightOnly());
        assertTrue(nt.contains("<https://kompile.ai/kg/weight>"),
                "weight-only edge must still produce reification: " + nt);
        assertFalse(nt.contains("<https://kompile.ai/kg/confidence>"),
                "confidence predicate must NOT appear when edge has no confidence: " + nt);
    }

    @Test
    void ntriples_confidenceOnly_reificationEmitted() {
        String nt = ntOf(edgeWithConfidenceOnly());
        assertTrue(nt.contains("<https://kompile.ai/kg/confidence>"),
                "confidence-only edge must produce reification: " + nt);
        assertFalse(nt.contains("<https://kompile.ai/kg/weight>"),
                "weight predicate must NOT appear when edge has no weight: " + nt);
    }

    @Test
    void ntriples_noAttributes_noReificationBlock() {
        String nt = ntOf(edgeWithNoAttributes());
        assertFalse(nt.contains("stmt/"),
                "plain edge must not produce any reification block: " + nt);
        assertFalse(nt.contains("rdf-syntax-ns#Statement"),
                "rdf:Statement must not appear for plain edge: " + nt);
    }

    @Test
    void ntriples_baseEdgeTriple_stillEmitted_evenWhenReified() {
        // The reification block must not replace the original edge triple
        String nt = ntOf(edgeWithWeightAndConfidence());
        assertTrue(nt.contains("<https://kompile.ai/kg/node/alice> "
                + "<https://kompile.ai/kg/rel/KNOWS> "
                + "<https://kompile.ai/kg/node/bob> ."),
                "original edge triple must still be present alongside reification: " + nt);
    }

    // ─── L-2: Turtle reification ──────────────────────────────────────────────

    @Test
    void turtle_edgeWeight_emitsReificationBlock() {
        String ttl = ttlOf(edgeWithWeightAndConfidence());
        assertTrue(ttl.contains("https://kompile.ai/kg/stmt/alice/KNOWS/bob"),
                "reification stmt IRI must appear in Turtle: " + ttl);
        assertTrue(ttl.contains("https://kompile.ai/kg/weight"),
                "kompile:weight must appear in Turtle reification: " + ttl);
        assertTrue(ttl.contains("\"0.75\"^^xsd:double"),
                "weight xsd:double literal must appear in Turtle: " + ttl);
    }

    @Test
    void turtle_edgeConfidence_emitsReificationBlock() {
        String ttl = ttlOf(edgeWithWeightAndConfidence());
        assertTrue(ttl.contains("https://kompile.ai/kg/confidence"),
                "kompile:confidence must appear in Turtle reification: " + ttl);
        assertTrue(ttl.contains("\"0.9\"^^xsd:double"),
                "confidence xsd:double literal must appear in Turtle: " + ttl);
    }

    @Test
    void turtle_reification_usesRdfPrefix() {
        String ttl = ttlOf(edgeWithWeightAndConfidence());
        // Turtle emits @prefix rdf: so rdf:Statement is the shorthand
        assertTrue(ttl.contains("@prefix rdf:"),
                "Turtle must declare @prefix rdf: for reification shorthands: " + ttl);
        assertTrue(ttl.contains("rdf:Statement") || ttl.contains("rdf-syntax-ns#Statement"),
                "rdf:Statement (or full IRI) must appear: " + ttl);
    }

    @Test
    void turtle_noAttributes_noReificationBlock() {
        String ttl = ttlOf(edgeWithNoAttributes());
        assertFalse(ttl.contains("stmt/"),
                "plain edge must not produce reification block in Turtle: " + ttl);
    }

    @Test
    void turtle_baseEdgeTriple_stillEmitted_evenWhenReified() {
        String ttl = ttlOf(edgeWithWeightAndConfidence());
        // Original edge: alice's subject block should contain the KNOWS predicate
        assertTrue(ttl.contains("<https://kompile.ai/kg/rel/KNOWS>"),
                "KNOWS predicate must still be present in Turtle alongside reification: " + ttl);
        assertTrue(ttl.contains("<https://kompile.ai/kg/node/bob>"),
                "bob node IRI must still appear as object of original triple: " + ttl);
    }

    @Test
    void turtle_ntriples_consistentReificationIris() {
        // The reification stmt IRI must be identical in both formats
        String nt  = ntOf(edgeWithWeightAndConfidence());
        String ttl = ttlOf(edgeWithWeightAndConfidence());
        assertTrue(nt.contains("https://kompile.ai/kg/stmt/alice/KNOWS/bob"),
                "N-Triples stmt IRI: " + nt);
        assertTrue(ttl.contains("https://kompile.ai/kg/stmt/alice/KNOWS/bob"),
                "Turtle stmt IRI: " + ttl);
    }

    // ─── L-5: JSON-LD @context uses kompile IRIs ─────────────────────────────

    @Test
    void jsonld_context_usesKompileBase_notSchemaOrg() throws IOException {
        byte[] bytes = new JsonLdGraphExporter(new ObjectMapper()).toBytes(PortableGraph.empty());
        String json  = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("https://kompile.ai/kg/"),
                "JSON-LD @context must contain kompile IRI base: " + json);
        assertFalse(json.contains("schema.org"),
                "JSON-LD @context must NOT reference schema.org after L-5 fix: " + json);
    }

    @Test
    void jsonld_context_titleMapsToRdfsLabel() throws IOException {
        JsonNode ctx = jsonLdContext();
        assertTrue(ctx.has("title"), "@context must have 'title' key");
        assertEquals("http://www.w3.org/2000/01/rdf-schema#label",
                ctx.get("title").asText(),
                "'title' must map to rdfs:label — same as N-Triples/Turtle exporters");
    }

    @Test
    void jsonld_context_descriptionMapsToRdfsComment() throws IOException {
        JsonNode ctx = jsonLdContext();
        assertTrue(ctx.has("description"), "@context must have 'description' key");
        assertEquals("http://www.w3.org/2000/01/rdf-schema#comment",
                ctx.get("description").asText(),
                "'description' must map to rdfs:comment — same as N-Triples/Turtle exporters");
    }

    @Test
    void jsonld_context_weightMapsToKompileWeightIri() throws IOException {
        JsonNode ctx = jsonLdContext();
        assertTrue(ctx.has("weight"), "@context must have 'weight' key");
        assertEquals("https://kompile.ai/kg/weight",
                ctx.get("weight").asText(),
                "'weight' must map to kompile:weight — consistent with reification IRIs");
    }

    @Test
    void jsonld_context_confidenceMapsToKompileConfidenceIri() throws IOException {
        JsonNode ctx = jsonLdContext();
        assertTrue(ctx.has("confidence"), "@context must have 'confidence' key");
        assertEquals("https://kompile.ai/kg/confidence",
                ctx.get("confidence").asText(),
                "'confidence' must map to kompile:confidence — consistent with reification IRIs");
    }

    @Test
    void jsonld_edgeWeight_presentInOutput() throws IOException {
        PortableNode a = new PortableNode("a", "A", null, "ENTITY", null);
        PortableNode b = new PortableNode("b", "B", null, "ENTITY", null);
        PortableEdge e = new PortableEdge("a", "b", "SCORED", 0.88, null, null, null);
        byte[] bytes = new JsonLdGraphExporter(new ObjectMapper())
                .toBytes(new PortableGraph(List.of(a, b), List.of(e)));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("0.88"),
                "edge weight must appear in JSON-LD output: " + json);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static String ntOf(PortableGraph g) {
        return new String(new NTriplesGraphExporter().toBytes(g), StandardCharsets.UTF_8);
    }

    private static String ttlOf(PortableGraph g) {
        return new String(new TurtleGraphExporter().toBytes(g), StandardCharsets.UTF_8);
    }

    /** Returns the parsed @context node from a minimal JSON-LD export. */
    private static JsonNode jsonLdContext() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        byte[] bytes = new JsonLdGraphExporter(mapper).toBytes(PortableGraph.empty());
        return mapper.readTree(bytes).get("@context");
    }
}
