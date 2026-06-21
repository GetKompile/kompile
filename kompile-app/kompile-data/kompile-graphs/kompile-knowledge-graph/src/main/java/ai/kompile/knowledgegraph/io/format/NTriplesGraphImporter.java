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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses <a href="https://www.w3.org/TR/n-triples/">N-Triples</a> produced by
 * {@link NTriplesGraphExporter} back into a {@link PortableGraph}.
 *
 * <p>Only triples minted under the {@value RdfSupport#BASE} namespace are
 * recognized — i.e. the output of our own exporter. Foreign triples (external
 * ontologies, arbitrary RDF) are silently ignored, keeping the importer
 * purpose-built for the round-trip use case and not a general N-Triples
 * processor.</p>
 *
 * <p>Reconstruction logic mirrors {@link NTriplesGraphExporter} in reverse:
 * <ul>
 *   <li>{@code <base>node/X rdf:type <base>class/TYPE>} → node externalId + nodeType</li>
 *   <li>{@code <base>node/X rdfs:label "title"} → node title</li>
 *   <li>{@code <base>node/X rdfs:comment "desc"} → node description</li>
 *   <li>{@code <base>node/X <base>prop/confidence "0.9"^^xsd:double} → node confidence</li>
 *   <li>{@code <base>node/X <base>prop/occurredAt "..."} → node occurredAt</li>
 *   <li>{@code <base>node/X <base>prop/KEY "val"} → node metadata[KEY]=val</li>
 *   <li>{@code <base>node/FROM <base>rel/TYPE <base>node/TO .} → edge</li>
 * </ul>
 */
public final class NTriplesGraphImporter {

    private static final Logger log = LoggerFactory.getLogger(NTriplesGraphImporter.class);

    /**
     * Parse N-Triples bytes into a {@link PortableGraph}.
     *
     * @param payload UTF-8 encoded N-Triples content
     * @return reconstructed graph; never {@code null}, may be empty
     */
    public PortableGraph parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return PortableGraph.empty();
        }
        String text = new String(payload, StandardCharsets.UTF_8);

        // Intermediate builder maps: externalId (decoded) → field accumulators
        Map<String, NodeBuilder> nodeBuilders = new LinkedHashMap<>();
        List<PortableEdge> edges = new ArrayList<>();

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Remove trailing " ." and leading/trailing whitespace
            if (line.endsWith(" .")) {
                line = line.substring(0, line.length() - 2).trim();
            } else if (line.endsWith(".")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            try {
                parseLine(line, nodeBuilders, edges);
            } catch (Exception e) {
                log.debug("Skipping malformed N-Triples line: {} — {}", rawLine.trim(), e.getMessage());
            }
        }

        List<PortableNode> nodes = new ArrayList<>(nodeBuilders.size());
        for (NodeBuilder b : nodeBuilders.values()) {
            nodes.add(b.build());
        }
        return new PortableGraph(nodes, edges);
    }

    private static void parseLine(String line,
                                   Map<String, NodeBuilder> nodeBuilders,
                                   List<PortableEdge> edges) {
        // Expect: <subject> <predicate> <object-or-literal>
        // We do a minimal hand-split rather than a full N-Triples parser to avoid
        // importing a triplestore library. The exporter only produces one shape of
        // literal ("value" or "value"^^<datatype>), so this is sufficient.
        String subject = extractIriRef(line);
        if (subject == null || !subject.startsWith(RdfSupport.BASE + "node/")) {
            return; // not one of our minted node subjects
        }
        String externalId = decode(subject.substring((RdfSupport.BASE + "node/").length()));
        NodeBuilder builder = nodeBuilders.computeIfAbsent(externalId, NodeBuilder::new);

        // Consume subject from line
        int afterSubject = line.indexOf('>') + 1;
        String rest = line.substring(afterSubject).trim();

        String predicate = extractIriRef(rest);
        if (predicate == null) {
            return;
        }
        int afterPredicate = rest.indexOf('>') + 1;
        String objectPart = rest.substring(afterPredicate).trim();

        // ── rdf:type → nodeType class IRI ──────────────────────────
        if (RdfSupport.RDF_TYPE.equals(predicate)) {
            String classIri = extractIriRef(objectPart);
            if (classIri != null && classIri.startsWith(RdfSupport.BASE + "class/")) {
                builder.nodeType = decode(classIri.substring((RdfSupport.BASE + "class/").length()));
            }
            return;
        }

        // ── rel/<TYPE> → edge to another node ──────────────────────
        if (predicate.startsWith(RdfSupport.BASE + "rel/")) {
            String edgeType = decode(predicate.substring((RdfSupport.BASE + "rel/").length()));
            String toIri = extractIriRef(objectPart);
            if (toIri != null && toIri.startsWith(RdfSupport.BASE + "node/")) {
                String toId = decode(toIri.substring((RdfSupport.BASE + "node/").length()));
                edges.add(new PortableEdge(externalId, toId, edgeType, null, null));
            }
            return;
        }

        // ── rdfs:label → title ─────────────────────────────────────
        if (RdfSupport.RDFS_LABEL.equals(predicate)) {
            builder.title = extractLiteral(objectPart);
            return;
        }

        // ── rdfs:comment → description ─────────────────────────────
        if (RdfSupport.RDFS_COMMENT.equals(predicate)) {
            builder.description = extractLiteral(objectPart);
            return;
        }

        // ── prop/<key> → metadata or special field ─────────────────
        if (predicate.startsWith(RdfSupport.BASE + "prop/")) {
            String key = decode(predicate.substring((RdfSupport.BASE + "prop/").length()));
            String value = extractLiteral(objectPart);
            if (value == null) return;
            switch (key) {
                case "confidence" -> {
                    try {
                        builder.confidence = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        log.debug("Non-numeric confidence '{}' for node {}", value, externalId);
                    }
                }
                case "occurredAt" -> builder.occurredAt = value;
                default -> builder.metadata.put(key, value);
            }
        }
    }

    /** Extract the IRI from an {@code <iri>} token at the start of {@code s}. */
    private static String extractIriRef(String s) {
        if (!s.startsWith("<")) return null;
        int close = s.indexOf('>');
        if (close < 0) return null;
        return s.substring(1, close);
    }

    /**
     * Extract the string value from an N-Triples literal.
     * Handles {@code "value"}, {@code "value"^^<datatype>}, and unescapes ECHAR sequences.
     */
    static String extractLiteral(String s) {
        if (!s.startsWith("\"")) return null;
        // find the closing unescaped quote
        int end = -1;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++; // skip next char
            } else if (c == '"') {
                end = i;
                break;
            }
        }
        if (end < 0) return null;
        return unescape(s.substring(1, end));
    }

    /** Reverse of {@link RdfSupport#escape(String)}. */
    private static String unescape(String s) {
        if (!s.contains("\\")) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    default -> { b.append('\\'); b.append(next); }
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Reverse of {@link RdfSupport#enc(String)}: percent-decode UTF-8 sequences. */
    static String decode(String encoded) {
        try {
            return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded; // best-effort
        }
    }

    // ─── Inner builder ──────────────────────────────────────────────────────

    private static final class NodeBuilder {
        final String externalId;
        String nodeType;
        String title;
        String description;
        Double confidence;
        String occurredAt;
        final Map<String, Object> metadata = new LinkedHashMap<>();

        NodeBuilder(String externalId) {
            this.externalId = externalId;
        }

        PortableNode build() {
            return new PortableNode(
                    externalId,
                    title,
                    description,
                    nodeType == null ? "ENTITY" : nodeType,
                    metadata.isEmpty() ? null : metadata,
                    null,   // factSheetId not carried in N-Triples
                    null,   // namedGraphId not carried in N-Triples
                    confidence,
                    occurredAt);
        }
    }
}
