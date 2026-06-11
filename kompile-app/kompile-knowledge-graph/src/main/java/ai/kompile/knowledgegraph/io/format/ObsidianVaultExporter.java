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
 * Exports a {@link PortableGraph} as an Obsidian-compatible vault inside a ZIP archive.
 * Each entity becomes a Markdown file with YAML frontmatter and {@code [[wikilinks]]}
 * for relationships.
 *
 * <p>Structure:
 * <pre>
 *   obsidian-vault/
 *     index.md            — graph overview with links to all entities
 *     entities/
 *       EntityTitle.md    — one per node, with frontmatter + links
 * </pre>
 */
public final class ObsidianVaultExporter {

    public byte[] toZip(PortableGraph graph) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Build lookup structures
            Map<String, PortableNode> nodeById = new LinkedHashMap<>();
            for (PortableNode n : graph.nodes()) {
                nodeById.put(n.externalId(), n);
            }

            // outgoing and incoming edges per node
            Map<String, List<PortableEdge>> outgoing = new HashMap<>();
            Map<String, List<PortableEdge>> incoming = new HashMap<>();
            for (PortableEdge e : graph.edges()) {
                outgoing.computeIfAbsent(e.fromExternalId(), k -> new ArrayList<>()).add(e);
                incoming.computeIfAbsent(e.toExternalId(), k -> new ArrayList<>()).add(e);
            }

            // Group by type
            Map<String, List<PortableNode>> byType = graph.nodes().stream()
                    .collect(Collectors.groupingBy(n -> n.nodeType() != null ? n.nodeType() : "UNKNOWN",
                            LinkedHashMap::new, Collectors.toList()));

            // --- index.md ---
            StringBuilder idx = new StringBuilder();
            idx.append("---\ntags: [kompile, knowledge-graph, index]\n---\n\n");
            idx.append("# Knowledge Graph\n\n");
            idx.append("**Nodes:** ").append(graph.nodes().size())
                    .append(" | **Edges:** ").append(graph.edges().size()).append("\n\n");
            for (Map.Entry<String, List<PortableNode>> entry : byType.entrySet()) {
                idx.append("## ").append(entry.getKey()).append("\n\n");
                for (PortableNode n : entry.getValue()) {
                    String fname = safeFilename(title(n));
                    idx.append("- [[entities/").append(fname).append("|").append(title(n)).append("]]\n");
                }
                idx.append("\n");
            }
            addEntry(zos, "index.md", idx.toString());

            // --- per-entity files ---
            for (PortableNode n : graph.nodes()) {
                StringBuilder md = new StringBuilder();
                // YAML frontmatter
                md.append("---\n");
                md.append("id: \"").append(n.externalId()).append("\"\n");
                md.append("type: ").append(n.nodeType() != null ? n.nodeType() : "UNKNOWN").append("\n");
                md.append("tags: [kompile, ").append(
                        (n.nodeType() != null ? n.nodeType() : "UNKNOWN").toLowerCase()).append("]\n");
                md.append("---\n\n");

                md.append("# ").append(title(n)).append("\n\n");
                if (n.description() != null && !n.description().isBlank()) {
                    md.append(n.description()).append("\n\n");
                }

                // Outgoing relationships
                List<PortableEdge> out = outgoing.getOrDefault(n.externalId(), List.of());
                if (!out.isEmpty()) {
                    md.append("## Outgoing Relationships\n\n");
                    for (PortableEdge e : out) {
                        PortableNode target = nodeById.get(e.toExternalId());
                        String targetTitle = target != null ? title(target) : e.toExternalId();
                        String targetFile = safeFilename(targetTitle);
                        String relType = e.edgeType() != null ? e.edgeType() : "RELATED_TO";
                        String prov = e.provenance() != null ? " `" + e.provenance() + "`" : "";
                        md.append("- **").append(relType).append("** → [[entities/")
                                .append(targetFile).append("|").append(targetTitle).append("]]").append(prov);
                        if (e.description() != null) {
                            md.append(" — ").append(e.description());
                        }
                        md.append("\n");
                    }
                    md.append("\n");
                }

                // Incoming relationships
                List<PortableEdge> inc = incoming.getOrDefault(n.externalId(), List.of());
                if (!inc.isEmpty()) {
                    md.append("## Incoming Relationships\n\n");
                    for (PortableEdge e : inc) {
                        PortableNode source = nodeById.get(e.fromExternalId());
                        String sourceTitle = source != null ? title(source) : e.fromExternalId();
                        String sourceFile = safeFilename(sourceTitle);
                        String relType = e.edgeType() != null ? e.edgeType() : "RELATED_TO";
                        String prov = e.provenance() != null ? " `" + e.provenance() + "`" : "";
                        md.append("- [[entities/").append(sourceFile).append("|").append(sourceTitle)
                                .append("]] → **").append(relType).append("**").append(prov);
                        if (e.description() != null) {
                            md.append(" — ").append(e.description());
                        }
                        md.append("\n");
                    }
                    md.append("\n");
                }

                // Metadata
                if (n.metadata() != null && !n.metadata().isEmpty()) {
                    md.append("## Metadata\n\n");
                    for (Map.Entry<String, Object> m : n.metadata().entrySet()) {
                        md.append("- **").append(m.getKey()).append(":** ").append(m.getValue()).append("\n");
                    }
                    md.append("\n");
                }

                String fname = "entities/" + safeFilename(title(n)) + ".md";
                addEntry(zos, fname, md.toString());
            }
        }
        return baos.toByteArray();
    }

    private static String title(PortableNode n) {
        return n.title() != null && !n.title().isBlank() ? n.title() : n.externalId();
    }

    private static String safeFilename(String name) {
        // Remove characters invalid in filenames, replace spaces with hyphens
        return name.replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    private static void addEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
