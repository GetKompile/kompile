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

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metamorphic tests for graph admission. These deliberately encode the intended admission
 * invariants rather than the current implementation's whole-graph structural quirks.
 *
 * <p>The first batch is expected to expose red tests until the admission projection and policy are
 * repaired. Keeping the fixtures small and deterministic makes each failure directly replayable.</p>
 */
class HybridGraphAdmissionEvaluatorMetamorphicTest {

    @Test
    void disconnectedEvidenceCannotChangeTheSelectedDecision() {
        UnifiedGraph graph = new UnifiedGraph().addEntity(entity("candidate", 1.0, 1.0));
        GraphAdmissionResult before = evaluate(graph, "candidate", List.of(candidate("candidate")));

        graph.addEntity(entity("unrelated-hub", 1.0, 1.0));
        GraphAdmissionResult after = evaluate(graph, "candidate", List.of(candidate("candidate")));

        assertEquals(AdmissionDecision.REUSE, before.decision());
        assertEquals(AdmissionDecision.REUSE, after.decision(),
                "an unrelated component must not become the candidate's competitor");
        assertEquals(before.score(), after.score(), 1e-9);
    }

    @Test
    void edgesBelowTheInferenceCutoffDoNotInflateAdmissionEvidence() {
        UnifiedGraph graph = new UnifiedGraph().addEntity(entity("candidate", 0.75, 1.0));
        GraphAdmissionResult before = evaluate(graph, "candidate", List.of(candidate("candidate")));

        for (int i = 0; i < 10; i++) {
            String source = "noise-" + i;
            graph.addEntity(entity(source, 0.1, 0.0));
            graph.addRelation(new SimpleGraphRelation(
                    "noise-edge-" + i, "candidate", source, "NOISE", 0.01, 0.0,
                    true, Set.of(), null, null, Map.of()));
        }
        GraphAdmissionResult after = evaluate(graph, "candidate", List.of(candidate("candidate")));

        assertEquals(before.decision(), after.decision());
        assertEquals(before.score(), after.score(), 1e-6,
                "relations discarded by the reasoner must not affect the node prior");
    }

    @Test
    void contradictoryEvidenceCannotBeEquivalentToSupportingEvidence() {
        UnifiedGraph supports = relatedGraph("SUPPORTS");
        UnifiedGraph contradicts = relatedGraph("CONTRADICTS");

        GraphAdmissionResult positive = evaluate(supports, "candidate", List.of(candidate("candidate")));
        GraphAdmissionResult negative = evaluate(contradicts, "candidate", List.of(candidate("candidate")));

        assertTrue(negative.score() < positive.score(),
                "negative relation evidence must lower identity admission confidence");
        assertNotEquals(AdmissionDecision.REUSE, negative.decision(),
                "contradictory evidence must not admit the candidate as a reuse");
    }

    @Test
    void zeroConfidenceEvidenceCannotAdmitLikeTrustedEvidence() {
        UnifiedGraph trusted = new UnifiedGraph().addEntity(entity("candidate", 0.9, 1.0));
        UnifiedGraph untrusted = new UnifiedGraph().addEntity(entity("candidate", 0.9, 0.0));

        GraphAdmissionResult trustedResult = evaluate(trusted, "candidate", List.of(candidate("candidate")));
        GraphAdmissionResult untrustedResult = evaluate(untrusted, "candidate", List.of(candidate("candidate")));

        assertEquals(AdmissionDecision.REUSE, trustedResult.decision());
        assertNotEquals(AdmissionDecision.REUSE, untrustedResult.decision(),
                "zero-confidence nodes must not be treated as trusted identity evidence");
        assertTrue(untrustedResult.score() < trustedResult.score());
    }

    @Test
    void reversingAnUndirectedRelationDoesNotChangeAdmission() {
        UnifiedGraph forward = new UnifiedGraph()
                .addEntity(entity("candidate", 0.4, 1.0))
                .addEntity(entity("source", 0.4, 1.0))
                .addRelation(SimpleGraphRelation.undirected("r", "source", "candidate", "SAME_AS", 0.8));
        UnifiedGraph reverse = new UnifiedGraph()
                .addEntity(entity("candidate", 0.4, 1.0))
                .addEntity(entity("source", 0.4, 1.0))
                .addRelation(SimpleGraphRelation.undirected("r", "candidate", "source", "SAME_AS", 0.8));

        GraphAdmissionResult first = evaluate(forward, "candidate", List.of(candidate("candidate")));
        GraphAdmissionResult second = evaluate(reverse, "candidate", List.of(candidate("candidate")));

        assertEquals(first.score(), second.score(), 1e-9);
        assertEquals(first.margin(), second.margin(), 1e-9);
        assertEquals(first.decision(), second.decision());
    }

    @Test
    void parallelRelationsAreProjectedIndependentOfInsertionOrder() {
        UnifiedGraph strongLast = parallelGraph(0.1, 0.9);
        UnifiedGraph weakLast = parallelGraph(0.9, 0.1);

        var first = new GraphPslProgramBuilder().build(strongLast);
        var second = new GraphPslProgramBuilder().build(weakLast);

        assertEquals(linkValue(first), linkValue(second), 1e-9,
                "parallel evidence must have an explicit deterministic aggregation rule");
    }

    @Test
    void bayesianEqualWeightCyclesArePermutationInvariant() {
        UnifiedGraph first = cycleGraph(List.of("a", "b", "c"));
        UnifiedGraph second = cycleGraph(List.of("b", "c", "a"));

        Map<String, Double> firstScores = scores(new HybridReasoner()
                .structural(HybridReasoner.Structural.BAYESIAN).rank(first));
        Map<String, Double> secondScores = scores(new HybridReasoner()
                .structural(HybridReasoner.Structural.BAYESIAN).rank(second));

        for (String id : firstScores.keySet()) {
            assertEquals(firstScores.get(id), secondScores.get(id), 1e-9,
                    "cycle projection changed for node " + id);
        }
    }

    @Test
    void admissionUsesBallotCompetitorsInsteadOfEveryGraphNode() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity("candidate", 1.0, 1.0))
                .addEntity(entity("unrelated", 0.85, 1.0));

        GraphAdmissionResult result = evaluate(graph, "candidate", List.of(candidate("candidate")));

        assertEquals(AdmissionDecision.REUSE, result.decision(),
                "a node outside the ballot must not block a singleton admission");
    }

    @Test
    void canonicalMatchCanReuseAnExistingDifferentNodeId() {
        UnifiedGraph graph = new UnifiedGraph().addEntity(new SimpleGraphEntity(
                "persisted-id", "PERSON", "Acme", 0.9, 1.0, Set.of(), null, null,
                Map.of("canonicalKey", "person:acme")));

        GraphAdmissionResult result = evaluate(graph, "extracted-id",
                List.of(candidate("extracted-id", "person:acme")));

        assertEquals(AdmissionDecision.REUSE, result.decision(),
                "canonical identity evidence must be usable even when source ids differ");
        assertEquals("persisted-id", result.reuseTargetId(),
                "reuse must identify the persisted node selected by canonical identity");
    }

    @Test
    void canonicalMatchDefersWhenSeveralPersistedNodesShareTheKey() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(new SimpleGraphEntity(
                        "summary-amer", "SHEET", "Summary", 0.9, 1.0, Set.of(), null, null,
                        Map.of("canonicalKey", "sheet:summary")))
                .addEntity(new SimpleGraphEntity(
                        "summary-apac", "SHEET", "Summary", 0.9, 1.0, Set.of(), null, null,
                        Map.of("canonicalKey", "sheet:summary")));

        GraphAdmissionResult result = evaluate(graph, "extracted-summary",
                List.of(candidate("extracted-summary", "sheet:summary")));

        assertEquals(AdmissionDecision.DEFER, result.decision());
        assertEquals("candidate canonical identity matches multiple graph entities", result.reason());
        assertNull(result.reuseTargetId());
        assertEquals(2, result.evidenceTrace()
                .itemsOfKind(AdmissionEvidence.Kind.AMBIGUITY).size());
    }

    @Test
    void identitySeparationBetweenPersistedNodesDoesNotInvalidateEitherSingleton() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity("mei", 1.0, 1.0))
                .addEntity(entity("sarah", 1.0, 1.0))
                .addRelation(SimpleGraphRelation.undirected(
                        "distinct-people", "mei", "sarah", "DIFFERENT_FROM", 1.0));

        GraphAdmissionResult mei = evaluate(graph, "mei", List.of(candidate("mei")));
        GraphAdmissionResult sarah = evaluate(graph, "sarah", List.of(candidate("sarah")));

        assertEquals(AdmissionDecision.REUSE, mei.decision(),
                "a forbidden merge is evidence that both canonical nodes remain valid");
        assertEquals(AdmissionDecision.REUSE, sarah.decision(),
                "identity separation must not be projected as epistemic invalidity");
    }

    @Test
    void identitySeparationAgainstABallotCompetitorPreventsReuse() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity("selected", 1.0, 1.0))
                .addEntity(entity("competitor", 0.1, 0.1))
                .addRelation(SimpleGraphRelation.undirected(
                        "distinct-candidates", "selected", "competitor", "DIFFERENT_FROM", 1.0));

        GraphAdmissionResult result = evaluate(graph, "selected",
                List.of(candidate("selected"), candidate("competitor")));

        assertEquals(AdmissionDecision.DEFER, result.decision());
        assertEquals("candidate has contradictory graph evidence", result.reason());
        assertTrue(result.evidenceTrace().hasKind(AdmissionEvidence.Kind.IDENTITY_CONSTRAINT));
    }

    @Test
    void canonicalIdentityAndStructuralScoresAreExposedAsMachineReadableEvidence() {
        UnifiedGraph graph = new UnifiedGraph().addEntity(new SimpleGraphEntity(
                "persisted-id", "PERSON", "Acme", 0.9, 1.0, Set.of(), null, null,
                Map.of("canonicalKey", "person:acme")));

        GraphAdmissionResult result = evaluate(graph, "extracted-id",
                List.of(candidate("extracted-id", "person:acme")));

        AdmissionEvidence identity = result.evidenceTrace()
                .itemsOfKind(AdmissionEvidence.Kind.IDENTITY_MATCH).get(0);
        AdmissionEvidence score = result.evidenceTrace()
                .itemsOfKind(AdmissionEvidence.Kind.STRUCTURAL_SCORE).get(0);
        assertEquals("identity.canonical-match", identity.ruleId());
        assertEquals(List.of("persisted-id"), identity.entityPath());
        assertEquals("hybrid.structural-score", score.ruleId());
        assertTrue(score.summary().contains("structural="));
    }

    @Test
    void doNotUseStatusIsCautionaryWithoutInvalidatingIdentityReuse() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("sheet-summary", "SHEET", "Summary", 1.0, 1.0))
                .addEntity(typedEntity("status-do-not-use", "STATUS", "Do not use", 0.8, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "summary-status", "sheet-summary", "status-do-not-use", "HAS_STATUS", 1.0));

        GraphAdmissionResult result = evaluate(
                graph, "sheet-summary", List.of(candidate("sheet-summary")));

        assertEquals(AdmissionDecision.REUSE, result.decision(),
                "operational status must not be confused with entity existence");
        assertTrue(result.reason().contains("cautionary domain evidence"));
        AdmissionEvidence caution = result.evidenceTrace()
                .itemsOfKind(AdmissionEvidence.Kind.CAUTION).get(0);
        assertEquals("fpna.status.not-usable", caution.ruleId());
        assertEquals(List.of("HAS_STATUS"), caution.predicatePath());
        assertEquals(List.of("sheet-summary", "status-do-not-use"), caution.entityPath());
    }

    @Test
    void authoritativeStatusProducesAffirmingEvidence() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("version-v2", "VERSION", "Version 2", 1.0, 1.0))
                .addEntity(typedEntity(
                        "status-authoritative", "STATUS", "Authoritative", 0.8, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "v2-status", "version-v2", "status-authoritative", "HAS_STATUS", 1.0));

        GraphAdmissionResult result = evaluate(
                graph, "version-v2", List.of(candidate("version-v2")));

        AdmissionEvidence affirming = result.evidenceTrace()
                .itemsOfKind(AdmissionEvidence.Kind.AFFIRMING_RELATION).get(0);
        assertEquals("fpna.status.authoritative", affirming.ruleId());
    }

    @Test
    void statusConceptItselfIsContextRatherThanTheSubjectOfItsWarning() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("sheet-summary", "SHEET", "Summary", 1.0, 1.0))
                .addEntity(typedEntity("status-do-not-use", "STATUS", "Do not use", 1.0, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "summary-status", "sheet-summary", "status-do-not-use", "HAS_STATUS", 1.0));

        GraphAdmissionResult result = evaluate(
                graph, "status-do-not-use", List.of(candidate("status-do-not-use")));

        assertTrue(result.evidenceTrace().items().stream()
                .noneMatch(item -> "fpna.status.not-usable".equals(item.ruleId())));
        assertTrue(result.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.status.assignment".equals(item.ruleId())
                        && item.kind() == AdmissionEvidence.Kind.CONTEXT_RELATION));
    }

    @Test
    void supersedesEvidenceIsDirectional() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("version-v2", "VERSION", "Version 2", 1.0, 1.0))
                .addEntity(typedEntity("version-v1", "VERSION", "Version 1", 1.0, 1.0))
                .addEntity(typedEntity("status-ignore", "STATUS", "Ignore", 1.0, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "v2-supersedes-v1", "version-v2", "version-v1", "SUPERSEDES", 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "v1-status", "version-v1", "status-ignore", "HAS_STATUS", 1.0));

        GraphAdmissionResult current = evaluate(
                graph, "version-v2", List.of(candidate("version-v2")));
        GraphAdmissionResult obsolete = evaluate(
                graph, "version-v1", List.of(candidate("version-v1")));

        assertTrue(current.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.version.supersedes".equals(item.ruleId())
                        && item.kind() == AdmissionEvidence.Kind.AFFIRMING_RELATION));
        assertTrue(current.evidenceTrace().items().stream()
                .noneMatch(item -> "fpna.status.not-usable".equals(item.ruleId())),
                "a warning on the superseded version must not leak onto the current version");
        assertTrue(obsolete.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.version.superseded".equals(item.ruleId())
                        && item.kind() == AdmissionEvidence.Kind.CAUTION));
        assertTrue(obsolete.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.status.not-usable".equals(item.ruleId())));
    }

    @Test
    void requirementPredicatesDescribeTheirSubjectAndTargetDifferently() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("policy", "POLICY", "Return policy", 1.0, 1.0))
                .addEntity(typedEntity("action", "ACTION", "Return to region", 1.0, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "required-action", "policy", "action", "REQUIRES_ACTION", 1.0));

        GraphAdmissionResult policy = evaluate(
                graph, "policy", List.of(candidate("policy")));
        GraphAdmissionResult action = evaluate(
                graph, "action", List.of(candidate("action")));

        assertTrue(policy.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.requires-action".equals(item.ruleId())
                        && item.kind() == AdmissionEvidence.Kind.REQUIREMENT));
        assertTrue(action.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.requires-action.target".equals(item.ruleId())
                        && item.kind() == AdmissionEvidence.Kind.CONTEXT_RELATION));
        assertTrue(action.evidenceTrace().items().stream()
                .noneMatch(item -> "fpna.requires-action".equals(item.ruleId())));
    }

    @Test
    void governedByPredicateIsDirectional() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("pattern", "PATTERN", "Channel mismatch", 1.0, 1.0))
                .addEntity(typedEntity("policy", "POLICY", "Channel override", 1.0, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "governance", "pattern", "policy", "GOVERNED_BY", 1.0));

        GraphAdmissionResult pattern = evaluate(
                graph, "pattern", List.of(candidate("pattern")));
        GraphAdmissionResult policy = evaluate(
                graph, "policy", List.of(candidate("policy")));

        assertTrue(pattern.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.governed-by".equals(item.ruleId())
                        && item.summary().contains("is governed by")));
        assertTrue(policy.evidenceTrace().items().stream()
                .anyMatch(item -> "fpna.governs".equals(item.ruleId())
                        && item.summary().contains("governs the related entity")));
    }

    @Test
    void conflictAndIdentityConstraintsDoNotLeakAcrossUnrelatedContextEdges() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity("candidate", 1.0, 1.0))
                .addEntity(entity("neighbor", 1.0, 1.0))
                .addEntity(entity("conflict", 1.0, 1.0))
                .addEntity(entity("distinct", 1.0, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "candidate-context", "candidate", "neighbor", "RELATED", 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "neighbor-conflict", "neighbor", "conflict", "CONTRADICTS", 1.0))
                .addRelation(SimpleGraphRelation.undirected(
                        "neighbor-distinct", "neighbor", "distinct", "DIFFERENT_FROM", 1.0));

        GraphAdmissionResult result = evaluate(
                graph, "candidate", List.of(candidate("candidate")));

        assertTrue(result.evidenceTrace().items().stream()
                .noneMatch(item -> item.kind() == AdmissionEvidence.Kind.CONFLICT
                        || item.kind() == AdmissionEvidence.Kind.IDENTITY_CONSTRAINT));
    }

    @Test
    void twoHopStatusPathSurfacesWorkbookUsabilityWarning() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(typedEntity("workbook-apac", "WORKBOOK", "APAC", 1.0, 1.0))
                .addEntity(typedEntity("sheet-summary", "SHEET", "Summary", 0.9, 1.0))
                .addEntity(typedEntity("status-do-not-use", "STATUS", "Do not use", 0.8, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "contains-summary", "workbook-apac", "sheet-summary", "CONTAINS_SHEET", 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "summary-status", "sheet-summary", "status-do-not-use", "HAS_STATUS", 1.0));

        GraphAdmissionResult result = evaluate(
                graph, "workbook-apac", List.of(candidate("workbook-apac")));

        AdmissionEvidence warning = result.evidenceTrace().items().stream()
                .filter(item -> "fpna.status.not-usable".equals(item.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("workbook-apac", "sheet-summary", "status-do-not-use"),
                warning.entityPath());
        assertEquals(List.of("CONTAINS_SHEET", "HAS_STATUS"), warning.predicatePath());
        String promptContext = result.evidenceTrace().toPromptContext();
        assertTrue(promptContext.startsWith("graph_evidence examined_paths="));
        assertTrue(promptContext.contains("kind=CAUTION rule=fpna.status.not-usable"));
        assertTrue(promptContext.contains("predicates=CONTAINS_SHEET>HAS_STATUS"));
        assertTrue(promptContext.contains(
                "entities=workbook-apac>sheet-summary>status-do-not-use"));
        String compactContext = result.evidenceTrace().toCompactPromptContext();
        assertTrue(compactContext.startsWith("graph_evidence_compact examined_paths="));
        assertTrue(compactContext.contains(
                "CAUTION|fpna.status.not-usable|s=1.0000"
                        + "|e=workbook-apac>sheet-summary>status-do-not-use"
                        + "|p=CONTAINS_SHEET>HAS_STATUS"));
        assertFalse(compactContext.contains("|summary="));
        assertTrue(compactContext.length() < promptContext.length());
        String limitedContext = result.evidenceTrace().toCompactPromptContext(3);
        assertTrue(limitedContext.contains("shown=3 total="));
        assertTrue(limitedContext.contains("truncated=true"));
        assertTrue(limitedContext.contains("CAUTION|fpna.status.not-usable"));
        assertEquals(4, limitedContext.lines().count());
    }

    @Test
    void relationAttributesCanOverrideCanonicalPredicateSemantics() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity("candidate", 1.0, 1.0))
                .addEntity(entity("policy", 0.8, 1.0))
                .addRelation(new SimpleGraphRelation(
                        "custom", "candidate", "policy", "RELATED", 1.0, 1.0,
                        true, Set.of(), null, null,
                        Map.of(
                                "admissionEvidenceKind", "REQUIREMENT",
                                "admissionRuleId", "tenant.must-review",
                                "admissionSummary", "tenant policy requires review")));

        GraphAdmissionResult result = evaluate(
                graph, "candidate", List.of(candidate("candidate")));

        AdmissionEvidence evidence = result.evidenceTrace().items().stream()
                .filter(item -> "tenant.must-review".equals(item.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(AdmissionEvidence.Kind.REQUIREMENT, evidence.kind());
        assertTrue(evidence.summary().contains("tenant policy requires review"));
    }

    @Test
    void duplicateSemanticPathsCollapseToTheStrongestEvidence() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(entity("candidate", 1.0, 1.0))
                .addEntity(entity("context", 0.8, 1.0))
                .addRelation(SimpleGraphRelation.directed(
                        "weak-source", "candidate", "context", "RELATED", 0.4))
                .addRelation(SimpleGraphRelation.directed(
                        "strong-source", "candidate", "context", "RELATED", 0.9));

        GraphAdmissionResult result = evaluate(
                graph, "candidate", List.of(candidate("candidate")));
        List<AdmissionEvidence> paths = result.evidenceTrace().items().stream()
                .filter(item -> item.entityPath().equals(List.of("candidate", "context")))
                .filter(item -> item.predicatePath().equals(List.of("RELATED")))
                .toList();

        assertEquals(1, paths.size());
        assertEquals(0.9, paths.get(0).strength(), 1e-9);
        assertEquals(List.of("strong-source"), paths.get(0).relationPath());
        assertEquals(2, result.evidenceTrace().examinedPathCount());
    }

    @Test
    void relationEvidenceIsDeterministicallyBounded() {
        UnifiedGraph graph = new UnifiedGraph().addEntity(entity("candidate", 1.0, 1.0));
        for (int i = 0; i < 24; i++) {
            String id = "context-" + i;
            graph.addEntity(entity(id, 0.4, 1.0));
            graph.addRelation(SimpleGraphRelation.directed(
                    "context-edge-" + i, "candidate", id, "RELATED", 0.5));
        }

        GraphAdmissionResult first = evaluate(
                graph, "candidate", List.of(candidate("candidate")));
        GraphAdmissionResult second = evaluate(
                graph, "candidate", List.of(candidate("candidate")));

        assertTrue(first.evidenceTrace().truncated());
        assertTrue(first.evidenceTrace().examinedPathCount() >= 24);
        assertEquals(AdmissionEvidenceCollector.MAX_RELATION_EVIDENCE,
                first.evidenceTrace().items().stream()
                        .filter(item -> !item.relationPath().isEmpty())
                        .count());
        assertEquals(first.evidenceTrace(), second.evidenceTrace());
    }

    @Test
    void mutatingTheSourceGraphAfterRequestCreationCannotChangeTheResult() {
        UnifiedGraph graph = new UnifiedGraph().addEntity(entity("candidate", 1.0, 1.0));
        AdmissionRequest request = request("candidate", List.of(candidate("candidate")), graph);
        GraphAdmissionResult before = new HybridGraphAdmissionEvaluator().evaluate(request);

        graph.addEntity(entity("late-node", 1.0, 1.0));
        GraphAdmissionResult after = new HybridGraphAdmissionEvaluator().evaluate(request);

        assertEquals(before.decision(), after.decision());
        assertEquals(before.score(), after.score(), 1e-9);
        assertEquals(before.margin(), after.margin(), 1e-9);
    }

    private UnifiedGraph relatedGraph(String relationType) {
        return new UnifiedGraph()
                .addEntity(entity("candidate", 0.4, 1.0))
                .addEntity(entity("source", 0.4, 1.0))
                .addRelation(new SimpleGraphRelation(
                        "r", "source", "candidate", relationType, 0.9, 1.0,
                        true, Set.of(), null, null, Map.of()));
    }

    private UnifiedGraph parallelGraph(double firstWeight, double secondWeight) {
        return new UnifiedGraph()
                .addEntity(entity("candidate", 0.3, 1.0))
                .addEntity(entity("evidence", 0.3, 1.0))
                .addRelation(SimpleGraphRelation.directed("first", "candidate", "evidence", "SUPPORTS", firstWeight))
                .addRelation(SimpleGraphRelation.directed("second", "candidate", "evidence", "SUPPORTS", secondWeight));
    }

    private UnifiedGraph cycleGraph(List<String> order) {
        UnifiedGraph graph = new UnifiedGraph();
        for (String id : order) graph.addEntity(entity(id, 0.2, 1.0));
        for (int i = 0; i < order.size(); i++) {
            graph.addRelation(SimpleGraphRelation.directed(
                    "r" + i, order.get(i), order.get((i + 1) % order.size()), "CAUSES", 0.8));
        }
        return graph;
    }

    private GraphAdmissionResult evaluate(UnifiedGraph graph, String id, List<AdmissionCandidate> ballot) {
        return new HybridGraphAdmissionEvaluator().evaluate(request(id, ballot, graph));
    }

    private AdmissionRequest request(String id, List<AdmissionCandidate> ballot, UnifiedGraph graph) {
        AdmissionCandidate candidate = ballot.stream()
                .filter(item -> item.candidateId().equals(id))
                .findFirst()
                .orElseGet(() -> candidate(id));
        return new AdmissionRequest("group", candidate, ballot, "snapshot:v1", "policy:v1", graph);
    }

    private AdmissionCandidate candidate(String id) {
        return candidate(id, id);
    }

    private AdmissionCandidate candidate(String id, String canonicalKey) {
        return new AdmissionCandidate(id, canonicalKey);
    }

    private SimpleGraphEntity entity(String id, double weight, double confidence) {
        return new SimpleGraphEntity(id, "ENTITY", id, weight, confidence,
                Set.of(), null, null, Map.of());
    }

    private SimpleGraphEntity typedEntity(String id,
                                          String type,
                                          String label,
                                          double weight,
                                          double confidence) {
        return new SimpleGraphEntity(id, type, label, weight, confidence,
                Set.of(), null, null, Map.of());
    }

    private Map<String, Double> scores(List<HybridReasoner.ScoredEntity> ranking) {
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        for (HybridReasoner.ScoredEntity scored : ranking) result.put(scored.entityId(), scored.score());
        return result;
    }

    private double linkValue(ai.kompile.graph.reasoning.psl.PslProgram program) {
        return program.atomKeys().stream()
                .filter(key -> key.startsWith("Link("))
                .mapToDouble(program::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("PSL projection did not contain a Link atom"));
    }
}
