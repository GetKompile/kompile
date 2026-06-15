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
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Markdown-formatted knowledge graph reports similar to Graphify's
 * GRAPH_REPORT.md. Identifies hub ("god") nodes, surprising cross-domain
 * connections, community summaries, and suggested follow-up queries.
 */
@Component
public class MarkdownReporter implements Reporter {

    private static final int TOP_HUBS = 10;
    private static final int TOP_SURPRISING = 10;
    private static final int SUGGESTED_QUERIES = 5;

    @Override
    public void generateGraphSummaryReport(Graph graph, OutputStream output) {
        PrintWriter w = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);

        List<Entity> entities = graph.getEntities() == null ? List.of() : graph.getEntities();
        List<Relationship> relationships = graph.getRelationships() == null ? List.of() : graph.getRelationships();
        List<Community> communities = graph.getCommunities() == null ? List.of() : graph.getCommunities();

        w.println("# Knowledge Graph Report");
        w.println();
        w.println("_Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "_");
        w.println();

        // --- Overview ---
        w.println("## Overview");
        w.println();
        w.println("| Metric | Count |");
        w.println("|--------|------:|");
        w.printf("| Entities | %,d |%n", entities.size());
        w.printf("| Relationships | %,d |%n", relationships.size());
        w.printf("| Communities | %,d |%n", communities.size());
        Map<String, Long> typeCounts = entities.stream()
                .filter(e -> e.getType() != null)
                .collect(Collectors.groupingBy(Entity::getType, Collectors.counting()));
        for (Map.Entry<String, Long> tc : typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
            w.printf("| &nbsp;&nbsp;%s | %,d |%n", tc.getKey(), tc.getValue());
        }
        Map<String, Long> relTypeCounts = relationships.stream()
                .filter(r -> r.getType() != null)
                .collect(Collectors.groupingBy(Relationship::getType, Collectors.counting()));
        if (!relTypeCounts.isEmpty()) {
            w.println();
            w.println("### Relationship Types");
            w.println();
            w.println("| Type | Count |");
            w.println("|------|------:|");
            for (Map.Entry<String, Long> rc : relTypeCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
                w.printf("| %s | %,d |%n", rc.getKey(), rc.getValue());
            }
        }

        // --- Edge type breakdown (by type) ---
        Map<String, Long> edgeTypeCounts = relationships.stream()
                .filter(r -> r.getType() != null)
                .collect(Collectors.groupingBy(Relationship::getType, Collectors.counting()));
        if (!edgeTypeCounts.isEmpty()) {
            w.println();
            w.println("### Edge Types");
            w.println();
            w.println("| Type | Count |");
            w.println("|------|------:|");
            for (Map.Entry<String, Long> pc : edgeTypeCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
                w.printf("| %s | %,d |%n", pc.getKey(), pc.getValue());
            }
        }

        w.println();

        // --- God Nodes (highest-degree entities) ---
        w.println("## Hub Nodes (Highest Degree)");
        w.println();
        w.println("These are the most connected entities in the graph — the \"god nodes\" that act as central connection hubs.");
        w.println();

        Map<String, Integer> degree = computeDegree(entities, relationships);
        List<Map.Entry<String, Integer>> topHubs = degree.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_HUBS)
                .toList();
        Map<String, Entity> entityById = entities.stream()
                .filter(e -> e.getId() != null)
                .collect(Collectors.toMap(Entity::getId, e -> e, (a, b) -> a));

        w.println("| Rank | Entity | Type | Degree | Description |");
        w.println("|-----:|--------|------|-------:|-------------|");
        int rank = 1;
        for (Map.Entry<String, Integer> hub : topHubs) {
            Entity e = entityById.get(hub.getKey());
            String title = e != null ? e.getTitle() : hub.getKey();
            String type = e != null && e.getType() != null ? e.getType() : "—";
            String desc = e != null && e.getDescription() != null
                    ? truncate(e.getDescription(), 80) : "—";
            w.printf("| %d | **%s** | %s | %d | %s |%n", rank++, title, type, hub.getValue(), desc);
        }
        w.println();

        // --- Surprising Connections ---
        w.println("## Surprising Connections");
        w.println();
        w.println("Cross-domain edges ranked by a composite score: low-frequency type pairs with high confidence score highest.");
        w.println();

        List<ScoredRelationship> surprising = findSurprisingConnections(entities, relationships, entityById);
        if (surprising.isEmpty()) {
            w.println("_No cross-type connections found._");
        } else {
            w.println("| Score | Source | Target | Relationship | Description |");
            w.println("|------:|--------|--------|--------------|-------------|");
            for (ScoredRelationship sr : surprising.stream().limit(TOP_SURPRISING).toList()) {
                Entity src = entityById.get(sr.rel.getSource());
                Entity tgt = entityById.get(sr.rel.getTarget());
                String srcName = src != null ? src.getTitle() : sr.rel.getSource();
                String tgtName = tgt != null ? tgt.getTitle() : sr.rel.getTarget();
                String relType = sr.rel.getType() != null ? sr.rel.getType() : "RELATED_TO";
                String desc = sr.rel.getDescription() != null
                        ? truncate(sr.rel.getDescription(), 60) : "—";
                w.printf("| %.2f | %s | %s | %s | %s |%n", sr.score, srcName, tgtName, relType, desc);
            }
        }
        w.println();

        // --- Community Summaries ---
        if (!communities.isEmpty()) {
            w.println("## Communities");
            w.println();
            for (Community c : communities) {
                String label = c.getId() != null ? c.getId() : "unnamed";
                int memberCount = c.getEntities() != null ? c.getEntities().size() : 0;
                w.printf("### Community: %s (%d members)%n", label, memberCount);
                w.println();
                if (c.getSummary() != null && !c.getSummary().isBlank()) {
                    w.println(c.getSummary());
                }
                if (c.getEntities() != null && !c.getEntities().isEmpty()) {
                    w.println();
                    w.println("**Members:** " + c.getEntities().stream()
                            .map(id -> {
                                Entity ent = entityById.get(id);
                                return ent != null ? ent.getTitle() : id;
                            })
                            .limit(20)
                            .collect(Collectors.joining(", ")));
                    if (c.getEntities().size() > 20) {
                        w.printf(" _…and %d more_%n", c.getEntities().size() - 20);
                    }
                }
                w.println();
            }
        }

        // --- Suggested Follow-up Queries ---
        w.println("## Suggested Queries");
        w.println();
        w.println("Questions the graph can distinctly answer:");
        w.println();
        List<String> queries = generateSuggestedQueries(entities, relationships, communities, topHubs, entityById);
        for (int i = 0; i < queries.size(); i++) {
            w.printf("%d. %s%n", i + 1, queries.get(i));
        }
        w.println();

        w.flush();
    }

    @Override
    public void generateCommunityReports(Graph graph, OutputStream output) {
        PrintWriter w = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        List<Community> communities = graph.getCommunities() == null ? List.of() : graph.getCommunities();
        List<Entity> entities = graph.getEntities() == null ? List.of() : graph.getEntities();
        List<Relationship> relationships = graph.getRelationships() == null ? List.of() : graph.getRelationships();
        Map<String, Entity> entityById = entities.stream()
                .filter(e -> e.getId() != null)
                .collect(Collectors.toMap(Entity::getId, e -> e, (a, b) -> a));

        w.println("# Community Reports");
        w.println();
        w.println("_Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "_");
        w.println();

        if (communities.isEmpty()) {
            w.println("_No communities detected. Run community detection first._");
            w.flush();
            return;
        }

        for (Community c : communities) {
            String label = c.getId() != null ? c.getId() : "unnamed";
            Set<String> memberSet = c.getEntities() != null ? new HashSet<>(c.getEntities()) : Set.of();
            w.printf("## Community: %s%n", label);
            w.println();
            if (c.getSummary() != null && !c.getSummary().isBlank()) {
                w.println(c.getSummary());
                w.println();
            }
            w.printf("**Members (%d):**%n%n", memberSet.size());
            for (String memberId : memberSet) {
                Entity ent = entityById.get(memberId);
                if (ent != null) {
                    w.printf("- **%s** (%s)%s%n", ent.getTitle(),
                            ent.getType() != null ? ent.getType() : "unknown",
                            ent.getDescription() != null ? " — " + truncate(ent.getDescription(), 80) : "");
                } else {
                    w.printf("- %s%n", memberId);
                }
            }
            w.println();

            // Internal relationships
            List<Relationship> internal = relationships.stream()
                    .filter(r -> memberSet.contains(r.getSource()) && memberSet.contains(r.getTarget()))
                    .toList();
            if (!internal.isEmpty()) {
                w.printf("**Internal Relationships (%d):**%n%n", internal.size());
                for (Relationship r : internal.stream().limit(20).toList()) {
                    Entity src = entityById.get(r.getSource());
                    Entity tgt = entityById.get(r.getTarget());
                    w.printf("- %s → %s (`%s`)%n",
                            src != null ? src.getTitle() : r.getSource(),
                            tgt != null ? tgt.getTitle() : r.getTarget(),
                            r.getType() != null ? r.getType() : "RELATED_TO");
                }
                w.println();
            }

            // Cross-community edges
            List<Relationship> crossEdges = relationships.stream()
                    .filter(r -> (memberSet.contains(r.getSource()) && !memberSet.contains(r.getTarget()))
                            || (!memberSet.contains(r.getSource()) && memberSet.contains(r.getTarget())))
                    .toList();
            if (!crossEdges.isEmpty()) {
                w.printf("**Cross-Community Edges (%d):**%n%n", crossEdges.size());
                for (Relationship r : crossEdges.stream().limit(10).toList()) {
                    Entity src = entityById.get(r.getSource());
                    Entity tgt = entityById.get(r.getTarget());
                    w.printf("- %s → %s (`%s`)%n",
                            src != null ? src.getTitle() : r.getSource(),
                            tgt != null ? tgt.getTitle() : r.getTarget(),
                            r.getType() != null ? r.getType() : "RELATED_TO");
                }
                w.println();
            }
            w.println("---");
            w.println();
        }
        w.flush();
    }

    @Override
    public void generateEntityRelationshipReport(Graph graph, OutputStream output) {
        PrintWriter w = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        List<Entity> entities = graph.getEntities() == null ? List.of() : graph.getEntities();
        List<Relationship> relationships = graph.getRelationships() == null ? List.of() : graph.getRelationships();

        w.println("# Entity & Relationship Report");
        w.println();
        w.println("_Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "_");
        w.println();

        // Group entities by type
        Map<String, List<Entity>> byType = entities.stream()
                .collect(Collectors.groupingBy(e -> e.getType() != null ? e.getType() : "UNKNOWN"));

        for (Map.Entry<String, List<Entity>> entry : byType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            w.printf("## %s (%d)%n%n", entry.getKey(), entry.getValue().size());
            for (Entity e : entry.getValue()) {
                w.printf("### %s%n", e.getTitle() != null ? e.getTitle() : e.getId());
                if (e.getDescription() != null) {
                    w.println(e.getDescription());
                }
                if (e.getConfidence() != null) {
                    w.printf("_Confidence: %.2f_%n", e.getConfidence());
                }
                // Outgoing relationships
                List<Relationship> outgoing = relationships.stream()
                        .filter(r -> e.getId() != null && e.getId().equals(r.getSource()))
                        .toList();
                List<Relationship> incoming = relationships.stream()
                        .filter(r -> e.getId() != null && e.getId().equals(r.getTarget()))
                        .toList();
                if (!outgoing.isEmpty() || !incoming.isEmpty()) {
                    w.println();
                    for (Relationship r : outgoing) {
                        w.printf("- → **%s** (`%s`)%n", r.getTarget(),
                                r.getType() != null ? r.getType() : "RELATED_TO");
                    }
                    for (Relationship r : incoming) {
                        w.printf("- ← **%s** (`%s`)%n", r.getSource(),
                                r.getType() != null ? r.getType() : "RELATED_TO");
                    }
                }
                w.println();
            }
        }
        w.flush();
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private Map<String, Integer> computeDegree(List<Entity> entities, List<Relationship> relationships) {
        Map<String, Integer> deg = new HashMap<>();
        for (Entity e : entities) {
            if (e.getId() != null) deg.put(e.getId(), 0);
        }
        for (Relationship r : relationships) {
            deg.merge(r.getSource(), 1, Integer::sum);
            deg.merge(r.getTarget(), 1, Integer::sum);
        }
        return deg;
    }

    private record ScoredRelationship(Relationship rel, double score) {}

    private List<ScoredRelationship> findSurprisingConnections(
            List<Entity> entities, List<Relationship> relationships,
            Map<String, Entity> entityById) {
        // Count how often each type-pair occurs
        Map<String, Integer> typePairCount = new HashMap<>();
        int totalRels = relationships.size();
        for (Relationship r : relationships) {
            Entity src = entityById.get(r.getSource());
            Entity tgt = entityById.get(r.getTarget());
            if (src == null || tgt == null) continue;
            String srcType = src.getType() != null ? src.getType() : "UNKNOWN";
            String tgtType = tgt.getType() != null ? tgt.getType() : "UNKNOWN";
            if (srcType.equals(tgtType)) continue; // same-type edges aren't surprising
            String pair = srcType.compareTo(tgtType) < 0 ? srcType + "|" + tgtType : tgtType + "|" + srcType;
            typePairCount.merge(pair, 1, Integer::sum);
        }

        List<ScoredRelationship> scored = new ArrayList<>();
        for (Relationship r : relationships) {
            Entity src = entityById.get(r.getSource());
            Entity tgt = entityById.get(r.getTarget());
            if (src == null || tgt == null) continue;
            String srcType = src.getType() != null ? src.getType() : "UNKNOWN";
            String tgtType = tgt.getType() != null ? tgt.getType() : "UNKNOWN";
            if (srcType.equals(tgtType)) continue;
            String pair = srcType.compareTo(tgtType) < 0 ? srcType + "|" + tgtType : tgtType + "|" + srcType;
            int pairFreq = typePairCount.getOrDefault(pair, 1);
            double rarity = 1.0 - ((double) pairFreq / Math.max(totalRels, 1));
            double conf = r.getConfidence() != null ? r.getConfidence() : 0.5;
            double score = rarity * 0.6 + conf * 0.4; // composite score
            scored.add(new ScoredRelationship(r, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredRelationship::score).reversed());
        return scored;
    }

    private List<String> generateSuggestedQueries(
            List<Entity> entities, List<Relationship> relationships,
            List<Community> communities,
            List<Map.Entry<String, Integer>> topHubs,
            Map<String, Entity> entityById) {
        List<String> queries = new ArrayList<>();

        // Query about the top hub
        if (!topHubs.isEmpty()) {
            Entity top = entityById.get(topHubs.get(0).getKey());
            if (top != null) {
                queries.add("What is the role of **" + top.getTitle() + "** and what connects to it?");
            }
        }
        // Query about relationship between top two hubs
        if (topHubs.size() >= 2) {
            Entity h1 = entityById.get(topHubs.get(0).getKey());
            Entity h2 = entityById.get(topHubs.get(1).getKey());
            if (h1 != null && h2 != null) {
                queries.add("What is the connection path between **" + h1.getTitle() + "** and **" + h2.getTitle() + "**?");
            }
        }
        // Query about communities
        if (!communities.isEmpty()) {
            queries.add("What are the main topic clusters in this graph and how do they interrelate?");
        }
        // Type-specific queries
        Map<String, Long> typeCounts = entities.stream()
                .filter(e -> e.getType() != null)
                .collect(Collectors.groupingBy(Entity::getType, Collectors.counting()));
        List<String> topTypes = typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(2)
                .map(Map.Entry::getKey)
                .toList();
        if (topTypes.size() >= 2) {
            queries.add("How do " + topTypes.get(0) + " entities relate to " + topTypes.get(1) + " entities?");
        }
        // Isolation query
        Map<String, Integer> degree = computeDegree(entities, relationships);
        long isolated = degree.values().stream().filter(d -> d <= 1).count();
        if (isolated > 0) {
            queries.add("Which entities are isolated or weakly connected, and why?");
        }

        while (queries.size() < SUGGESTED_QUERIES) {
            queries.add("What patterns emerge from the relationships in this graph?");
            break;
        }
        return queries.stream().limit(SUGGESTED_QUERIES).toList();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
