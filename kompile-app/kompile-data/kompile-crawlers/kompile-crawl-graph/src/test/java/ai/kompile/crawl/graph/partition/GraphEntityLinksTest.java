/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.partition.grouping.EntityGroup;
import ai.kompile.core.graphrag.partition.grouping.EntityLink;
import ai.kompile.core.graphrag.partition.grouping.GroupingPlan;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reading a fact sheet as the input to grouping.
 *
 * <p>The planner is pure; this is the only place that knows a graph exists. What it decides to
 * feed the planner — and what it refuses to — is therefore the whole of grouping's view of a
 * crawl.</p>
 */
@DisplayName("Graph entity links")
class GraphEntityLinksTest {

    private static final long FACT_SHEET = 11L;

    private KnowledgeGraphService graph;

    @BeforeEach
    void setUp() {
        graph = mock(KnowledgeGraphService.class);
    }

    private static GraphNode entity(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .externalId(nodeId)
                .title(title)
                .nodeType(NodeLevel.ENTITY)
                .build();
    }

    private static GraphEdge edge(String sourceId, String targetId) {
        return edge(sourceId, targetId, null);
    }

    private static GraphEdge edge(String sourceId, String targetId, Double confidence) {
        return GraphEdge.builder()
                .edgeId(sourceId + "->" + targetId)
                .sourceNode(GraphNode.builder().nodeId(sourceId).build())
                .targetNode(GraphNode.builder().nodeId(targetId).build())
                .edgeType(EdgeType.USER_DEFINED)
                .relationType("employs")
                .confidence(confidence)
                .build();
    }

    private void factSheetOf(List<GraphNode> nodes, List<GraphEdge> edges) {
        when(graph.getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(FACT_SHEET)).thenReturn(edges);
    }

    @Nested
    @DisplayName("Subjects")
    class Subjects {

        @Test
        void everyLiveEntityNodeIsASubject() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of());
            assertEquals(List.of("entity_acme", "entity_beta"),
                    GraphEntityLinks.subjectsIn(graph, FACT_SHEET));
        }

        @Test
        void subjectsAreNodeIdsBecauseATitleIsNotUnique() {
            // Two distinct entities sharing a title is the one mistake grouping cannot recover
            // from: the partition it produced would be a claim about neither of them.
            factSheetOf(List.of(entity("entity_acme_1", "Acme"), entity("entity_acme_2", "Acme")),
                    List.of());
            assertEquals(List.of("entity_acme_1", "entity_acme_2"),
                    GraphEntityLinks.subjectsIn(graph, FACT_SHEET));
        }

        @Test
        void aTombstonedEntityIsNotASubject() {
            GraphNode retracted = GraphNode.builder().nodeId("entity_gone").externalId("gone")
                    .nodeType(NodeLevel.ENTITY).stale(true).build();
            factSheetOf(List.of(entity("entity_acme", "Acme"), retracted), List.of());
            assertEquals(List.of("entity_acme"), GraphEntityLinks.subjectsIn(graph, FACT_SHEET));
        }

        @Test
        void aNodeWithNoIdIsNotASubject() {
            GraphNode hollow = GraphNode.builder().title("Acme").nodeType(NodeLevel.ENTITY).build();
            factSheetOf(List.of(hollow, entity("entity_beta", "Beta")), List.of());
            assertEquals(List.of("entity_beta"), GraphEntityLinks.subjectsIn(graph, FACT_SHEET));
        }

        @Test
        void onlyEntityNodesAreEverAskedFor() {
            factSheetOf(List.of(entity("entity_acme", "Acme")), List.of());
            GraphEntityLinks.subjectsIn(graph, FACT_SHEET);
            verify(graph, times(1)).getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY);
            verify(graph, never()).getNodesInFactSheet(anyLong());
        }

        @Test
        void noFactSheetMeansNoSubjectsAndNoStoreAccess() {
            assertTrue(GraphEntityLinks.subjectsIn(graph, null).isEmpty());
            assertTrue(GraphEntityLinks.subjectsIn(null, FACT_SHEET).isEmpty());
            verifyNoInteractions(graph);
        }

        @Test
        void aStoreReturningNothingIsAnEmptyFactSheetNotAnError() {
            when(graph.getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY)).thenReturn(null);
            assertTrue(GraphEntityLinks.subjectsIn(graph, FACT_SHEET).isEmpty());
        }
    }

    @Nested
    @DisplayName("Links")
    class Links {

        @Test
        void anEntityToEntityRelationPullsTwoSubjectsTogether() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "entity_beta")));
            assertEquals(List.of(EntityLink.between("entity_acme", "entity_beta",
                            GraphEntityLinks.DEFAULT_STRENGTH)),
                    GraphEntityLinks.linksIn(graph, FACT_SHEET));
        }

        @Test
        void anEdgeToSomethingThatIsNotASubjectIsNotEvidenceOfBelonging() {
            // Real edge, but there is only one subject on it: a document or chunk node says
            // nothing about which subjects belong in one partition.
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "doc_17")));
            assertTrue(GraphEntityLinks.linksIn(graph, FACT_SHEET).isEmpty());
        }

        @Test
        void aSelfRelationIsNotALink() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "entity_acme")));
            assertTrue(GraphEntityLinks.linksIn(graph, FACT_SHEET).isEmpty());
        }

        @Test
        void aTombstonedRelationIsNotALink() {
            GraphEdge retracted = edge("entity_acme", "entity_beta");
            retracted.setStale(true);
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(retracted));
            assertTrue(GraphEntityLinks.linksIn(graph, FACT_SHEET).isEmpty());
        }

        @Test
        void anEdgeToATombstonedEntityIsNotALinkEither() {
            GraphNode retracted = GraphNode.builder().nodeId("entity_gone").externalId("gone")
                    .nodeType(NodeLevel.ENTITY).stale(true).build();
            factSheetOf(List.of(entity("entity_acme", "Acme"), retracted),
                    List.of(edge("entity_acme", "entity_gone")));
            assertTrue(GraphEntityLinks.linksIn(graph, FACT_SHEET).isEmpty());
        }

        @Test
        void oneSubjectAloneNeedsNoEdgeReadAtAll() {
            factSheetOf(List.of(entity("entity_acme", "Acme")), List.of());
            assertTrue(GraphEntityLinks.linksIn(graph, FACT_SHEET).isEmpty());
            verify(graph, never()).getEdgesInFactSheet(anyLong());
        }

        @Test
        void theExtractorsOwnConfidenceIsHowHardTheRelationPulls() {
            assertEquals(0.4, GraphEntityLinks.strengthOf(edge("a", "b", 0.4)), 1e-9);
        }

        @Test
        void aRelationWithNoRecordedConfidenceStillPullsFully() {
            // Absent confidence is not weak evidence, it is unrecorded evidence; treating it as
            // near-zero would quietly stop grouping anything an older extractor produced.
            assertEquals(GraphEntityLinks.DEFAULT_STRENGTH,
                    GraphEntityLinks.strengthOf(edge("a", "b", null)), 1e-9);
            assertEquals(GraphEntityLinks.DEFAULT_STRENGTH,
                    GraphEntityLinks.strengthOf(edge("a", "b", 0.0)), 1e-9);
            assertEquals(GraphEntityLinks.DEFAULT_STRENGTH,
                    GraphEntityLinks.strengthOf(edge("a", "b", Double.NaN)), 1e-9);
        }

        @Test
        void aConfidenceAboveOneIsStillJustCertain() {
            assertEquals(1.0, GraphEntityLinks.strengthOf(edge("a", "b", 4.0)), 1e-9);
        }

        @Test
        void repeatedRelationsBetweenThePairAreKeptSeparateSoTheyCanAddUp() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "entity_beta", 0.3),
                            edge("entity_acme", "entity_beta", 0.3)));
            List<EntityLink> links = GraphEntityLinks.linksIn(graph, FACT_SHEET);
            assertEquals(2, links.size(),
                    "five weak mentions of a pair should pull as one strong link, not be merged "
                            + "into one weak one");
        }
    }

    @Nested
    @DisplayName("Planning")
    class Planning {

        @Test
        void connectedSubjectsAreReadTogether() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta"),
                            entity("entity_gamma", "Gamma")),
                    List.of(edge("entity_acme", "entity_beta")));
            GroupingPlan plan = GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.defaults());
            EntityGroup together = plan.owner("entity_acme").orElseThrow();
            assertTrue(together.contains("entity_beta"),
                    "expected the linked pair in one group, got " + together.members());
            assertFalse(together.contains("entity_gamma"),
                    "an unlinked subject is not part of that neighbourhood");
            assertEquals(3, plan.subjectsCovered(), "no subject may be dropped by grouping");
        }

        @Test
        void anUnlinkedSubjectIsStillItsOwnPartition() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of());
            GroupingPlan plan = GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.defaults());
            assertEquals(2, plan.groups().size());
            assertEquals(2, plan.subjectsCovered());
        }

        @Test
        void thePolicyInForceTravelsWithThePlan() {
            factSheetOf(List.of(entity("entity_acme", "Acme")), List.of());
            assertEquals(GroupingPolicy.HYBRID_VERSION,
                    GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.defaults())
                            .policy().version());
            assertEquals(GroupingPolicy.PER_ENTITY_VERSION,
                    GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.perEntity())
                            .policy().version());
        }

        @Test
        void perEntityGroupingGivesEachSubjectItsOwnPartitionHoweverLinkedTheyAre() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "entity_beta")));
            GroupingPlan plan = GraphEntityLinks.plan(graph, FACT_SHEET,
                    GroupingPolicy.perEntity());
            assertEquals(2, plan.groups().size());
            plan.groups().forEach(group -> assertEquals(1, group.members().size()));
        }

        @Test
        void anEmptyFactSheetIsARealStateOfACrawlNotAFailure() {
            when(graph.getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY))
                    .thenReturn(List.of());
            GroupingPlan plan = GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.defaults());
            assertTrue(plan.groups().isEmpty());
            assertEquals(0, plan.subjectsCovered());
            verify(graph, never()).getEdgesInFactSheet(anyLong());
        }

        @Test
        void theStoreIsReadOncePerPlanNotOncePerSubject() {
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "entity_beta")));
            GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.defaults());
            verify(graph, times(1)).getNodesByTypeInFactSheet(FACT_SHEET, NodeLevel.ENTITY);
            verify(graph, times(1)).getEdgesInFactSheet(FACT_SHEET);
        }

        @Test
        void groupingReadsTheGraphThroughTheServiceAndNothingElse() {
            // Graph code never touches JPA: the only surface used here is KnowledgeGraphService.
            factSheetOf(List.of(entity("entity_acme", "Acme"), entity("entity_beta", "Beta")),
                    List.of(edge("entity_acme", "entity_beta")));
            GraphEntityLinks.plan(graph, FACT_SHEET, GroupingPolicy.defaults());
            verify(graph, never()).getNodesInFactSheet(anyLong());
            verify(graph, never()).getNodeByExternalIdInFactSheet(any(), any(), anyLong());
        }
    }
}
