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
 * Exports a {@link PortableGraph} as a GraphML XML document compatible with Gephi,
 * yEd, NetworkX, and igraph.
 */
public final class GraphMLExporter {

    public byte[] toBytes(PortableGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        sb.append("  <key id=\"title\" for=\"node\" attr.name=\"title\" attr.type=\"string\"/>\n");
        sb.append("  <key id=\"description\" for=\"node\" attr.name=\"description\" attr.type=\"string\"/>\n");
        sb.append("  <key id=\"nodeType\" for=\"node\" attr.name=\"nodeType\" attr.type=\"string\"/>\n");
        sb.append("  <key id=\"weight\" for=\"edge\" attr.name=\"weight\" attr.type=\"double\"/>\n");
        sb.append("  <key id=\"edgeType\" for=\"edge\" attr.name=\"edgeType\" attr.type=\"string\"/>\n");
        sb.append("  <graph id=\"G\" edgedefault=\"directed\">\n");
        for (PortableNode n : graph.nodes()) {
            sb.append("    <node id=\"").append(escape(n.externalId())).append("\">\n");
            if (n.title() != null) {
                sb.append("      <data key=\"title\">").append(escape(n.title())).append("</data>\n");
            }
            if (n.description() != null) {
                sb.append("      <data key=\"description\">").append(escape(n.description())).append("</data>\n");
            }
            if (n.nodeType() != null) {
                sb.append("      <data key=\"nodeType\">").append(escape(n.nodeType())).append("</data>\n");
            }
            sb.append("    </node>\n");
        }
        int edgeIdx = 0;
        for (PortableEdge e : graph.edges()) {
            sb.append("    <edge id=\"e").append(edgeIdx++).append("\" source=\"")
                    .append(escape(e.fromExternalId())).append("\" target=\"")
                    .append(escape(e.toExternalId())).append("\">\n");
            if (e.weight() != null) {
                sb.append("      <data key=\"weight\">").append(e.weight()).append("</data>\n");
            }
            if (e.edgeType() != null) {
                sb.append("      <data key=\"edgeType\">").append(escape(e.edgeType())).append("</data>\n");
            }
            sb.append("    </edge>\n");
        }
        sb.append("  </graph>\n");
        sb.append("</graphml>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
