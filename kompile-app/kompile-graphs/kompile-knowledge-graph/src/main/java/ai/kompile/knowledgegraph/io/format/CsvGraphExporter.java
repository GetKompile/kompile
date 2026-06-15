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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a {@link PortableGraph} as a ZIP archive containing nodes.csv and edges.csv.
 * Format matches what {@link CsvGraphImporter} accepts.
 */
public final class CsvGraphExporter {

    public byte[] toZip(PortableGraph graph) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("nodes.csv"));
            zip.write(nodesCsv(graph).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("edges.csv"));
            zip.write(edgesCsv(graph).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    public String nodesCsv(PortableGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("externalId,title,description,nodeType\n");
        for (PortableNode n : graph.nodes()) {
            sb.append(quote(n.externalId())).append(',')
                    .append(quote(n.title())).append(',')
                    .append(quote(n.description())).append(',')
                    .append(quote(n.nodeType())).append('\n');
        }
        return sb.toString();
    }

    public String edgesCsv(PortableGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("fromExternalId,toExternalId,edgeType,weight,description\n");
        for (PortableEdge e : graph.edges()) {
            sb.append(quote(e.fromExternalId())).append(',')
                    .append(quote(e.toExternalId())).append(',')
                    .append(quote(e.edgeType())).append(',')
                    .append(e.weight() == null ? "" : e.weight()).append(',')
                    .append(quote(e.description())).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
