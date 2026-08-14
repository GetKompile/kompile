/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Child-JVM entry point for one complete project-local crawl and graph lifecycle. */
public final class LocalCrawlSubprocessMain {
    private LocalCrawlSubprocessMain() {
    }

    public static void main(String[] args) {
        NativeLibraryResolver.bootstrapOrThrow();
        Runtime.getRuntime().addShutdownHook(new Thread(
                LocalCrawlSubprocessMain::terminateChildren,
                "kompile-local-crawl-child-cleanup"));
        if (args.length != 1) {
            System.err.println("Usage: LocalCrawlSubprocessMain <request.json>");
            System.exit(2);
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path resultFile = null;
        try {
            JsonNode payload = mapper.readTree(Path.of(args[0]).toFile());
            resultFile = Path.of(payload.path("resultFile").asText());
            Path projectRoot = Path.of(payload.path("projectRoot").asText());
            boolean dryRun = payload.path("dryRun").asBoolean(false);
            KompileProjectCrawlProfile profile = mapper.treeToValue(
                    payload.path("profile"), KompileProjectCrawlProfile.class);
            JsonNode request = payload.path("request");
            LocalCrawlSubprocessRunner.GraphContext graphContext =
                    payload.path("graphContext").isObject()
                            ? mapper.treeToValue(payload.path("graphContext"),
                                    LocalCrawlSubprocessRunner.GraphContext.class)
                            : null;
            String validationError = LocalCrawlCapabilities.validationError(request);
            if (validationError != null) throw new IllegalArgumentException(validationError);
            String workerValidationError =
                    LocalModelPipelineRunner.validateWorkerConfiguration(projectRoot, request);
            if (workerValidationError != null) throw new IllegalArgumentException(workerValidationError);

            ProjectCrawlCommand.LocalCrawlExecution execution =
                    ProjectCrawlCommand.executeLocalCrawl(profile, projectRoot, dryRun, request);
            LocalProjectGraphBackend.GraphUpdate graphUpdate =
                    LocalCrawlSubprocessRunner.updateGraph(
                            projectRoot, dryRun, request, graphContext, execution, mapper);
            ObjectNode result = mapper.createObjectNode();
            result.put("crawlId", execution.crawlId());
            result.put("outputDirectory", execution.outputDirectory().toString());
            result.put("markdownDirectory", execution.markdownDirectory().toString());
            result.put("documentCount", execution.documentCount());
            result.put("chunkCount", execution.chunkCount());
            result.put("markdownCount", execution.markdownCount());
            result.put("status", execution.status());
            result.put("failedDocumentCount", execution.documentFailures().size());
            result.set("documentFailures", mapper.valueToTree(execution.documentFailures()));
            result.put("dryRun", execution.dryRun());
            if (graphUpdate != null) {
                result.set("graphUpdate", mapper.valueToTree(graphUpdate));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);
            System.exit(0);
        } catch (Exception e) {
            try {
                if (resultFile != null) {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("error", message(e));
                    mapper.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);
                }
            } catch (Exception ignored) {
                // Parent also observes the non-zero exit and worker log.
            }
            System.err.println(message(e));
            System.exit(1);
        }
    }

    private static void terminateChildren() {
        List<ProcessHandle> descendants = ProcessHandle.current().descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        descendants.forEach(ProcessHandle::destroy);
        descendants.stream().filter(ProcessHandle::isAlive)
                .forEach(ProcessHandle::destroyForcibly);
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
