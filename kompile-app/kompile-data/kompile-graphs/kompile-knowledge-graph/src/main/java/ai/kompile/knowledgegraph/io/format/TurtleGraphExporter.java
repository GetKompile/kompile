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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a {@link PortableGraph} as <a href="https://www.w3.org/TR/turtle/">Turtle</a> — the same
 * real RDF as {@link NTriplesGraphExporter} (minted absolute IRIs), but grouped by subject with
 * {@code ;} and using the {@code a}/{@code rdfs:}/{@code xsd:} shorthands for readability.
 */
public final class TurtleGraphExporter {

    public byte[] toBytes(PortableGraph graph) {
        // subject IRI (full <...>) -> its predicate-object phrases, in stable insertion order.
        Map<String, List<String>> bySubject = new LinkedHashMap<>();

        for (PortableNode n : graph.nodes()) {
            String subject = RdfSupport.iriRef(RdfSupport.nodeIri(n.externalId()));
            List<String> po = bySubject.computeIfAbsent(subject, k -> new ArrayList<>());
            po.add("a " + RdfSupport.iriRef(RdfSupport.classIri(n.nodeType())));
            if (n.title() != null) {
                po.add("rdfs:label " + RdfSupport.literal(n.title()));
            }
            if (n.description() != null) {
                po.add("rdfs:comment " + RdfSupport.literal(n.description()));
            }
            if (n.confidence() != null) {
                po.add(RdfSupport.iriRef(RdfSupport.propIri("confidence")) + " "
                        + RdfSupport.literal(n.confidence().toString()) + "^^xsd:double");
            }
            if (n.occurredAt() != null) {
                po.add(RdfSupport.iriRef(RdfSupport.propIri("occurredAt")) + " " + RdfSupport.literal(n.occurredAt()));
            }
            if (n.metadata() != null) {
                for (Map.Entry<String, Object> m : n.metadata().entrySet()) {
                    if (m.getValue() != null) {
                        po.add(RdfSupport.iriRef(RdfSupport.propIri(m.getKey())) + " "
                                + RdfSupport.literal(String.valueOf(m.getValue())));
                    }
                }
            }
        }
        for (PortableEdge e : graph.edges()) {
            String subject = RdfSupport.iriRef(RdfSupport.nodeIri(e.fromExternalId()));
            bySubject.computeIfAbsent(subject, k -> new ArrayList<>())
                    .add(RdfSupport.iriRef(RdfSupport.relIri(e.edgeType())) + " "
                            + RdfSupport.iriRef(RdfSupport.nodeIri(e.toExternalId())));
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("@prefix rdfs: <").append(RdfSupport.RDFS).append("> .\n");
        sb.append("@prefix xsd: <").append(RdfSupport.XSD).append("> .\n\n");
        for (Map.Entry<String, List<String>> entry : bySubject.entrySet()) {
            sb.append(entry.getKey()).append('\n');
            List<String> po = entry.getValue();
            for (int i = 0; i < po.size(); i++) {
                sb.append("    ").append(po.get(i)).append(i == po.size() - 1 ? " .\n" : " ;\n");
            }
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
