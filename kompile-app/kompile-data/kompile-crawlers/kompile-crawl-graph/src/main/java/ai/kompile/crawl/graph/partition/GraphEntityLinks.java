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

import ai.kompile.core.graphrag.partition.grouping.EntityGroupPlanner;
import ai.kompile.core.graphrag.partition.grouping.EntityLink;
import ai.kompile.core.graphrag.partition.grouping.GroupingPlan;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a fact sheet as the input to grouping: which subjects there are, and what pulls them
 * together.
 *
 * <p>{@link EntityGroupPlanner} is pure on purpose — it knows nothing about graphs, fact sheets or
 * stores. This is the adapter that feeds it, and the whole of what the crawl side has to know:
 * entity nodes become subjects, entity-to-entity relations become {@link EntityLink}s, and
 * everything else in the graph is not grouping's business.</p>
 *
 * <p>Subjects are node ids rather than titles. A title is not unique inside a fact sheet, and two
 * distinct entities sharing one would be grouped as if they were the same subject — the one
 * mistake grouping cannot recover from, because the partition it produces would be a claim about
 * neither of them. Both discovery channels accept a node id as a seed, so nothing is lost by being
 * precise here.</p>
 */
public final class GraphEntityLinks {

    /** Strength of a relation whose extractor recorded no confidence. */
    public static final double DEFAULT_STRENGTH = 1.0;

    private GraphEntityLinks() {
    }

    /** The live entity nodes of a fact sheet, by node id, in store order. */
    public static List<String> subjectsIn(KnowledgeGraphService graph, Long factSheetId) {
        return List.copyOf(subjectSet(graph, factSheetId));
    }

    /** The relations between those entities, one link per edge. */
    public static List<EntityLink> linksIn(KnowledgeGraphService graph, Long factSheetId) {
        return linksAmong(graph, factSheetId, subjectSet(graph, factSheetId));
    }

    /**
     * Groups the entities of a fact sheet under {@code policy}.
     *
     * <p>One read of the nodes and one of the edges; the planner does the rest in memory. An empty
     * fact sheet produces an empty plan rather than an error — a graph with nothing in it is a
     * real state of a crawl, not a failure of grouping.</p>
     */
    public static GroupingPlan plan(KnowledgeGraphService graph, Long factSheetId,
                                    GroupingPolicy policy) {
        Set<String> subjects = subjectSet(graph, factSheetId);
        List<EntityLink> links = linksAmong(graph, factSheetId, subjects);
        return new EntityGroupPlanner(policy).plan(subjects, links);
    }

    private static Set<String> subjectSet(KnowledgeGraphService graph, Long factSheetId) {
        if (graph == null || factSheetId == null) {
            return Set.of();
        }
        List<GraphNode> nodes = graph.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        if (nodes == null || nodes.isEmpty()) {
            return Set.of();
        }
        Set<String> subjects = new LinkedHashSet<>(Math.max(16, nodes.size() * 2));
        for (GraphNode node : nodes) {
            if (node == null || node.getNodeId() == null || Boolean.TRUE.equals(node.getStale())) {
                // A tombstoned entity is not a subject: grouping it would open a partition to make
                // a claim about something the graph has already retracted.
                continue;
            }
            subjects.add(node.getNodeId());
        }
        return subjects;
    }

    private static List<EntityLink> linksAmong(KnowledgeGraphService graph, Long factSheetId,
                                               Set<String> subjects) {
        if (subjects.size() < 2) {
            return List.of();
        }
        List<GraphEdge> edges = graph.getEdgesInFactSheet(factSheetId);
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        List<EntityLink> links = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge == null || Boolean.TRUE.equals(edge.getStale())) {
                continue;
            }
            String source = edge.getSourceNodeId();
            String target = edge.getTargetNodeId();
            if (source == null || target == null || source.equals(target)) {
                // A self-relation says nothing about who belongs with whom, and counting it would
                // inflate the degree that bridge detection reads.
                continue;
            }
            if (!subjects.contains(source) || !subjects.contains(target)) {
                // An edge to a document, table or chunk node is real, but it is not evidence that
                // two subjects belong in one partition — there is only one subject on it.
                continue;
            }
            links.add(EntityLink.between(source, target, strengthOf(edge)));
        }
        return List.copyOf(links);
    }

    /**
     * How hard a relation pulls: the extractor's own confidence, or {@link #DEFAULT_STRENGTH}.
     *
     * <p>Repeated relations between the same pair are left as separate links deliberately — the
     * planner sums them, so five weak mentions of the same pair pull as one strong link instead of
     * five that each fall short of the floor.</p>
     */
    static double strengthOf(GraphEdge edge) {
        Double confidence = edge.getConfidence();
        if (confidence == null || confidence.isNaN() || confidence <= 0.0) {
            return DEFAULT_STRENGTH;
        }
        return Math.min(1.0, confidence);
    }
}
