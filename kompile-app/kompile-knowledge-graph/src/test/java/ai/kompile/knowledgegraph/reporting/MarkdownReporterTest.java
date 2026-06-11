/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.reporting;

import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MarkdownReporter} — graph summary, community reports,
 * entity-relationship reports, hub detection, surprising connections,
 * and suggested queries.
 */
class MarkdownReporterTest {

    private MarkdownReporter reporter;

    @BeforeEach
    void setUp() {
        reporter = new MarkdownReporter();
    }

    // ─── Helper builders ──────────────────────────────────────────────

    private Entity entity(String id, String title, String type, String description, Double confidence) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(title);
        e.setType(type);
        e.setDescription(description);
        e.setConfidence(confidence);
        return e;
    }

    private Relationship relationship(String source, String target, String type,
                                       String description, Double confidence) {
        Relationship r = new Relationship();
        r.setSource(source);
        r.setTarget(target);
        r.setType(type);
        r.setDescription(description);
        r.setConfidence(confidence);
        return r;
    }

    private Community community(String id, String summary, List<String> entities) {
        Community c = new Community();
        c.setId(id);
        c.setSummary(summary);
        c.setEntities(entities);
        return c;
    }

    private String generateReport(Graph graph) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reporter.generateGraphSummaryReport(graph, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String generateCommunityReport(Graph graph) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reporter.generateCommunityReports(graph, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String generateEntityReport(Graph graph) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reporter.generateEntityRelationshipReport(graph, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ─── Graph Summary ────────────────────────────────────────────────

    @Test
    void graphSummary_emptyGraph_containsHeaderAndZeroCounts() {
        Graph graph = new Graph();
        String md = generateReport(graph);

        assertTrue(md.contains("# Knowledge Graph Report"));
        assertTrue(md.contains("Generated:"));
        assertTrue(md.contains("## Overview"));
        assertTrue(md.contains("Entities | 0"));
        assertTrue(md.contains("Relationships | 0"));
        assertTrue(md.contains("Communities | 0"));
    }

    @Test
    void graphSummary_withEntities_showsTypeCounts() {
        Graph graph = new Graph();
        graph.setEntities(List.of(
                entity("e1", "Alice", "PERSON", null, null),
                entity("e2", "Bob", "PERSON", null, null),
                entity("e3", "Acme", "ORGANIZATION", null, null)
        ));
        graph.setRelationships(List.of());
        graph.setCommunities(List.of());

        String md = generateReport(graph);

        assertTrue(md.contains("Entities | 3"));
        assertTrue(md.contains("PERSON"));
        assertTrue(md.contains("ORGANIZATION"));
    }

    @Test
    void graphSummary_hubNodes_rankedByDegree() {
        Entity hub = entity("hub", "Central Hub", "CONCEPT", "Very connected", null);
        Entity leaf1 = entity("l1", "Leaf 1", "CONCEPT", null, null);
        Entity leaf2 = entity("l2", "Leaf 2", "CONCEPT", null, null);

        Graph graph = new Graph();
        graph.setEntities(List.of(hub, leaf1, leaf2));
        graph.setRelationships(List.of(
                relationship("hub", "l1", "RELATED_TO", null, null),
                relationship("hub", "l2", "RELATED_TO", null, null)
        ));
        graph.setCommunities(List.of());

        String md = generateReport(graph);

        assertTrue(md.contains("## Hub Nodes"));
        assertTrue(md.contains("Central Hub"));
        // Hub should appear first since it has degree 2
        int hubPos = md.indexOf("Central Hub");
        int leafPos = md.indexOf("Leaf 1");
        assertTrue(hubPos < leafPos || leafPos == -1);
    }

    @Test
    void graphSummary_surprisingConnections_crossTypeEdges() {
        Entity person = entity("p1", "Alice", "PERSON", null, null);
        Entity doc = entity("d1", "Report", "DOCUMENT", null, null);

        Graph graph = new Graph();
        graph.setEntities(List.of(person, doc));
        graph.setRelationships(List.of(
                relationship("p1", "d1", "AUTHORED", "Alice wrote this report", 0.95)
        ));
        graph.setCommunities(List.of());

        String md = generateReport(graph);

        assertTrue(md.contains("## Surprising Connections"));
        assertTrue(md.contains("AUTHORED"));
    }

    @Test
    void graphSummary_noCrossTypeEdges_showsNoSurprising() {
        Entity e1 = entity("e1", "A", "PERSON", null, null);
        Entity e2 = entity("e2", "B", "PERSON", null, null);

        Graph graph = new Graph();
        graph.setEntities(List.of(e1, e2));
        graph.setRelationships(List.of(
                relationship("e1", "e2", "KNOWS", null, null)
        ));
        graph.setCommunities(List.of());

        String md = generateReport(graph);

        assertTrue(md.contains("No cross-type connections found"));
    }

    @Test
    void graphSummary_withCommunities_showsSummaries() {
        Graph graph = new Graph();
        graph.setEntities(List.of(entity("e1", "A", "T", null, null)));
        graph.setRelationships(List.of());
        graph.setCommunities(List.of(
                community("cluster-1", "A cluster about testing", List.of("e1"))
        ));

        String md = generateReport(graph);

        assertTrue(md.contains("## Communities"));
        assertTrue(md.contains("cluster-1"));
        assertTrue(md.contains("A cluster about testing"));
    }

    @Test
    void graphSummary_suggestedQueries_generated() {
        Entity e1 = entity("e1", "TopHub", "TYPE_A", null, null);
        Entity e2 = entity("e2", "SecondHub", "TYPE_B", null, null);

        Graph graph = new Graph();
        graph.setEntities(List.of(e1, e2));
        graph.setRelationships(List.of(
                relationship("e1", "e2", "R", null, null)
        ));
        graph.setCommunities(List.of());

        String md = generateReport(graph);

        assertTrue(md.contains("## Suggested Queries"));
        // Should contain numbered queries
        assertTrue(md.contains("1."));
    }

    @Test
    void graphSummary_relationshipTypes_listed() {
        Graph graph = new Graph();
        graph.setEntities(List.of(
                entity("e1", "A", "T", null, null),
                entity("e2", "B", "T", null, null)
        ));
        graph.setRelationships(List.of(
                relationship("e1", "e2", "WORKS_FOR", null, null),
                relationship("e2", "e1", "MANAGES", null, null)
        ));
        graph.setCommunities(List.of());

        String md = generateReport(graph);

        assertTrue(md.contains("WORKS_FOR"));
        assertTrue(md.contains("MANAGES"));
    }

    // ─── Community Reports ────────────────────────────────────────────

    @Test
    void communityReport_noCommunities_showsMessage() {
        Graph graph = new Graph();
        graph.setCommunities(List.of());

        String md = generateCommunityReport(graph);

        assertTrue(md.contains("# Community Reports"));
        assertTrue(md.contains("No communities detected"));
    }

    @Test
    void communityReport_withMembers_listsEntities() {
        Entity e1 = entity("e1", "Alice", "PERSON", "Engineer", null);
        Entity e2 = entity("e2", "Bob", "PERSON", "Manager", null);

        Graph graph = new Graph();
        graph.setEntities(List.of(e1, e2));
        graph.setRelationships(List.of(
                relationship("e1", "e2", "WORKS_WITH", null, null)
        ));
        graph.setCommunities(List.of(
                community("team-1", "Engineering team", List.of("e1", "e2"))
        ));

        String md = generateCommunityReport(graph);

        assertTrue(md.contains("## Community: team-1"));
        assertTrue(md.contains("Engineering team"));
        assertTrue(md.contains("Alice"));
        assertTrue(md.contains("Bob"));
        assertTrue(md.contains("Internal Relationships"));
    }

    @Test
    void communityReport_crossCommunityEdges_listed() {
        Entity e1 = entity("e1", "Alice", "PERSON", null, null);
        Entity e2 = entity("e2", "Bob", "PERSON", null, null);
        Entity e3 = entity("e3", "Charlie", "PERSON", null, null);

        Graph graph = new Graph();
        graph.setEntities(List.of(e1, e2, e3));
        graph.setRelationships(List.of(
                relationship("e1", "e3", "KNOWS", null, null) // cross-community
        ));
        // Community 1 has e1, e2; e3 is outside
        graph.setCommunities(List.of(
                community("c1", "Group 1", List.of("e1", "e2"))
        ));

        String md = generateCommunityReport(graph);

        assertTrue(md.contains("Cross-Community Edges"));
    }

    // ─── Entity & Relationship Report ─────────────────────────────────

    @Test
    void entityReport_emptyGraph_containsHeader() {
        Graph graph = new Graph();

        String md = generateEntityReport(graph);

        assertTrue(md.contains("# Entity & Relationship Report"));
        assertTrue(md.contains("Generated:"));
    }

    @Test
    void entityReport_groupedByType() {
        Entity person = entity("p1", "Alice", "PERSON", "An engineer", 0.9);
        Entity org = entity("o1", "Acme", "ORGANIZATION", "A company", 0.8);

        Graph graph = new Graph();
        graph.setEntities(List.of(person, org));
        graph.setRelationships(List.of(
                relationship("p1", "o1", "WORKS_AT", null, null)
        ));

        String md = generateEntityReport(graph);

        assertTrue(md.contains("## PERSON"));
        assertTrue(md.contains("## ORGANIZATION"));
        assertTrue(md.contains("### Alice"));
        assertTrue(md.contains("An engineer"));
        assertTrue(md.contains("Confidence: 0.90"));
    }

    @Test
    void entityReport_showsIncomingAndOutgoing() {
        Entity e1 = entity("e1", "A", "TYPE", null, null);
        Entity e2 = entity("e2", "B", "TYPE", null, null);

        Graph graph = new Graph();
        graph.setEntities(List.of(e1, e2));
        graph.setRelationships(List.of(
                relationship("e1", "e2", "LINKS_TO", null, null)
        ));

        String md = generateEntityReport(graph);

        // e1 should have outgoing arrow
        assertTrue(md.contains("→"));
        // e2 should have incoming arrow
        assertTrue(md.contains("←"));
    }

    // ─── Null-safe graph fields ───────────────────────────────────────

    @Test
    void nullEntities_handledGracefully() {
        Graph graph = new Graph();
        graph.setEntities(null);
        graph.setRelationships(null);
        graph.setCommunities(null);

        String md = generateReport(graph);

        assertTrue(md.contains("Entities | 0"));
        assertTrue(md.contains("Relationships | 0"));
    }
}
