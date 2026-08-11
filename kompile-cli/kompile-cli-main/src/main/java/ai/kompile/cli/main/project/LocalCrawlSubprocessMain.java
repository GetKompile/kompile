/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/** Child-JVM entry point for a single project-local crawl request. */
public final class LocalCrawlSubprocessMain {
    private LocalCrawlSubprocessMain() {
    }

    public static void main(String[] args) {
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
            String validationError = LocalCrawlCapabilities.validationError(request);
            if (validationError != null) throw new IllegalArgumentException(validationError);

            ProjectCrawlCommand.LocalCrawlExecution execution =
                    ProjectCrawlCommand.executeLocalCrawl(profile, projectRoot, dryRun, request);
            ObjectNode result = mapper.createObjectNode();
            result.put("crawlId", execution.crawlId());
            result.put("outputDirectory", execution.outputDirectory().toString());
            result.put("markdownDirectory", execution.markdownDirectory().toString());
            result.put("documentCount", execution.documentCount());
            result.put("chunkCount", execution.chunkCount());
            result.put("markdownCount", execution.markdownCount());
            result.put("dryRun", execution.dryRun());
            mapper.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), result);
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

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
