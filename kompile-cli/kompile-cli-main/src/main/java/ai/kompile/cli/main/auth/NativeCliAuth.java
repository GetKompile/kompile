/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Delegates authentication to a CLI agent's native credential manager.
 *
 * <p>Native credentials never enter Kompile's credential store. The agent
 * remains responsible for its provider OAuth/API choices, token storage, and
 * provider catalog.</p>
 */
public final class NativeCliAuth {

    public enum Action {
        LOGIN,
        LIST,
        LOGOUT
    }

    private NativeCliAuth() {
    }

    public static boolean isSupported(String providerId) {
        return resolve(providerId).isPresent();
    }

    public static List<AgentProvider> providers() {
        return CliAgentRegistry.loadAll().stream()
                .filter(provider -> provider.getAuthCommand() != null
                        && !provider.getAuthCommand().isEmpty())
                .toList();
    }

    public static int login(String providerId) {
        return execute(providerId, Action.LOGIN);
    }

    public static int list(String providerId) {
        return execute(providerId, Action.LIST);
    }

    public static int logout(String providerId) {
        return execute(providerId, Action.LOGOUT);
    }

    private static int execute(String providerId, Action action) {
        Optional<AgentProvider> definition = resolve(providerId);
        if (definition.isEmpty()) {
            System.err.println("No native CLI authentication is registered for " + providerId + ".");
            return 2;
        }

        // Antigravity has no `auth` subcommand: sign-in is the interactive TUI
        // (silent OS keyring first, then browser OAuth), logout is the in-TUI
        // `/logout` command, and CI runs use GEMINI_API_KEY instead. Route the
        // three actions through that contract instead of appending an action verb.
        String command0 = definition.get().getCommand() == null
                ? "" : definition.get().getCommand().toLowerCase(Locale.ROOT);
        String name0 = definition.get().getName() == null
                ? "" : definition.get().getName().toLowerCase(Locale.ROOT);
        if (command0.contains("agy") || command0.contains("antigravity")
                || name0.contains("agy") || name0.contains("antigravity")) {
            return executeAgy(providerId, action);
        }

        List<String> command = new ArrayList<>(definition.get().getAuthCommand());
        // The registered command is provider-specific (for OpenCode this is
        // `opencode auth`); let the native CLI perform its own provider selection.
        command.add(action.name().toLowerCase(Locale.ROOT));

        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            String binary = command.isEmpty() ? providerId : command.get(0);
            System.err.println("Could not start native authentication for " + providerId
                    + ". Install the '" + binary
                    + "' CLI, ensure it is on PATH, and retry. Details: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Native authentication was interrupted.");
            return 130;
        }
    }

    /**
     * Antigravity's authentication actions. LOGIN attaches this terminal to a
     * fresh interactive {@code agy} session — its first launch performs the
     * silent keyring sign-in and falls back to the browser OAuth loop; the
     * credentials it caches are exactly what headless {@code agy -p} runs use.
     * LOGOUT can only happen inside that TUI ({@code /logout}), so the guidance
     * is printed rather than simulated. LIST probes the cached credential state
     * with the CLI's own zero-cost {@code /model} report (no model turn runs).
     */
    private static int executeAgy(String providerId, Action action) {
        switch (action) {
            case LOGIN -> {
                List<String> command = List.of(definitionCommand(providerId), "--prompt-interactive");
                try {
                    Process process = new ProcessBuilder(command).inheritIO().start();
                    return process.waitFor();
                } catch (IOException e) {
                    System.err.println("Could not start the Antigravity CLI for " + providerId
                            + ". Install the 'agy' CLI, ensure it is on PATH, and retry. Details: "
                            + e.getMessage());
                    return 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Antigravity sign-in was interrupted.");
                    return 130;
                }
            }
            case LOGOUT -> {
                System.out.println("Antigravity signs out inside its interactive session:");
                System.out.println("  1. Run `agy` (or `agy --prompt-interactive`).");
                System.out.println("  2. Type /logout in the prompt box.");
                System.out.println("This clears the cached keyring credentials and local cache.");
                System.out.println("Headless CI runs use GEMINI_API_KEY instead of the keyring profile.");
                return 0;
            }
            case LIST -> {
                List<String> command = List.of(definitionCommand(providerId), "-p", "/model",
                        "--output-format", "json");
                try {
                    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    String output;
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8))) {
                        output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
                    }
                    int exit = process.waitFor();
                    if (exit != 0) {
                        System.out.println("agy: not authenticated (exit " + exit + ")");
                        return exit == 1 ? 3 : exit;
                    }
                    if (output.contains("\"command_result\"") || output.contains("\"command\"")) {
                        int idStart = output.indexOf("\"id\"");
                        System.out.println("agy: authenticated ("
                                + (idStart >= 0
                                        ? "active model " + output.substring(idStart).replaceFirst("\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1")
                                        : "active keyring profile") + ")");
                        return 0;
                    }
                    System.out.println("agy: authenticated");
                    return 0;
                } catch (IOException e) {
                    System.err.println("Could not probe Antigravity authentication for " + providerId
                            + ": " + e.getMessage());
                    return 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Antigravity authentication probe was interrupted.");
                    return 130;
                }
            }
        }
        return 2;
    }

    private static String definitionCommand(String providerId) {
        return resolve(providerId)
                .map(def -> def.getCommand() == null || def.getCommand().isBlank()
                        ? "agy" : def.getCommand())
                .orElse("agy");
    }

    private static Optional<AgentProvider> resolve(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return providers().stream()
                .filter(provider -> providerId.equalsIgnoreCase(provider.getCommand())
                        || providerId.equalsIgnoreCase(provider.getName()))
                .findFirst();
    }
}
