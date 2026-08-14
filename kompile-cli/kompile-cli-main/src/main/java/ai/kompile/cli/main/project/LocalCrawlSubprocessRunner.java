/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.mcp.McpToolInjectionSupport;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Launches one complete project-local crawl lifecycle in an isolated worker JVM.
 * Model-backed stages may launch Kompile's nested one-shot workers; all descendants are bounded by
 * this command and are terminated with the worker on timeout or interruption.
 */
public final class LocalCrawlSubprocessRunner {
    private static final String EXECUTION_PROPERTY = "kompile.local.crawl.execution";

    private LocalCrawlSubprocessRunner() {
    }

    public static String executionMode() {
        if ("inline".equalsIgnoreCase(System.getProperty(EXECUTION_PROPERTY, "subprocess"))) {
            return NativeImageInfo.isRunningInNativeImage()
                    ? "in-process-native-explicit" : "in-process-explicit";
        }
        return NativeImageInfo.isRunningInNativeImage()
                ? "subprocess-native" : "subprocess";
    }

    public static ExecutionResult execute(
            KompileProjectCrawlProfile profile,
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            GraphContext graphContext,
            ObjectMapper mapper) throws IOException {
        if (executionMode().startsWith("in-process")) {
            return executeInline(profile, projectRoot, dryRun, request, graphContext, mapper);
        }

        Path tempDirectory = Files.createTempDirectory("kompile-local-crawl-");
        Path requestFile = tempDirectory.resolve("request.json");
        Path resultFile = tempDirectory.resolve("result.json");
        Path logFile = tempDirectory.resolve("worker.log");
        Process process = null;
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
            payload.put("dryRun", dryRun);
            payload.set("profile", mapper.valueToTree(profile));
            payload.set("request", request == null ? mapper.createObjectNode() : request.deepCopy());
            if (graphContext != null) {
                payload.set("graphContext", mapper.valueToTree(graphContext));
            }
            payload.put("resultFile", resultFile.toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(requestFile.toFile(), payload);

            List<String> command = workerCommand(requestFile);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(projectRoot.toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            process = builder.start();
            long timeoutMinutes = Math.max(1, profile.getTimeoutMin());
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                terminateProcessTree(process);
                throw new IOException("Project-local crawl subprocess timed out after "
                        + timeoutMinutes + " minute(s).");
            }
            if (!Files.isRegularFile(resultFile)) {
                throw new IOException("Project-local crawl subprocess produced no result (exit "
                        + process.exitValue() + "): " + readLog(logFile));
            }
            JsonNode result = mapper.readTree(resultFile.toFile());
            if (process.exitValue() != 0 || result.hasNonNull("error")) {
                String error = result.path("error").asText(readLog(logFile));
                throw new IOException("Project-local crawl subprocess failed: " + error);
            }
            List<ProjectCrawlCommand.LocalCrawlFailure> failures = new ArrayList<>();
            JsonNode failureNodes = result.path("documentFailures");
            if (failureNodes.isArray()) {
                for (JsonNode failure : failureNodes) {
                    failures.add(new ProjectCrawlCommand.LocalCrawlFailure(
                            failure.path("documentId").asText(null),
                            failure.path("source").asText(null),
                            failure.path("relativePath").asText(null),
                            failure.path("message").asText(null),
                            failure.path("pipelineId").asText(null),
                            failure.path("pipelineType").asText(null)));
                }
            }
            ProjectCrawlCommand.LocalCrawlExecution crawlExecution =
                    new ProjectCrawlCommand.LocalCrawlExecution(
                    result.path("crawlId").asText(),
                    Path.of(result.path("outputDirectory").asText()),
                    Path.of(result.path("markdownDirectory").asText()),
                    result.path("documentCount").asInt(),
                    result.path("chunkCount").asInt(),
                    result.path("markdownCount").asInt(),
                    result.path("status").asText(dryRun ? "DRY_RUN" : "COMPLETED"),
                    failures,
                    result.path("dryRun").asBoolean());
            LocalProjectGraphBackend.GraphUpdate graphUpdate = result.path("graphUpdate").isObject()
                    ? mapper.treeToValue(result.path("graphUpdate"),
                            LocalProjectGraphBackend.GraphUpdate.class)
                    : null;
            return new ExecutionResult(crawlExecution, graphUpdate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for project-local crawl subprocess.", e);
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcessTree(process);
            }
            deleteTree(tempDirectory);
        }
    }

    private static List<String> workerCommand(Path requestFile) throws IOException {
        List<String> command = McpToolInjectionSupport.buildCliProcessCommand(List.of(
                "--subprocess=local-crawl",
                requestFile.toAbsolutePath().normalize().toString()));
        if (command == null) {
            throw new IOException(
                    "Cannot launch the project-local crawl worker: no native Kompile CLI or "
                            + "executable CLI JAR was found. Set KOMPILE_CLI_BINARY or "
                            + "KOMPILE_CLI_JAR.");
        }
        return command;
    }

    private static ExecutionResult executeInline(
            KompileProjectCrawlProfile profile,
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            GraphContext graphContext,
            ObjectMapper mapper) throws IOException {
        ProjectCrawlCommand.LocalCrawlExecution execution =
                ProjectCrawlCommand.executeLocalCrawl(profile, projectRoot, dryRun, request);
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
                    request);
        } catch (Exception e) {
            throw new IOException("Project-local graph lifecycle failed: " + rootMessage(e), e);
        }
    }

    private static void terminateProcessTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants = process.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                descendants.stream().filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            descendants.stream().filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
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

    private static String readLog(Path logFile) {
        try {
            if (!Files.isRegularFile(logFile)) return "no worker log";
            String log = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            if (log.length() > 4_000) return log.substring(log.length() - 4_000);
            return log;
        } catch (IOException ignored) {
            return "worker log unavailable";
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary subprocess files are best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Temporary subprocess files are best-effort cleanup only.
        }
    }

    public record GraphContext(String knowledgeBaseId,
                               String knowledgeBaseName,
                               Long factSheetId,
                               String projectId,
                               List<LocalProjectGraphBackend.CodeProjectSource> codeProjects) {
        public GraphContext {
            codeProjects = codeProjects == null ? List.of() : List.copyOf(codeProjects);
        }
    }

    public record ExecutionResult(ProjectCrawlCommand.LocalCrawlExecution crawlExecution,
                                  LocalProjectGraphBackend.GraphUpdate graphUpdate) {
    }
}
