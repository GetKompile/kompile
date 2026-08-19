/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.core.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves the model catalog exposed by a CLI agent.
 *
 * <p>The registry supplies the command. This class deliberately has no model
 * catalog of its own, so newly added or provider-specific models remain usable
 * without a Kompile release.</p>
 */
public final class CliAgentModelDiscovery {

    private static final long TIMEOUT_SECONDS = 15;

    private CliAgentModelDiscovery() {
    }

    /**
     * Run the agent's configured model-list command.
     *
     * @return the opaque model ids printed by the command, or an empty list when
     *         the agent is unavailable, unauthenticated, or has no list command
     */
    public static List<String> discover(AgentProvider agent) {
        if (agent == null || agent.getModelListCommand() == null
                || agent.getModelListCommand().isEmpty()) {
            return List.of();
        }

        List<String> command = List.copyOf(agent.getModelListCommand());
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "kompile-cli-agent-model-discovery");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        Future<List<String>> output = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            process = processBuilder.start();
            Process runningProcess = process;
            output = readerExecutor.submit(() -> readModels(runningProcess));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            List<String> models = output.get(2, TimeUnit.SECONDS);
            return process.exitValue() == 0 ? models : List.of();
        } catch (IOException | InterruptedException | TimeoutException | java.util.concurrent.ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null) {
                process.destroyForcibly();
            }
            return List.of();
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    private static List<String> readModels(Process process) throws IOException {
        Set<String> models = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseModelListLine(line).ifPresent(models::add);
            }
        }
        return List.copyOf(new ArrayList<>(models));
    }

    /**
     * Parse one opaque model id from a line of CLI output.
     */
    public static Optional<String> parseModelListLine(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String candidate = line.trim();
        if (candidate.isEmpty() || candidate.startsWith("#") || candidate.startsWith("//")) {
            return Optional.empty();
        }

        if (candidate.startsWith("-") || candidate.startsWith("*") || candidate.startsWith("•")) {
            candidate = candidate.substring(1).trim();
        }
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.startsWith("no models available")
                || lower.startsWith("use /login")
                || lower.startsWith("see:")
                || lower.startsWith("error")
                || lower.startsWith("warning")
                || lower.startsWith("available models")
                || lower.startsWith("model id")
                || lower.equals("provider")
                || lower.startsWith("provider:")) {
            return Optional.empty();
        }

        if (candidate.startsWith("/") || candidate.startsWith("~/")
                || candidate.startsWith("./") || candidate.startsWith("../")) {
            return Optional.empty();
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file:")) {
            return Optional.empty();
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".html")
                || lower.endsWith(".json") || lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return Optional.empty();
        }
        if (candidate.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }

        return Optional.of(candidate);
    }
}
