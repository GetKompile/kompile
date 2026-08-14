package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.knowledgegraph.service.ConceptExtractor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCorpusSchemaInferencerTest {

    @Test
    void collectorPreservesDomainCategoriesAndInferenceReusesAuthoritativeSchema() {
        Map<String, ConceptExtractor.ExtractionResult> passages = new LinkedHashMap<>();
        passages.put("window-2", extraction(
                new ConceptExtractor.ExtractedConcept(
                        "VP Finance", "vp finance", "APPROVAL_ROLE", 0.91d, 1, "role"),
                new ConceptExtractor.ExtractedConcept(
                        "Mira Chen", "mira chen", "PERSON", 0.95d, 1, "person"),
                new ConceptExtractor.ConceptRelationship(
                        "Mira Chen", "VP Finance", "has role", 0.88d)));
        passages.put("window-1", extraction(
                new ConceptExtractor.ExtractedConcept(
                        "Mira Chen", "mira chen", "PERSON", 0.93d, 1, "person")));

        CorpusSchemaCandidates.Inventory inventory =
                CorpusSchemaCandidateCollector.collect(passages);
        assertEquals(List.of("APPROVAL_ROLE"),
                inventory.nodeCandidates().stream()
                        .filter(value -> value.candidateKey().equals("vp finance"))
                        .findFirst().orElseThrow().categories());
        assertEquals(List.of("PERSON"),
                inventory.nodeCandidates().stream()
                        .filter(value -> value.candidateKey().equals("mira chen"))
                        .findFirst().orElseThrow().categories());

        GraphSchema configured = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "Authoritative person.", null),
                        new NodeType("APPROVAL_ROLE", "Authoritative approval role.", null)),
                List.of(new RelationshipType(
                        "HAS_ROLE", "Authoritative role assignment.", null,
                        List.of("holds role"))),
                List.of("(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)"));

        GraphSchema inferred =
                DeterministicCorpusSchemaInferencer.infer(inventory, configured);
        assertNotNull(inferred);
        assertEquals(List.of("APPROVAL_ROLE", "PERSON"),
                inferred.getNodeTypes().stream().map(NodeType::getLabel).toList());
        assertEquals("Authoritative person.",
                inferred.getNodeTypeMap().get("PERSON").getDescription());
        assertEquals(List.of("HAS_ROLE"),
                inferred.getRelationshipTypes().stream()
                        .map(RelationshipType::getType).toList());
        assertTrue(inferred.getRelationshipTypes().get(0).getAliases()
                .containsAll(List.of("has role", "holds role")));
        assertEquals(List.of("(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)"),
                inferred.getPatterns());

        Map<String, ConceptExtractor.ExtractionResult> reversed = new LinkedHashMap<>();
        reversed.put("window-1", passages.get("window-1"));
        reversed.put("window-2", passages.get("window-2"));
        GraphSchema repeated = DeterministicCorpusSchemaInferencer.infer(
                CorpusSchemaCandidateCollector.collect(reversed), configured);
        assertEquals(inferred, repeated,
                "schema must be invariant to passage insertion order");
    }

    @Test
    void instanceNamesAndStatisticalCategoriesAreNotPromotedToOntologyTypes() {
        CorpusSchemaCandidates.Inventory inventory =
                CorpusSchemaCandidateCollector.collect(Map.of(
                        "window", extraction(
                                new ConceptExtractor.ExtractedConcept(
                                        "Mira Chen", "mira chen", "KEYWORD",
                                        0.99d, 5, "person name"),
                                new ConceptExtractor.ExtractedConcept(
                                        "variance bridge", "variance bridge", "TECHNICAL",
                                        0.91d, 3, "technical phrase"),
                                new ConceptExtractor.ConceptRelationship(
                                        "Mira Chen", "variance bridge", "CO_OCCURS", 0.88d))));

        assertEquals(2, inventory.nodeCandidates().size(),
                "candidate collection remains permissive for fallback observations");
        assertNull(DeterministicCorpusSchemaInferencer.infer(inventory, null),
                "statistical categories and co-occurrence must not become ontology types");
    }

    private static ConceptExtractor.ExtractionResult extraction(
            ConceptExtractor.ExtractedConcept first) {
        return new ConceptExtractor.ExtractionResult(
                List.of(first), List.of(), Map.of());
    }

    private static ConceptExtractor.ExtractionResult extraction(
            ConceptExtractor.ExtractedConcept first,
            ConceptExtractor.ExtractedConcept second,
            ConceptExtractor.ConceptRelationship relationship) {
        return new ConceptExtractor.ExtractionResult(
                List.of(first, second), List.of(relationship), Map.of());
    }
}
