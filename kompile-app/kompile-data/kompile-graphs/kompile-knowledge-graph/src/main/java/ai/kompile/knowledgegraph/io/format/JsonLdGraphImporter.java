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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON-LD importer. Reads {@code @graph} array; treats each element as a node.
 * Inline references to other {@code @id}s become edges. Reserved keys
 * {@code @id}, {@code @type}, {@code @context}, {@code title}, {@code description}
 * are mapped to node fields; everything else either becomes metadata (scalar) or
 * an edge (object/array containing an {@code @id}).
 */
public final class JsonLdGraphImporter {

    private final ObjectMapper mapper;

    public JsonLdGraphImporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public PortableGraph parse(byte[] bytes) throws IOException {
        JsonNode root = mapper.readTree(bytes);
        JsonNode graph = root.has("@graph") ? root.get("@graph") : root;
        if (!graph.isArray()) {
            throw new IOException("JSON-LD payload missing @graph array");
        }
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        for (JsonNode entry : graph) {
            String id = textOrNull(entry, "@id");
            if (id == null) continue;
            String type = textOrNull(entry, "@type");
            String title = textOrNull(entry, "title");
            if (title == null) title = textOrNull(entry, "name");
            if (title == null) title = id;
            String description = textOrNull(entry, "description");

            Map<String, Object> meta = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = e.getKey();
                JsonNode v = e.getValue();
                if (key.startsWith("@") || key.equals("title") || key.equals("name") || key.equals("description")) continue;
                if (v.isValueNode()) {
                    meta.put(key, v.asText());
                } else if (v.isObject() && v.has("@id")) {
                    edges.add(new PortableEdge(id, v.get("@id").asText(), key.toUpperCase(), null, null));
                } else if (v.isArray()) {
                    for (JsonNode item : v) {
                        if (item.isObject() && item.has("@id")) {
                            edges.add(new PortableEdge(id, item.get("@id").asText(), key.toUpperCase(), null, null));
                        }
                    }
                }
            }
            nodes.add(new PortableNode(id, title, description,
                    type == null ? "ENTITY" : type.toUpperCase(),
                    meta.isEmpty() ? null : meta));
        }
        return new PortableGraph(nodes, edges);
    }

    private static String textOrNull(JsonNode entry, String field) {
        JsonNode v = entry.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
