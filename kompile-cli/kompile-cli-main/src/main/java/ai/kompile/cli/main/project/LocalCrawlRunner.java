/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs project-local crawl orchestration in the long-lived stdio MCP host.
 * Heavy model/native execution is isolated exclusively through pooled pipeline runtimes.
 */
public final class LocalCrawlRunner {
    private LocalCrawlRunner() {
    }

    public static String executionMode() {
        return NativeImageInfo.isRunningInNativeImage() ? "mcp-host-native" : "mcp-host";
    }

    public static ExecutionResult execute(
            KompileProjectCrawlProfile profile,
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            GraphContext graphContext,
            ObjectMapper mapper) throws IOException {
        return execute(profile, projectRoot, dryRun, request, graphContext, mapper, null);
    }

    public static ExecutionResult execute(
            KompileProjectCrawlProfile profile,
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            GraphContext graphContext,
            ObjectMapper mapper,
            ProjectCrawlCommand.ModelPipelineExecutor modelPipelineExecutor) throws IOException {
        ProjectCrawlCommand.LocalCrawlExecution execution = modelPipelineExecutor == null
                ? ProjectCrawlCommand.executeLocalCrawl(profile, projectRoot, dryRun, request)
                : ProjectCrawlCommand.executeLocalCrawl(
                        profile, projectRoot, dryRun, request, modelPipelineExecutor);
        LocalProjectGraphBackend.GraphUpdate graphUpdate =
                updateGraph(projectRoot, dryRun, request, graphContext, execution, mapper);
        return new ExecutionResult(execution, graphUpdate);
    }

    static LocalProjectGraphBackend.GraphUpdate updateGraph(
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            GraphContext graphContext,
            ProjectCrawlCommand.LocalCrawlExecution execution,
            ObjectMapper mapper) throws IOException {
        if (dryRun || graphContext == null || "FAILED".equals(execution.status())) return null;
        try {
            return new LocalProjectGraphBackend(mapper).updateCrawlGraph(
                    projectRoot,
                    graphContext.knowledgeBaseId(),
                    graphContext.knowledgeBaseName(),
                    graphContext.factSheetId(),
                    graphContext.projectId(),
                    graphContext.codeProjects(),
                    graphContext.crawlJobId(),
                    request);
        } catch (Exception e) {
            throw new IOException("Project-local graph lifecycle failed: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record GraphContext(String knowledgeBaseId,
                               String knowledgeBaseName,
                               Long factSheetId,
                               String projectId,
                               List<LocalProjectGraphBackend.CodeProjectSource> codeProjects,
                               String crawlJobId) {
        public GraphContext(
                String knowledgeBaseId,
                String knowledgeBaseName,
                Long factSheetId,
                String projectId,
                List<LocalProjectGraphBackend.CodeProjectSource> codeProjects) {
            this(knowledgeBaseId, knowledgeBaseName, factSheetId, projectId, codeProjects, null);
        }

        public GraphContext {
            codeProjects = codeProjects == null ? List.of() : List.copyOf(codeProjects);
        }
    }

    public record ExecutionResult(ProjectCrawlCommand.LocalCrawlExecution crawlExecution,
                                  LocalProjectGraphBackend.GraphUpdate graphUpdate) {
    }
}
