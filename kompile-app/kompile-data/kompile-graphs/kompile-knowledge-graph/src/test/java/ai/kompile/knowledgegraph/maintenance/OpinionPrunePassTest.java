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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.pruning.PrunePolicy;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpinionPrunePass}.
 *
 * <p>No Spring context — uses Mockito for the {@link KnowledgeGraphService} seam.
 * Each test constructs synthetic {@link GraphEdge} objects with varying
 * {@code metadataJson} containing a serialized {@link Opinion} (via {@link Opinion#toJson()})
 * under the {@link GraphProvenanceKeys#OPINION} key.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OpinionPrunePass")
class OpinionPrunePassTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @InjectMocks
    private OpinionPrunePass pass;
    private static final Long FS = 42L;
    private static final PrunePolicy DEFAULT_POLICY = PrunePolicy.defaults();

    @BeforeEach
    void setUp() {
        // `pass` is created and field-injected by @InjectMocks (Mockito populates the @Autowired
        // KnowledgeGraphService seam the same way Spring does on the live path).
        // Default: pruneEdges soft-deletes and reports the count back
        when(knowledgeGraphService.pruneEdges(anyCollection(), eq(true), eq(false)))
                .thenAnswer(inv -> {
                    Collection<String> ids = inv.getArgument(0);
                    return GraphPruneResult.ofSoftDelete(List.copyOf(ids), false);
                });
    }

    // ─── Helper builders ─────────────────────────────────────────────────────

    /** Build an active (not stale) edge with a serialized Opinion in its metadataJson. */
    private static GraphEdge edgeWithOpinion(String edgeId, EdgeProvenance prov, Opinion opinion) {
        // Embed the opinion JSON as the value of the "_opinion" key in a flat JSON map.
        String metaJson = "{\"" + GraphProvenanceKeys.OPINION + "\":" + opinion.toJson() + "}";
        return GraphEdge.builder()
                .edgeId(edgeId)
                .provenanceType(prov)
                .metadataJson(metaJson)
                .stale(false)
                .build();
    }

    /** Build an active edge with no opinion in metadata. */
    private static GraphEdge edgeNoOpinion(String edgeId, EdgeProvenance prov) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .provenanceType(prov)
                .metadataJson("{\"_source\":\"crawl\"}")
                .stale(false)
                .build();
    }

    /** Build an active edge with null metadata. */
    private static GraphEdge edgeNullMeta(String edgeId, EdgeProvenance prov) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .provenanceType(prov)
                .metadataJson(null)
                .stale(false)
                .build();
    }

    // ─── Provenance gate ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Provenance gate")
    class ProvenanceGateTests {

        @Test
        @DisplayName("EXTRACTED edges are never pruned regardless of opinion")
        void extractedNeverPruned() {
            Opinion suppressed = Opinion.fromObservedValue(0.02); // would be pruned if eligible
            GraphEdge extracted = edgeWithOpinion("e-ext", EdgeProvenance.EXTRACTED, suppressed);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(extracted));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.scanned());
            assertEquals(1, result.skipped(), "EXTRACTED edge must be skipped");
            assertEquals(0, result.pruned(), "EXTRACTED edge must not be pruned");
            verify(knowledgeGraphService, never()).pruneEdges(anyCollection(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("INFERRED edge with SUPPRESSED opinion is pruned")
        void inferredSuppressedIsPruned() {
            Opinion suppressed = Opinion.fromObservedValue(0.02);
            GraphEdge inferred = edgeWithOpinion("e-inf", EdgeProvenance.INFERRED, suppressed);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(inferred));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.scanned());
            assertEquals(0, result.skipped());
            assertEquals(1, result.pruned());
        }

        @Test
        @DisplayName("AMBIGUOUS edge with SUPPRESSED opinion is pruned")
        void ambiguousSuppressedIsPruned() {
            Opinion suppressed = Opinion.fromObservedValue(0.03);
            GraphEdge ambiguous = edgeWithOpinion("e-amb", EdgeProvenance.AMBIGUOUS, suppressed);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(ambiguous));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.pruned());
        }

        @Test
        @DisplayName("edge with null provenanceType is treated as eligible")
        void nullProvenanceTreatedAsEligible() {
            Opinion suppressed = Opinion.fromObservedValue(0.02);
            GraphEdge edge = edgeWithOpinion("e-null-prov", null, suppressed);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.pruned(), "null provenance type should be treated as eligible");
        }
    }

    // ─── Opinion gate ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Opinion gate")
    class OpinionGateTests {

        @Test
        @DisplayName("INFERRED edge with no _opinion key is skipped")
        void noOpinionKeySkipped() {
            GraphEdge edge = edgeNoOpinion("e-no-opinion", EdgeProvenance.INFERRED);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.scanned());
            assertEquals(1, result.skipped());
            assertEquals(0, result.pruned());
        }

        @Test
        @DisplayName("INFERRED edge with null metadataJson is skipped")
        void nullMetaSkipped() {
            GraphEdge edge = edgeNullMeta("e-null-meta", EdgeProvenance.INFERRED);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.skipped());
            assertEquals(0, result.pruned());
        }
    }

    // ─── Prune decisions ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Prune decisions")
    class PruneDecisionTests {

        @Test
        @DisplayName("ESTABLISHED-band INFERRED edge is kept")
        void establishedKept() {
            Opinion established = Opinion.fromObservedValue(0.95);
            GraphEdge edge = edgeWithOpinion("e-estab", EdgeProvenance.INFERRED, established);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(0, result.pruned(), "ESTABLISHED edge must be kept");
            verify(knowledgeGraphService, never()).pruneEdges(anyCollection(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("belief below minBelief is pruned (band not SUPPRESSED)")
        void lowBeliefPruned() {
            // b=0.05, d=0.60, u=0.35, a=0.5 → SPECULATIVE band, but belief < 0.10
            Opinion low = new Opinion(0.05, 0.60, 0.35, 0.5);
            GraphEdge edge = edgeWithOpinion("e-lowb", EdgeProvenance.INFERRED, low);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            // Policy with pruneSuppressedBand=false but minBelief=0.10
            PrunePolicy policy = new PrunePolicy(0.10, 0.90, 0.05, false);
            OpinionPrunePass.Result result = pass.execute(FS, policy, false);

            assertEquals(1, result.pruned(), "belief=0.05 < minBelief=0.10 → should be pruned");
        }

        @Test
        @DisplayName("high-uncertainty INFERRED edge is pruned")
        void highUncertaintyPruned() {
            // b=0.12, d=0.03, u=0.85, a=0.5 → u > 0.80
            Opinion highU = new Opinion(0.12, 0.03, 0.85, 0.5);
            GraphEdge edge = edgeWithOpinion("e-highu", EdgeProvenance.INFERRED, highU);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(1, result.pruned());
        }

        @Test
        @DisplayName("mixed batch: pruned + kept edges are split correctly")
        void mixedBatch() {
            Opinion suppressed = Opinion.fromObservedValue(0.02);  // pruned
            Opinion established = Opinion.fromObservedValue(0.95); // kept
            Opinion highU = new Opinion(0.12, 0.03, 0.85, 0.5);   // pruned (u > 0.80)

            List<GraphEdge> edges = List.of(
                    edgeWithOpinion("e-s", EdgeProvenance.INFERRED, suppressed),
                    edgeWithOpinion("e-e", EdgeProvenance.INFERRED, established),
                    edgeWithOpinion("e-u", EdgeProvenance.AMBIGUOUS, highU),
                    edgeWithOpinion("e-x", EdgeProvenance.EXTRACTED, suppressed)  // gate → skip
            );
            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(edges);

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

            assertEquals(4, result.scanned());
            assertEquals(1, result.skipped(), "only the EXTRACTED edge should be skipped");
            assertEquals(2, result.pruned(), "suppressed + high-uncertainty should be pruned");
        }
    }

    // ─── Dry-run ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Dry-run mode")
    class DryRunTests {

        @Test
        @DisplayName("dry-run reports pruned count but does not call pruneEdges")
        void dryRunNoWrite() {
            Opinion suppressed = Opinion.fromObservedValue(0.02);
            GraphEdge edge = edgeWithOpinion("e-dry", EdgeProvenance.INFERRED, suppressed);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, /* dryRun= */ true);

            assertEquals(1, result.pruned(), "dry-run should count the would-be pruned edge");
            assertTrue(result.dryRun());
            verify(knowledgeGraphService, never())
                    .pruneEdges(anyCollection(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("non-dry-run calls pruneEdges with correct edge ids")
        void nonDryRunCallsPruneEdges() {
            Opinion suppressed = Opinion.fromObservedValue(0.02);
            GraphEdge edge = edgeWithOpinion("e-live", EdgeProvenance.INFERRED, suppressed);

            when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(edge));

            pass.execute(FS, DEFAULT_POLICY, /* dryRun= */ false);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(knowledgeGraphService).pruneEdges(captor.capture(), eq(true), eq(false));
            assertTrue(captor.getValue().contains("e-live"));
        }
    }

    // ─── Empty graph ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty fact-sheet returns zeroed result")
    void emptyGraph() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of());

        OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

        assertEquals(0, result.scanned());
        assertEquals(0, result.skipped());
        assertEquals(0, result.pruned());
        assertFalse(result.dryRun());
    }

    // ─── readOpinion (static helper) ─────────────────────────────────────────

    @Nested
    @DisplayName("OpinionPrunePass.readOpinion static helper")
    class ReadOpinionTests {

        @Test
        @DisplayName("round-trip: Opinion → toJson → readOpinion → same values")
        void roundTrip() {
            Opinion original = new Opinion(0.5, 0.3, 0.2, 0.6);
            String metaJson = "{\"" + GraphProvenanceKeys.OPINION + "\":" + original.toJson() + "}";

            Opinion parsed = OpinionPrunePass.readOpinion(metaJson, "edge-rt");

            assertNotNull(parsed);
            assertEquals(original.belief(), parsed.belief(), 1e-7);
            assertEquals(original.disbelief(), parsed.disbelief(), 1e-7);
            assertEquals(original.uncertainty(), parsed.uncertainty(), 1e-7);
            assertEquals(original.baseRate(), parsed.baseRate(), 1e-7);
        }

        @Test
        @DisplayName("absent _opinion key returns null")
        void absentKeyReturnsNull() {
            String metaJson = "{\"_source\":\"crawl\"}";
            assertNull(OpinionPrunePass.readOpinion(metaJson, "edge-absent"));
        }

        @Test
        @DisplayName("opinion embedded in larger metadata map is extracted correctly")
        void opinionInLargerMap() {
            Opinion o = Opinion.fromBetaEvidence(5, 2);
            String metaJson = "{\"_source\":\"crawl\",\"_crawlRunId\":\"run-1\","
                    + "\"" + GraphProvenanceKeys.OPINION + "\":" + o.toJson()
                    + ",\"_basisType\":\"LLM_EXTRACTION\"}";

            Opinion parsed = OpinionPrunePass.readOpinion(metaJson, "edge-big");

            assertNotNull(parsed);
            assertEquals(o.belief(), parsed.belief(), 1e-7);
        }

        @Test
        @DisplayName("malformed JSON for _opinion returns null without throwing")
        void malformedJsonReturnsNull() {
            String metaJson = "{\"" + GraphProvenanceKeys.OPINION + "\":{BROKEN}}";
            // Should not throw — should return null and log warn
            assertDoesNotThrow(() -> OpinionPrunePass.readOpinion(metaJson, "edge-broken"));
        }
    }

    // ─── Stale edges are excluded ─────────────────────────────────────────────

    @Test
    @DisplayName("stale edges are excluded from evaluation")
    void staleEdgesExcluded() {
        Opinion suppressed = Opinion.fromObservedValue(0.02);
        GraphEdge stale = GraphEdge.builder()
                .edgeId("e-stale")
                .provenanceType(EdgeProvenance.INFERRED)
                .metadataJson("{\"" + GraphProvenanceKeys.OPINION + "\":" + suppressed.toJson() + "}")
                .stale(true)
                .build();

        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(stale));

        OpinionPrunePass.Result result = pass.execute(FS, DEFAULT_POLICY, false);

        assertEquals(0, result.scanned(), "stale edge should not be scanned");
        assertEquals(0, result.pruned());
    }
}
