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
package ai.kompile.crawl.graph.passes;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.graph.reasoning.admission.AdmissionComparison;
import ai.kompile.graph.reasoning.admission.AdmissionDecision;
import ai.kompile.graph.reasoning.admission.AdmissionMode;
import ai.kompile.graph.reasoning.admission.GraphOperationalPolicy;
import ai.kompile.graph.reasoning.admission.OperationalDisposition;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration-level admission assertions through the crawler extraction adapter. */
class ExtractionAdmissionComparatorComplexGraphTest {

    @Test
    void canonicalIdentityCanReuseAnExistingDifferentSourceId() {
        UnifiedGraph graph = new UnifiedGraph().graphId("graph-complex");
        graph.addEntity(GraphEntity.builder("persisted-acme")
                .type("PERSON")
                .label("Acme")
                .weight(0.90)
                .confidence(1.0)
                .attribute("canonicalKey", "acme\u0000person\u0000acme")
                .build());

        List<AdmissionComparison> comparisons = compare(
                graph, new ExtractedEntity("mention-acme", "Acme", "PERSON",
                        List.of("Acme"), "new", 0.95, Map.of()));

        assertEquals(1, comparisons.size());
        assertEquals(AdmissionDecision.REUSE, comparisons.get(0).graphResult().decision(),
                "the graph branch should use canonical/type evidence, not exact source id only");
    }

    @Test
    void unrelatedHighPriorNodesCannotVetoAnExistingExtractedCandidate() {
        UnifiedGraph graph = new UnifiedGraph().graphId("graph-complex");
        graph.addEntity(GraphEntity.builder("known")
                .type("PERSON").label("Known").weight(1.0).confidence(1.0).build());
        graph.addEntity(GraphEntity.builder("unrelated-hub")
                .type("PERSON").label("Celebrity").weight(1.0).confidence(1.0).build());
        for (int i = 0; i < 24; i++) {
            graph.addEntity(GraphEntity.builder("background-" + i)
                    .type("FACT").label("background").weight(0.10).confidence(0.10).build());
        }

        List<AdmissionComparison> comparisons = compare(
                graph, new ExtractedEntity("known", "Known", "PERSON",
                        List.of(), "existing", 0.95, Map.of()));

        assertEquals(1, comparisons.size());
        AdmissionComparison comparison = comparisons.get(0);
        assertEquals(AdmissionDecision.REUSE, comparison.llmDecision());
        assertEquals(AdmissionDecision.REUSE, comparison.authoritativeDecision());
        assertEquals(AdmissionDecision.REUSE, comparison.graphResult().decision(),
                "graph shadow mode must not compare against the whole unrelated snapshot");
    }

    @Test
    void everyEntityInOneExtractionBatchUsesTheSameSnapshotRevision() {
        UnifiedGraph graph = new UnifiedGraph().graphId("graph-complex");
        graph.addEntity("known", "PERSON", "Known");
        graph.addEntity("known-2", "PERSON", "Known Two");

        List<AdmissionComparison> comparisons = new ArrayList<>();
        ExtractionAdmissionComparator.compare(
                new ExtractionResult(
                        GraphExtractionSchema.SCHEMA_VERSION,
                        List.of(
                                new ExtractedEntity("known", "Known", "PERSON", List.of(), "existing", 0.9, Map.of()),
                                new ExtractedEntity("known-2", "Known Two", "PERSON", List.of(), "existing", 0.9, Map.of())),
                        List.of(),
                        new GraphExtractionSchema.ExtractionMetadata("chunk", "doc", "model", "now")),
                null, graph, "revision-complex", AdmissionMode.SHADOW_COMPARE, comparisons::add);

        assertEquals(2, comparisons.size());
        assertTrue(comparisons.stream().allMatch(item -> "revision-complex".equals(item.snapshotId())));
        assertTrue(comparisons.stream().allMatch(item -> item.graphResult().evaluated()));
    }

    @Test
    void graphPolicyWithholdsAnExistingEntityUpdateWithoutDeletingTheSnapshotEntity() {
        UnifiedGraph graph = new UnifiedGraph().graphId("graph-complex");
        graph.addEntity(GraphEntity.builder("existing-workbook")
                .type("WORKBOOK")
                .label("Existing workbook")
                .weight(1.0)
                .confidence(1.0)
                .build());
        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(
                        new ExtractedEntity(
                                "existing-workbook", "Existing workbook", "WORKBOOK",
                                List.of(), "attempted update", 0.95, Map.of()),
                        new ExtractedEntity(
                                "status-blocked", "Do not use", "STATUS",
                                List.of(), null, 0.95, Map.of())),
                List.of(new ExtractedRelation(
                        "existing-workbook", "status-blocked", "HAS_STATUS",
                        null, 1.0, Map.of())),
                null);
        GraphOperationalPolicy policy = GraphOperationalPolicy.builder()
                .rule("fpna.status.not-usable", OperationalDisposition.DENY, 100,
                        "Do not mutate the workbook.")
                .build();

        ExtractionAdmissionComparator.AdmissionOutcome outcome =
                ExtractionAdmissionComparator.admit(
                        extraction,
                        null,
                        graph,
                        "revision-complex",
                        AdmissionMode.GRAPH_POLICY,
                        Set.of("WORKBOOK"),
                        policy,
                        null);

        assertEquals(List.of("status-blocked"),
                outcome.extraction().entities().stream().map(ExtractedEntity::id).toList());
        assertTrue(outcome.extraction().relations().isEmpty());
        assertEquals(OperationalDisposition.DENY, outcome.verdicts().get(0).disposition());
        assertTrue(graph.entity("existing-workbook").isPresent(),
                "admission filters the staged delta and never mutates the frozen snapshot");
        assertFalse(graph.entity("status-blocked").isPresent(),
                "candidate evidence is overlaid only on a transient graph");
    }

    private List<AdmissionComparison> compare(UnifiedGraph graph, ExtractedEntity entity) {
        List<AdmissionComparison> comparisons = new ArrayList<>();
        ExtractionAdmissionComparator.compare(
                extraction(entity), null, graph, "revision-complex", AdmissionMode.SHADOW_COMPARE,
                comparisons::add);
        return comparisons;
    }

    private ExtractionResult extraction(ExtractedEntity entity) {
        return new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(entity),
                List.of(),
                new GraphExtractionSchema.ExtractionMetadata("chunk", "doc", "model", "now"));
    }
}
