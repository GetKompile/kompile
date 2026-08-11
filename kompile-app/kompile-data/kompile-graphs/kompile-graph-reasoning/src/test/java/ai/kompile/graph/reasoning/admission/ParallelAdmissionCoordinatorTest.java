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
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAdmissionCoordinatorTest {

    @Test
    void shadowModeSharesTheRequestAndKeepsLlmAuthoritative() {
        AdmissionRequest request = request();
        AtomicReference<AdmissionRequest> llmSeen = new AtomicReference<>();
        AtomicReference<AdmissionRequest> graphSeen = new AtomicReference<>();

        GraphAdmissionEvaluator graph = input -> {
            graphSeen.set(input);
            return new GraphAdmissionResult(
                    AdmissionDecision.REUSE, 0.91, 0.35,
                    "graph supports reuse", true, false, null);
        };

        AdmissionComparison comparison = new ParallelAdmissionCoordinator(
                AdmissionMode.SHADOW_COMPARE, graph, Runnable::run, null)
                .evaluate(request, input -> {
                    llmSeen.set(input);
                    return AdmissionDecision.REJECT;
                });

        assertSame(request, llmSeen.get());
        assertSame(request, graphSeen.get());
        assertEquals(AdmissionDecision.REJECT, comparison.llmDecision());
        assertEquals(AdmissionDecision.REUSE, comparison.graphResult().decision());
        assertEquals(AdmissionDecision.REJECT, comparison.authoritativeDecision());
        assertFalse(comparison.agrees());
        assertTrue(comparison.graphAvailable());
        assertEquals(request.ballotFingerprint(), comparison.ballotFingerprint());
    }

    @Test
    void graphFailureDegradesToDeferredWithoutChangingLlmDecision() {
        AdmissionComparison comparison = new ParallelAdmissionCoordinator(
                AdmissionMode.SHADOW_COMPARE,
                input -> { throw new IllegalStateException("snapshot unavailable"); },
                Runnable::run,
                null)
                .evaluate(request(), input -> AdmissionDecision.CREATE_PROVISIONAL);

        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.authoritativeDecision());
        assertEquals(AdmissionDecision.DEFER, comparison.graphResult().decision());
        assertTrue(comparison.graphResult().degraded());
        assertEquals("snapshot unavailable", comparison.graphResult().error());
        assertFalse(comparison.graphAvailable());
    }

    @Test
    void llmOnlyNeverInvokesTheGraphBranch() {
        AtomicBoolean invoked = new AtomicBoolean();
        AdmissionComparison comparison = new ParallelAdmissionCoordinator(
                AdmissionMode.LLM_ONLY,
                input -> {
                    invoked.set(true);
                    throw new AssertionError("graph branch must not run");
                },
                Runnable::run,
                null)
                .evaluate(request(), input -> AdmissionDecision.REUSE);

        assertFalse(invoked.get());
        assertEquals(AdmissionDecision.REUSE, comparison.authoritativeDecision());
        assertFalse(comparison.graphResult().evaluated());
        assertEquals(AdmissionMode.LLM_ONLY, comparison.mode());
    }

    @Test
    void llmOnlyCanRunWithoutMaterializingAGraphSnapshot() {
        AdmissionCandidate selected = new AdmissionCandidate("candidate", "person:acme");
        AdmissionRequest request = new AdmissionRequest(
                "group-1", selected, List.of(selected),
                "not-materialized", "policy:v1", null);

        AdmissionComparison comparison = new ParallelAdmissionCoordinator(
                AdmissionMode.LLM_ONLY, null, Runnable::run, null)
                .evaluate(request, input -> AdmissionDecision.CREATE_PROVISIONAL);

        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.authoritativeDecision());
        assertFalse(comparison.graphResult().evaluated());
    }

    @Test
    void requestFreezesAndFingerprintsTheBallot() {
        AdmissionCandidate selected = new AdmissionCandidate("candidate", "person:acme");
        AdmissionCandidate rival = new AdmissionCandidate("rival", "person:globex");
        AdmissionRequest request = new AdmissionRequest(
                "group-1", selected,
                List.of(selected, rival, selected),
                "graph:42", "policy:v1", new UnifiedGraph());

        assertEquals(2, request.ballot().size());
        assertEquals(request.ballotFingerprint(), request.ballotFingerprint());
        assertTrue(request.ballot().contains(selected));
    }

    @Test
    void hybridEvaluatorDefersAProvisionalNodeAbsentFromTheSnapshot() {
        AdmissionRequest request = request();
        GraphAdmissionResult result = new HybridGraphAdmissionEvaluator().evaluate(request);

        assertEquals(AdmissionDecision.DEFER, result.decision());
        assertTrue(result.evaluated());
        assertFalse(result.degraded());
    }

    @Test
    void hybridRankingUsesAnIdTieBreakForReproducibleBallots() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("z", "TYPE", "z");
        graph.addEntity("a", "TYPE", "a");

        var ranking = new ai.kompile.graph.reasoning.hybrid.HybridReasoner()
                .rankWithStructuralScores(graph, Map.of("z", 0.5, "a", 0.5), null);

        assertEquals("a", ranking.get(0).entityId());
        assertEquals("z", ranking.get(1).entityId());
    }

    private AdmissionRequest request() {
        AdmissionCandidate selected = new AdmissionCandidate("candidate", "person:acme");
        AdmissionCandidate rival = new AdmissionCandidate("rival", "person:globex");
        return new AdmissionRequest(
                "group-1", selected, List.of(selected, rival),
                "graph:42", "policy:v1", new UnifiedGraph());
    }
}
