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
package ai.kompile.graph.reasoning.mebn.type.owl;

import java.util.Objects;

/**
 * Namespace constants and IRI-minting helpers for the infra-free OWL layer.
 *
 * <p>This class is a standalone utility within {@code kompile-graph-reasoning} — it does not
 * depend on the {@code RdfSupport} class in {@code kompile-knowledge-graph}, which carries
 * different module-level dependencies. Both classes mint IRIs under the same
 * {@code https://kompile.ai/kg/} base namespace for interoperability.</p>
 *
 * <p>All methods are static; this class is not instantiable.</p>
 */
public final class OwlIri {

    // ─── Namespace constants ──────────────────────────────────────────────────────

    /** The OWL 2 namespace ({@value}). */
    public static final String OWL  = "http://www.w3.org/2002/07/owl#";

    /** The RDF Schema namespace ({@value}). */
    public static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";

    /** The RDF namespace ({@value}). */
    public static final String RDF  = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /** The XML Schema Datatypes namespace ({@value}). */
    public static final String XSD  = "http://www.w3.org/2001/XMLSchema#";

    /**
     * The kompile knowledge-graph base namespace ({@value}).
     * Matches {@code RdfSupport.BASE} in {@code kompile-knowledge-graph} for IRI interoperability.
     */
    public static final String BASE = "https://kompile.ai/kg/";

    // ─── Well-known XSD datatype IRIs ─────────────────────────────────────────────

    public static final String XSD_STRING   = XSD + "string";
    public static final String XSD_INTEGER  = XSD + "integer";
    public static final String XSD_LONG     = XSD + "long";
    public static final String XSD_DOUBLE   = XSD + "double";
    public static final String XSD_FLOAT    = XSD + "float";
    public static final String XSD_DECIMAL  = XSD + "decimal";
    public static final String XSD_BOOLEAN  = XSD + "boolean";
    public static final String XSD_DATETIME = XSD + "dateTime";
    public static final String XSD_DATE     = XSD + "date";
    public static final String XSD_ANY_URI  = XSD + "anyURI";

    // ─── IRI minting ──────────────────────────────────────────────────────────────

    /**
     * Mint an absolute class IRI for the given simple class name.
     *
     * <p>Example: {@code classIri("Person")} → {@code "https://kompile.ai/kg/class/Person"}</p>
     *
     * @param name the simple class name (never {@code null})
     * @return an absolute IRI string
     */
    public static String classIri(String name) {
        return BASE + "class/" + encode(Objects.requireNonNull(name, "name"));
    }

    /**
     * Mint an absolute property IRI for the given simple property name.
     *
     * <p>Example: {@code propIri("owns")} → {@code "https://kompile.ai/kg/prop/owns"}</p>
     *
     * @param name the simple property name (never {@code null})
     * @return an absolute IRI string
     */
    public static String propIri(String name) {
        return BASE + "prop/" + encode(Objects.requireNonNull(name, "name"));
    }

    /**
     * Mint an absolute individual IRI for the given node/individual ID.
     *
     * <p>Example: {@code indIri("alice-1")} → {@code "https://kompile.ai/kg/node/alice-1"}</p>
     *
     * @param id the individual/node identifier (never {@code null})
     * @return an absolute IRI string
     */
    public static String indIri(String id) {
        return BASE + "node/" + encode(Objects.requireNonNull(id, "id"));
    }

    /**
     * Mint an absolute ontology IRI for the given ontology name.
     *
     * <p>Example: {@code ontologyIri("MyOntology")} →
     * {@code "https://kompile.ai/kg/ontology/MyOntology"}</p>
     *
     * @param name the ontology name (never {@code null})
     * @return an absolute IRI string
     */
    public static String ontologyIri(String name) {
        return BASE + "ontology/" + encode(Objects.requireNonNull(name, "name"));
    }

    /**
     * Minimal percent-encoding for IRI path segments: replaces space, {@code <}, {@code >},
     * {@code "}, {@code {}, {@code }}, {@code |}, {@code \}, {@code ^}, and backtick.
     * Other characters — including {@code /}, {@code #}, {@code ?} — are left as-is because
     * they are valid in IRI paths and are meaningful here.
     *
     * <p>For full RFC 3987 encoding use a dedicated IRI library; this helper is intentionally
     * minimal (infra-free constraint).</p>
     *
     * @param segment the path segment to encode (never {@code null})
     * @return the percent-encoded segment
     */
    public static String encode(String segment) {
        // Fast path: no special characters — avoid StringBuilder allocation
        boolean needsEncoding = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == ' ' || c == '<' || c == '>' || c == '"'
                    || c == '{' || c == '}' || c == '|' || c == '\\'
                    || c == '^' || c == '`') {
                needsEncoding = true;
                break;
            }
        }
        if (!needsEncoding) return segment;

        StringBuilder sb = new StringBuilder(segment.length() + 8);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            switch (c) {
                case ' '  -> sb.append("%20");
                case '<'  -> sb.append("%3C");
                case '>'  -> sb.append("%3E");
                case '"'  -> sb.append("%22");
                case '{'  -> sb.append("%7B");
                case '}'  -> sb.append("%7D");
                case '|'  -> sb.append("%7C");
                case '\\' -> sb.append("%5C");
                case '^'  -> sb.append("%5E");
                case '`'  -> sb.append("%60");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private OwlIri() { /* utility class — not instantiable */ }
}
