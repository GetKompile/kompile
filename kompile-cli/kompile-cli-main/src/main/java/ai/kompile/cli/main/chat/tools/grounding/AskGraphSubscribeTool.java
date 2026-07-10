/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_subscribe}
 *
 * <h3>Two-call protocol</h3>
 * <p><strong>First call</strong> (no {@code subscriptionId} param): creates a server-side
 * subscription and returns an initial snapshot of current KB matches for the requested
 * predicates — one conjunctive query per predicate. The response includes
 * {@code subscriptionId} and {@code nextCursor} (initially 0). Store these values.</p>
 *
 * <p><strong>Subsequent calls</strong> (pass {@code subscriptionId} + {@code cursor}):
 * performs a cursor-based long-poll against {@code GET /api/kb-grounding/subscribe/{id}/poll},
 * parking up to {@code waitMs} ms server-side. Returns {@code events} (may be empty on timeout)
 * and {@code nextCursor} to use on the next call. MCP tools use this because they are
 * request/response; the server-side SSE stream is used by browser/UI consumers instead.</p>
 *
 * <h3>Event types</h3>
 * <ul>
 *   <li>{@code asserted}    — a fact was written to the KB</li>
 *   <li>{@code retracted}   — a fact was removed from the KB via TMS retraction</li>
 *   <li>{@code graph_mutated} — a node or edge in the graph changed</li>
 *   <li>{@code changed}     — a batch mutation occurred on this fact sheet</li>
 * </ul>
 */
public class AskGraphSubscribeTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphSubscribeTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphSubscribeTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String id() { return "ask_graph_subscribe"; }

    @Override
    public String description() {
        return "Subscribe to KB fact changes and poll for events using a cursor.\n" +
                "FIRST CALL (no subscriptionId): creates a server-side subscription and returns " +
                "an initial snapshot of current KB matches plus {subscriptionId, nextCursor}.\n" +
                "SUBSEQUENT CALLS (pass subscriptionId + cursor): long-polls the server for new " +
                "events since the cursor and returns {events, nextCursor}. Pass the returned " +
                "nextCursor on each subsequent call. " +
                "Server-side SSE is live; this tool uses cursor long-poll because MCP tools are " +
                "request/response — call again with the returned cursor to drain new events.";
    }

    @Override
    public String compactHint() {
        return "First call: {predicates, factSheetId?} → {subscriptionId, nextCursor, snapshot}. " +
                "Next calls: {subscriptionId, cursor, waitMs?} → {events, nextCursor}.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode predsNode = props.putObject("predicates");
        predsNode.put("type", "array");
        predsNode.putObject("items").put("type", "string");
        predsNode.put("description",
                "Predicate names to subscribe to (e.g. [\"worksFor\",\"locatedIn\"]). " +
                "Required on the first call; ignored on subsequent calls (subscription already has them).");
        predsNode.put("minItems", 1);

        props.putObject("subscriptionId")
                .put("type", "string")
                .put("description",
                        "Subscription UUID from a prior first call. When present, " +
                        "performs a long-poll drain instead of a new snapshot.");

        props.putObject("cursor")
                .put("type", "integer")
                .put("description",
                        "Cursor (nextCursor from the prior call). Pass on subsequent calls. " +
                        "Omit or use -1 to read from the start of the ring buffer.");

        props.putObject("waitMs")
                .put("type", "integer")
                .put("description",
                        "Max server-side park time in ms on long-poll (capped at 25000). Default 10000.");

        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Scope to a specific fact sheet. Null = default (0). First-call only.");
        props.putObject("maxBindingsPerPredicate")
                .put("type", "integer")
                .put("description", "Maximum sample bindings in the initial snapshot per predicate. Default 5.");
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Agent session ID for provenance tracking. First-call only.");

        // Note: predicates is required only on the first call — we enforce this in execute()
        // rather than in JSON schema so that subsequent calls aren't rejected by schema validation.
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_subscribe"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Subscribe to KB fact changes");

        if (!groundingClient.isAvailable()) {
            return ToolResult.error("ask_graph_subscribe requires a running kompile-app.");
        }

        String subscriptionId = params.path("subscriptionId").asText(null);
        boolean isSubscribed = subscriptionId != null && !subscriptionId.isBlank();

        if (isSubscribed) {
            return executePoll(params, subscriptionId);
        } else {
            return executeFirstCall(params);
        }
    }

    // ── First call: create subscription + initial snapshot ────────────────────────

    private ToolResult executeFirstCall(JsonNode params) {
        JsonNode predicatesNode = params.path("predicates");
        if (!predicatesNode.isArray() || predicatesNode.isEmpty()) {
            return ToolResult.error("predicates array is required on the first call (when subscriptionId is not set)");
        }

        List<String> predicates = new ArrayList<>();
        for (JsonNode p : predicatesNode) {
            String name = p.asText("").trim();
            if (!name.isEmpty()) predicates.add(name);
        }
        if (predicates.isEmpty()) {
            return ToolResult.error("predicates array contained no non-empty strings");
        }

        int maxBindings = params.path("maxBindingsPerPredicate").asInt(5);
        if (maxBindings < 1) maxBindings = 5;
        if (maxBindings > 50) maxBindings = 50;

        // 1. Create the server-side subscription
        String createSubscriptionId;
        Instant expiresAt;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            if (!params.path("factSheetId").isMissingNode()) {
                body.set("factSheetId", params.get("factSheetId"));
            }
            ArrayNode predsArr = body.putArray("predicates");
            for (String pred : predicates) predsArr.add(pred);

            GroundingBackendClient.GroundingResponse resp;
            try {
                resp = groundingClient.post("/api/kb-grounding/subscribe",
                        objectMapper.writeValueAsString(body));
            } catch (HttpClientErrorException e) {
                return ToolResult.error("Failed to create subscription (HTTP " + e.getStatusCode().value()
                        + "): " + e.getResponseBodyAsString());
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                return ToolResult.error("Failed to create subscription (HTTP " + e.getStatusCode().value()
                        + "): " + e.getResponseBodyAsString());
            }
            if (resp.statusCode() != 200) {
                return ToolResult.error("Failed to create subscription (HTTP " + resp.statusCode()
                        + "): " + resp.body());
            }
            JsonNode subResp = objectMapper.readTree(resp.body());
            createSubscriptionId = subResp.path("subscriptionId").asText(null);
            String rawExpiry = subResp.path("expiresAt").asText(null);
            expiresAt = rawExpiry != null ? Instant.parse(rawExpiry) : null;

            if (createSubscriptionId == null || createSubscriptionId.isBlank()) {
                return ToolResult.error("Server did not return a subscriptionId: " + resp.body());
            }
        } catch (Exception e) {
            return ToolResult.error("Failed to create subscription: " + e.getMessage());
        }

        // 2. Initial snapshot: one conjunctive query per predicate
        StringBuilder sb = new StringBuilder();
        sb.append("KB subscription created at ").append(Instant.now()).append("\n");
        sb.append("subscriptionId: ").append(createSubscriptionId).append("\n");
        if (expiresAt != null) sb.append("expiresAt: ").append(expiresAt).append("\n");
        sb.append("\nInitial snapshot:\n\n");

        int totalMatches = 0;

        for (String predicate : predicates) {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode conjuncts = body.putArray("conjuncts");
                ObjectNode conjunct = conjuncts.addObject();
                conjunct.put("predicate", predicate);
                ArrayNode args = conjunct.putArray("args");
                args.add("?s");
                args.add("?o");
                body.put("maxResults", maxBindings);
                body.put("minConfidence", 0.0);
                if (!params.path("factSheetId").isMissingNode()) {
                    body.set("factSheetId", params.get("factSheetId"));
                }
                if (!params.path("sessionId").isMissingNode()) {
                    body.set("sessionId", params.get("sessionId"));
                }

                var resp = groundingClient.post("/api/kb-grounding/query",
                        objectMapper.writeValueAsString(body));

                if (resp.statusCode() != 200) {
                    sb.append("predicate: ").append(predicate)
                            .append(" — query error (HTTP ").append(resp.statusCode()).append(")\n\n");
                    continue;
                }

                JsonNode result = objectMapper.readTree(resp.body());
                int count = result.path("total").asInt(0);
                boolean truncated = result.path("truncated").asBoolean(false);
                boolean stale = result.path("meta").path("stale").asBoolean(false);
                JsonNode bindings = result.path("bindings");
                totalMatches += count;

                sb.append("predicate: ").append(predicate)
                        .append(" — ").append(count).append(" match(es)");
                if (truncated) sb.append(" (truncated)");
                if (stale) sb.append(" [KB stale, pending cascade]");
                sb.append("\n");

                if (bindings.isArray()) {
                    int shown = 0;
                    for (JsonNode row : bindings) {
                        if (shown >= maxBindings) break;
                        double conf = row.path("confidence").asDouble(0.0);
                        JsonNode vars = row.path("variables");
                        String sVal = vars.path("s").asText("?");
                        String oVal = vars.path("o").asText("?");
                        sb.append("  - ").append(predicate).append("(")
                                .append(sVal).append(", ").append(oVal)
                                .append(") conf=").append(String.format("%.3f", conf)).append("\n");
                        shown++;
                    }
                }
                sb.append("\n");

            } catch (Exception e) {
                sb.append("predicate: ").append(predicate)
                        .append(" — error: ").append(e.getMessage()).append("\n\n");
            }
        }

        sb.append("Total matches: ").append(totalMatches).append("\n");
        sb.append("\nTo poll for new events: call ask_graph_subscribe again with:\n");
        sb.append("  subscriptionId: \"").append(createSubscriptionId).append("\"\n");
        sb.append("  cursor: 0  (or the nextCursor from the previous poll)\n");
        sb.append("  waitMs: 10000  (park up to 10s for new events)\n");

        return ToolResult.success(
                "ask_graph_subscribe: subscription created, " + predicates.size() + " predicate(s) snapshot",
                sb.toString(),
                Map.of(
                        "subscriptionId", createSubscriptionId,
                        "nextCursor", 0,
                        "predicates", predicates.size(),
                        "totalMatches", totalMatches
                ));
    }

    // ── Subsequent calls: long-poll drain ─────────────────────────────────────────

    private ToolResult executePoll(JsonNode params, String subscriptionId) {
        long cursor = params.path("cursor").asLong(-1L);
        long waitMs = params.path("waitMs").asLong(10_000L);
        if (waitMs < 0) waitMs = 0;
        if (waitMs > 25_000L) waitMs = 25_000L;

        try {
            String pollPath = "/api/kb-grounding/subscribe/" + subscriptionId
                    + "/poll?cursor=" + cursor + "&waitMs=" + waitMs;

            GroundingBackendClient.GroundingResponse resp;
            try {
                resp = groundingClient.get(pollPath);
            } catch (HttpClientErrorException.NotFound e) {
                return ToolResult.error("Subscription not found or expired: " + subscriptionId
                        + ". Create a new subscription by calling without subscriptionId.");
            } catch (HttpClientErrorException e) {
                return ToolResult.error("Poll failed (HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
            }

            if (resp.statusCode() == 404) {
                return ToolResult.error("Subscription not found or expired: " + subscriptionId
                        + ". Create a new subscription by calling without subscriptionId.");
            }
            if (resp.statusCode() != 200) {
                return ToolResult.error("Poll failed (HTTP " + resp.statusCode() + "): " + resp.body());
            }

            JsonNode pollResp = objectMapper.readTree(resp.body());
            long nextCursor = pollResp.path("nextCursor").asLong(cursor);
            boolean overflow = pollResp.path("overflow").asBoolean(false);
            JsonNode events = pollResp.path("events");

            StringBuilder sb = new StringBuilder();
            sb.append("KB poll result at ").append(Instant.now()).append("\n");
            sb.append("subscriptionId: ").append(subscriptionId).append("\n");
            sb.append("cursor was: ").append(cursor).append(", nextCursor: ").append(nextCursor).append("\n");
            if (overflow) {
                sb.append("WARNING: ring buffer overflow — some events were dropped. Re-query the KB for full state.\n");
            }
            sb.append("\n");

            int eventCount = 0;
            if (events.isArray()) {
                for (JsonNode ev : events) {
                    eventCount++;
                    String type = ev.path("type").asText("?");
                    long factSheetId = ev.path("factSheetId").asLong(0);
                    String atomKey = ev.path("atomKey").asText(null);
                    double value = ev.path("value").asDouble(-1);
                    String source = ev.path("source").asText(null);
                    long seq = ev.path("seq").asLong(-1);
                    sb.append("[seq=").append(seq).append("] type=").append(type)
                            .append(" factSheetId=").append(factSheetId);
                    if (atomKey != null) sb.append(" atom=").append(atomKey);
                    if (value >= 0) sb.append(" value=").append(String.format("%.3f", value));
                    if (source != null) sb.append(" src=").append(source);
                    sb.append("\n");
                }
            }

            if (eventCount == 0) {
                sb.append("No new events (timeout after ").append(waitMs).append(" ms).\n");
            } else {
                sb.append("\nTotal: ").append(eventCount).append(" event(s).\n");
            }

            sb.append("\nNext call: pass cursor=").append(nextCursor).append(" to drain new events.\n");

            return ToolResult.success(
                    "ask_graph_subscribe: " + eventCount + " event(s) received",
                    sb.toString(),
                    Map.of(
                            "subscriptionId", subscriptionId,
                            "eventCount", eventCount,
                            "nextCursor", nextCursor,
                            "overflow", overflow
                    ));

        } catch (Exception e) {
            return ToolResult.error("Poll error: " + e.getMessage());
        }
    }
}
