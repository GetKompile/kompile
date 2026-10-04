/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reflection-free portable snapshot of consensus <em>training targets</em>, not an inference
 * program, posterior store, or verified fact store. Target keys are opaque literal strings:
 * neither this codec nor its snapshot splits, normalizes, or remaps them. In particular a
 * relational key may contain commas, parentheses, quotes, or Unicode in its entity identifiers.
 * The separate alias map records the producing PSL builder's synthetic constant-to-entity IDs.
 *
 * <p>The v1 object has exactly {@code format}, integer {@code version}, {@code semantics},
 * {@code targets} (an array of objects with exactly string {@code key} and numeric {@code value}),
 * and {@code entityAliases} (an object mapping unique synthetic nX constants to unique entity IDs).
 * Consumers must not interpret successful decoding as inference or verification. The saved alias
 * map describes the training pass, even if subsequent topology changes assign different nX names.</p>
 */
public final class ConsensusTargetsArtifactCodec {
    public static final String ARTIFACT_NAME = "reasoning/consensus-targets.v1.json";
    public static final String FORMAT = "kompile-consensus-targets";
    public static final int VERSION = 1;
    public static final String SEMANTICS = "training-targets";
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TARGETS = 100_000;
    public static final int MAX_ALIASES = 100_000;
    public static final int MAX_TEXT_BYTES = 16 * 1024;
    public static final int MAX_JSON_DEPTH = 3;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "format", "version", "semantics", "targets", "entityAliases");
    private static final Set<String> TARGET_FIELDS = Set.of("key", "value");
    private static final Pattern ALIAS = Pattern.compile("n(?:0|[1-9][0-9]*)");

    private ConsensusTargetsArtifactCodec() { }

    /** Detached immutable maps; numeric values are finite soft-truth training targets in [0,1]. */
    public record Snapshot(Map<String, Double> targets, Map<String, String> entityAliases) {
        public Snapshot {
            if (targets == null || entityAliases == null
                    || targets.size() > MAX_TARGETS || entityAliases.size() > MAX_ALIASES) {
                throw invalid("Missing or oversized snapshot maps");
            }
            Map<String, Double> targetCopy = new LinkedHashMap<>();
            targets.forEach((key, value) -> targetCopy.put(text(key, "key"), truth(value)));
            Map<String, String> aliasCopy = new LinkedHashMap<>();
            Set<String> entityIds = new HashSet<>();
            entityAliases.forEach((alias, entityId) -> {
                String checkedAlias = text(alias, "alias");
                String checkedId = text(entityId, "entityId");
                if (!ALIAS.matcher(checkedAlias).matches() || !entityIds.add(checkedId)) {
                    throw invalid("Entity aliases must be unique nX-to-entity mappings");
                }
                aliasCopy.put(checkedAlias, checkedId);
            });
            targets = Collections.unmodifiableMap(targetCopy);
            entityAliases = Collections.unmodifiableMap(aliasCopy);
        }
    }

    public static String encode(Map<String, Double> targets, Map<String, String> entityAliases) {
        return encode(new Snapshot(targets, entityAliases));
    }

    /** Emit explicit target rows and a separate alias map, with no Java object serialization. */
    public static String encode(Snapshot snapshot) {
        if (snapshot == null) throw invalid("Snapshot is required");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("version", VERSION);
        header.put("semantics", SEMANTICS);
        StringBuilder out = new StringBuilder(MiniJson.write(header));
        out.setLength(out.length() - 1);
        out.append(",\"targets\":[");
        boolean first = true;
        for (Map.Entry<String, Double> target : snapshot.targets().entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"key\":");
            MiniJson.writeString(out, target.getKey());
            out.append(",\"value\":").append(target.getValue()).append('}');
            requireCharLimit(out.length());
        }
        out.append("],\"entityAliases\":{");
        first = true;
        for (Map.Entry<String, String> alias : snapshot.entityAliases().entrySet()) {
            if (!first) out.append(',');
            first = false;
            MiniJson.writeString(out, alias.getKey());
            out.append(':');
            MiniJson.writeString(out, alias.getValue());
            requireCharLimit(out.length());
        }
        out.append("}}");
        String json = out.toString();
        requireSize(json);
        return json;
    }

    /** Strict v1 decoding: no coercion, unknown fields, duplicate keys, or non-finite truths. */
    public static Snapshot decode(String json) {
        requireSize(json);
        Map<?, ?> root = object(MiniJson.parseStrict(json, MAX_JSON_DEPTH), ROOT_FIELDS);
        if (!FORMAT.equals(root.get("format")) || !SEMANTICS.equals(root.get("semantics"))) {
            throw invalid("Unsupported format or semantics");
        }
        if (!(root.get("version") instanceof Long version) || version != VERSION) {
            throw invalid("Unsupported version; expected integer " + VERSION);
        }
        if (!(root.get("targets") instanceof List<?> rows) || rows.size() > MAX_TARGETS) {
            throw invalid("Invalid targets array");
        }
        Map<String, Double> targets = new LinkedHashMap<>();
        for (Object item : rows) {
            Map<?, ?> row = object(item, TARGET_FIELDS);
            String key = text(row.get("key"), "key");
            double value = truth(row.get("value"));
            if (targets.putIfAbsent(key, value) != null) throw invalid("Duplicate target key");
        }
        if (!(root.get("entityAliases") instanceof Map<?, ?> aliases)
                || aliases.size() > MAX_ALIASES) {
            throw invalid("Invalid entityAliases object");
        }
        Map<String, String> entityAliases = new LinkedHashMap<>();
        aliases.forEach((alias, id) -> entityAliases.put(text(alias, "alias"), text(id, "entityId")));
        return new Snapshot(targets, entityAliases);
    }

    private static Map<?, ?> object(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> object) || !object.keySet().equals(fields)) {
            throw invalid("Object fields must be exactly " + fields);
        }
        return object;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_TEXT_BYTES) {
            throw invalid("Invalid " + field + " string");
        }
        // A malformed UTF-16 ID would silently change when putArtifactText encodes it as UTF-8.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw invalid("Unpaired surrogate in " + field);
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid("Unpaired surrogate in " + field);
            }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw invalid("Oversized " + field + " string");
        }
        return text;
    }

    private static double truth(Object value) {
        if (!(value instanceof Number number)) throw invalid("Target value must be numeric");
        double truth = number.doubleValue();
        if (!Double.isFinite(truth) || truth < 0.0 || truth > 1.0) {
            throw invalid("Target value must be finite and in [0,1]");
        }
        return truth;
    }

    private static void requireSize(String json) {
        if (json == null) throw invalid("JSON is required");
        requireCharLimit(json.length());
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("JSON too large");
    }

    private static void requireCharLimit(int length) {
        if (length > MAX_BYTES) throw invalid("JSON too large");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Consensus targets: " + message);
    }

}
