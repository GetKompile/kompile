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
            if (n.confidence() != null) {
                sb.append(", confidence: ").append(n.confidence());
            }
            if (n.namedGraphId() != null) {
                sb.append(", namedGraphId: '").append(escape(n.namedGraphId())).append("'");
            }
            if (n.factSheetId() != null) {
                sb.append(", factSheetId: ").append(n.factSheetId());
            }
            if (n.occurredAt() != null) {
                sb.append(", occurredAt: '").append(escape(n.occurredAt())).append("'");
            }
            sb.append("});\n");
        }
        for (PortableEdge e : graph.edges()) {
            sb.append("MATCH (a {externalId: '").append(escape(e.fromExternalId())).append("'})")
                    .append(", (b {externalId: '").append(escape(e.toExternalId())).append("'}) ")
                    .append("CREATE (a)-[:").append(safeLabel(e.edgeType()));
            boolean hasProps = e.weight() != null || e.description() != null
                    || e.confidence() != null || e.relationType() != null
                    || e.provenance() != null || e.factSheetId() != null;
            if (hasProps) {
                sb.append(" {");
                boolean first = true;
                if (e.weight() != null) {
                    sb.append("weight: ").append(e.weight());
                    first = false;
                }
                if (e.description() != null) {
                    if (!first) sb.append(", ");
                    sb.append("description: '").append(escape(e.description())).append("'");
                    first = false;
                }
                if (e.confidence() != null) {
                    if (!first) sb.append(", ");
                    sb.append("confidence: ").append(e.confidence());
                    first = false;
                }
                if (e.relationType() != null) {
                    if (!first) sb.append(", ");
                    sb.append("relationType: '").append(escape(e.relationType())).append("'");
                    first = false;
                }
                if (e.provenance() != null) {
                    if (!first) sb.append(", ");
                    sb.append("provenance: '").append(escape(e.provenance())).append("'");
                    first = false;
                }
                if (e.factSheetId() != null) {
                    if (!first) sb.append(", ");
                    sb.append("factSheetId: ").append(e.factSheetId());
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
