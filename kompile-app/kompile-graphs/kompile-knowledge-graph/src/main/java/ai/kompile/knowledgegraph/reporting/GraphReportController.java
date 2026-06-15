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
package ai.kompile.knowledgegraph.reporting;

import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.reporting.Reporter;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * REST endpoint for generating knowledge graph reports in Markdown format.
 */
@RestController
@RequestMapping("/api/graph/report")
public class GraphReportController {

    private final Reporter reporter;
    private final KnowledgeGraphService graphService;

    public GraphReportController(Reporter reporter, KnowledgeGraphService graphService) {
        this.reporter = reporter;
        this.graphService = graphService;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> generateReport(
            @RequestParam(value = "type", defaultValue = "summary") String type,
            @RequestParam(value = "factSheetId", required = false) Long factSheetId) {

        Graph graph = buildGraphModel(factSheetId);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        switch (type.toLowerCase()) {
            case "communities" -> reporter.generateCommunityReports(graph, baos);
            case "entities" -> reporter.generateEntityRelationshipReport(graph, baos);
            default -> reporter.generateGraphSummaryReport(graph, baos);
        }

        return ResponseEntity.ok(baos.toString());
    }

    private Graph buildGraphModel(Long factSheetId) {
        Graph graph = new Graph();
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        Set<String> emittedEdgeIds = new HashSet<>();

        for (NodeLevel level : NodeLevel.values()) {
            for (GraphNode node : graphService.searchNodes("", level, Integer.MAX_VALUE)) {
                if (factSheetId != null && !factSheetId.equals(node.getFactSheetId())) continue;

                Entity e = new Entity();
                e.setId(node.getNodeId());
                e.setTitle(node.getTitle());
                e.setType(node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN");
                e.setDescription(node.getDescription());
                entities.add(e);

                for (GraphEdge edge : graphService.getEdgesForNode(node.getNodeId())) {
                    if (factSheetId != null && !factSheetId.equals(edge.getFactSheetId())) continue;
                    if (!emittedEdgeIds.add(edge.getEdgeId())) continue;

                    Relationship r = new Relationship();
                    r.setSource(edge.getSourceNode().getNodeId());
                    r.setTarget(edge.getTargetNode().getNodeId());
                    r.setType(edge.getEdgeType() != null ? edge.getEdgeType().name() : "RELATED_TO");
                    r.setDescription(edge.getDescription());
                    r.setWeight(edge.getWeight());
                    r.setConfidence(edge.getSimilarityScore());
                    relationships.add(r);
                }
            }
        }

        graph.setEntities(entities);
        graph.setRelationships(relationships);
        graph.setCommunities(List.of()); // communities populated by algorithm runs
        return graph;
    }
}
