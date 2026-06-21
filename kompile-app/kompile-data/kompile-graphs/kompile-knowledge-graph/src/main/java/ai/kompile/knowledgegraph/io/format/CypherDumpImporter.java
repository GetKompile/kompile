/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal line-oriented Cypher dump parser. Designed to round-trip the output of
 * {@link CypherDumpExporter} — not a full Cypher grammar.
 *
 * <p>Recognized statements (one per line, semicolon-terminated):
 * <ul>
 *   <li>{@code CREATE (n:Label {externalId:'...', title:'...', description:'...', ...});}</li>
 *   <li>{@code MATCH (a {externalId:'...'}), (b {externalId:'...'}) CREATE (a)-[:TYPE {weight: 1.0}]->(b);}</li>
 * </ul>
 */
public final class CypherDumpImporter {

    private static final Logger log = LoggerFactory.getLogger(CypherDumpImporter.class);

    private static final Pattern CREATE_NODE = Pattern.compile(
            "(?i)CREATE\\s*\\(\\s*\\w*\\s*:([A-Z_]+)\\s*\\{([^}]*)\\}\\s*\\)\\s*;?");
    private static final Pattern CREATE_REL = Pattern.compile(
            "(?i)MATCH\\s*\\(\\s*\\w+\\s*\\{([^}]*)\\}\\s*\\)\\s*,\\s*\\(\\s*\\w+\\s*\\{([^}]*)\\}\\s*\\)\\s*"
                    + "CREATE\\s*\\(\\s*\\w+\\s*\\)\\s*-\\s*\\[\\s*:([A-Z_]+)\\s*(?:\\{([^}]*)\\})?\\s*\\]\\s*->\\s*\\(\\s*\\w+\\s*\\)\\s*;?");
    private static final Pattern PROPERTY = Pattern.compile(
            "(\\w+)\\s*:\\s*('([^']*)'|\"([^\"]*)\"|([0-9eE.+-]+))");

    public PortableGraph parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        for (String stmt : text.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            Matcher node = CREATE_NODE.matcher(trimmed);
            if (node.find()) {
                Map<String, String> props = parseProps(node.group(2));
                String externalId = props.get("externalId");
                if (externalId == null) externalId = props.get("nodeId");
                if (externalId == null) continue;
                // Known scalar props that go into dedicated PortableNode fields
                java.util.Set<String> knownNodeProps = java.util.Set.of(
                        "externalId", "nodeId", "title", "description",
                        "confidence", "namedGraphId", "factSheetId", "occurredAt");
                Map<String, Object> meta = new HashMap<>();
                for (Map.Entry<String, String> e : props.entrySet()) {
                    if (!knownNodeProps.contains(e.getKey())) {
                        meta.put(e.getKey(), e.getValue());
                    }
                }
                Double nodeConfidence = null;
                if (props.containsKey("confidence")) {
                    try { nodeConfidence = Double.parseDouble(props.get("confidence")); }
                    catch (NumberFormatException ex) {
                        log.debug("Invalid node confidence '{}' in Cypher dump: {}", props.get("confidence"), ex.getMessage());
                    }
                }
                Long nodeFactSheetId = null;
                if (props.containsKey("factSheetId")) {
                    try { nodeFactSheetId = Long.parseLong(props.get("factSheetId")); }
                    catch (NumberFormatException ex) {
                        log.debug("Invalid node factSheetId '{}' in Cypher dump: {}", props.get("factSheetId"), ex.getMessage());
                    }
                }
                nodes.add(new PortableNode(
                        externalId,
                        props.getOrDefault("title", externalId),
                        props.get("description"),
                        node.group(1).toUpperCase(),
                        meta.isEmpty() ? null : meta,
                        nodeFactSheetId,
                        props.get("namedGraphId"),
                        nodeConfidence,
                        props.get("occurredAt")));
                continue;
            }
            Matcher rel = CREATE_REL.matcher(trimmed);
            if (rel.find()) {
                Map<String, String> from = parseProps(rel.group(1));
                Map<String, String> to = parseProps(rel.group(2));
                Map<String, String> relProps = rel.group(4) == null ? Map.of() : parseProps(rel.group(4));
                Double weight = null;
                if (relProps.containsKey("weight")) {
                    try { weight = Double.parseDouble(relProps.get("weight")); } catch (NumberFormatException e) {
                        log.debug("Invalid relationship weight '{}' in Cypher dump, treating as null: {}", relProps.get("weight"), e.getMessage());
                    }
                }
                Double edgeConfidence = null;
                if (relProps.containsKey("confidence")) {
                    try { edgeConfidence = Double.parseDouble(relProps.get("confidence")); }
                    catch (NumberFormatException e) {
                        log.debug("Invalid edge confidence '{}' in Cypher dump: {}", relProps.get("confidence"), e.getMessage());
                    }
                }
                Long edgeFactSheetId = null;
                if (relProps.containsKey("factSheetId")) {
                    try { edgeFactSheetId = Long.parseLong(relProps.get("factSheetId")); }
                    catch (NumberFormatException e) {
                        log.debug("Invalid edge factSheetId '{}' in Cypher dump: {}", relProps.get("factSheetId"), e.getMessage());
                    }
                }
                edges.add(new PortableEdge(
                        from.getOrDefault("externalId", from.get("nodeId")),
                        to.getOrDefault("externalId", to.get("nodeId")),
                        rel.group(3).toUpperCase(),
                        weight,
                        relProps.get("description"),
                        relProps.get("provenance"),
                        edgeConfidence,
                        null,
                        relProps.get("relationType"),
                        edgeFactSheetId,
                        null, null, null, null, null));
            }
        }
        return new PortableGraph(nodes, edges);
    }

    private static Map<String, String> parseProps(String inside) {
        Map<String, String> out = new HashMap<>();
        Matcher m = PROPERTY.matcher(inside);
        while (m.find()) {
            String key = m.group(1);
            String val;
            if (m.group(3) != null) val = m.group(3);
            else if (m.group(4) != null) val = m.group(4);
            else val = m.group(5);
            out.put(key, val);
        }
        return out;
    }
}
