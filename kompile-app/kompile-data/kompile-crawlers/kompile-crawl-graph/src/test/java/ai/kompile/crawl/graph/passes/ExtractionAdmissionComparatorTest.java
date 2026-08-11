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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionAdmissionComparatorTest {

    @Test
    void shadowComparesTheSameExtractedIdsAndKeepsLlmAuthoritative() {
        UnifiedGraph graph = new UnifiedGraph().graphId("graph-1");
        graph.addEntity(GraphEntity.builder("known")
                .type("PERSON")
                .label("Known Person")
                .confidence(0.95)
                .build());

        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(
                        new ExtractedEntity("known", "Known Person", "PERSON",
                                List.of("KP"), "existing", 0.9, java.util.Map.of()),
                        new ExtractedEntity("new", "New Person", "PERSON",
                                List.of(), "new", 0.8, java.util.Map.of())),
                List.of(),
                new GraphExtractionSchema.ExtractionMetadata("chunk-1", "doc-1", "model", "now"));

        List<AdmissionComparison> comparisons = new ArrayList<>();
        ExtractionAdmissionComparator.compare(
                extraction, null, graph, "revision-7", AdmissionMode.SHADOW_COMPARE, comparisons::add);

        assertEquals(2, comparisons.size());
        assertEquals("known", comparisons.get(0).candidateId());
        assertEquals(AdmissionDecision.REUSE, comparisons.get(0).llmDecision());
        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparisons.get(1).llmDecision());
        assertEquals(AdmissionMode.SHADOW_COMPARE, comparisons.get(0).mode());
        assertEquals("revision-7", comparisons.get(0).snapshotId());
        assertTrue(comparisons.stream().allMatch(AdmissionComparison::graphAvailable));
        assertTrue(comparisons.stream().allMatch(item -> item.authoritativeDecision() == item.llmDecision()));
    }

    @Test
    void llmOnlyDoesNotMaterializeOrEmitAComparison() {
        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(new ExtractedEntity("new", "New Person", "PERSON",
                        List.of(), null, 0.8, java.util.Map.of())),
                List.of(),
                new GraphExtractionSchema.ExtractionMetadata("chunk-1", "doc-1", "model", "now"));

        boolean[] emitted = {false};
        ExtractionAdmissionComparator.compare(
                extraction, null, null, null, AdmissionMode.LLM_ONLY,
                comparison -> emitted[0] = true);

        assertFalse(emitted[0]);
    }

    @Test
    void sinkFailureIsIsolatedFromExtractionComparison() {
        UnifiedGraph graph = new UnifiedGraph().graphId("graph-1");
        graph.addEntity(GraphEntity.builder("known")
                .type("PERSON").label("Known Person").confidence(0.95).build());

        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(new ExtractedEntity("known", "Known Person", "PERSON",
                        List.of(), null, 0.9, java.util.Map.of())),
                List.of(),
                new GraphExtractionSchema.ExtractionMetadata("chunk-1", "doc-1", "model", "now"));

        boolean completed = true;
        try {
            ExtractionAdmissionComparator.compare(
                    extraction, null, graph, "revision-7", AdmissionMode.SHADOW_COMPARE,
                    comparison -> {
                        throw new IllegalStateException("diagnostic sink unavailable");
                    });
        } catch (RuntimeException ignored) {
            completed = false;
        }
        assertTrue(completed);
    }

    @Test
    void graphPolicyAdmitsOnlyExplicitAllowsFromTheCandidateInclusiveGraph() {
        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(
                        entity("workbook-approved", "Approved workbook", "WORKBOOK"),
                        entity("workbook-blocked", "Blocked workbook", "WORKBOOK"),
                        entity("workbook-unmatched", "Unmatched workbook", "WORKBOOK"),
                        entity("status-current", "Current", "STATUS"),
                        entity("status-blocked", "Do not use", "STATUS"),
                        entity("neutral-context", "Unclassified context", "CONTEXT")),
                List.of(
                        relation("workbook-approved", "status-current", "HAS_STATUS"),
                        relation("workbook-blocked", "status-blocked", "HAS_STATUS"),
                        relation("workbook-unmatched", "neutral-context", "RELATED_TO")),
                new GraphExtractionSchema.ExtractionMetadata(
                        "chunk-policy", "doc-policy", "model", "now"));
        GraphOperationalPolicy policy = GraphOperationalPolicy.builder()
                .rule("fpna.status.not-usable", OperationalDisposition.DENY, 100,
                        "The workbook must not be inserted.")
                .rule("fpna.status.authoritative", OperationalDisposition.ALLOW, 10,
                        "The workbook is authoritative.")
                .build();
        List<AdmissionComparison> emitted = new ArrayList<>();

        ExtractionAdmissionComparator.AdmissionOutcome outcome =
                ExtractionAdmissionComparator.admit(
                        extraction,
                        null,
                        new UnifiedGraph().graphId("graph-policy"),
                        "revision-policy",
                        AdmissionMode.GRAPH_POLICY,
                        Set.of("WORKBOOK"),
                        policy,
                        emitted::add);

        assertEquals(3, emitted.size());
        assertEquals(3, outcome.comparisons().size());
        assertEquals(3, outcome.verdicts().size());
        assertEquals(
                List.of("neutral-context", "status-blocked", "status-current", "workbook-approved"),
                outcome.extraction().entities().stream()
                        .map(ExtractedEntity::id)
                        .sorted()
                        .toList());
        assertEquals(1, outcome.extraction().relations().size());
        assertEquals("workbook-approved", outcome.extraction().relations().get(0).source());

        Map<String, OperationalDisposition> dispositions = outcome.verdicts().stream()
                .collect(java.util.stream.Collectors.toMap(
                        verdict -> verdict.candidateId(),
                        verdict -> verdict.disposition()));
        assertEquals(OperationalDisposition.ALLOW, dispositions.get("workbook-approved"));
        assertEquals(OperationalDisposition.DENY, dispositions.get("workbook-blocked"));
        assertEquals(OperationalDisposition.REVIEW, dispositions.get("workbook-unmatched"));
        Map<String, AdmissionDecision> authoritative = outcome.comparisons().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AdmissionComparison::candidateId,
                        AdmissionComparison::authoritativeDecision));
        assertEquals(AdmissionDecision.CREATE_PROVISIONAL,
                authoritative.get("workbook-approved"));
        assertEquals(AdmissionDecision.REJECT, authoritative.get("workbook-blocked"));
        assertEquals(AdmissionDecision.DEFER, authoritative.get("workbook-unmatched"));
    }

    @Test
    void graphPolicyConfigurationAndModeTyposFailClosed() {
        assertEquals(AdmissionMode.GRAPH_POLICY,
                ExtractionAdmissionComparator.mode(" graph_policy "));
        assertThrows(IllegalArgumentException.class,
                () -> ExtractionAdmissionComparator.mode("graph-policy-typo"));

        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(entity("candidate", "Candidate", "WORKBOOK")),
                List.of(),
                null);
        assertThrows(NullPointerException.class, () ->
                ExtractionAdmissionComparator.admit(
                        extraction,
                        null,
                        new UnifiedGraph().graphId("graph-policy"),
                        "revision-policy",
                        AdmissionMode.GRAPH_POLICY,
                        Set.of("WORKBOOK"),
                        null,
                        null));
        assertThrows(IllegalArgumentException.class, () ->
                ExtractionAdmissionComparator.admit(
                        extraction,
                        null,
                        new UnifiedGraph().graphId("graph-policy"),
                        "revision-policy",
                        AdmissionMode.GRAPH_POLICY,
                        Set.of(),
                        GraphOperationalPolicy.builder()
                                .rule("allow", OperationalDisposition.ALLOW, 1, "Allow.")
                                .build(),
                        null));
    }

    private static ExtractedEntity entity(String id, String name, String type) {
        return new ExtractedEntity(id, name, type, List.of(), null, 0.95, Map.of());
    }

    private static ExtractedRelation relation(String source, String target, String type) {
        return new ExtractedRelation(source, target, type, null, 1.0, Map.of());
    }
}
