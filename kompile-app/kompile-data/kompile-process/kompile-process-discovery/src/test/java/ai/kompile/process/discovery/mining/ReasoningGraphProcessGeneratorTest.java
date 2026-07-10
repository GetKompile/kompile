/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.process.discovery.ProcessUnifiedGraphArtifacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningGraphProcessGeneratorTest {

    @Test
    void generatesRankedEntailedFlowFromTopologyWithoutTimestampsOrStepOrder() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(step("task:receive", "Receive order", "Order Fulfillment"));
        graph.addEntity(step("task:validate", "Validate order", "Order Fulfillment"));
        graph.addEntity(step("task:approve", "Approve order", "Order Fulfillment"));
        graph.addEntity(step("task:publish", "Publish order", "Order Fulfillment"));
        graph.addRelation(relation("r1", "task:receive", "task:validate", "FEEDS_INTO"));
        graph.addRelation(relation("r2", "task:validate", "task:approve", "FEEDS_INTO"));
        graph.addRelation(relation("r3", "task:approve", "task:publish", "FEEDS_INTO"));

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        ReasoningGraphProcessGenerator.Candidate flow = result.candidates().stream()
                .filter(candidate -> candidate.projection()
                        == ReasoningGraphProcessGenerator.Projection.ACTIVITY_FLOW)
                .filter(candidate -> candidate.family().equals("flow"))
                .findFirst()
                .orElseThrow();
        assertEquals("Order Fulfillment", flow.suggestion().getName());
        assertEquals(Set.of("Receive order", "Validate order", "Approve order", "Publish order"),
                flow.eventLog().activityNames());
        assertEquals(List.of("Receive order", "Validate order", "Approve order", "Publish order"),
                flow.eventLog().traces().get(0).activitySequence());
        assertFalse(flow.entailment().accepted().isEmpty());
        assertFalse(flow.hybridActivation().isEmpty());
        assertTrue(flow.score() > 0.0);
        assertTrue(flow.rank() >= 1);
        assertNotNull(flow.reasoningTrace());
        assertEquals(Set.of("r1", "r2", "r3"), Set.copyOf(flow.evidenceRelationIds()));
        assertEquals(flow.rank(), flow.suggestion().getReasoningRank());
        assertEquals("ACTIVITY_FLOW", flow.suggestion().getReasoningProjection());
        assertEquals("flow", flow.suggestion().getReasoningFamily());
        assertEquals(flow.hybridActivation().meanHybrid(), flow.suggestion().getHybridScore());
        assertNotNull(flow.suggestion().getHybridReasoning());
        assertEquals("ACTIVITY_ACTIVATION_CONSENSUS",
                flow.suggestion().getHybridReasoning().getInterpretation());
        assertEquals(flow.activityCount(), flow.suggestion().getHybridReasoning().getActivityCount());
        assertEquals(flow.activityCount(), flow.suggestion().getHybridReasoning().getActivities().size());
        assertEquals("STRUCTURAL_ONLY", flow.suggestion().getHybridReasoning().getSemanticMode());
        assertEquals(flow.entailment().fusedOpinion().expectation(), flow.suggestion().getEntailmentScore());
        assertEquals(flow.traceCount(), flow.suggestion().getProcessCaseCount());
        assertEquals(flow.activityCount(), flow.suggestion().getProcessActivityCount());
        assertEquals(flow.dfg().arcs().size(), flow.suggestion().getDirectlyFollowsCount());
        assertEquals(flow.entailment().accepted().size(), flow.suggestion().getAcceptedPrecedenceCount());
        assertEquals(flow.entailment().entailedOnly().size(), flow.suggestion().getEntailedOnlyPrecedenceCount());
        assertEquals(Set.of("r1", "r2", "r3"),
                Set.copyOf(flow.suggestion().getSourceGraphRelationIds()));

        ReasoningTrace.Step hybridTrace = flow.reasoningTrace().conclusion().premises().stream()
                .filter(step -> "ACTIVITY_ACTIVATION_CONSENSUS".equals(step.meta().get("interpretation")))
                .findFirst()
                .orElseThrow();
        assertEquals(flow.activityCount(), hybridTrace.premises().size());
        assertEquals("STRUCTURAL_ONLY", hybridTrace.meta().get("semanticMode"));
        assertTrue(hybridTrace.premises().stream()
                .allMatch(step -> step.premises().size() == 2));
        assertTrue(hybridTrace.premises().stream()
                .flatMap(step -> step.premises().stream())
                .allMatch(step -> step.meta().containsKey("structuralScore")
                        && step.meta().containsKey("semanticScore")));
    }

    @Test
    void aggregatesDisconnectedControlPairsIntoOneRankedSuite() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("control:tax", "CONTROL_ASSERTION", "Tax total control"));
        graph.addEntity(entity("control:credit", "CONTROL_ASSERTION", "Credit limit control"));
        graph.addEntity(entity("task:tax", "PROCESS_STEP", "Validate tax total"));
        graph.addEntity(entity("task:credit", "PROCESS_STEP", "Validate credit limit"));
        graph.addRelation(relation("v1", "control:tax", "task:tax", "VALIDATES"));
        graph.addRelation(relation("v2", "control:credit", "task:credit", "VALIDATES"));

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        ReasoningGraphProcessGenerator.Candidate aggregate = result.candidates().stream()
                .filter(candidate -> candidate.family().equals("control"))
                .filter(candidate -> candidate.traceCount() == 2)
                .findFirst()
                .orElseThrow();
        assertEquals("Control process", aggregate.suggestion().getName());
        assertEquals(4, aggregate.activityCount());
        assertEquals(Set.of("v1", "v2"), Set.copyOf(aggregate.evidenceRelationIds()));
        assertTrue(aggregate.suggestion().getEvidence().stream()
                .anyMatch(evidence -> evidence.contains("Graph-only")));
    }

    @Test
    void clustersRelationEventsIntoACommunicationProcess() {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 1; i <= 3; i++) {
            String suffix = String.valueOf(i);
            graph.addEntity(entity("email:" + suffix, "EMAIL_MESSAGE", "Email " + suffix));
            graph.addEntity(entity("person:" + suffix, "PERSON", "Sender " + suffix));
            graph.addEntity(entity("file:" + suffix, "DOCUMENT", "Workbook " + suffix));
            graph.addEntity(entity("forecast:" + suffix, "FORECAST", "Forecast " + suffix));
            Map<String, Object> attrs = Map.of("caseId", "mail:" + suffix);
            graph.addRelation(relation("sent:" + suffix, "email:" + suffix,
                    "person:" + suffix, "SENT_BY", attrs));
            graph.addRelation(relation("attached:" + suffix, "email:" + suffix,
                    "file:" + suffix, "HAS_ATTACHMENT", attrs));
            graph.addRelation(relation("submitted:" + suffix, "email:" + suffix,
                    "forecast:" + suffix, "SUBMITS_FORECAST", attrs));
        }

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        ReasoningGraphProcessGenerator.Candidate communication = result.candidates().stream()
                .filter(candidate -> candidate.projection()
                        == ReasoningGraphProcessGenerator.Projection.RELATION_EVENTS)
                .filter(candidate -> candidate.family().equals("communication"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, communication.traceCount());
        assertEquals(3, communication.activityCount());
        assertEquals(Set.of(
                        "Email Message Sent By Person",
                        "Email Message Has Attachment Document",
                        "Email Message Submits Forecast Forecast"),
                communication.eventLog().activityNames());
        assertTrue(communication.entailedOnlyCount() > 0);
    }

    @Test
    void keepsDisconnectedControlSuitesSeparatedByResolvedSourceType() {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 1; i <= 2; i++) {
            graph.addEntity(entity("assertion:" + i, "CONTROL_ASSERTION", "Control " + i));
            graph.addEntity(entity("rule:" + i, "DATA_QUALITY_RULE", "Quality rule " + i));
            graph.addEntity(entity("task:control:" + i, "PROCESS_STEP", "Controlled task " + i));
            graph.addEntity(entity("task:quality:" + i, "PROCESS_STEP", "Quality task " + i));
            graph.addRelation(relation("control:" + i, "assertion:" + i,
                    "task:control:" + i, "VALIDATES"));
            graph.addRelation(relation("quality:" + i, "rule:" + i,
                    "task:quality:" + i, "VALIDATES"));
        }

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        List<ReasoningGraphProcessGenerator.Candidate> suites = result.candidates().stream()
                .filter(candidate -> candidate.family().equals("control"))
                .filter(candidate -> candidate.traceCount() > 1)
                .toList();
        assertEquals(2, suites.size());
        assertTrue(suites.stream().allMatch(candidate -> candidate.traceCount() == 2));
        assertFalse(suites.stream().anyMatch(candidate ->
                candidate.evidenceRelationIds().contains("control:1")
                        && candidate.evidenceRelationIds().contains("quality:1")));
    }

    @Test
    void correlatesExplicitCasesAcrossRelationFamilies() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("email:1", "EMAIL_MESSAGE", "Forecast submission"));
        graph.addEntity(entity("person:1", "PERSON", "Submitter"));
        graph.addEntity(entity("file:1", "DOCUMENT", "Forecast workbook"));
        graph.addEntity(entity("control:1", "CONTROL_ASSERTION", "Submission control"));
        Map<String, Object> caseAttributes = Map.of("caseId", "submission:1");
        graph.addRelation(relation(
                "sent", "email:1", "person:1", "SENT_BY", caseAttributes));
        graph.addRelation(relation(
                "attached", "email:1", "file:1", "HAS_ATTACHMENT", caseAttributes));
        graph.addRelation(relation(
                "validated", "control:1", "email:1", "VALIDATES", caseAttributes));

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        ReasoningGraphProcessGenerator.Candidate correlated = result.candidates().stream()
                .filter(candidate -> candidate.family().equals("case"))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of("sent", "attached", "validated"),
                Set.copyOf(correlated.evidenceRelationIds()));
        assertEquals(1, correlated.traceCount());
        assertEquals(3, correlated.activityCount());
        assertFalse(correlated.entailment().accepted().isEmpty());
    }

    @Test
    void fusesDisjointProjectionEvidenceWhenGraphEmbeddingsAgree() {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 1; i <= 2; i++) {
            graph.addEntity(embeddedEntity(
                    "control:" + i, "CONTROL_ASSERTION", "Control " + i,
                    1.0, 0.05, 0.0));
            graph.addEntity(entity(
                    "task:control:" + i,
                    i == 1 ? "PROCESS_STEP" : "FORECAST",
                    "Controlled task " + i));
            graph.addRelation(relation(
                    "validates:" + i, "control:" + i, "task:control:" + i, "VALIDATES"));

            graph.addEntity(embeddedEntity(
                    "exception:" + i, "EXCEPTION_RULE", "Exception " + i,
                    0.99, 0.04, 0.01));
            graph.addEntity(entity(
                    "task:exception:" + i,
                    i == 1 ? "PROCESS_STEP" : "DOCUMENT",
                    "Remediation " + i));
            graph.addRelation(relation(
                    "triggers:" + i, "exception:" + i, "task:exception:" + i, "TRIGGERS"));
        }
        graph.addEntity(entity("control:noise", "CONTROL_ASSERTION", "Unrelated control"));
        graph.addEntity(entity("task:noise", "PROCESS_STEP", "Unrelated task"));
        graph.addRelation(relation(
                "validates:noise", "control:noise", "task:noise", "VALIDATES"));

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        ReasoningGraphProcessGenerator.Candidate fused = result.candidates().stream()
                .filter(candidate -> candidate.family().equals("semantic-fusion"))
                .filter(candidate -> candidate.evidenceRelationIds().containsAll(
                        List.of("validates:1", "validates:2", "triggers:1", "triggers:2")))
                .findFirst()
                .orElseThrow();
        assertEquals(2, fused.traceCount());
        assertEquals(4, fused.activityCount());
        assertFalse(fused.evidenceRelationIds().contains("validates:noise"));
        assertFalse(fused.entailment().accepted().isEmpty());
        assertFalse(fused.hybridActivation().isEmpty());
        assertTrue(fused.hybridActivation().semanticEngaged());
        assertEquals("GRAPH_VECTOR_RESOLVED",
                fused.suggestion().getHybridReasoning().getEmbeddingSource());
        assertEquals(0,
                fused.suggestion().getHybridReasoning().getDirectlyEmbeddedActivityCount());
        assertEquals(4,
                fused.suggestion().getHybridReasoning().getInferredEmbeddingActivityCount());
        ReasoningTrace.Step hybridTrace = fused.reasoningTrace().conclusion().premises().stream()
                .filter(step -> "ACTIVITY_ACTIVATION_CONSENSUS".equals(
                        step.meta().get("interpretation")))
                .findFirst()
                .orElseThrow();
        assertEquals("GRAPH_VECTOR_RESOLVED", hybridTrace.meta().get("embeddingSource"));
        assertEquals("4", hybridTrace.meta().get("inferredEmbeddingActivityCount"));
    }

    @Test
    void doesNotFuseDissimilarGraphEmbeddings() {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 1; i <= 2; i++) {
            graph.addEntity(embeddedEntity(
                    "left:" + i, "CONTROL_ASSERTION", "Left " + i,
                    1.0, 0.0, 0.0));
            graph.addEntity(entity(
                    "left-target:" + i,
                    i == 1 ? "PROCESS_STEP" : "FORECAST",
                    "Left target " + i));
            graph.addRelation(relation(
                    "left-relation:" + i, "left:" + i, "left-target:" + i, "VALIDATES"));

            graph.addEntity(embeddedEntity(
                    "right:" + i, "EXCEPTION_RULE", "Right " + i,
                    0.0, 1.0, 0.0));
            graph.addEntity(entity(
                    "right-target:" + i,
                    i == 1 ? "PROCESS_STEP" : "DOCUMENT",
                    "Right target " + i));
            graph.addRelation(relation(
                    "right-relation:" + i, "right:" + i, "right-target:" + i, "TRIGGERS"));
        }

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);

        assertFalse(result.candidates().stream()
                .anyMatch(candidate -> candidate.family().equals("semantic-fusion")));

        for (GraphEntity entity : graph.entities()) {
            graph.putEntityVector("learned", entity.id(), new double[]{1.0, 0.1, 0.0});
        }
        ReasoningGraphProcessGenerator.Result separatelyScored =
                ReasoningGraphProcessGenerator.generate(graph, graph.withEmbeddingLayer("learned"));
        assertFalse(separatelyScored.candidates().stream()
                .anyMatch(candidate -> candidate.family().equals("semantic-fusion")),
                "learned scoring vectors must not redefine crawl-observed projection membership");
        assertTrue(separatelyScored.candidates().stream()
                .allMatch(candidate -> candidate.hybridActivation().semanticEngaged()),
                "the separate learned view must still supply activity scoring embeddings");
    }

    @Test
    void writesSuggestionsAndCanonicalTracesToUnifiedGraph() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(step("task:a", "Collect request", "Request Handling"));
        graph.addEntity(step("task:b", "Approve request", "Request Handling"));
        graph.addRelation(relation("r1", "task:a", "task:b", "FEEDS_INTO"));

        ReasoningGraphProcessGenerator.Result result =
                ReasoningGraphProcessGenerator.generate(graph);
        result.putArtifacts(graph);

        assertFalse(result.candidates().isEmpty());
        String suggestionsJson = graph.artifactText(ProcessUnifiedGraphArtifacts.SUGGESTIONS_JSON);
        assertNotNull(suggestionsJson);
        assertTrue(suggestionsJson.contains("\"hybridReasoning\""));
        assertTrue(suggestionsJson.contains("\"pslStructuralScore\""));
        for (ReasoningGraphProcessGenerator.Candidate candidate : result.candidates()) {
            assertNotNull(graph.model(ProcessUnifiedGraphArtifacts.traceArtifactName(
                    candidate.suggestion().getId())));
        }
    }

    private static GraphEntity step(String id, String label, String processName) {
        return GraphEntity.builder(id)
                .type("PROCESS_STEP")
                .label(label)
                .attributes(Map.of("processName", processName))
                .build();
    }

    private static GraphEntity entity(String id, String type, String label) {
        return GraphEntity.builder(id)
                .type(type)
                .label(label)
                .build();
    }

    private static GraphEntity embeddedEntity(
            String id, String type, String label, double... embedding) {
        return GraphEntity.builder(id)
                .type(type)
                .label(label)
                .embedding(embedding)
                .build();
    }

    private static GraphRelation relation(String id, String sourceId, String targetId, String type) {
        return relation(id, sourceId, targetId, type, Map.of());
    }

    private static GraphRelation relation(String id,
                                          String sourceId,
                                          String targetId,
                                          String type,
                                          Map<String, Object> attributes) {
        return GraphRelation.builder(id, sourceId, targetId)
                .type(type)
                .confidence(0.9)
                .attributes(attributes)
                .build();
    }
}
