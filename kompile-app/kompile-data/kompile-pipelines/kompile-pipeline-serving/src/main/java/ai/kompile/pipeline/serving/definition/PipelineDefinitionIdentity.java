/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stable identity for compatibility-affecting pipeline definition content. */
public final class PipelineDefinitionIdentity {
    private PipelineDefinitionIdentity() {
    }

    public static String contentDigest(ObjectMapper mapper, UnifiedPipelineDefinition definition) {
        try {
            JsonNode value = mapper.valueToTree(definition);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Pipeline definition must serialize as an object");
            }
            object.remove("contentDigest");
            object.remove("definitionVersion");
            object.remove("lifecycleState");
            object.remove("createdAt");
            object.remove("updatedAt");
            object.remove("createdBy");
            object.remove("updatedBy");
            object.remove("resolvedModels");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] canonical = mapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(object)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to compute pipeline definition digest", e);
        }
    }
}
