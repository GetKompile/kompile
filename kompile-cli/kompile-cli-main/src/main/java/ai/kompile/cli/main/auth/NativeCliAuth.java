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

        List<String> command = new ArrayList<>(definition.get().getAuthCommand());
        command.add(action.name().toLowerCase(Locale.ROOT));
        if (action == Action.LOGOUT) {
            command.add(providerId);
        }

        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            System.err.println("Could not start native authentication for "
                    + providerId + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Native authentication was interrupted.");
            return 130;
        }
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
