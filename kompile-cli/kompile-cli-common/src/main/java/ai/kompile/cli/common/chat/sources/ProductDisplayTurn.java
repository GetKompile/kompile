package ai.kompile.cli.common.chat.sources;

import ai.kompile.cli.common.chat.ToolPresentationV1;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Additive canonical display projection. Deliberately separate from model-facing ChatTurn. */
public record ProductDisplayTurn(String role, String content,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String productTurnId,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolPresentation> toolPresentations) {
    public static final int MAX_PRESENTATIONS = 16;
    public static final int MAX_METADATA_BYTES = 65536;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ProductDisplayTurn {
        toolPresentations = toolPresentations == null ? List.of() : List.copyOf(toolPresentations);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ToolPresentation(String callId, String toolName, ObjectNode presentation) {
        public ToolPresentation { presentation = presentation == null ? null : presentation.deepCopy(); }
        @Override public ObjectNode presentation() { return presentation == null ? null : presentation.deepCopy(); }
    }

    /** Same validation and stable call identity on live collection and durable read. */
    public static ToolPresentation presentation(String sessionId, String turnId, String callId,
                                                 String toolName, JsonNode value) {
        if (!ToolPresentationV1.validId(toolName)) return null;
        ObjectNode projected = ToolPresentationV1.project(value, sessionId, turnId, callId);
        return projected == null ? null : new ToolPresentation(callId, toolName, projected);
    }

    public static ProductDisplayTurn project(String sessionId, String role, String content, JsonNode metadata) {
        String turnId = metadata == null ? null : metadata.path("productTurnId").asText(null);
        if (!"assistant".equalsIgnoreCase(role) || !ToolPresentationV1.validId(turnId)
                || !metadata.path("productTurnId").isTextual()
                || !ToolPresentationV1.exact(metadata, Set.of("productTurnId", "toolPresentations"))
                || !metadata.path("toolPresentations").isArray()
                || metadata.path("toolPresentations").size() > MAX_PRESENTATIONS)
            return new ProductDisplayTurn(role, content, null, List.of());
        List<ToolPresentation> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : metadata.path("toolPresentations")) {
            if (!ToolPresentationV1.exact(node, Set.of("callId", "toolName", "presentation"))
                    || !node.path("callId").isTextual() || !node.path("toolName").isTextual()) continue;
            String callId = node.path("callId").asText(null);
            ToolPresentation item = presentation(sessionId, turnId, callId, node.path("toolName").asText(null), node.get("presentation"));
            if (item != null && seen.add(callId)) entries.add(item);
        }
        return new ProductDisplayTurn(role, content, turnId, entries);
    }

    public static ObjectNode metadata(String sessionId, String turnId, List<ToolPresentation> entries) {
        if (!ToolPresentationV1.validId(turnId)) return null;
        ObjectNode node = JSON.createObjectNode().put("productTurnId", turnId);
        var array = node.putArray("toolPresentations");
        Set<String> seen = new HashSet<>();
        if (entries != null) for (ToolPresentation entry : entries) {
            if (entry == null || array.size() >= MAX_PRESENTATIONS) continue;
            ToolPresentation valid = presentation(sessionId, turnId, entry.callId(), entry.toolName(), entry.presentation());
            if (valid == null || !seen.add(valid.callId())) continue;
            JsonNode serialized = JSON.valueToTree(valid);
            array.add(serialized);
            if (node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
                array.remove(array.size() - 1);
                break;
            }
        }
        return node;
    }
}
