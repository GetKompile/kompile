/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Launches the project-local crawl engine in an isolated JVM, like Kompile's serving workers. */
public final class LocalCrawlSubprocessRunner {
    private static final String EXECUTION_PROPERTY = "kompile.local.crawl.execution";
    private static final String MAIN_CLASS = LocalCrawlSubprocessMain.class.getName();

    private LocalCrawlSubprocessRunner() {
    }

    public static String executionMode() {
        if (NativeImageInfo.isRunningInNativeImage()) return "in-process-native";
        if ("inline".equalsIgnoreCase(System.getProperty(EXECUTION_PROPERTY, "subprocess"))) {
            return "in-process";
        }
        return "subprocess";
    }

    public static ProjectCrawlCommand.LocalCrawlExecution execute(
            KompileProjectCrawlProfile profile,
            Path projectRoot,
            boolean dryRun,
            JsonNode request,
            ObjectMapper mapper) throws IOException {
        if (!"subprocess".equals(executionMode())) {
            return ProjectCrawlCommand.executeLocalCrawl(profile, projectRoot, dryRun, request);
        }

        Path tempDirectory = Files.createTempDirectory("kompile-local-crawl-");
        Path requestFile = tempDirectory.resolve("request.json");
        Path resultFile = tempDirectory.resolve("result.json");
        Path logFile = tempDirectory.resolve("worker.log");
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
            payload.put("dryRun", dryRun);
            payload.set("profile", mapper.valueToTree(profile));
            payload.set("request", request == null ? mapper.createObjectNode() : request.deepCopy());
            payload.put("resultFile", resultFile.toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(requestFile.toFile(), payload);

            String classpath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path", ""));
            if (classpath.isBlank()) {
                return ProjectCrawlCommand.executeLocalCrawl(profile, projectRoot, dryRun, request);
            }
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            List<String> command = List.of(java.toString(), "-cp", classpath, MAIN_CLASS,
                    requestFile.toString());
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(projectRoot.toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            Process process = builder.start();
            long timeoutMinutes = Math.max(1, profile.getTimeoutMin());
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
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
            return new ProjectCrawlCommand.LocalCrawlExecution(
                    result.path("crawlId").asText(),
                    Path.of(result.path("outputDirectory").asText()),
                    Path.of(result.path("markdownDirectory").asText()),
                    result.path("documentCount").asInt(),
                    result.path("chunkCount").asInt(),
                    result.path("markdownCount").asInt(),
                    result.path("dryRun").asBoolean());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for project-local crawl subprocess.", e);
        } finally {
            deleteTree(tempDirectory);
        }
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
}
