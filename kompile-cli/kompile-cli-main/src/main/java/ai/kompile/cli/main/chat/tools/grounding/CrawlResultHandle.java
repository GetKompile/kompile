/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stable, backend-neutral reference returned by crawl lifecycle tools.
 *
 * <p>The handle deliberately contains executable follow-up calls. Agents can carry it from
 * {@code crawl_documents} through monitoring, result inspection, knowledge search, and graph
 * reasoning without reconstructing local paths or managed fact-sheet selectors.</p>
 */
final class CrawlResultHandle {
    private static final String SCHEMA = "kompile-crawl-result/v1";
    private static final long DEFAULT_POLL_AFTER_MS = 1_000L;
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMPLETED", "COMPLETED_WITH_ERRORS", "SUCCESS", "SUCCEEDED",
            "FAILED", "CANCELLED", "CANCELED", "SKIPPED");

    private final String backend;
    private final String jobId;
    private final String knowledgeBase;
    private final Long factSheetId;
    private final String status;
    private final Long pollAfterMs;
    private final String graphPath;
    private final boolean cancellable;

    private CrawlResultHandle(String backend,
                              String jobId,
                              String knowledgeBase,
                              Long factSheetId,
                              String status,
                              Long pollAfterMs,
                              String graphPath,
                              boolean cancellable) {
        this.backend = nonBlank(backend, "unknown");
        this.jobId = blankToNull(jobId);
        this.knowledgeBase = blankToNull(knowledgeBase);
        this.factSheetId = factSheetId;
        this.status = nonBlank(status, "UNKNOWN").toUpperCase(Locale.ROOT);
        this.pollAfterMs = pollAfterMs;
        this.graphPath = blankToNull(graphPath);
        this.cancellable = cancellable;
    }

    static CrawlResultHandle from(JsonNode payload,
                                  String backend,
                                  String fallbackJobId,
                                  String fallbackKnowledgeBase) {
        JsonNode source = payload == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : payload;
        String jobId = firstText(source, "jobId", "internalJobId", "profileId");
        if (jobId == null) jobId = fallbackJobId;

        String knowledgeBase = firstText(source,
                "knowledgeBaseId", "knowledge_base", "profileId");
        JsonNode knowledgeBaseNode = source.path("knowledgeBase");
        if (knowledgeBase == null && knowledgeBaseNode.isTextual()) {
            knowledgeBase = blankToNull(knowledgeBaseNode.asText());
        }
        if (knowledgeBase == null) knowledgeBase = fallbackKnowledgeBase;

        Long factSheetId = firstLong(source, "factSheetId", "fact_sheet_id");
        String status = firstText(source, "status", "state");
        if (status == null && source.has("completed")) {
            status = source.path("completed").asBoolean(false) ? "COMPLETED" : "RUNNING";
        }
        return new CrawlResultHandle(
                backend,
                jobId,
                knowledgeBase,
                factSheetId,
                status,
                pollAfterMs(source),
                firstText(source, "graphPath", "graph_path"),
                !source.has("cancellable") || source.path("cancellable").asBoolean(true));
    }

    boolean terminal() {
        return TERMINAL_STATUSES.contains(status);
    }

    Map<String, Object> asMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", SCHEMA);
        value.put("backend", backend);
        putIfPresent(value, "jobId", jobId);
        putIfPresent(value, "knowledgeBase", knowledgeBase);
        putIfPresent(value, "factSheetId", factSheetId);
        value.put("status", status);
        value.put("terminal", terminal());
        value.put("cancellable", !terminal() && cancellable);
        putIfPresent(value, "pollAfterMs", pollAfterMs);
        putIfPresent(value, "graphPath", graphPath);
        value.put("nextActions", nextActions());
        return value;
    }

    List<Map<String, Object>> nextActions() {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (jobId != null && !terminal()) {
            Map<String, Object> monitorArguments = new LinkedHashMap<>();
            monitorArguments.put("operation", "status");
            monitorArguments.put("jobId", jobId);
            if (pollAfterMs != null) monitorArguments.put("pollAfterMs", pollAfterMs);
            actions.add(action("monitor", "crawl_control", monitorArguments, List.of()));
            if (cancellable) {
                actions.add(action("cancel", "crawl_control",
                        Map.of("operation", "cancel", "jobId", jobId), List.of()));
            }
        }
        if (jobId != null) {
            actions.add(action("inspectResult", "crawl_result",
                    Map.of("jobId", jobId), List.of()));
        }

        Map<String, Object> selector = selector();
        if (!selector.isEmpty()) {
            actions.add(action("inspectKnowledge", "knowledge_status", selector, List.of()));

            Map<String, Object> search = new LinkedHashMap<>(selector);
            actions.add(action("searchCorpus", "knowledge_search", search, List.of("query")));

            Map<String, Object> overview = new LinkedHashMap<>(selector);
            overview.put("operation", "OVERVIEW");
            actions.add(action("inspectGraph", "graph_reasoning_query", overview, List.of()));

            Map<String, Object> assertion = new LinkedHashMap<>(selector);
            assertion.put("value", 1.0);
            actions.add(action("assertGraphFact", "ask_graph_assert", assertion, List.of("atom")));

            actions.add(action("retractGraphFact", "ask_graph_retract",
                    selector, List.of("atomKey")));
        }
        return List.copyOf(actions);
    }

    void attachTo(Map<String, Object> metadata) {
        metadata.put("crawlResult", asMap());
        metadata.put("nextActions", nextActions());

        LinkedHashSet<String> tools = new LinkedHashSet<>();
        Object existing = metadata.get("nextTools");
        if (existing instanceof Collection<?> collection) {
            for (Object value : collection) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    tools.add(String.valueOf(value));
                }
            }
        }
        for (Map<String, Object> action : nextActions()) {
            tools.add(String.valueOf(action.get("tool")));
        }
        tools.add("graph_reason");
        tools.add("knowledge_graph");
        tools.add("ask_graph_assert");
        tools.add("ask_graph_retract");
        metadata.put("nextTools", List.copyOf(tools));
    }

    private Map<String, Object> selector() {
        Map<String, Object> selector = new LinkedHashMap<>();
        if (knowledgeBase != null) {
            selector.put("knowledgeBase", knowledgeBase);
        } else if (factSheetId != null) {
            selector.put("factSheetId", factSheetId);
        }
        return selector;
    }

    private static Map<String, Object> action(String name,
                                               String tool,
                                               Map<String, Object> arguments,
                                               List<String> requiredArguments) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", name);
        action.put("tool", tool);
        action.put("arguments", new LinkedHashMap<>(arguments));
        if (!requiredArguments.isEmpty()) {
            action.put("requiredArguments", requiredArguments);
        }
        return action;
    }

    private static String firstText(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode value = source.path(field);
            if (value.isTextual()) {
                String text = blankToNull(value.asText());
                if (text != null) return text;
            }
        }
        return null;
    }

    private static Long pollAfterMs(JsonNode source) {
        Long configured = firstLong(source, "pollAfterMs", "poll_after_ms");
        return configured == null ? DEFAULT_POLL_AFTER_MS : configured;
    }

    private static Long firstLong(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode value = source.path(field);
            if (value.isIntegralNumber()) {
                long number = value.asLong();
                if (number >= 0) return number;
            } else if (value.isTextual()) {
                try {
                    long number = Long.parseLong(value.asText().trim());
                    if (number >= 0) return number;
                } catch (NumberFormatException ignored) {
                    // Try the next compatible selector.
                }
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String nonBlank(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
