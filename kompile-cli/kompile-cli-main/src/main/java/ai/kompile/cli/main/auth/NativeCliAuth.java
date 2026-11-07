/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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

    /** The OAuth provider id the agy sign-in flow stores its Google credential under. */
    private static final String GEMINI_OAUTH_PROVIDER = "gemini";

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

    /**
     * Antigravity's account state: whether the CLI has a usable cached Google
     * Code Assist credential and the active Google account label (never token
     * material). No model turn runs; {@code agy models} is the zero-cost probe.
     */
    public record AgyAccountState(boolean signedIn, String account) {
        /** Display label for the active account. */
        public String summary() {
            return account == null || account.isBlank()
                    ? "an Antigravity profile" : account;
        }
    }

    /** The CLI's current Antigravity account state (see {@link AgyAccountState}). */
    public static AgyAccountState agyState(String providerId) {
        return agySignInState(providerId);
    }

    private static int execute(String providerId, Action action) {
        Optional<AgentProvider> definition = resolve(providerId);
        if (definition.isEmpty()) {
            System.err.println("No native CLI authentication is registered for " + providerId + ".");
            return 2;
        }

        // Antigravity has no `auth` subcommand: its login is Kompile's own Google
        // OAuth flow (a login URL + local callback + stored credential), logout
        // points at the stored-credential command, and LIST probes the CLI's
        // cached credential. Route the three actions through that contract
        // instead of appending an action verb.
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
     * Antigravity's authentication actions. LOGIN always opens Kompile's own
     * Google OAuth flow — a login URL, a local callback, and a stored Gemini
     * credential — the same surface every other subscription vendor gets; it
     * never merely attaches to a TUI. LOGOUT clears the stored credential by
     * pointing at {@code kompile auth logout gemini}; agy's own cached login
     * clears inside its TUI ({@code /logout}). LIST probes the CLI's cached
     * credential state with its zero-cost {@code agy models} report.
     */
    private static int executeAgy(String providerId, Action action) {
        switch (action) {
            case LOGIN -> {
                try {
                    OAuthCredentialManager.create().login(GEMINI_OAUTH_PROVIDER,
                            new OAuthProviderFlow.LoginOptions(null, false, null, null),
                            terminalInteraction());
                    System.out.println("Signed in — the Google OAuth credential is stored for the"
                            + " Gemini vendor; chat turns run through the `agy` CLI.");
                    return 0;
                } catch (IOException e) {
                    System.err.println("Google sign-in failed: " + e.getMessage());
                    return 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Google sign-in was interrupted.");
                    return 130;
                }
            }
            case LOGOUT -> {
                System.out.println("Clear the stored Google OAuth credential with"
                        + " `kompile auth logout gemini`.");
                System.out.println("agy's own cached login clears inside its TUI:"
                        + " run `agy`, type /logout, quit.");
                return 0;
            }
            case LIST -> {
                AgyAccountState session = agySignInState(providerId);
                if (!session.signedIn()) {
                    System.out.println("agy: not authenticated — run `kompile auth login agy`");
                    System.out.println("(Google sign-in), or choose the Gemini OAuth route in the wizard.");
                    return 3;
                }
                System.out.println("agy: authenticated (Google account: " + session.summary() + ")");
                return 0;
            }
        }
        return 2;
    }

    /**
     * Probes Antigravity's sign-in state through {@code agy models} — the CLI's
     * own zero-model-turn listing, which fails only when no usable Google Code
     * Assist credential is cached for this user. The account label comes from
     * agy's {@code google_accounts.json}, read without touching token material.
     */
    private static AgyAccountState agySignInState(String providerId) {
        try {
            Process process = new ProcessBuilder(
                    List.of(definitionCommand(providerId), "models"))
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new AgyAccountState(false, null);
            }
            if (process.exitValue() != 0 || output.isEmpty()) {
                return new AgyAccountState(false, null);
            }
            return new AgyAccountState(true, agyCachedAccount());
        } catch (IOException spawnOrReadFailure) {
            return new AgyAccountState(false, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new AgyAccountState(false, null);
        }
    }

    /** The Google account label agy caches for this user, or null. */
    private static String agyCachedAccount() {
        try {
            Path accounts = Path.of(System.getProperty("user.home"),
                    ".gemini", "google_accounts.json");
            if (!Files.isRegularFile(accounts)) {
                return null;
            }
            JsonNode root = new ObjectMapper().readTree(Files.readString(accounts));
            JsonNode active = root.path("active");
            return active.isValueNode() && !active.asText().isBlank()
                    ? active.asText() : null;
        } catch (Exception readFailure) {
            return null;
        }
    }

    /** Terminal interaction for the Google OAuth flow: prints the URL, opens the browser. */
    private static OAuthProviderFlow.Interaction terminalInteraction() {
        return new OAuthProviderFlow.Interaction() {
            @Override
            public void info(String message) {
                System.out.println(message);
            }

            @Override
            public void authorizationUrl(URI url, String instructions) {
                System.out.println(instructions);
                System.out.println("Google sign-in URL:");
                System.out.println(url);
                try {
                    new ProcessBuilder("xdg-open", url.toString())
                            .redirectErrorStream(true).start();
                } catch (IOException browserFailure) {
                    System.out.println("(Could not open a browser automatically"
                            + " — paste the URL above.)");
                }
            }

            @Override
            public void deviceCode(String userCode, URI verificationUri,
                    Integer intervalSeconds, Integer expiresInSeconds) {
                System.out.println("Visit " + verificationUri + " and enter code " + userCode);
            }

            @Override
            public String prompt(String message) throws IOException {
                System.out.println(message);
                return new java.io.BufferedReader(new java.io.InputStreamReader(
                        System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
            }
        };
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
