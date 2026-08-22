/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.tools.grounding.LocalProjectCrawlBackend;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-scoped in-process runtime for MCP tools that historically delegated to kompile-app.
 *
 * <p>Stdio is the default execution boundary. An explicit {@code --url} may still select a remote
 * deployment, but absence of a URL never triggers service discovery or a centralized-service
 * requirement.</p>
 */
public final class OfflineToolRuntime {

    private static final Map<Path, LocalProjectGraphBackend> GRAPH_BACKENDS = new ConcurrentHashMap<>();
    private static final Map<Path, LocalProjectCrawlBackend> CRAWL_BACKENDS = new ConcurrentHashMap<>();
    private static final KompileProjectStore PROJECT_STORE = new KompileProjectStore();

    private OfflineToolRuntime() {
    }

    public static ToolResult execute(String toolId, JsonNode params, ToolContext context,
                                     ObjectMapper mapper) {
        Path working = context.getWorkingDirectory().toAbsolutePath().normalize();
        Path root = PROJECT_STORE.findProjectRoot(working).orElse(working);
        if ("rag_search".equals(toolId)) {
            String query = params.path("query").asText("").trim();
            if (query.isEmpty()) {
                return ToolResult.error("query is required");
            }
            String topic = params.path("topic").asText(null);
            String knowledgeBase = params.path("knowledgeBase").asText(null);
            int limit = Math.min(50, Math.max(1, params.path("limit").asInt(20)));
            return CRAWL_BACKENDS.computeIfAbsent(root, ignored -> new LocalProjectCrawlBackend(mapper))
                    .search(query, topic, knowledgeBase, limit, context);
        }
        return GRAPH_BACKENDS.computeIfAbsent(root, ignored -> new LocalProjectGraphBackend(mapper))
                .executeOfflineTool(toolId, params, context);
    }
}
