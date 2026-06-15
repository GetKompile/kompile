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
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a {@link PortableGraph} as a Wikipedia-style wiki inside a ZIP archive.
 * Each node type group gets its own article, and an {@code index.md} entry point
 * cross-links everything.
 *
 * <p>When community information is embedded in node metadata (key {@code "community"}),
 * articles are organised by community instead of type.</p>
 *
 * <p>Structure:
 * <pre>
 *   wiki/
 *     index.md            — table of contents
 *     articles/
 *       Community-1.md    — per-community article (or per-type fallback)
 *       Community-2.md
 *       ...
 *     entities/
 *       Entity-Name.md    — per-entity detail page
 * </pre>
 */
public final class WikiExporter {

    public byte[] toZip(PortableGraph graph) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Map<String, PortableNode> nodeById = new LinkedHashMap<>();
            for (PortableNode n : graph.nodes()) nodeById.put(n.externalId(), n);

            Map<String, List<PortableEdge>> outgoing = new HashMap<>();
            Map<String, List<PortableEdge>> incoming = new HashMap<>();
            for (PortableEdge e : graph.edges()) {
                outgoing.computeIfAbsent(e.fromExternalId(), k -> new ArrayList<>()).add(e);
                incoming.computeIfAbsent(e.toExternalId(), k -> new ArrayList<>()).add(e);
            }

            // Group nodes into communities (from metadata) or by type
            Map<String, List<PortableNode>> groups = groupNodes(graph.nodes());

            // --- index.md ---
            StringBuilder idx = new StringBuilder();
            idx.append("# Knowledge Graph Wiki\n\n");
            idx.append("> Auto-generated from ").append(graph.nodes().size())
                    .append(" entities and ").append(graph.edges().size()).append(" relationships.\n\n");
            idx.append("## Topics\n\n");
            for (String groupName : groups.keySet()) {
                String fname = safe(groupName);
                idx.append("- [").append(groupName).append("](articles/").append(fname).append(".md) — ")
                        .append(groups.get(groupName).size()).append(" entities\n");
            }
            idx.append("\n## All Entities\n\n");
            for (PortableNode n : graph.nodes()) {
                idx.append("- [").append(title(n)).append("](entities/").append(safe(title(n))).append(".md)\n");
            }
            addEntry(zos, "index.md", idx.toString());

            // --- per-group articles ---
            for (Map.Entry<String, List<PortableNode>> group : groups.entrySet()) {
                StringBuilder art = new StringBuilder();
                art.append("# ").append(group.getKey()).append("\n\n");

                // Summarise the group
                Map<String, Long> typeDist = group.getValue().stream()
                        .collect(Collectors.groupingBy(
                                n -> n.nodeType() != null ? n.nodeType() : "UNKNOWN",
                                Collectors.counting()));
                art.append("**Entities:** ").append(group.getValue().size()).append(" | ");
                art.append("**Types:** ").append(typeDist.entrySet().stream()
                        .map(e -> e.getKey() + " (" + e.getValue() + ")")
                        .collect(Collectors.joining(", "))).append("\n\n");

                // Entity table
                art.append("| Entity | Type | Connections | Description |\n");
                art.append("|--------|------|------------:|-------------|\n");
                for (PortableNode n : group.getValue()) {
                    int conn = outgoing.getOrDefault(n.externalId(), List.of()).size()
                            + incoming.getOrDefault(n.externalId(), List.of()).size();
                    String desc = n.description() != null ? truncate(n.description(), 60) : "—";
                    art.append("| [").append(title(n)).append("](../entities/").append(safe(title(n))).append(".md) | ")
                            .append(n.nodeType() != null ? n.nodeType() : "—").append(" | ")
                            .append(conn).append(" | ").append(desc).append(" |\n");
                }
                art.append("\n");

                // Internal relationships
                Set<String> memberIds = group.getValue().stream()
                        .map(PortableNode::externalId).collect(Collectors.toSet());
                List<PortableEdge> internal = new ArrayList<>();
                for (PortableNode n : group.getValue()) {
                    for (PortableEdge e : outgoing.getOrDefault(n.externalId(), List.of())) {
                        if (memberIds.contains(e.toExternalId())) internal.add(e);
                    }
                }
                if (!internal.isEmpty()) {
                    art.append("## Key Relationships\n\n");
                    for (PortableEdge e : internal.stream().limit(30).toList()) {
                        PortableNode src = nodeById.get(e.fromExternalId());
                        PortableNode tgt = nodeById.get(e.toExternalId());
                        art.append("- **").append(title(src)).append("** →")
                                .append(e.edgeType() != null ? " _" + e.edgeType() + "_" : "")
                                .append(" → **").append(title(tgt)).append("**");
                        if (e.description() != null) art.append(": ").append(truncate(e.description(), 80));
                        art.append("\n");
                    }
                    art.append("\n");
                }

                // Cross-group connections
                List<PortableEdge> cross = new ArrayList<>();
                for (PortableNode n : group.getValue()) {
                    for (PortableEdge e : outgoing.getOrDefault(n.externalId(), List.of())) {
                        if (!memberIds.contains(e.toExternalId())) cross.add(e);
                    }
                }
                if (!cross.isEmpty()) {
                    art.append("## External Connections\n\n");
                    for (PortableEdge e : cross.stream().limit(15).toList()) {
                        PortableNode src = nodeById.get(e.fromExternalId());
                        PortableNode tgt = nodeById.get(e.toExternalId());
                        art.append("- ").append(title(src)).append(" → [").append(title(tgt))
                                .append("](../entities/").append(safe(title(tgt))).append(".md)");
                        if (e.edgeType() != null) art.append(" (").append(e.edgeType()).append(")");
                        art.append("\n");
                    }
                    art.append("\n");
                }

                addEntry(zos, "articles/" + safe(group.getKey()) + ".md", art.toString());
            }

            // --- per-entity detail pages ---
            for (PortableNode n : graph.nodes()) {
                StringBuilder md = new StringBuilder();
                md.append("# ").append(title(n)).append("\n\n");
                md.append("**Type:** ").append(n.nodeType() != null ? n.nodeType() : "Unknown").append("\n\n");
                if (n.description() != null && !n.description().isBlank()) {
                    md.append(n.description()).append("\n\n");
                }

                List<PortableEdge> out = outgoing.getOrDefault(n.externalId(), List.of());
                List<PortableEdge> inc = incoming.getOrDefault(n.externalId(), List.of());
                if (!out.isEmpty()) {
                    md.append("## Relates To\n\n");
                    for (PortableEdge e : out) {
                        PortableNode tgt = nodeById.get(e.toExternalId());
                        md.append("- [").append(title(tgt)).append("](").append(safe(title(tgt))).append(".md)")
                                .append(" — ").append(e.edgeType() != null ? e.edgeType() : "RELATED_TO");
                        if (e.provenance() != null) md.append(" `").append(e.provenance()).append("`");
                        md.append("\n");
                    }
                    md.append("\n");
                }
                if (!inc.isEmpty()) {
                    md.append("## Referenced By\n\n");
                    for (PortableEdge e : inc) {
                        PortableNode src = nodeById.get(e.fromExternalId());
                        md.append("- [").append(title(src)).append("](").append(safe(title(src))).append(".md)")
                                .append(" — ").append(e.edgeType() != null ? e.edgeType() : "RELATED_TO");
                        if (e.provenance() != null) md.append(" `").append(e.provenance()).append("`");
                        md.append("\n");
                    }
                    md.append("\n");
                }

                addEntry(zos, "entities/" + safe(title(n)) + ".md", md.toString());
            }
        }
        return baos.toByteArray();
    }

    private Map<String, List<PortableNode>> groupNodes(List<PortableNode> nodes) {
        // Prefer community metadata if present
        boolean hasCommunities = nodes.stream().anyMatch(n ->
                n.metadata() != null && n.metadata().containsKey("community"));
        if (hasCommunities) {
            return nodes.stream().collect(Collectors.groupingBy(
                    n -> {
                        if (n.metadata() != null && n.metadata().containsKey("community")) {
                            return "Community: " + n.metadata().get("community");
                        }
                        return "Uncategorised";
                    },
                    LinkedHashMap::new, Collectors.toList()));
        }
        // Fallback: group by node type
        return nodes.stream().collect(Collectors.groupingBy(
                n -> n.nodeType() != null ? n.nodeType() : "UNKNOWN",
                LinkedHashMap::new, Collectors.toList()));
    }

    private static String title(PortableNode n) {
        if (n == null) return "unknown";
        return n.title() != null && !n.title().isBlank() ? n.title() : n.externalId();
    }

    private static String safe(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static void addEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
