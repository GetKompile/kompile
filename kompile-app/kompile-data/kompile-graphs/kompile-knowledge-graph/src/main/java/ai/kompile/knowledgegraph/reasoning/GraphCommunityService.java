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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.community.CommunityAssignment;
import ai.kompile.graph.reasoning.community.CommunityDetector;
import ai.kompile.graph.reasoning.community.LabelPropagationDetector;
import ai.kompile.graph.reasoning.community.LouvainDetector;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring service that runs community-detection algorithms over a fact-sheet's knowledge graph.
 *
 * <p>Builds a bounded {@link ReasoningGraph} subgraph via {@link KnowledgeGraphReasoningAdapter}
 * seeded from every node in the fact sheet, then delegates to the requested
 * {@link CommunityDetector} implementation (Louvain or Label Propagation).</p>
 */
@Slf4j
@Service
public class GraphCommunityService {

    private final KnowledgeGraphService graphService;

    public GraphCommunityService(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Result record returned by {@link #detectCommunities}.
     *
     * @param nodeToCommunit  assignment of each node id to its community id
     * @param modularity      modularity score Q of the partition (higher → better-separated)
     * @param communityCount  number of distinct communities found
     * @param communityMembers inverted index: communityId → list of node ids in that community
     * @param factSheetId     the fact sheet this detection was run over
     * @param method          the algorithm used ("louvain" or "label_propagation")
     */
    public record CommunityResult(
            Map<String, Integer> nodeToCommunit,
            double modularity,
            int communityCount,
            Map<Integer, List<String>> communityMembers,
            long factSheetId,
            String method) {
    }

    /**
     * Detect communities in the knowledge graph for the given fact sheet.
     *
     * @param factSheetId  the fact sheet whose graph is analysed
     * @param method       algorithm: "louvain" (default) or "label_propagation"
     * @param resolution   Louvain resolution γ (ignored for label_propagation); typical range 0.5–2.0
     * @param seed         random seed for reproducibility
     * @param maxNodes     maximum number of nodes to include in the reasoning subgraph
     * @return a {@link CommunityResult} with the full partition
     */
    public CommunityResult detectCommunities(long factSheetId,
                                              String method,
                                              double resolution,
                                              long seed,
                                              int maxNodes) {
        log.info("Detecting communities for factSheetId={} method={} resolution={} seed={} maxNodes={}",
                factSheetId, method, resolution, seed, maxNodes);

        // 1. Collect all node ids in the fact sheet
        List<GraphNode> allNodes = graphService.getNodesInFactSheet(factSheetId);
        List<String> seedNodeIds = new ArrayList<>(allNodes.size());
        for (GraphNode node : allNodes) {
            seedNodeIds.add(node.getNodeId());
        }
        log.debug("factSheetId={} has {} nodes to use as seeds", factSheetId, seedNodeIds.size());

        // 2. Build bounded subgraph via the reasoning adapter
        ReasoningGraph graph = new KnowledgeGraphReasoningAdapter(graphService)
                .maxNodes(maxNodes)
                .subgraph(seedNodeIds);
        log.debug("Built reasoning subgraph: {} entities", graph.entities().size());

        // 3. Pick detector
        String normalised = method == null ? "louvain" : method.trim().toLowerCase();
        CommunityDetector detector;
        if ("label_propagation".equals(normalised)) {
            detector = new LabelPropagationDetector();
        } else {
            // Default: louvain
            normalised = "louvain";
            detector = new LouvainDetector(resolution, 50, true);
        }

        // 4. Run detection
        CommunityAssignment assignment = detector.detect(graph, seed);
        log.info("Community detection complete: factSheetId={} method={} communities={} modularity={}",
                factSheetId, normalised, assignment.communityCount(), assignment.modularity());

        // 5. Build inverted index
        Map<Integer, List<String>> members = communityMembers(assignment);

        return new CommunityResult(
                assignment.assignments(),
                assignment.modularity(),
                assignment.communityCount(),
                members,
                factSheetId,
                normalised);
    }

    /**
     * Invert a {@link CommunityAssignment} into a communityId → list-of-nodeIds map.
     *
     * @param assignment the assignment to invert
     * @return map from community id to the list of node ids belonging to it
     */
    public static Map<Integer, List<String>> communityMembers(CommunityAssignment assignment) {
        Map<Integer, List<String>> members = new HashMap<>();
        for (Map.Entry<String, Integer> entry : assignment.assignments().entrySet()) {
            members.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        return members;
    }
}
