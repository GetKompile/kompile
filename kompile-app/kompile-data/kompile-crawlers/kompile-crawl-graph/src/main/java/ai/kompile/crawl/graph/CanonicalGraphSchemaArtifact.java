/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.Objects;

/**
 * Codec for the one crawl-scoped schema artifact carried by a {@code UnifiedGraph} archive.
 *
 * <p>The artifact deliberately contains both the complete canonical schema and its content
 * fingerprint.  The fingerprint is checked on every read, so a stale or hand-edited artifact is
 * never silently reused as the next crawl's schema seed.  Absence is the only legacy-compatible
 * case; once the artifact is present, malformed content is an error.</p>
 */
public final class CanonicalGraphSchemaArtifact {

    /** Stable name inside {@code UnifiedGraph.artifacts()}. */
    public static final String ARTIFACT_NAME = "schema/canonical-graph-schema.json";
    public static final int FORMAT_VERSION = 1;
    private static final ObjectMapper ARTIFACT_MAPPER = JsonUtils.newStandardMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private CanonicalGraphSchemaArtifact() {
    }

    /** A validated, canonical schema plus the fingerprint stored beside it. */
    public record Value(GraphSchema schema, String fingerprint) {
        public Value {
            schema = CrawlOntology.canonicalize(Objects.requireNonNull(schema, "schema"));
            fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            String actual = CrawlOntology.contentFingerprint(schema);
            if (!actual.equals(fingerprint)) {
                throw new IllegalArgumentException(
                        "Canonical graph schema fingerprint does not match schema content");
            }
        }
    }

    /** Encode a canonical schema using the stable artifact mapper. */
    public static byte[] encode(GraphSchema schema) {
        return encode(ARTIFACT_MAPPER, schema);
    }

    /** Encode a canonical schema with a caller-supplied mapper (useful for project-local archives). */
    public static byte[] encode(ObjectMapper mapper, GraphSchema schema) {
        Objects.requireNonNull(mapper, "mapper");
        Value value = new Value(CrawlOntology.canonicalize(schema),
                CrawlOntology.contentFingerprint(schema));
        try {
            ObjectMapper effective = mapper.copy()
                    .setSerializationInclusion(JsonInclude.Include.ALWAYS);
            var root = effective.createObjectNode();
            root.put("formatVersion", FORMAT_VERSION);
            root.set("schema", effective.valueToTree(value.schema()));
            root.put("fingerprint", value.fingerprint());
            return effective.writeValueAsBytes(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode canonical graph schema artifact", failure);
        }
    }

    /** Decode and validate an artifact using the stable artifact mapper. */
    public static Value decode(byte[] bytes) {
        return decode(ARTIFACT_MAPPER, bytes);
    }

    /** Decode and validate an artifact with a caller-supplied mapper. */
    public static Value decode(ObjectMapper mapper, byte[] bytes) {
        Objects.requireNonNull(mapper, "mapper");
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Canonical graph schema artifact is empty");
        }
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Canonical graph schema artifact must be a JSON object");
            }
            JsonNode version = root.get("formatVersion");
            if (version == null || !version.canConvertToInt()
                    || version.asInt() != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unsupported canonical graph schema artifact formatVersion: " + version);
            }
            JsonNode schemaNode = root.get("schema");
            if (schemaNode == null || schemaNode.isNull() || !schemaNode.isObject()) {
                throw new IllegalStateException(
                        "Canonical graph schema artifact must contain a non-null schema object");
            }
            JsonNode fingerprintNode = root.get("fingerprint");
            if (fingerprintNode == null || !fingerprintNode.isTextual()
                    || fingerprintNode.asText().isBlank()) {
                throw new IllegalStateException(
                        "Canonical graph schema artifact is missing its fingerprint");
            }
            GraphSchema schema = mapper.treeToValue(schemaNode, GraphSchema.class);
            GraphSchema canonical = CrawlOntology.canonicalize(schema);
            String actual = CrawlOntology.contentFingerprint(canonical);
            String stored = fingerprintNode.asText().trim();
            if (!actual.equals(stored)) {
                throw new IllegalStateException(
                        "Canonical graph schema artifact fingerprint mismatch: stored="
                                + stored + ", actual=" + actual);
            }
            return new Value(canonical, stored);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "Malformed canonical graph schema artifact: " + failure.getMessage(), failure);
        }
    }
}
