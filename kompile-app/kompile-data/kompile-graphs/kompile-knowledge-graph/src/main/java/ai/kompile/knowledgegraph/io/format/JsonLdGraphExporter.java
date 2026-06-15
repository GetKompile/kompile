/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonLdGraphExporter {

    private final ObjectMapper mapper;

    public JsonLdGraphExporter(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public byte[] toBytes(PortableGraph graph) throws IOException {
        Map<String, Map<String, Object>> nodeIndex = new LinkedHashMap<>();
        for (PortableNode n : graph.nodes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("@id", n.externalId());
            entry.put("@type", n.nodeType());
            entry.put("title", n.title());
            if (n.description() != null) entry.put("description", n.description());
            if (n.metadata() != null) {
                for (Map.Entry<String, Object> m : n.metadata().entrySet()) {
                    entry.putIfAbsent(m.getKey(), m.getValue());
                }
            }
            nodeIndex.put(n.externalId(), entry);
        }
        for (PortableEdge e : graph.edges()) {
            Map<String, Object> from = nodeIndex.computeIfAbsent(e.fromExternalId(), id -> {
                Map<String, Object> stub = new LinkedHashMap<>();
                stub.put("@id", id);
                stub.put("@type", "ENTITY");
                return stub;
            });
            String key = e.edgeType() == null ? "relatedTo" : e.edgeType().toLowerCase();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> existing = (List<Map<String, Object>>) from.get(key);
            if (existing == null) {
                existing = new ArrayList<>();
                from.put(key, existing);
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("@id", e.toExternalId());
            if (e.weight() != null) ref.put("weight", e.weight());
            if (e.description() != null) ref.put("description", e.description());
            existing.add(ref);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@context", Map.of(
                "title", "http://schema.org/name",
                "description", "http://schema.org/description"));
        root.put("@graph", new ArrayList<>(nodeIndex.values()));
        return mapper.writeValueAsBytes(root);
    }
}
