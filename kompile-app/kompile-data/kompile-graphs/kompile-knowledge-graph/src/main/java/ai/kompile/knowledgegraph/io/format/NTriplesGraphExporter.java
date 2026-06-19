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
 * {@code prop/<key>} triple per metadata entry. Per edge: one {@code <from> <rel/TYPE> <to>} triple.
 * Edge attributes (weight/confidence) are intentionally not represented (that needs reification).
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
            triple(sb,
                    RdfSupport.iriRef(RdfSupport.nodeIri(e.fromExternalId())),
                    RdfSupport.iriRef(RdfSupport.relIri(e.edgeType())),
                    RdfSupport.iriRef(RdfSupport.nodeIri(e.toExternalId())));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void triple(StringBuilder sb, String subject, String predicate, String object) {
        sb.append(subject).append(' ').append(predicate).append(' ').append(object).append(" .\n");
    }
}
