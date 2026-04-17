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

import java.nio.charset.StandardCharsets;

/**
 * Emits one CREATE statement per node and one MATCH+CREATE per edge. Round-trips
 * via {@link CypherDumpImporter}.
 */
public final class CypherDumpExporter {

    public byte[] toBytes(PortableGraph graph) {
        StringBuilder sb = new StringBuilder();
        for (PortableNode n : graph.nodes()) {
            sb.append("CREATE (n:").append(safeLabel(n.nodeType())).append(" {")
                    .append("externalId: '").append(escape(n.externalId())).append("'");
            if (n.title() != null) {
                sb.append(", title: '").append(escape(n.title())).append("'");
            }
            if (n.description() != null) {
                sb.append(", description: '").append(escape(n.description())).append("'");
            }
            sb.append("});\n");
        }
        for (PortableEdge e : graph.edges()) {
            sb.append("MATCH (a {externalId: '").append(escape(e.fromExternalId())).append("'})")
                    .append(", (b {externalId: '").append(escape(e.toExternalId())).append("'}) ")
                    .append("CREATE (a)-[:").append(safeLabel(e.edgeType()));
            boolean hasProps = e.weight() != null || e.description() != null;
            if (hasProps) {
                sb.append(" {");
                if (e.weight() != null) sb.append("weight: ").append(e.weight());
                if (e.description() != null) {
                    if (e.weight() != null) sb.append(", ");
                    sb.append("description: '").append(escape(e.description())).append("'");
                }
                sb.append("}");
            }
            sb.append("]->(b);\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    private static String safeLabel(String label) {
        if (label == null || label.isBlank()) return "Node";
        return label.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
