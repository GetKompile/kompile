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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses <a href="https://www.w3.org/TR/turtle/">Turtle</a> produced by
 * {@link TurtleGraphExporter} back into a {@link PortableGraph}.
 *
 * <p>Strategy: the Turtle output groups predicate-object pairs under each subject IRI,
 * separated by {@code ;} and terminated by {@code .}.  This importer normalises that
 * structure into a flat N-Triples-like stream and then delegates to
 * {@link NTriplesGraphImporter} for term interpretation, so all decoding/escape logic
 * lives in exactly one place.</p>
 *
 * <p>Only the single-subject-block form produced by {@link TurtleGraphExporter} is
 * supported — not arbitrary Turtle syntax.</p>
 */
public final class TurtleGraphImporter {

    private static final Logger log = LoggerFactory.getLogger(TurtleGraphImporter.class);

    /** Matches a Turtle prefix declaration: {@code @prefix rdfs: <...> .} */
    private static final Pattern PREFIX_LINE = Pattern.compile(
            "^@prefix\\s+(\\w+):\\s*<([^>]+)>\\s*\\.?$", Pattern.CASE_INSENSITIVE);

    /**
     * Parse Turtle bytes into a {@link PortableGraph}.
     *
     * @param payload UTF-8 encoded Turtle content
     * @return reconstructed graph; never {@code null}, may be empty
     */
    public PortableGraph parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return PortableGraph.empty();
        }
        String text = new String(payload, StandardCharsets.UTF_8);

        // Collect prefix declarations so we can expand them during normalisation.
        Map<String, String> prefixes = new LinkedHashMap<>();
        // Defaults that the exporter always emits:
        prefixes.put("rdfs", RdfSupport.RDFS);
        prefixes.put("xsd", RdfSupport.XSD);

        // ── Pass 1: collect prefixes ────────────────────────────────────────
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            Matcher pm = PREFIX_LINE.matcher(line);
            if (pm.matches()) {
                prefixes.put(pm.group(1), pm.group(2));
            }
        }

        // ── Pass 2: normalise subject blocks into N-Triples lines ──────────
        // The Turtle exporter writes:
        //   <subject>
        //       predObjPair1 ;
        //       predObjPair2 ;
        //       predObjPairN .
        //
        // We collect each block (subject + its predicate-object pairs) then expand
        // each pair into a synthetic "<subject> <predicate> <object> ." line.
        StringBuilder ntLines = new StringBuilder(text.length());

        String currentSubject = null;
        // Accumulate lines of a block (until we hit a line ending with " .")
        StringBuilder blockBuffer = new StringBuilder();

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || PREFIX_LINE.matcher(line).matches()) {
                if (currentSubject != null && blockBuffer.length() > 0) {
                    // Flush any accumulated PO pairs
                    flushBlock(currentSubject, blockBuffer.toString(), prefixes, ntLines);
                    blockBuffer.setLength(0);
                    currentSubject = null;
                }
                continue;
            }

            // A line starting with '<' that is not a continuation is a new subject.
            if (line.startsWith("<") && !line.startsWith("<" + RdfSupport.BASE)
                    // any IRI-ref as first token that is followed by nothing else on the line
                    // means it's just the subject opening
                    || (line.startsWith("<") && endsWithSubjectClose(line))) {
                // Flush previous block if any
                if (currentSubject != null && blockBuffer.length() > 0) {
                    flushBlock(currentSubject, blockBuffer.toString(), prefixes, ntLines);
                    blockBuffer.setLength(0);
                }
                currentSubject = extractIriRef(line);
                // Rest of the line after the subject (if any) is a predicate-object pair
                int afterSubject = line.indexOf('>') + 1;
                String afterS = line.substring(afterSubject).trim();
                if (!afterS.isEmpty()) {
                    blockBuffer.append(afterS).append("\n");
                }
            } else if (currentSubject != null) {
                blockBuffer.append(line).append("\n");
            } else {
                // line contains '<base>...' as subject — detect by looking for IRI prefix
                if (line.startsWith("<" + RdfSupport.BASE)) {
                    if (currentSubject != null && blockBuffer.length() > 0) {
                        flushBlock(currentSubject, blockBuffer.toString(), prefixes, ntLines);
                        blockBuffer.setLength(0);
                    }
                    currentSubject = extractIriRef(line);
                    int afterSubject = line.indexOf('>') + 1;
                    String afterS = line.substring(afterSubject).trim();
                    if (!afterS.isEmpty()) {
                        blockBuffer.append(afterS).append("\n");
                    }
                }
            }
        }
        // Flush the last block
        if (currentSubject != null && blockBuffer.length() > 0) {
            flushBlock(currentSubject, blockBuffer.toString(), prefixes, ntLines);
        }

        // ── Pass 3: parse the normalised N-Triples stream ─────────────────
        return new NTriplesGraphImporter().parse(ntLines.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Expand a block's predicate-object section into N-Triples lines.
     * The block content uses {@code ;}-separated pairs, each pair ended by {@code ;} or {@code .}.
     */
    private static void flushBlock(String subjectIri,
                                    String poBlock,
                                    Map<String, String> prefixes,
                                    StringBuilder out) {
        // Split on ";" but preserve literal strings — simple split is sufficient because
        // the exporter escapes ';' inside literals.
        String[] pairs = poBlock.split(";");
        for (String pair : pairs) {
            String trimmed = removeDot(pair.trim());
            if (trimmed.isEmpty()) continue;
            // expand predicate shorthand (a → rdf:type IRI, rdfs:xxx → full IRI, etc.)
            String expanded = expandPO(trimmed, prefixes);
            if (expanded != null) {
                out.append('<').append(subjectIri).append("> ")
                   .append(expanded).append(" .\n");
            }
        }
    }

    /**
     * Expand a predicate-object pair string, resolving prefix shorthands to full IRIs.
     * Returns the pair as-is if already in N-Triples form, or {@code null} if unparseable.
     */
    private static String expandPO(String po, Map<String, String> prefixes) {
        if (po.isEmpty()) return null;

        // Split into predicate token and object part (rest of string)
        // Predicate is up to first whitespace
        int ws = firstWhitespace(po);
        if (ws < 0) return null;
        String pred = po.substring(0, ws).trim();
        String obj = po.substring(ws).trim();

        String expandedPred = expandTerm(pred, prefixes);
        if (expandedPred == null) return null;

        // Expand object if it's an IRI-ref or prefixed name
        String expandedObj = expandObject(obj, prefixes);
        if (expandedObj == null) return null;

        return expandedPred + " " + expandedObj;
    }

    /**
     * Expand a predicate token:
     * <ul>
     *   <li>{@code a} → {@code <rdf:type-iri>}</li>
     *   <li>{@code rdfs:label} → {@code <rdfs:label-iri>}</li>
     *   <li>{@code <absolute-iri>} → unchanged</li>
     * </ul>
     */
    private static String expandTerm(String term, Map<String, String> prefixes) {
        if ("a".equals(term)) {
            return "<" + RdfSupport.RDF_TYPE + ">";
        }
        if (term.startsWith("<") && term.endsWith(">")) {
            return term; // already absolute IRI-ref
        }
        int colon = term.indexOf(':');
        if (colon > 0) {
            String prefix = term.substring(0, colon);
            String local = term.substring(colon + 1);
            String ns = prefixes.get(prefix);
            if (ns != null) {
                return "<" + ns + local + ">";
            }
        }
        return null; // unknown
    }

    /**
     * Expand an object token — may be an IRI-ref, prefixed name, or string literal
     * (possibly with a {@code ^^datatype}).
     */
    private static String expandObject(String obj, Map<String, String> prefixes) {
        if (obj.isEmpty()) return null;

        if (obj.startsWith("<")) {
            return obj; // already an absolute IRI-ref
        }
        if (obj.startsWith("\"")) {
            // String literal, possibly with ^^<datatype> or ^^prefix:local
            // We normalise ^^xsd:double → ^^<xsd:double-iri>
            int litEnd = findLiteralEnd(obj);
            if (litEnd < 0) return obj; // best-effort
            String lit = obj.substring(0, litEnd + 1);
            String suffix = obj.substring(litEnd + 1).trim();
            if (suffix.startsWith("^^")) {
                String dtToken = suffix.substring(2).trim();
                String dtExpanded = expandTerm(dtToken, prefixes);
                if (dtExpanded != null) {
                    return lit + "^^" + dtExpanded;
                }
                return lit; // drop unknown datatype, keep literal value
            }
            return lit;
        }
        // Try as a prefixed name (for object IRI references)
        String expanded = expandTerm(obj, prefixes);
        return expanded; // may be null → skip
    }

    /** Returns the index of the closing {@code "} of a Turtle quoted string, or {@code -1}. */
    private static int findLiteralEnd(String s) {
        // s starts with "
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String removeDot(String s) {
        if (s.endsWith(".")) return s.substring(0, s.length() - 1).trim();
        return s;
    }

    /** Returns true when the line contains only a subject IRI-ref (nothing after the closing {@code >}). */
    private static boolean endsWithSubjectClose(String line) {
        int close = line.indexOf('>');
        if (close < 0) return false;
        return close == line.length() - 1;
    }

    private static String extractIriRef(String s) {
        if (!s.startsWith("<")) return null;
        int close = s.indexOf('>');
        if (close < 0) return null;
        return s.substring(1, close);
    }
}
