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

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Exports a {@link PortableGraph} as <a href="https://www.w3.org/TR/n-triples/">N-Triples</a> — real
 * RDF with minted, absolute IRIs (one triple per line), unlike the syntactic-only JSON-LD exporter.
 *
 * <p>Per node: {@code rdf:type} → class IRI, {@code rdfs:label} ← title, {@code rdfs:comment} ←
 * description, a {@code prop/confidence} typed double, {@code prop/occurredAt}, and one
 * {@code prop/<key>} triple per metadata entry.</p>
 *
 * <p>Per edge: one {@code <from> <rel/TYPE> <to>} triple. When the edge carries a {@code weight}
 * and/or {@code confidence} value, a standard RDF reification block is appended: an
 * {@code rdf:Statement} node (IRI form {@code https://kompile.ai/kg/stmt/<from>/<type>/<to>})
 * whose {@code rdf:subject/predicate/object} point at the original triple, annotated with
 * kompile-namespaced predicates {@code https://kompile.ai/kg/weight} and
 * {@code https://kompile.ai/kg/confidence}, each a typed {@code xsd:double} literal.</p>
 */
public final class NTriplesGraphExporter {

    public byte[] toBytes(PortableGraph graph) {
        StringBuilder sb = new StringBuilder(256);
        for (PortableNode n : graph.nodes()) {
            String subject = RdfSupport.iriRef(RdfSupport.nodeIri(n.externalId()));
            triple(sb, subject, RdfSupport.iriRef(RdfSupport.RDF_TYPE),
                    RdfSupport.iriRef(RdfSupport.classIri(n.nodeType())));
            if (n.title() != null) {
                triple(sb, subject, RdfSupport.iriRef(RdfSupport.RDFS_LABEL), RdfSupport.literal(n.title()));
            }
            if (n.description() != null) {
                triple(sb, subject, RdfSupport.iriRef(RdfSupport.RDFS_COMMENT), RdfSupport.literal(n.description()));
            }
            if (n.confidence() != null) {
                triple(sb, subject, RdfSupport.iriRef(RdfSupport.propIri("confidence")),
                        RdfSupport.typedLiteral(n.confidence().toString(), RdfSupport.XSD_DOUBLE));
            }
            if (n.occurredAt() != null) {
                triple(sb, subject, RdfSupport.iriRef(RdfSupport.propIri("occurredAt")),
                        RdfSupport.literal(n.occurredAt()));
            }
            if (n.metadata() != null) {
                for (Map.Entry<String, Object> m : n.metadata().entrySet()) {
                    if (m.getValue() != null) {
                        triple(sb, subject, RdfSupport.iriRef(RdfSupport.propIri(m.getKey())),
                                RdfSupport.literal(String.valueOf(m.getValue())));
                    }
                }
            }
        }
        for (PortableEdge e : graph.edges()) {
            String fromRef = RdfSupport.iriRef(RdfSupport.nodeIri(e.fromExternalId()));
            String predRef = RdfSupport.iriRef(RdfSupport.relIri(e.edgeType()));
            String toRef   = RdfSupport.iriRef(RdfSupport.nodeIri(e.toExternalId()));
            triple(sb, fromRef, predRef, toRef);

            // RDF reification for edge weight / confidence (only when present)
            if (e.weight() != null || e.confidence() != null) {
                String stmt = RdfSupport.iriRef(
                        RdfSupport.stmtIri(e.fromExternalId(), e.edgeType(), e.toExternalId()));
                triple(sb, stmt, RdfSupport.iriRef(RdfSupport.RDF_TYPE),
                        RdfSupport.iriRef(RdfSupport.RDF_STATEMENT));
                triple(sb, stmt, RdfSupport.iriRef(RdfSupport.RDF_SUBJECT),   fromRef);
                triple(sb, stmt, RdfSupport.iriRef(RdfSupport.RDF_PREDICATE), predRef);
                triple(sb, stmt, RdfSupport.iriRef(RdfSupport.RDF_OBJECT),    toRef);
                if (e.weight() != null) {
                    triple(sb, stmt, RdfSupport.iriRef(RdfSupport.WEIGHT_IRI),
                            RdfSupport.typedLiteral(e.weight().toString(), RdfSupport.XSD_DOUBLE));
                }
                if (e.confidence() != null) {
                    triple(sb, stmt, RdfSupport.iriRef(RdfSupport.CONFIDENCE_IRI),
                            RdfSupport.typedLiteral(e.confidence().toString(), RdfSupport.XSD_DOUBLE));
                }
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void triple(StringBuilder sb, String subject, String predicate, String object) {
        sb.append(subject).append(' ').append(predicate).append(' ').append(object).append(" .\n");
    }
}
