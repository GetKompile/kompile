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

import java.nio.charset.StandardCharsets;

/**
 * Shared RDF term minting + serialization for the N-Triples and Turtle exporters. Unlike the
 * (syntactic-only) JSON-LD exporter, these mint real, absolute IRIs under a kompile namespace so the
 * output is genuine RDF that loads in any triplestore.
 *
 * <p>IRI scheme (base {@value #BASE}):
 * <ul>
 *   <li>node subject — {@code <base>node/<encoded externalId>}</li>
 *   <li>{@code rdf:type} object (semantic class) — {@code <base>class/<encoded nodeType>}</li>
 *   <li>relationship predicate — {@code <base>rel/<encoded edgeType>}</li>
 *   <li>property predicate (metadata, confidence, …) — {@code <base>prop/<encoded key>}</li>
 * </ul>
 * Local parts are percent-encoded to the IRI-safe set so arbitrary externalIds / metadata keys /
 * edge types are always valid.
 */
final class RdfSupport {

    private RdfSupport() {}

    static final String BASE = "https://kompile.ai/kg/";
    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    static final String XSD_DOUBLE = XSD + "double";

    static String nodeIri(String externalId) {
        return BASE + "node/" + enc(externalId == null ? "_blank" : externalId);
    }

    static String classIri(String nodeType) {
        return BASE + "class/" + enc(nodeType == null || nodeType.isBlank() ? "UNKNOWN" : nodeType);
    }

    static String relIri(String edgeType) {
        return BASE + "rel/" + enc(edgeType == null || edgeType.isBlank() ? "relatedTo" : edgeType);
    }

    static String propIri(String key) {
        return BASE + "prop/" + enc(key == null || key.isBlank() ? "value" : key);
    }

    /** Wrap an absolute IRI as an N-Triples/Turtle IRIREF: {@code <iri>}. */
    static String iriRef(String iri) {
        return "<" + iri + ">";
    }

    /** A plain string literal: {@code "escaped"}. */
    static String literal(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** A typed literal with a full datatype IRI: {@code "escaped"^^<datatype>}. */
    static String typedLiteral(String value, String datatypeIri) {
        return "\"" + escape(value) + "\"^^" + iriRef(datatypeIri);
    }

    /**
     * Percent-encode {@code s} (UTF-8) down to the IRI-safe unreserved set
     * {@code [A-Za-z0-9-_.]}, so the result is a valid IRI local part AND a valid Turtle
     * PN_LOCAL (percent-escapes are legal there).
     */
    static String enc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (byte raw : s.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (safe) {
                b.append((char) c);
            } else {
                b.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return b.toString();
    }

    /** Escape a string for an RDF quoted literal (N-Triples/Turtle ECHAR set). */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
