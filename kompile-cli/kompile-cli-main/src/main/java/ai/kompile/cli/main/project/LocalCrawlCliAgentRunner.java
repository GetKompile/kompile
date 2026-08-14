/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.config.AgentSubprocessClient;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.core.agent.CliAgentRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Folder-scoped CLI-agent bridge for headless crawl extraction.
 *
 * <p>It reuses the standard chat subprocess command builder and JSON-stream parser, but suppresses
 * terminal rendering because the model call is an internal stage of the crawl tool.</p>
 */
public final class LocalCrawlCliAgentRunner implements CliAgentRunner {

    private final String workingDirectory;
    private final String modelOverride;
    private final ObjectMapper mapper;

    public LocalCrawlCliAgentRunner(Path workingDirectory,
                                    String modelOverride,
                                    ObjectMapper mapper) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize().toString();
        this.modelOverride = modelOverride == null || modelOverride.isBlank()
                ? null : modelOverride.trim();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String run(String agentName, String prompt, int timeoutSeconds) {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("A CLI agent name is required");
        }
        int effectiveTimeout = Math.max(1, timeoutSeconds);
        try (AgentSubprocessClient client = new AgentSubprocessClient(
                agentName.trim(), workingDirectory, mapper)) {
            if (!client.isAvailable()) {
                throw new IllegalStateException("CLI agent '" + agentName + "' is not installed");
            }
            client.setQuiet(true);
            client.setOutputConsumer(ignored -> { });

            ExecutorService callExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "local-crawl-agent-" + agentName);
                thread.setDaemon(true);
                return thread;
            });
            Future<DirectLlmClient.StreamResult> call = callExecutor.submit(
                    () -> client.streamOneShot(prompt, null, modelOverride));
            try {
                DirectLlmClient.StreamResult result = call.get(effectiveTimeout, TimeUnit.SECONDS);
                String text = result != null ? result.text : null;
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("CLI agent '" + agentName
                            + "' returned no content");
                }
                if (text.startsWith("Error running agent:")) {
                    throw new IllegalStateException(text);
                }
                return text;
            } catch (TimeoutException timeout) {
                call.cancel(true);
                client.close();
                throw new IllegalStateException("CLI agent '" + agentName
                        + "' timed out after " + effectiveTimeout + " seconds", timeout);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("CLI agent '" + agentName + "' failed", cause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                call.cancel(true);
                client.close();
                throw new IllegalStateException("CLI agent '" + agentName
                        + "' was interrupted", interrupted);
            } finally {
                callExecutor.shutdownNow();
            }
        }
    }
}
