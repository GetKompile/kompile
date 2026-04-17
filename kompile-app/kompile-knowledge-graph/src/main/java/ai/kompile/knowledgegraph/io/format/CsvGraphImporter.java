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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses neo4j-admin-style CSV files.
 *
 * <p>Nodes header: {@code externalId,title,description,nodeType[,...metadata cols]}.
 * Edges header: {@code fromExternalId,toExternalId,edgeType,weight,description}.
 *
 * <p>Suffixes such as {@code :ID}, {@code :LABEL}, {@code :TYPE}, {@code :START_ID},
 * {@code :END_ID}, {@code :double} are stripped from header names so the documented
 * neo4j-admin format is also accepted.
 */
public final class CsvGraphImporter {

    private static final List<String> RESERVED_NODE_COLS = List.of(
            "externalid", "title", "description", "nodetype");
    private static final List<String> RESERVED_EDGE_COLS = List.of(
            "fromexternalid", "toexternalid", "edgetype", "weight", "description");

    public PortableGraph parse(byte[] nodesCsv, byte[] edgesCsv) throws IOException {
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        if (nodesCsv != null && nodesCsv.length > 0) parseNodes(new String(nodesCsv, StandardCharsets.UTF_8), nodes);
        if (edgesCsv != null && edgesCsv.length > 0) parseEdges(new String(edgesCsv, StandardCharsets.UTF_8), edges);
        return new PortableGraph(nodes, edges);
    }

    private void parseNodes(String csv, List<PortableNode> sink) throws IOException {
        try (BufferedReader r = new BufferedReader(new StringReader(csv))) {
            String headerLine = r.readLine();
            if (headerLine == null) return;
            List<String> headers = parseRow(headerLine).stream().map(CsvGraphImporter::normalize).toList();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = parseRow(line);
                Map<String, String> cells = zip(headers, row);
                String externalId = cells.get("externalid");
                if (externalId == null || externalId.isBlank()) continue;
                Map<String, Object> meta = new HashMap<>();
                for (Map.Entry<String, String> e : cells.entrySet()) {
                    if (!RESERVED_NODE_COLS.contains(e.getKey()) && e.getValue() != null && !e.getValue().isBlank()) {
                        meta.put(e.getKey(), e.getValue());
                    }
                }
                sink.add(new PortableNode(
                        externalId,
                        cells.getOrDefault("title", externalId),
                        cells.get("description"),
                        cells.getOrDefault("nodetype", "ENTITY"),
                        meta.isEmpty() ? null : meta
                ));
            }
        }
    }

    private void parseEdges(String csv, List<PortableEdge> sink) throws IOException {
        try (BufferedReader r = new BufferedReader(new StringReader(csv))) {
            String headerLine = r.readLine();
            if (headerLine == null) return;
            List<String> headers = parseRow(headerLine).stream().map(CsvGraphImporter::normalize).toList();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = parseRow(line);
                Map<String, String> cells = zip(headers, row);
                String from = cells.get("fromexternalid");
                String to = cells.get("toexternalid");
                if (from == null || to == null || from.isBlank() || to.isBlank()) continue;
                Double weight = null;
                String w = cells.get("weight");
                if (w != null && !w.isBlank()) {
                    try { weight = Double.parseDouble(w); } catch (NumberFormatException ignored) {}
                }
                sink.add(new PortableEdge(
                        from,
                        to,
                        cells.getOrDefault("edgetype", "USER_DEFINED"),
                        weight,
                        cells.get("description")
                ));
            }
        }
    }

    private static String normalize(String header) {
        if (header == null) return "";
        String name = header.trim();
        // Strip neo4j-admin type suffixes: e.g. nodeId:ID, weight:double, :TYPE, :START_ID
        int colon = name.indexOf(':');
        if (colon == 0) {
            // bare :TYPE / :LABEL / :START_ID / :END_ID — map to canonical names
            String tag = name.substring(1).toLowerCase();
            return switch (tag) {
                case "id" -> "externalid";
                case "type" -> "edgetype";
                case "label" -> "nodetype";
                case "start_id" -> "fromexternalid";
                case "end_id" -> "toexternalid";
                default -> tag;
            };
        }
        if (colon > 0) name = name.substring(0, colon);
        // Special: nodeId -> externalId convention
        String lower = name.toLowerCase();
        if (lower.equals("nodeid")) return "externalid";
        if (lower.equals("startid")) return "fromexternalid";
        if (lower.equals("endid")) return "toexternalid";
        return lower;
    }

    private static List<String> parseRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    buf.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cells.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(ch);
            }
        }
        cells.add(buf.toString());
        return cells;
    }

    private static Map<String, String> zip(List<String> keys, List<String> values) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keys.size() && i < values.size(); i++) {
            map.put(keys.get(i), values.get(i));
        }
        return map;
    }
}
