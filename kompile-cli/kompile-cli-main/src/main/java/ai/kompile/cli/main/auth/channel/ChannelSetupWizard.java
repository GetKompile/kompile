/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelTestRequest;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interactive login wizard for kompile channel connections, mirroring the kompile chat
 * setup wizard: a boxed header, numbered menus, and a summary footer. The flow is
 * descriptor-driven from the admin control plane — no per-provider logic:
 *
 * <ol>
 *   <li>Pick a provider</li>
 *   <li>Review non-secret auth readiness; optionally run the provider OAuth sign-in</li>
 *   <li>Pick an installed inbound engine (agent/model when the engine supports them)</li>
 *   <li>Answer the provider's declared non-secret settings</li>
 *   <li>Enter required secrets through a masked prompt</li>
 *   <li>Name the connection, create it, optionally enable and wait for RUNNING</li>
 *   <li>Optionally deliver a test message</li>
 * </ol>
 *
 * <p>Secrets are masked on entry and sent directly to the admin control plane; the CLI
 * never stores them and no wizard output echoes a secret value.</p>
 */
public final class ChannelSetupWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";

    private static final int READINESS_WAIT_SECONDS = 60;
    private static final String CONNECTION_NAME_PATTERN = "[a-z0-9][a-z0-9._-]{0,62}";

    private final ChannelControlPlaneClient client;

    private ChannelSetupWizard(ChannelControlPlaneClient client) {
        this.client = client;
    }

    /** Result of a completed wizard run. */
    public record SetupOutcome(
            String connectionName,
            ChannelConnectionView connection,
            boolean running,
            String testTarget,
            boolean testAccepted,
            String testMessage) {
    }

    /** Run the wizard against the routed admin control plane. Null on cancel/unreachable. */
    public static SetupOutcome run() {
        return run(null);
    }

    /**
     * Run the wizard against an explicit control plane base URL
     * (null/blank routes through the default admin endpoint). Null on cancel/unreachable.
     */
    public static SetupOutcome run(String baseUrl) {
        try {
            ChannelControlPlaneClient client = baseUrl == null || baseUrl.isBlank()
                    ? new ChannelControlPlaneClient((String) null)
                    : new ChannelControlPlaneClient(baseUrl);
            return runWithClient(client);
        } catch (Exception error) {
            System.err.println("Channel setup error: " + safeMessage(error));
            return null;
        }
    }

    /** Run the wizard with a pre-built control plane client (used by the CLI subcommand). */
    public static SetupOutcome runWithClient(ChannelControlPlaneClient controlPlane) {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            return runWithReader(controlPlane, reader);
        } catch (Exception error) {
            System.err.println("Channel setup error: " + safeMessage(error));
            return null;
        } finally {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (Exception error) {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /** Run the wizard with a provided reader (for testing or embedding). */
    public static SetupOutcome runWithReader(ChannelControlPlaneClient controlPlane, LineReader reader) {
        try {
            return new ChannelSetupWizard(controlPlane).execute(reader);
        } catch (Cancelled cancelled) {
            System.out.println();
            if (cancelled.getMessage() != null) {
                System.out.println(YELLOW + "  " + cancelled.getMessage() + RESET);
            } else {
            System.out.println(YELLOW + "  Channel login cancelled." + RESET);
            }
            System.out.println();
            return null;
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Channel setup failed: " + safeMessage(error));
            return null;
        }
    }

    private SetupOutcome execute(LineReader reader) throws Exception {
        List<ChannelProviderDescriptor> providers = client.providers();
        if (providers.isEmpty()) {
            System.out.println(YELLOW + "  No channel providers are installed on the server." + RESET);
            return null;
        }

        printHeader();

        // Step 1: provider
        ChannelProviderDescriptor provider = selectProvider(reader, providers);

        // Step 2: auth readiness + optional OAuth sign-in
        ChannelProviderAuthView auth = client.providerAuth(provider.id());
        printAuthStatus(provider, auth);
        if (auth.loginSupported() && (!auth.oauthConnected() || !auth.channelScopesGranted())) {
            auth = attemptOAuthSignIn(reader, provider, auth);
        }

        // Step 3: engine + agent/model (one readiness fetch feeds both prompts)
        List<ChannelEngineDescriptor> engines = client.engines();
        ChannelChatEngine engine = selectEngine(reader, engines);
        ChannelEngineDescriptor engineDescriptor = engines.stream()
                .filter(candidate -> candidate.engine() == engine)
                .findFirst()
                .orElse(null);
        String agentId = engineDescriptor != null && engineDescriptor.supportsAgent()
                ? promptText(reader, "  Agent handling inbound messages [jarvis]: ", "jarvis")
                : "jarvis";
        String model = engineDescriptor != null && engineDescriptor.supportsModel()
                ? promptOptionalText(reader, "  Model override (or Enter for default): ")
                : null;

        // Step 4: non-secret settings
        Map<String, Object> settings = collectSettings(reader, provider, auth);

        // Step 5: secrets
        Map<String, String> secrets = collectSecrets(reader, provider, auth, settings);

        // Step 6: name + create
        String name = promptConnectionName(reader, provider);
        boolean enableNow = promptYesNo(reader, "Enable and start the connection right away?", true);
        if (!enableNow) {
            System.out.println();
            System.out.println(DIM + "  Creating the connection disabled. Run 'kompile auth channel run "
                    + name + "' when you are ready." + RESET);
        }
        ChannelConnectionView connection = client.create(new ChannelConnectionRequest(
                name, provider.id(), engine, agentId, model, settings, secrets, enableNow));
        printConnectionSummary(connection);

        // Step 7: wait for readiness when enabled
        boolean running = false;
        if (enableNow) {
            if (connection.runtimeState() == ChannelConnectionView.RuntimeState.STARTING) {
                try {
                    connection = waitForRunning(name);
                    running = connection.runtimeState() == ChannelConnectionView.RuntimeState.RUNNING;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    System.out.println(YELLOW
                            + "  Readiness wait interrupted; the connection remains saved." + RESET);
                } catch (Exception error) {
                    System.out.println(YELLOW + "  Could not read final channel readiness: "
                            + safeMessage(error) + RESET);
                }
            } else {
                running = connection.runtimeState() == ChannelConnectionView.RuntimeState.RUNNING;
                if (!running && connection.lastError() != null) {
                    System.out.println(YELLOW + "  Connection state: " + connection.runtimeState()
                            + " — " + connection.lastError() + RESET);
                }
            }
        }

        // Step 8: optional test delivery
        String testTarget = null;
        boolean testAccepted = false;
        String testMessage = null;
        try {
            if (running && promptYesNo(reader, "Send a test message now?", false)) {
                testTarget = promptOptionalText(reader, "  Target (channel, chat, address, or phone): ");
                if (testTarget != null && !testTarget.isBlank()) {
                    try {
                        var result = client.test(name, new ChannelTestRequest(testTarget.trim(), null));
                        testAccepted = result.accepted();
                        testMessage = result.message();
                        System.out.println("  " + (result.accepted() ? GREEN + "✓" : YELLOW + "!")
                                + " " + result.message() + RESET);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        testMessage = "Test delivery interrupted; the channel connection remains saved.";
                        System.out.println(YELLOW + "  " + testMessage + RESET);
                    } catch (Exception error) {
                        testMessage = "Test delivery failed: " + safeMessage(error);
                        System.out.println(YELLOW + "  " + testMessage + RESET);
                    }
                }
            }
        } catch (Cancelled cancelled) {
            System.out.println(YELLOW
                    + "  Test delivery skipped; the channel connection is already saved." + RESET);
        }

        System.out.println();
        System.out.println(GREEN + "  ✓ Channel login complete!" + RESET);
        System.out.println();
        System.out.println("  " + DIM + "Status:    kompile auth channel status " + name + RESET);
        System.out.println("  " + DIM + "Re-run:    kompile auth channel login" + RESET);
        System.out.println();
        return new SetupOutcome(name, connection, running, testTarget, testAccepted, testMessage);
    }

    // ── Steps ──────────────────────────────────────────────────────────────

    private ChannelProviderDescriptor selectProvider(
            LineReader reader, List<ChannelProviderDescriptor> providers) {
        List<String> options = providers.stream()
                .map(provider -> provider.displayName() + DIM + " (" + provider.id() + ")" + RESET)
                .toList();
        int selected = selectNumbered(reader, "Select channel provider:", options);
        if (selected < 0) {
            throw Cancelled.INSTANCE;
        }
        ChannelProviderDescriptor provider = providers.get(selected);
        System.out.println("  → " + GREEN + provider.displayName() + RESET);
        System.out.println();
        return provider;
    }

    private ChannelProviderAuthView attemptOAuthSignIn(
            LineReader reader,
            ChannelProviderDescriptor provider,
            ChannelProviderAuthView auth) {
        if (!auth.loginSupported()) {
            System.out.println("  " + auth.guidance());
            System.out.println();
            return auth;
        }
        List<String> options = List.of(
                "Sign in with " + provider.displayName() + " now (shows an authorization URL)",
                "Skip — credentials can be completed later");
        int selected = selectNumbered(reader, "How would you like to authenticate?", options);
        System.out.println();
        if (selected < 0) {
            throw Cancelled.INSTANCE;
        }
        if (selected != 0) {
            return auth;
        }
        try {
            var response = client.initiateOAuthLogin(provider.id());
            String authorizationUrl = response.path("authorizationUrl").asText("");
            if (authorizationUrl.isBlank()) {
                System.out.println(YELLOW
                        + "  The server did not return an authorization URL. "
                        + "Complete provider setup and re-run." + RESET);
                System.out.println();
                return auth;
            }
            System.out.println("  Open this authorization URL in a browser:");
            System.out.println("  " + CYAN + authorizationUrl + RESET);
            promptOptionalText(reader, "  Press Enter once you have approved access... ");
            ChannelProviderAuthView refreshed = client.providerAuth(provider.id());
            if (refreshed.oauthConnected() && refreshed.channelScopesGranted()) {
                System.out.println(GREEN + "  ✓ OAuth authentication complete." + RESET);
                if (!refreshed.missingCredentialFields().isEmpty()) {
                    System.out.println("  Remaining runtime credentials will be requested below: "
                            + String.join(", ", refreshed.missingCredentialFields()));
                }
            } else {
                System.out.println(YELLOW
                        + "  Authentication is not complete yet — you can re-run setup later." + RESET);
            }
            System.out.println();
            return refreshed;
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new Cancelled("Channel authentication was interrupted.");
            }
            System.out.println(YELLOW + "  OAuth sign-in failed: " + safeMessage(error) + RESET);
            System.out.println();
            return auth;
        }
    }

    private ChannelChatEngine selectEngine(LineReader reader, List<ChannelEngineDescriptor> engines) {
        List<ChannelEngineDescriptor> usable = engines.stream()
                .filter(ChannelEngineDescriptor::available)
                .toList();
        if (usable.isEmpty()) {
            System.out.println(YELLOW + "  No inbound chat engine is ready." + RESET);
            for (ChannelEngineDescriptor engine : engines) {
                System.out.println("  " + engine.engine() + ": " + engine.status());
            }
            throw new Cancelled(
                    "Start or install a channel engine, then re-run 'kompile auth channel login'.");
        }
        List<String> options = usable.stream()
                .map(engine -> engine.displayName() + DIM + " — " + engine.description() + RESET)
                .toList();
        int selected = selectNumbered(reader, "Select inbound chat engine:", options);
        if (selected < 0) {
            throw Cancelled.INSTANCE;
        }
        ChannelChatEngine engine = usable.get(selected).engine();
        System.out.println("  → " + GREEN + engine + RESET);
        System.out.println();
        return engine;
    }

    private Map<String, Object> collectSettings(
            LineReader reader,
            ChannelProviderDescriptor provider,
            ChannelProviderAuthView auth) {
        List<ChannelProviderDescriptor.Field> fields = provider.settings();
        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        System.out.println(BOLD + "  Connection settings for " + provider.displayName() + RESET);
        System.out.println();
        for (ChannelProviderDescriptor.Field field : fields) {
            Object defaultValue = settingDefault(field, auth);
            String description = field.description() == null ? "" : field.description().trim();
            if (!description.isEmpty()) {
                System.out.println("  " + DIM + field.name() + " — " + description + RESET);
            }
            String prompt = "  " + field.label();
            if (defaultValue != null) {
                prompt += " [" + defaultValue + "]";
            }
            prompt += ": ";
            String raw = readLine(reader, prompt);
            String text = raw == null ? "" : raw.trim();
            if (text.isEmpty() && defaultValue != null) {
                settings.put(field.name(), defaultValue);
                continue;
            }
            if (text.isEmpty()) {
                if (field.required()) {
                    throw new Cancelled("Required setting missing: " + field.name());
                }
                continue;
            }
            settings.put(field.name(), parseValue(field, text));
        }
        System.out.println();
        return settings;
    }

    private Map<String, String> collectSecrets(
            LineReader reader,
            ChannelProviderDescriptor provider,
            ChannelProviderAuthView auth,
            Map<String, Object> settings) {
        List<ChannelProviderDescriptor.Field> fields = provider.secrets();
        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        System.out.println(BOLD + "  Credentials for " + provider.displayName() + RESET);
        System.out.println("  " + DIM + "Secrets are masked and sent straight to the server; "
                + "nothing is stored locally." + RESET);
        System.out.println();
        for (ChannelProviderDescriptor.Field field : fields) {
            String source = auth.credentialSources().get(field.name());
            boolean ignoredOAuthSource = source != null
                    && source.startsWith("oauth:")
                    && !Boolean.TRUE.equals(settings.get("useOAuth"));
            if (ignoredOAuthSource) {
                source = null;
            }
            if (source != null) {
                System.out.println("  " + DIM + "✓ " + field.label() + " is already resolved from "
                        + source + " (Enter to reuse)" + RESET);
            }
            String prompt = "  " + field.label();
            if (field.environmentHint() != null && !field.environmentHint().isBlank()) {
                prompt += DIM + " (env: " + field.environmentHint() + ")" + RESET;
            }
            prompt += ": ";
            String value = readMasked(reader, prompt);
            if (value == null || value.isBlank()) {
                boolean runtimeRequired = auth.missingCredentialFields().contains(field.name())
                        || ignoredOAuthSource;
                if ((field.required() || runtimeRequired) && source == null) {
                    throw new Cancelled("Required credential missing: " + field.name());
                }
                continue;
            }
            secrets.put(field.name(), value.trim());
        }
        System.out.println();
        return secrets;
    }

    private String promptConnectionName(LineReader reader, ChannelProviderDescriptor provider) {
        while (true) {
            String raw = readLine(reader, "  Connection name [" + provider.id() + "]: ");
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                return provider.id();
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.matches(CONNECTION_NAME_PATTERN)) {
                return normalized;
            }
            System.out.println("  " + YELLOW + "Names are 1-63 chars: lowercase letters, digits, "
                    + "'.', '_', '-' (starting alphanumeric)." + RESET);
        }
    }

    private ChannelConnectionView waitForRunning(String name) throws Exception {
        System.out.print("  Waiting for the connection to become RUNNING ");
        System.out.flush();
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(READINESS_WAIT_SECONDS);
        ChannelConnectionView view = client.connection(name);
        while (view.runtimeState() == ChannelConnectionView.RuntimeState.STARTING
                && System.nanoTime() < deadline) {
            Thread.sleep(250L);
            System.out.print(".");
            System.out.flush();
            view = client.connection(name);
        }
        System.out.println();
        if (view.runtimeState() == ChannelConnectionView.RuntimeState.RUNNING) {
            System.out.println(GREEN + "  ✓ Connection is RUNNING." + RESET);
            return view;
        }
        System.out.println(YELLOW + "  Connection state: " + view.runtimeState()
                + (view.lastError() == null ? "" : " — " + view.lastError()) + RESET);
        System.out.println();
        return view;
    }

    // ── Output helpers ─────────────────────────────────────────────────────

    private void printHeader() {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
        System.out.println(BOLD + CYAN + "  │      Kompile Channel Login           │" + RESET);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
        System.out.println("  " + DIM + "(Admin control plane: " + client.adminUrl() + ")" + RESET);
        System.out.println();
    }

    private void printAuthStatus(ChannelProviderDescriptor provider, ChannelProviderAuthView auth) {
        System.out.println(BOLD + "  Authentication status: " + provider.displayName() + RESET);
        if (auth.credentialReady()) {
            System.out.println(GREEN + "  ✓ Credentials are ready on the server." + RESET);
        } else {
            System.out.println(YELLOW + "  ! Credentials are not ready yet." + RESET);
            if (!auth.missingCredentialFields().isEmpty()) {
                System.out.println("  Missing: " + String.join(", ", auth.missingCredentialFields()));
            }
            if (!auth.loginSupported()) {
                System.out.println("  " + auth.guidance());
            }
        }
        System.out.println();
    }

    private void printConnectionSummary(ChannelConnectionView connection) {
        System.out.println();
        System.out.println(GREEN + "  Connection saved!" + RESET);
        System.out.println("  Name:     " + BOLD + connection.name() + RESET);
        System.out.println("  Provider: " + connection.providerId());
        System.out.println("  Engine:   " + connection.engine());
        System.out.println("  Agent:    " + connection.agentId());
        if (connection.model() != null) {
            System.out.println("  Model:    " + connection.model());
        }
        System.out.println("  Enabled:  " + connection.enabled());
        System.out.println("  State:    " + connection.runtimeState());
        if (!connection.configuredSecrets().isEmpty()) {
            System.out.println("  Secrets:  " + String.join(", ", connection.configuredSecrets()));
        }
        System.out.println();
    }

    // ── Input utilities (same UX contract as the chat/enforcer wizards) ────

    static int selectNumbered(LineReader reader, String title, List<String> items) {
        System.out.println(BOLD + "  " + title + RESET);
        System.out.println();
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, items.get(i));
        }
        System.out.println();
        while (true) {
            String input;
            try {
                input = reader.readLine("  Choice (1-" + items.size() + ", or Ctrl+C to cancel): ");
            } catch (Exception error) {
                return -1;
            }
            if (input == null) {
                return -1;
            }
            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("q")
                    || trimmed.equalsIgnoreCase("quit")
                    || trimmed.equalsIgnoreCase("cancel")) {
                return -1;
            }
            if (trimmed.isEmpty()) {
                System.out.println("  " + YELLOW + "Please make an explicit selection." + RESET);
                continue;
            }
            try {
                int number = Integer.parseInt(trimmed);
                if (number >= 1 && number <= items.size()) {
                    return number - 1;
                }
            } catch (NumberFormatException ignored) {
                // fall through to partial name matching
            }
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).toLowerCase(Locale.ROOT).contains(trimmed.toLowerCase(Locale.ROOT))) {
                    matches.add(i);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                System.out.println("  " + YELLOW + "That matches multiple choices; be more specific."
                        + RESET);
                continue;
            }
            System.out.println("  " + YELLOW + "Please enter a number 1-" + items.size()
                    + " or type part of the name" + RESET);
        }
    }

    /** Read text; blank input resolves to {@code defaultValue}. Null on reader failure. */
    static String promptText(LineReader reader, String prompt, String defaultValue) {
        String value = readLine(reader, prompt);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    /** Read text with no default; returns null for blank input or reader failure. */
    private static String promptOptionalText(LineReader reader, String prompt) {
        return promptText(reader, prompt, null);
    }

    private static boolean promptYesNo(LineReader reader, String question, boolean defaultYes) {
        String answer = promptText(reader,
                "  " + question + (defaultYes ? " [Y/n]: " : " [y/N]: "), null);
        if (answer == null || answer.isBlank()) {
            return defaultYes;
        }
        return answer.toLowerCase(Locale.ROOT).startsWith("y");
    }

    private static String readLine(LineReader reader, String prompt) {
        try {
            String value = reader.readLine(prompt);
            if (value == null) {
                throw Cancelled.INSTANCE;
            }
            return value;
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (Exception error) {
            throw Cancelled.INSTANCE;
        }
    }

    private static String readMasked(LineReader reader, String prompt) {
        try {
            String value = reader.readLine(prompt, '*');
            if (value == null) {
                throw Cancelled.INSTANCE;
            }
            return value;
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (Exception error) {
            throw Cancelled.INSTANCE;
        }
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    private static Object parseValue(ChannelProviderDescriptor.Field field, String text) {
        try {
            return switch (field.type()) {
                case STRING -> text;
                case INTEGER -> Integer.parseInt(text);
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                        throw new IllegalArgumentException("expected true or false");
                    }
                    yield Boolean.parseBoolean(text);
                }
                case STRING_LIST -> commaSeparated(text);
                case LONG_LIST -> commaSeparated(text).stream().map(Long::parseLong).toList();
            };
        } catch (RuntimeException error) {
            throw new Cancelled("Invalid value for " + field.name() + ": " + text);
        }
    }

    private static Object settingDefault(
            ChannelProviderDescriptor.Field field,
            ChannelProviderAuthView auth) {
        if ("useOAuth".equals(field.name())
                && field.type() == ChannelProviderDescriptor.FieldType.BOOLEAN
                && auth.oauthConnected()
                && auth.channelScopesGranted()) {
            return true;
        }
        return field.defaultValue();
    }

    private static List<String> commaSeparated(String value) {
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                values.add(item.trim());
            }
        }
        return List.copyOf(values);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Internal control-flow signal so the wizard can unwind to the same null contract as chat setup. */
    private static final class Cancelled extends RuntimeException {
        private static final Cancelled INSTANCE = new Cancelled();

        private Cancelled() {
            super(null, null, false, false);
        }

        private Cancelled(String message) {
            super(message, null, false, false);
        }
    }
}
