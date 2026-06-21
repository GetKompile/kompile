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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-rolled Turtle parser for the narrow OWL subset emitted by {@link OwlTurtleWriter}.
 *
 * <h2>Supported grammar</h2>
 * <ul>
 *   <li>{@code @prefix prefix: <iri> .} declarations.</li>
 *   <li>IRIs in angle-bracket form {@code <…>} and prefixed form {@code prefix:local}.</li>
 *   <li>Simple subject-predicate-object triples ending with {@code .}.</li>
 *   <li>{@code ;} (same subject, new predicate-object) and {@code ,} (same subject+predicate,
 *       new object) separators.</li>
 *   <li>Blank-node restriction blocks {@code [ a owl:Restriction ; … ]} appearing as the
 *       object of {@code rdfs:subClassOf} triples on a class subject.</li>
 *   <li>Typed literals {@code "value"^^xsd:nonNegativeInteger} (integer value extracted).</li>
 *   <li>{@code a} as shorthand for {@code rdf:type}.</li>
 *   <li>Single-line comments (text after {@code #} on a line containing no IRI).</li>
 * </ul>
 *
 * <h2>Scope and limitations</h2>
 * <p>This parser is scoped to exactly what {@link OwlTurtleWriter} produces plus simple
 * well-formed snippets with the same constructs. It does NOT attempt full Turtle grammar
 * compliance. In particular:</p>
 * <ul>
 *   <li>Multi-line string literals and escape sequences beyond {@code \"} are not handled.</li>
 *   <li>Nested blank nodes (blank nodes inside blank nodes) are not handled.</li>
 *   <li>Collection syntax ({@code ( … )}) is not handled.</li>
 *   <li>Base URI ({@code @base}) is not handled.</li>
 * </ul>
 *
 * <h2>Reconstructed model</h2>
 * <ul>
 *   <li>Subjects typed {@code owl:Class} or {@code owl:Ontology} are registered.</li>
 *   <li>Subjects typed {@code owl:ObjectProperty} (+ characteristic types) populate
 *       {@link OwlObjectProperty}s.</li>
 *   <li>Subjects typed {@code owl:DatatypeProperty} (+ {@code owl:FunctionalProperty})
 *       populate {@link OwlDataProperty}s.</li>
 *   <li>{@code rdfs:subClassOf}, {@code owl:equivalentClass}, {@code owl:disjointWith}
 *       predicates on class subjects populate {@link OwlClass} axioms.</li>
 *   <li>{@code rdfs:subClassOf [ a owl:Restriction ; … ]} blank nodes on class subjects
 *       populate {@link OwlRestriction}s on that class.</li>
 *   <li>{@code owl:sameAs} triples populate {@link OwlOntology#sameAs()}.</li>
 * </ul>
 *
 * <p>This class is stateless and thread-safe.</p>
 */
public final class OwlTurtleReader {

    // ─── Well-known predicate IRI strings (resolved after prefix expansion) ───────

    private static final String IRI_RDF_TYPE          = OwlIri.RDF  + "type";
    private static final String IRI_RDFS_SUBCLASSOF   = OwlIri.RDFS + "subClassOf";
    private static final String IRI_RDFS_DOMAIN       = OwlIri.RDFS + "domain";
    private static final String IRI_RDFS_RANGE        = OwlIri.RDFS + "range";
    private static final String IRI_RDFS_SUBPROPOF    = OwlIri.RDFS + "subPropertyOf";
    private static final String IRI_OWL_CLASS         = OwlIri.OWL  + "Class";
    private static final String IRI_OWL_ONTOLOGY      = OwlIri.OWL  + "Ontology";
    private static final String IRI_OWL_OBJPROP       = OwlIri.OWL  + "ObjectProperty";
    private static final String IRI_OWL_DATAPROP      = OwlIri.OWL  + "DatatypeProperty";
    private static final String IRI_OWL_FUNCTIONAL    = OwlIri.OWL  + "FunctionalProperty";
    private static final String IRI_OWL_INV_FUNC      = OwlIri.OWL  + "InverseFunctionalProperty";
    private static final String IRI_OWL_TRANSITIVE    = OwlIri.OWL  + "TransitiveProperty";
    private static final String IRI_OWL_SYMMETRIC     = OwlIri.OWL  + "SymmetricProperty";
    private static final String IRI_OWL_REFLEXIVE     = OwlIri.OWL  + "ReflexiveProperty";
    private static final String IRI_OWL_ASYMMETRIC    = OwlIri.OWL  + "AsymmetricProperty";
    private static final String IRI_OWL_IRREFLEXIVE   = OwlIri.OWL  + "IrreflexiveProperty";
    private static final String IRI_OWL_RESTRICTION   = OwlIri.OWL  + "Restriction";
    private static final String IRI_OWL_ONPROP        = OwlIri.OWL  + "onProperty";
    private static final String IRI_OWL_SOME_VF       = OwlIri.OWL  + "someValuesFrom";
    private static final String IRI_OWL_ALL_VF        = OwlIri.OWL  + "allValuesFrom";
    private static final String IRI_OWL_HAS_VALUE     = OwlIri.OWL  + "hasValue";
    private static final String IRI_OWL_MIN_CARD      = OwlIri.OWL  + "minCardinality";
    private static final String IRI_OWL_MAX_CARD      = OwlIri.OWL  + "maxCardinality";
    private static final String IRI_OWL_EXACT_CARD    = OwlIri.OWL  + "exactCardinality";
    private static final String IRI_OWL_MIN_QCARD     = OwlIri.OWL  + "minQualifiedCardinality";
    private static final String IRI_OWL_MAX_QCARD     = OwlIri.OWL  + "maxQualifiedCardinality";
    private static final String IRI_OWL_EXACT_QCARD   = OwlIri.OWL  + "exactQualifiedCardinality";
    private static final String IRI_OWL_ON_CLASS      = OwlIri.OWL  + "onClass";
    private static final String IRI_OWL_EQUIV_CLASS   = OwlIri.OWL  + "equivalentClass";
    private static final String IRI_OWL_DISJOINT_WITH = OwlIri.OWL  + "disjointWith";
    private static final String IRI_OWL_INVERSE_OF    = OwlIri.OWL  + "inverseOf";
    private static final String IRI_OWL_SAME_AS       = OwlIri.OWL  + "sameAs";
    private static final String IRI_OWL_EQUIV_PROP    = OwlIri.OWL  + "equivalentProperty";

    // ─── Public API ───────────────────────────────────────────────────────────────

    /**
     * Parse a Turtle document into an {@link OwlOntology}.
     *
     * @param turtle the Turtle text to parse (never {@code null})
     * @return an {@link OwlOntology} reconstructed from the triples in the document
     * @throws IllegalArgumentException if the document contains a syntax error this parser
     *         cannot recover from (e.g. an unclosed angle-bracket IRI)
     */
    public OwlOntology read(String turtle) {
        Objects.requireNonNull(turtle, "turtle");

        ParseState state = new ParseState();
        tokenize(turtle, state);
        return buildOntology(state);
    }

    // ─── Tokenizer / triple collector ─────────────────────────────────────────────

    /**
     * Tokenize the input into a flat list of {@link Triple}s (and collect prefix declarations)
     * by walking the character stream character-by-character.
     *
     * <p>The key complexity is the blank-node block {@code [ … ]}; we mint a synthetic
     * identifier for each blank node and collect the triples inside it with that identifier
     * as both subject (inner triples) and object (the outer {@code rdfs:subClassOf} triple).</p>
     */
    private void tokenize(String text, ParseState state) {
        // We work on a per-statement level: extract each "statement" (terminated by '.')
        // that is NOT inside a blank-node block. Blank-node blocks are parsed inline when
        // the '[' is encountered.

        int pos = 0;
        int len = text.length();
        int bnCounter = 0;

        while (pos < len) {
            pos = skipWs(text, pos, len);
            if (pos >= len) break;

            // ── Prefix declaration: @prefix prefix: <iri> . ─────────────────────
            if (text.startsWith("@prefix", pos)) {
                // The statement terminator '.' must be found AFTER the IRI's closing '>',
                // since the IRI itself (e.g. http://www.w3.org/...) contains dots.
                int gt  = text.indexOf('>', pos + 7);
                int end = text.indexOf('.', gt >= 0 ? gt + 1 : pos + 7);
                if (end < 0) end = len;
                String decl = text.substring(pos + 7, end).trim();
                int colon = decl.indexOf(':');
                if (colon >= 0) {
                    String prefix = decl.substring(0, colon).trim();
                    String rest   = decl.substring(colon + 1).trim();
                    if (rest.startsWith("<") && rest.endsWith(">")) {
                        state.prefixes.put(prefix, rest.substring(1, rest.length() - 1));
                    }
                }
                pos = end + 1;
                continue;
            }

            // ── Skip standalone comment lines ────────────────────────────────────
            if (text.charAt(pos) == '#') {
                pos = skipLine(text, pos, len);
                continue;
            }

            // ── Read a subject ───────────────────────────────────────────────────
            if (pos >= len) break;
            char ch = text.charAt(pos);
            if (ch == '.') { pos++; continue; }  // stray dot
            if (ch == '@') { // might be @prefix we missed — skip to next dot
                int end = text.indexOf('.', pos);
                pos = (end < 0) ? len : end + 1;
                continue;
            }

            // Read subject token
            TokenResult subjectTok = readToken(text, pos, len, state.prefixes, bnCounter);
            if (subjectTok == null) { pos++; continue; }
            String subject = subjectTok.value;
            pos = subjectTok.nextPos;

            if (subject.isEmpty()) { pos++; continue; }

            // ── Read predicate-object pairs until '.' (top level) ────────────────
            while (pos < len) {
                pos = skipWs(text, pos, len);
                if (pos >= len) break;
                ch = text.charAt(pos);
                if (ch == '.' || ch == '}') { pos++; break; }

                // Read predicate
                TokenResult predTok = readToken(text, pos, len, state.prefixes, bnCounter);
                if (predTok == null) { pos++; continue; }
                String predicate = predTok.value;
                pos = predTok.nextPos;

                // ── Read object(s) separated by ',' ──────────────────────────────
                boolean moreObjects = true;
                while (moreObjects && pos < len) {
                    pos = skipWs(text, pos, len);
                    if (pos >= len) break;
                    ch = text.charAt(pos);
                    if (ch == '.' || ch == '}' || ch == ';') {
                        moreObjects = false;
                        break;
                    }

                    // The object may be a blank-node block [ … ]
                    if (ch == '[') {
                        // Parse the blank-node block inline
                        bnCounter++;
                        String bnId = "_:bn" + bnCounter;
                        int[] newPos = new int[1];
                        newPos[0] = pos + 1; // skip '['
                        parseBlankNodeBlock(text, newPos, len, bnId, state, bnCounter);
                        pos = newPos[0];
                        // Emit the outer triple: subject predicate _:bnN
                        state.triples.add(new Triple(subject, predicate, bnId));
                    } else {
                        TokenResult objTok = readToken(text, pos, len, state.prefixes, bnCounter);
                        if (objTok == null) { pos++; moreObjects = false; continue; }
                        String object = objTok.value;
                        pos = objTok.nextPos;
                        state.triples.add(new Triple(subject, predicate, object));
                    }

                    // After an object, expect ',' (more objects), ';' (new pred), or '.'
                    pos = skipWs(text, pos, len);
                    if (pos < len) {
                        ch = text.charAt(pos);
                        if (ch == ',') {
                            pos++;            // consume ',', loop for next object
                        } else {
                            moreObjects = false;  // done with this predicate's objects
                        }
                    }
                }

                // After all objects, expect ';' (next predicate) or '.' (end of subject)
                pos = skipWs(text, pos, len);
                if (pos < len) {
                    ch = text.charAt(pos);
                    if (ch == ';') {
                        pos++; // consume ';' and continue the predicate loop
                    } else if (ch == '.') {
                        pos++; // consume '.', end of this subject block
                        break;
                    }
                    // '.' already consumed inside the object loop — just let the
                    // outer while re-check
                }
            }
        }
    }

    /**
     * Parse the content of a blank-node block {@code [ … ]} and emit triples with
     * {@code bnId} as the subject.  Updates {@code posRef[0]} to point past the closing
     * {@code ]}.
     */
    private void parseBlankNodeBlock(String text, int[] posRef, int len,
            String bnId, ParseState state, int bnCounter) {
        int pos = posRef[0];

        while (pos < len) {
            pos = skipWs(text, pos, len);
            if (pos >= len) break;
            char ch = text.charAt(pos);
            if (ch == ']') { pos++; break; }
            if (ch == '.') { pos++; continue; }

            // Read predicate
            TokenResult predTok = readToken(text, pos, len, state.prefixes, bnCounter);
            if (predTok == null) { pos++; continue; }
            String predicate = predTok.value;
            pos = predTok.nextPos;

            // Read object(s) separated by ','
            boolean moreObjects = true;
            while (moreObjects && pos < len) {
                pos = skipWs(text, pos, len);
                if (pos >= len) break;
                ch = text.charAt(pos);
                if (ch == ']' || ch == ';') { moreObjects = false; break; }

                TokenResult objTok = readToken(text, pos, len, state.prefixes, bnCounter);
                if (objTok == null) { pos++; moreObjects = false; continue; }
                String object = objTok.value;
                pos = objTok.nextPos;
                state.triples.add(new Triple(bnId, predicate, object));

                pos = skipWs(text, pos, len);
                if (pos < len && text.charAt(pos) == ',') {
                    pos++;
                } else {
                    moreObjects = false;
                }
            }

            pos = skipWs(text, pos, len);
            if (pos < len && text.charAt(pos) == ';') {
                pos++;
            }
        }

        posRef[0] = pos;
    }

    /**
     * Read the next token from {@code text} starting at {@code pos}.
     *
     * <p>Token types recognised:</p>
     * <ul>
     *   <li>{@code <iri>} — returns the IRI string (without angle brackets).</li>
     *   <li>{@code prefix:local} — expanded to its full IRI using the prefix map.</li>
     *   <li>{@code "literal"^^type} — returns the value between the quotes only
     *       (datatype annotation is consumed but discarded except for integer literals
     *       where the raw text {@code "N"^^xsd:nonNegativeInteger} is returned as-is so
     *       callers can extract {@code N} via {@link #extractIntLiteral}).</li>
     *   <li>{@code a} — returns the full IRI for {@code rdf:type}.</li>
     *   <li>{@code [} / {@code ]} / {@code ;} / {@code ,} / {@code .} — returns
     *       the single character as a string (callers check for these directly in the
     *       main loop so the method should not be called when {@code pos} is at one;
     *       handled defensively).</li>
     * </ul>
     *
     * @return a {@link TokenResult} or {@code null} if the position points to a
     *         terminator ({@code ] ; , .}) or end-of-input.
     */
    private TokenResult readToken(String text, int pos, int len,
            Map<String, String> prefixes, int bnCounter) {
        pos = skipWs(text, pos, len);
        if (pos >= len) return null;

        char ch = text.charAt(pos);

        // Terminators — caller handles these
        if (ch == '.' || ch == ';' || ch == ',' || ch == ']' || ch == '[' || ch == '}')
            return null;

        // IRI in angle brackets
        if (ch == '<') {
            int end = text.indexOf('>', pos + 1);
            if (end < 0) throw new IllegalArgumentException(
                    "Unclosed angle-bracket IRI near position " + pos);
            String iri = text.substring(pos + 1, end);
            return new TokenResult(iri, end + 1);
        }

        // String literal
        if (ch == '"') {
            // find the closing quote — handle \" escapes minimally
            int i = pos + 1;
            while (i < len) {
                char c = text.charAt(i);
                if (c == '\\') { i += 2; continue; }
                if (c == '"') break;
                i++;
            }
            String value = text.substring(pos + 1, i);
            int nextPos = i + 1; // past closing '"'
            // Check for ^^type annotation
            String raw;
            if (nextPos < len - 1 && text.charAt(nextPos) == '^' && text.charAt(nextPos + 1) == '^') {
                // Read the datatype token so we can return the whole literal text to callers
                // that need the integer value
                int typeStart = nextPos + 2;
                TokenResult dtTok = readToken(text, typeStart, len, prefixes, bnCounter);
                if (dtTok != null) {
                    // Return the whole literal including the datatype annotation
                    raw = "\"" + value + "\"^^" + dtTok.value;
                    return new TokenResult(raw, dtTok.nextPos);
                }
            }
            raw = "\"" + value + "\"";
            return new TokenResult(raw, nextPos);
        }

        // Plain word (could be "a", a prefixed name like "owl:Class", or a blank node "_:bn1")
        int start = pos;
        // Read until a whitespace or Turtle delimiter
        while (pos < len) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c) || c == '.' || c == ';' || c == ','
                    || c == '[' || c == ']' || c == '{' || c == '}' || c == '#')
                break;
            pos++;
        }
        String word = text.substring(start, pos);
        if (word.isEmpty()) return null;

        // Blank nodes pass through as-is
        if (word.startsWith("_:")) {
            return new TokenResult(word, pos);
        }

        // Keyword "a" → rdf:type
        if ("a".equals(word)) {
            return new TokenResult(IRI_RDF_TYPE, pos);
        }

        // Prefixed name: "prefix:local"
        int colon = word.indexOf(':');
        if (colon >= 0) {
            String prefix    = word.substring(0, colon);
            String localPart = word.substring(colon + 1);
            String ns = prefixes.get(prefix);
            if (ns != null) {
                return new TokenResult(ns + localPart, pos);
            }
            // Unknown prefix — return as-is
            return new TokenResult(word, pos);
        }

        // Bare word — return as-is
        return new TokenResult(word, pos);
    }

    // ─── Ontology builder — interpret the collected triples ──────────────────────

    /**
     * Walk the collected triples and build the {@link OwlOntology}.
     */
    private OwlOntology buildOntology(ParseState state) {
        // ── Pass 1: determine the ontology IRI and class of each subject ──────────
        String ontologyIri = null;
        // Map: subject IRI → set of rdf:type IRIs
        Map<String, List<String>> typeMap = new LinkedHashMap<>();
        for (Triple t : state.triples) {
            if (IRI_RDF_TYPE.equals(t.predicate)) {
                typeMap.computeIfAbsent(t.subject, k -> new ArrayList<>()).add(t.object);
                if (IRI_OWL_ONTOLOGY.equals(t.object)) {
                    ontologyIri = t.subject;
                }
            }
        }

        // ── Pass 2: build OwlClass builders, OwlObjectProperty builders, etc. ────
        Map<String, OwlClass.Builder>          classBuilders = new LinkedHashMap<>();
        Map<String, OwlObjectProperty.Builder> opBuilders    = new LinkedHashMap<>();
        Map<String, OwlDataProperty.Builder>   dpBuilders    = new LinkedHashMap<>();
        // Per blank-node subject: accumulate restriction slots
        Map<String, RestrictionAccumulator>    bnAccum       = new LinkedHashMap<>();

        OwlOntology.Builder ontBuilder = OwlOntology.of(ontologyIri);

        for (Triple t : state.triples) {
            String s = t.subject;
            String p = t.predicate;
            String o = t.object;

            // ── Determine subject role from type triples ──────────────────────────
            if (s.startsWith("_:")) {
                // Blank node — accumulate restriction components
                bnAccum.computeIfAbsent(s, k -> new RestrictionAccumulator());
                RestrictionAccumulator acc = bnAccum.get(s);
                if (IRI_RDF_TYPE.equals(p)) {
                    acc.type = o;
                } else if (IRI_OWL_ONPROP.equals(p)) {
                    acc.onPropertyIri = o;
                } else if (IRI_OWL_SOME_VF.equals(p)) {
                    acc.variant = "someValuesFrom";
                    acc.fillerClassIri = o;
                } else if (IRI_OWL_ALL_VF.equals(p)) {
                    acc.variant = "allValuesFrom";
                    acc.fillerClassIri = o;
                } else if (IRI_OWL_HAS_VALUE.equals(p)) {
                    acc.variant = "hasValue";
                    acc.individualId = o;
                } else if (IRI_OWL_MIN_CARD.equals(p)) {
                    acc.variant = "minCardinality";
                    acc.n = extractIntLiteral(o);
                } else if (IRI_OWL_MAX_CARD.equals(p)) {
                    acc.variant = "maxCardinality";
                    acc.n = extractIntLiteral(o);
                } else if (IRI_OWL_EXACT_CARD.equals(p)) {
                    acc.variant = "exactCardinality";
                    acc.n = extractIntLiteral(o);
                } else if (IRI_OWL_MIN_QCARD.equals(p)) {
                    acc.variant = "minQualifiedCardinality";
                    acc.n = extractIntLiteral(o);
                } else if (IRI_OWL_MAX_QCARD.equals(p)) {
                    acc.variant = "maxQualifiedCardinality";
                    acc.n = extractIntLiteral(o);
                } else if (IRI_OWL_EXACT_QCARD.equals(p)) {
                    acc.variant = "exactQualifiedCardinality";
                    acc.n = extractIntLiteral(o);
                } else if (IRI_OWL_ON_CLASS.equals(p)) {
                    acc.qualifiedOnClassIri = o;
                }
                continue;
            }

            // ── Named subject: determine its role ─────────────────────────────────
            boolean isClass     = isType(typeMap, s, IRI_OWL_CLASS);
            boolean isObjProp   = isType(typeMap, s, IRI_OWL_OBJPROP);
            boolean isDataProp  = isType(typeMap, s, IRI_OWL_DATAPROP);
            // Ontology-level triples are skipped
            if (IRI_OWL_ONTOLOGY.equals(s) || (ontologyIri != null && ontologyIri.equals(s))) {
                continue;
            }

            if (IRI_RDF_TYPE.equals(p)) {
                // Ensure builders are created for classes and properties
                if (IRI_OWL_CLASS.equals(o)) {
                    classBuilders.computeIfAbsent(s, OwlClass::of);
                } else if (IRI_OWL_OBJPROP.equals(o)) {
                    opBuilders.computeIfAbsent(s, OwlObjectProperty::of);
                } else if (IRI_OWL_DATAPROP.equals(o)) {
                    dpBuilders.computeIfAbsent(s, OwlDataProperty::of);
                } else if (isObjProp || opBuilders.containsKey(s)) {
                    // Additional type for an object property (characteristic)
                    OwlObjectProperty.Builder opb = opBuilders.computeIfAbsent(s, OwlObjectProperty::of);
                    applyCharacteristic(opb, o);
                } else if (isDataProp || dpBuilders.containsKey(s)) {
                    OwlDataProperty.Builder dpb = dpBuilders.computeIfAbsent(s, OwlDataProperty::of);
                    if (IRI_OWL_FUNCTIONAL.equals(o)) dpb.functional(true);
                }
            } else if (isClass || classBuilders.containsKey(s)) {
                OwlClass.Builder cb = classBuilders.computeIfAbsent(s, OwlClass::of);
                if (IRI_RDFS_SUBCLASSOF.equals(p)) {
                    if (o.startsWith("_:")) {
                        // Blank-node restriction — recorded via bnAccum; link it here
                        bnAccum.computeIfAbsent(o, k -> new RestrictionAccumulator())
                               .ownerClassIri = s;
                    } else {
                        cb.subClassOf(o);
                    }
                } else if (IRI_OWL_EQUIV_CLASS.equals(p)) {
                    cb.equivalentClass(o);
                } else if (IRI_OWL_DISJOINT_WITH.equals(p)) {
                    cb.disjointWith(o);
                }
            } else if (isObjProp || opBuilders.containsKey(s)) {
                OwlObjectProperty.Builder opb = opBuilders.computeIfAbsent(s, OwlObjectProperty::of);
                if (IRI_RDFS_DOMAIN.equals(p)) {
                    opb.domain(o);
                } else if (IRI_RDFS_RANGE.equals(p)) {
                    opb.range(o);
                } else if (IRI_OWL_INVERSE_OF.equals(p)) {
                    opb.inverseOf(o);
                } else if (IRI_RDFS_SUBPROPOF.equals(p)) {
                    opb.subPropertyOf(o);
                } else if (IRI_OWL_EQUIV_PROP.equals(p)) {
                    opb.equivalentProperty(o);
                }
            } else if (isDataProp || dpBuilders.containsKey(s)) {
                OwlDataProperty.Builder dpb = dpBuilders.computeIfAbsent(s, OwlDataProperty::of);
                if (IRI_RDFS_DOMAIN.equals(p)) {
                    dpb.domain(o);
                } else if (IRI_RDFS_RANGE.equals(p)) {
                    // "o" may be a prefixed name that was already expanded ("xsd:string" → full IRI)
                    dpb.range(o);
                } else if (IRI_RDFS_SUBPROPOF.equals(p)) {
                    dpb.subPropertyOf(o);
                } else if (IRI_OWL_EQUIV_PROP.equals(p)) {
                    dpb.equivalentProperty(o);
                }
            } else if (IRI_OWL_SAME_AS.equals(p)) {
                ontBuilder.sameAs(s, o);
            }
        }

        // ── Pass 3: attach blank-node restrictions to their owner classes ─────────
        for (Map.Entry<String, RestrictionAccumulator> e : bnAccum.entrySet()) {
            RestrictionAccumulator acc = e.getValue();
            if (acc.ownerClassIri == null || !IRI_OWL_RESTRICTION.equals(acc.type)) continue;
            OwlRestriction restriction = acc.toRestriction();
            if (restriction == null) continue;
            OwlClass.Builder cb = classBuilders.get(acc.ownerClassIri);
            if (cb != null) cb.restriction(restriction);
        }

        // ── Pass 4: assemble the ontology ─────────────────────────────────────────
        for (OwlClass.Builder cb : classBuilders.values()) {
            ontBuilder.addClass(cb.build());
        }
        for (OwlObjectProperty.Builder opb : opBuilders.values()) {
            ontBuilder.addObjectProperty(opb.build());
        }
        for (OwlDataProperty.Builder dpb : dpBuilders.values()) {
            ontBuilder.addDataProperty(dpb.build());
        }

        return ontBuilder.build();
    }

    // ─── Characteristic flag helper ───────────────────────────────────────────────

    private static void applyCharacteristic(OwlObjectProperty.Builder opb, String typeIri) {
        if (IRI_OWL_FUNCTIONAL.equals(typeIri))    { opb.functional(true);        return; }
        if (IRI_OWL_INV_FUNC.equals(typeIri))      { opb.inverseFunctional(true); return; }
        if (IRI_OWL_TRANSITIVE.equals(typeIri))    { opb.transitive(true);        return; }
        if (IRI_OWL_SYMMETRIC.equals(typeIri))     { opb.symmetric(true);         return; }
        if (IRI_OWL_REFLEXIVE.equals(typeIri))     { opb.reflexive(true);         return; }
        if (IRI_OWL_ASYMMETRIC.equals(typeIri))    { opb.asymmetric(true);        return; }
        if (IRI_OWL_IRREFLEXIVE.equals(typeIri))   { opb.irreflexive(true);       return; }
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────────

    private static boolean isType(Map<String, List<String>> typeMap, String subject, String typeIri) {
        List<String> types = typeMap.get(subject);
        return types != null && types.contains(typeIri);
    }

    private static int skipWs(String text, int pos, int len) {
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        // skip inline comments that are not inside an IRI
        if (pos < len && text.charAt(pos) == '#') {
            return skipLine(text, pos, len);
        }
        return pos;
    }

    private static int skipLine(String text, int pos, int len) {
        while (pos < len && text.charAt(pos) != '\n') pos++;
        return pos < len ? pos + 1 : pos;
    }

    /**
     * Extract the integer value from a typed literal such as
     * {@code "5"^^xsd:nonNegativeInteger} or the bare string {@code "5"}.
     */
    private static int extractIntLiteral(String token) {
        // Strip enclosing quotes: "N" or "N"^^...
        if (token.startsWith("\"")) {
            int end = token.indexOf('"', 1);
            if (end > 0) {
                try {
                    return Integer.parseInt(token.substring(1, end));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─── Internal data structures ─────────────────────────────────────────────────

    /** Accumulates the triple stream and prefix map during tokenization. */
    private static final class ParseState {
        final Map<String, String> prefixes = new LinkedHashMap<>();
        final List<Triple>        triples  = new ArrayList<>();
    }

    /** A single RDF triple. */
    private record Triple(String subject, String predicate, String object) {}

    /** Return value of {@link #readToken}: the token string and the position after it. */
    private record TokenResult(String value, int nextPos) {}

    /** Accumulates the components of an {@code owl:Restriction} blank node. */
    private static final class RestrictionAccumulator {
        String type;          // should be owl:Restriction
        String ownerClassIri; // set when an rdfs:subClassOf _:bnN triple links to us
        String onPropertyIri;
        String variant;       // someValuesFrom/allValuesFrom/hasValue/minCardinality/…
        String fillerClassIri;
        String individualId;
        int    n;
        String qualifiedOnClassIri;

        /** Build the {@link OwlRestriction} from accumulated data, or {@code null} if incomplete. */
        OwlRestriction toRestriction() {
            if (onPropertyIri == null || variant == null) return null;
            return switch (variant) {
                case "someValuesFrom" -> (fillerClassIri != null)
                        ? new OwlRestriction.SomeValuesFrom(onPropertyIri, fillerClassIri) : null;
                case "allValuesFrom" -> (fillerClassIri != null)
                        ? new OwlRestriction.AllValuesFrom(onPropertyIri, fillerClassIri) : null;
                case "hasValue" -> (individualId != null)
                        ? new OwlRestriction.HasValue(onPropertyIri, individualId) : null;
                case "minCardinality", "minQualifiedCardinality" ->
                        new OwlRestriction.MinCardinality(onPropertyIri, n, qualifiedOnClassIri);
                case "maxCardinality", "maxQualifiedCardinality" ->
                        new OwlRestriction.MaxCardinality(onPropertyIri, n, qualifiedOnClassIri);
                case "exactCardinality", "exactQualifiedCardinality" ->
                        new OwlRestriction.ExactCardinality(onPropertyIri, n, qualifiedOnClassIri);
                default -> null;
            };
        }
    }
}
