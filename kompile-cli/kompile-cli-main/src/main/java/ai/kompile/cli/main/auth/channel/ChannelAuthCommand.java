/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelConnectionUpdate;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.app.AppClientMixin;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.Console;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/** Manage server-owned Slack, Telegram, email, Discord, and WhatsApp connections. */
@Command(name = "channel", aliases = "channels", mixinStandardHelpOptions = true,
        description = "Manage authenticated external channel connections.",
        subcommands = {
                CommandLine.HelpCommand.class,
                ChannelAuthCommand.Providers.class,
                ChannelAuthCommand.AuthStatus.class,
                ChannelAuthCommand.Login.class,
                ChannelAuthCommand.Engines.class,
                ChannelAuthCommand.WebLogin.class,
                ChannelAuthCommand.Connect.class,
                ChannelAuthCommand.ListConnections.class,
                ChannelAuthCommand.Status.class,
                ChannelAuthCommand.Configure.class,
                ChannelAuthCommand.Rotate.class,
                ChannelAuthCommand.Enable.class,
                ChannelAuthCommand.Disable.class,
                ChannelAuthCommand.Test.class,
                ChannelAuthCommand.Run.class,
                ChannelAuthCommand.Telegram.class,
                ChannelAuthCommand.Disconnect.class
        })
public final class ChannelAuthCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Command(name = "engines", description = "List inbound chat engines and readiness.",
            mixinStandardHelpOptions = true)
    static final class Engines extends Base {
        @Override
        public Integer call() {
            return withClient(client -> {
                List<ChannelEngineDescriptor> engines = client.engines();
                if (app.isJsonOutput()) {
                    json(engines);
                } else {
                    System.out.printf("%-16s %-12s %s%n", "ENGINE", "STATUS", "DESCRIPTION");
                    engines.forEach(engine -> System.out.printf("%-16s %-12s %s%n",
                            engine.engine(), engine.available() ? "ready" : engine.status(),
                            engine.description()));
                }
                return engines.stream().anyMatch(ChannelEngineDescriptor::available) ? 0 : 1;
            });
        }
    }

    @Command(name = "web-login",
            description = "Mint a one-time code for the admin web channel console.",
            mixinStandardHelpOptions = true)
    static final class WebLogin extends Base {
        @Override
        public Integer call() {
            return withClient(client -> {
                var login = client.browserLogin();
                if (app.isJsonOutput()) {
                    json(login);
                } else {
                    System.out.println("One-time web login code (expires " + login.expiresAt() + "):");
                    System.out.println(login.code());
                    System.out.println("Enter it in Agent Hub → Channels. It can be used only once.");
                }
                return 0;
            });
        }
    }

    abstract static class Base implements Callable<Integer> {
        @CommandLine.Mixin
        AppClientMixin app = new AppClientMixin();

        final Integer withClient(Action action) {
            KompileHttpClient http = app.requireClient();
            if (http == null) {
                return 1;
            }
            try {
                return action.run(new ChannelControlPlaneClient(http));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Channel command was interrupted.");
                return 130;
            } catch (Exception e) {
                System.err.println("Channel command failed: " + safeMessage(e));
                return 1;
            }
        }

        final void json(Object value) throws Exception {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        }

        @FunctionalInterface
        interface Action {
            Integer run(ChannelControlPlaneClient client) throws Exception;
        }
    }

    @Command(name = "providers", description = "List channel providers installed on the server.",
            mixinStandardHelpOptions = true)
    static final class Providers extends Base {
        @Override
        public Integer call() {
            return withClient(client -> {
                List<ChannelProviderDescriptor> providers = client.providers();
                if (app.isJsonOutput()) {
                    json(providers);
                } else {
                    for (ChannelProviderDescriptor provider : providers) {
                        System.out.printf("%-12s %-16s %s%n",
                                provider.id(),
                                provider.capabilities().stream()
                                        .map(capability -> capability.name().toLowerCase(Locale.ROOT))
                                        .sorted()
                                        .collect(java.util.stream.Collectors.joining(",")),
                                provider.description());
                    }
                }
                return 0;
            });
        }
    }

    @Command(name = "auth-status", description = "Show non-secret channel authentication readiness.",
            mixinStandardHelpOptions = true)
    static final class AuthStatus extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER")
        String providerId;

        @Override
        public Integer call() {
            return withClient(client -> {
                if (providerId != null) {
                    var auth = client.providerAuth(providerId);
                    json(auth);
                    return auth.credentialReady() ? 0 : 1;
                }
                var statuses = new ArrayList<>();
                for (ChannelProviderDescriptor provider : client.providers()) {
                    statuses.add(client.providerAuth(provider.id()));
                }
                json(statuses);
                return 0;
            });
        }
    }

    @Command(name = "login",
            description = "Interactively authenticate, configure, enable, and test a channel.",
            mixinStandardHelpOptions = true)
    static final class Login extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER",
                description = "Optional provider for direct OAuth login; omit to use the wizard.")
        String providerId;

        @Override
        public Integer call() {
            return withClient(client -> {
                if (providerId == null || providerId.isBlank()) {
                    ChannelSetupWizard.SetupOutcome outcome =
                            ChannelSetupWizard.runWithClient(client);
                    if (outcome == null) {
                        return Thread.currentThread().isInterrupted() ? 130 : 1;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return 130;
                    }
                    return outcome.connection().enabled() && !outcome.running() ? 1 : 0;
                }
                var auth = client.providerAuth(providerId);
                if (!auth.loginSupported()) {
                    if (app.isJsonOutput()) json(auth);
                    else System.out.println(auth.guidance());
                    return 1;
                }
                var response = client.initiateOAuthLogin(providerId);
                if (app.isJsonOutput()) {
                    json(response);
                } else {
                    System.out.println("Open this authorization URL:");
                    System.out.println(response.path("authorizationUrl").asText());
                    System.out.println("After approval, run `kompile auth channel auth-status "
                            + providerId + "`.");
                }
                return response.path("authorizationUrl").asText().isBlank() ? 1 : 0;
            });
        }
    }

    @Command(name = "connect", description = "Create and optionally enable a named channel connection.",
            mixinStandardHelpOptions = true)
    static final class Connect extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER")
        String providerId;

        @Option(names = "--name", description = "Stable connection name (defaults to the provider id).")
        String name;

        @Option(names = "--agent", description = "Agent handling inbound messages (default: jarvis).")
        String agentId;

        @Option(names = "--engine", defaultValue = "REACT",
                description = "Inbound engine: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
        ChannelChatEngine engine;

        @Option(names = "--model", description = "Optional model override for compatible engines.")
        String model;

        @Option(names = "--set", paramLabel = "KEY=VALUE", description = "Provider setting; repeatable.")
        Map<String, String> settings = new LinkedHashMap<>();

        @Option(names = "--oauth",
                description = "Use the connected provider OAuth token where channel-capable (currently Slack bot token).")
        boolean oauth;

        @Option(names = "--secret-from-env", paramLabel = "FIELD=ENV", description = "Read a secret from an environment variable.")
        Map<String, String> secretEnvironment = new LinkedHashMap<>();

        @Option(names = "--secret-file", paramLabel = "FIELD=PATH", description = "Read a secret from a restricted file.")
        Map<String, Path> secretFiles = new LinkedHashMap<>();

        @Option(names = "--secret-stdin", paramLabel = "FIELD", description = "Read one secret line from stdin; repeatable.")
        List<String> secretStdin = new ArrayList<>();

        @Option(names = "--enable", description = "Enable the connection after saving it.")
        boolean enable;

        @Option(names = "--non-interactive", description = "Fail instead of prompting for missing values.")
        boolean nonInteractive;

        @Override
        public Integer call() {
            return withClient(client -> {
                List<ChannelProviderDescriptor> providers = client.providers();
                ChannelProviderDescriptor provider = chooseProvider(providers, providerId, nonInteractive);
                String connectionName = name == null || name.isBlank() ? provider.id() : name;
                if (oauth && !"slack".equals(provider.id())) {
                    throw new IllegalArgumentException(
                            "Channel OAuth credentials are currently supported only for Slack");
                }
                Map<String, Object> parsedSettings = new LinkedHashMap<>(
                        parseSettings(provider, settings, !nonInteractive));
                if (oauth) parsedSettings.put("useOAuth", true);
                Map<String, String> secrets = SecretInputResolver.system().resolve(
                        provider, secretEnvironment, secretFiles, secretStdin, !nonInteractive);
                ChannelConnectionView created = client.create(new ChannelConnectionRequest(
                        connectionName, provider.id(), engine, agentId, model,
                        parsedSettings, secrets, enable));
                printConnection(created, app.isJsonOutput());
                return created.runtimeState() == ChannelConnectionView.RuntimeState.ERROR ? 1 : 0;
            });
        }
    }

    @Command(name = "list", aliases = "ls", description = "List configured channel connections.",
            mixinStandardHelpOptions = true)
    static final class ListConnections extends Base {
        @Override
        public Integer call() {
            return withClient(client -> {
                List<ChannelConnectionView> connections = client.connections();
                if (app.isJsonOutput()) {
                    json(connections);
                } else if (connections.isEmpty()) {
                    System.out.println("No channel connections configured.");
                } else {
                    System.out.printf("%-20s %-12s %-14s %-10s %-16s %s%n",
                            "NAME", "PROVIDER", "ENGINE", "ENABLED", "STATE", "AGENT");
                    connections.forEach(connection -> System.out.printf(
                            "%-20s %-12s %-14s %-10s %-16s %s%n",
                            connection.name(), connection.providerId(), connection.engine(),
                            connection.enabled(), connection.runtimeState(), connection.agentId()));
                }
                return 0;
            });
        }
    }

    @Command(name = "status", description = "Show one channel connection without revealing secrets.",
            mixinStandardHelpOptions = true)
    static final class Status extends Base {
        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Override
        public Integer call() {
            return withClient(client -> {
                printConnection(client.connection(name), app.isJsonOutput());
                return 0;
            });
        }
    }

    @Command(name = "configure", description = "Update non-secret settings for a connection.",
            mixinStandardHelpOptions = true)
    static final class Configure extends Base {
        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Option(names = "--agent")
        String agentId;

        @Option(names = "--engine", description = "Replace the inbound chat engine.")
        ChannelChatEngine engine;

        @Option(names = "--model", description = "Replace/clear the model override.")
        String model;

        @Option(names = "--set", paramLabel = "KEY=VALUE")
        Map<String, String> settings = new LinkedHashMap<>();

        @Option(names = "--enable")
        boolean enable;

        @Option(names = "--disable")
        boolean disable;

        @Override
        public Integer call() {
            if (enable && disable) {
                System.err.println("Use only one of --enable or --disable.");
                return 2;
            }
            return withClient(client -> {
                ChannelConnectionView current = client.connection(name);
                ChannelProviderDescriptor provider = requireProvider(client.providers(), current.providerId());
                Map<String, Object> parsed = parseSettings(provider, settings, false);
                Boolean enabled = enable ? Boolean.TRUE : disable ? Boolean.FALSE : null;
                if (engine == null && agentId == null && model == null
                        && parsed.isEmpty() && enabled == null) {
                    throw new IllegalArgumentException("No configuration change was supplied");
                }
                ChannelConnectionView updated = client.update(name,
                        new ChannelConnectionUpdate(engine, agentId, model,
                                parsed, Map.of(), enabled));
                printConnection(updated, app.isJsonOutput());
                return updated.runtimeState() == ChannelConnectionView.RuntimeState.ERROR ? 1 : 0;
            });
        }
    }

    @Command(name = "rotate", description = "Rotate selected connection secrets.",
            mixinStandardHelpOptions = true)
    static final class Rotate extends Base {
        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Option(names = "--secret-from-env", paramLabel = "FIELD=ENV")
        Map<String, String> secretEnvironment = new LinkedHashMap<>();

        @Option(names = "--secret-file", paramLabel = "FIELD=PATH")
        Map<String, Path> secretFiles = new LinkedHashMap<>();

        @Option(names = "--secret-stdin", paramLabel = "FIELD")
        List<String> secretStdin = new ArrayList<>();

        @Override
        public Integer call() {
            return withClient(client -> {
                ChannelConnectionView current = client.connection(name);
                ChannelProviderDescriptor provider = requireProvider(client.providers(), current.providerId());
                Map<String, String> secrets = SecretInputResolver.system().resolve(
                        provider, secretEnvironment, secretFiles, secretStdin, false);
                if (secrets.isEmpty()) {
                    throw new IllegalArgumentException("No replacement secret was supplied");
                }
                ChannelConnectionView updated = client.update(name,
                        new ChannelConnectionUpdate(null, null, null,
                                Map.of(), secrets, null));
                printConnection(updated, app.isJsonOutput());
                return updated.runtimeState() == ChannelConnectionView.RuntimeState.ERROR ? 1 : 0;
            });
        }
    }

    abstract static class NamedAction extends Base {
        @Parameters(index = "0", paramLabel = "NAME")
        String name;
    }

    @Command(name = "enable", description = "Persistently enable a connection.", mixinStandardHelpOptions = true)
    static final class Enable extends NamedAction {
        @Override
        public Integer call() {
            return withClient(client -> {
                ChannelConnectionView view = client.enable(name);
                printConnection(view, app.isJsonOutput());
                return view.runtimeState() == ChannelConnectionView.RuntimeState.ERROR ? 1 : 0;
            });
        }
    }

    @Command(name = "disable", description = "Persistently disable a connection.", mixinStandardHelpOptions = true)
    static final class Disable extends NamedAction {
        @Override
        public Integer call() {
            return withClient(client -> {
                printConnection(client.disable(name), app.isJsonOutput());
                return 0;
            });
        }
    }

    @Command(name = "test", description = "Send a test message through a running connection.",
            mixinStandardHelpOptions = true)
    static final class Test extends NamedAction {
        @Option(names = "--target", required = true, description = "Provider target (channel, chat, address, or phone).")
        String target;

        @Option(names = "--message", description = "Test message text.")
        String message;

        @Override
        public Integer call() {
            return withClient(client -> {
                var result = client.test(name, new ChannelTestRequest(target, message));
                if (app.isJsonOutput()) {
                    json(result);
                } else {
                    System.out.println(result.message());
                }
                return result.accepted() ? 0 : 1;
            });
        }
    }

    @Command(name = "run", aliases = "start",
            description = "Verify the selected engine, enable the connection, and wait for readiness.",
            mixinStandardHelpOptions = true)
    static final class Run extends NamedAction {
        @Option(names = "--wait-seconds", defaultValue = "30",
                description = "Seconds to wait for RUNNING state (default: ${DEFAULT-VALUE}).")
        int waitSeconds;

        @Override
        public Integer call() {
            if (waitSeconds < 0 || waitSeconds > 300) {
                System.err.println("--wait-seconds must be between 0 and 300.");
                return 2;
            }
            return withClient(client -> {
                ChannelConnectionView current = client.connection(name);
                ChannelEngineDescriptor engine = client.engines().stream()
                        .filter(candidate -> candidate.engine() == current.engine())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Selected channel engine is not installed: " + current.engine()));
                if (!engine.available()) {
                    throw new IllegalStateException(
                            "Selected channel engine is not ready: " + engine.status());
                }

                ChannelConnectionView view = client.enable(name);
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(waitSeconds);
                while (view.runtimeState() == ChannelConnectionView.RuntimeState.STARTING
                        && System.nanoTime() < deadline) {
                    Thread.sleep(250L);
                    view = client.connection(name);
                }
                printConnection(view, app.isJsonOutput());
                if (view.runtimeState() == ChannelConnectionView.RuntimeState.RUNNING) {
                    if (!app.isJsonOutput()) {
                        System.out.println("Inbound messages now route to " + view.engine()
                                + " agent '" + view.agentId() + "'.");
                    }
                    return 0;
                }
                return 1;
            });
        }
    }

    @Command(name = "telegram", description = "Pair, diagnose, and take over Telegram bots.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    CommandLine.HelpCommand.class,
                    Telegram.Pair.class,
                    Telegram.PairStatus.class,
                    Telegram.Approve.class,
                    Telegram.Cancel.class,
                    Telegram.Diagnostics.class,
                    Telegram.Webhook.class,
                    Telegram.DeleteWebhook.class
            })
    static final class Telegram implements Callable<Integer> {
        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        abstract static class TelegramAction extends Base {
            @Parameters(index = "0", paramLabel = "NAME")
            String name;
        }

        abstract static class PairingAction extends TelegramAction {
            @Parameters(index = "1", paramLabel = "PAIRING_ID")
            String pairingId;
        }

        @Command(name = "pair", description = "Create a one-time Telegram pairing challenge.",
                mixinStandardHelpOptions = true)
        static final class Pair extends TelegramAction {
            @Override
            public Integer call() {
                return withClient(client -> {
                    var pairing = client.startTelegramPairing(name);
                    if (app.isJsonOutput()) {
                        json(pairing);
                    } else {
                        System.out.println("Pairing ID: " + pairing.pairingId());
                        System.out.println("Send this command to the bot within 10 minutes:");
                        System.out.println("  " + pairing.command());
                        System.out.println("The one-time code is not stored in plaintext.");
                    }
                    return 0;
                });
            }
        }

        @Command(name = "status", description = "Show a Telegram pairing candidate.",
                mixinStandardHelpOptions = true)
        static final class PairStatus extends PairingAction {
            @Override
            public Integer call() {
                return withClient(client -> {
                    var pairing = client.telegramPairing(name, pairingId);
                    if (app.isJsonOutput()) {
                        json(pairing);
                    } else {
                        System.out.println("Status: " + pairing.status());
                        if (pairing.candidate() != null) {
                            var candidate = pairing.candidate();
                            System.out.println("Chat ID: " + candidate.chatId());
                            System.out.println("Chat type: " + candidate.chatType());
                            System.out.println("Requested by: " + candidate.displayName()
                                    + " (" + candidate.userId() + ")");
                            if (candidate.authorizesEntireChat()) {
                                System.out.println("Warning: approval authorizes the entire group chat.");
                            }
                        }
                    }
                    return switch (pairing.status()) {
                        case EXPIRED, CANCELLED -> 1;
                        default -> 0;
                    };
                });
            }
        }

        @Command(name = "approve", description = "Approve the exact discovered Telegram chat.",
                mixinStandardHelpOptions = true)
        static final class Approve extends PairingAction {
            @Option(names = "--chat-id", required = true,
                    description = "Expected chat id (TOCTOU confirmation).")
            long chatId;

            @Override
            public Integer call() {
                return withClient(client -> {
                    ChannelConnectionView view = client.approveTelegramPairing(
                            name, pairingId, chatId);
                    printConnection(view, app.isJsonOutput());
                    return view.runtimeState() == ChannelConnectionView.RuntimeState.ERROR ? 1 : 0;
                });
            }
        }

        @Command(name = "cancel", description = "Cancel a Telegram pairing challenge.",
                mixinStandardHelpOptions = true)
        static final class Cancel extends PairingAction {
            @Override
            public Integer call() {
                return withClient(client -> {
                    client.cancelTelegramPairing(name, pairingId);
                    if (app.isJsonOutput()) json(Map.of("cancelled", pairingId));
                    else System.out.println("Cancelled Telegram pairing " + pairingId + ".");
                    return 0;
                });
            }
        }

        @Command(name = "diagnostics", description = "Show Telegram poller and checkpoint health.",
                mixinStandardHelpOptions = true)
        static final class Diagnostics extends TelegramAction {
            @Override
            public Integer call() {
                return withClient(client -> {
                    var diagnostics = client.telegramDiagnostics(name);
                    if (app.isJsonOutput()) {
                        json(diagnostics);
                    } else {
                        System.out.println("Bot:            @" + diagnostics.botUsername()
                                + " (" + diagnostics.botId() + ")");
                        System.out.println("Ready:          " + diagnostics.ready());
                        System.out.println("Poller alive:   " + diagnostics.pollerAlive());
                        System.out.println("Next offset:    " + diagnostics.nextOffset());
                        System.out.println("Last poll:      " + diagnostics.lastSuccessfulPoll());
                        System.out.println("Last update:    " + diagnostics.lastUpdateAt());
                        System.out.println("Failures:       " + diagnostics.consecutiveFailures());
                        System.out.println("Webhook active: " + diagnostics.webhookConfigured());
                        if (diagnostics.lastError() != null) {
                            System.out.println("Last error:     " + diagnostics.lastError());
                        }
                    }
                    return diagnostics.ready() && diagnostics.pollerAlive() ? 0 : 1;
                });
            }
        }

        @Command(name = "webhook", description = "Show redacted Telegram webhook state.",
                mixinStandardHelpOptions = true)
        static final class Webhook extends TelegramAction {
            @Override
            public Integer call() {
                return withClient(client -> {
                    var webhook = client.telegramWebhook(name);
                    if (app.isJsonOutput()) json(webhook);
                    else {
                        System.out.println("Configured:      " + webhook.configured());
                        System.out.println("Host:            " + webhook.host());
                        System.out.println("Pending updates: " + webhook.pendingUpdateCount());
                        if (webhook.lastError() != null) {
                            System.out.println("Last error:      " + webhook.lastError());
                        }
                    }
                    return webhook.configured() ? 1 : 0;
                });
            }
        }

        @Command(name = "delete-webhook", aliases = "take-over",
                description = "Explicitly delete Telegram's webhook so polling can own the bot.",
                mixinStandardHelpOptions = true)
        static final class DeleteWebhook extends TelegramAction {
            @Option(names = "--drop-pending-updates",
                    description = "Ask Telegram to discard queued updates during takeover.")
            boolean dropPendingUpdates;

            @Override
            public Integer call() {
                return withClient(client -> {
                    var webhook = client.deleteTelegramWebhook(name, dropPendingUpdates);
                    if (app.isJsonOutput()) json(webhook);
                    else System.out.println(webhook.configured()
                            ? "Telegram still reports an active webhook."
                            : "Telegram webhook deleted; run the connection to begin polling.");
                    return webhook.configured() ? 1 : 0;
                });
            }
        }
    }

    @Command(name = "disconnect", description = "Disable and permanently remove a connection.",
            mixinStandardHelpOptions = true)
    static final class Disconnect extends NamedAction {
        @Option(names = {"--yes", "-y"}, description = "Skip the confirmation prompt.")
        boolean confirmed;

        @Override
        public Integer call() {
            if (!confirmed && !confirm("Disconnect '" + name + "' and delete its stored secrets? [y/N] ")) {
                System.out.println("Disconnect cancelled.");
                return 0;
            }
            return withClient(client -> {
                client.disconnect(name);
                if (app.isJsonOutput()) {
                    json(Map.of("disconnected", name));
                } else {
                    System.out.println("Disconnected channel connection '" + name + "'.");
                }
                return 0;
            });
        }
    }

    private static ChannelProviderDescriptor chooseProvider(
            List<ChannelProviderDescriptor> providers,
            String requested,
            boolean nonInteractive) {
        if (requested != null && !requested.isBlank()) {
            return requireProvider(providers, requested);
        }
        Console console = System.console();
        if (nonInteractive || console == null) {
            throw new IllegalArgumentException("PROVIDER is required in non-interactive mode");
        }
        System.out.println("Available channel providers:");
        providers.forEach(provider -> System.out.println("  " + provider.id() + " - " + provider.displayName()));
        String selected = console.readLine("Provider: ");
        return requireProvider(providers, selected);
    }

    private static ChannelProviderDescriptor requireProvider(
            List<ChannelProviderDescriptor> providers,
            String providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException("Channel provider is required");
        }
        return providers.stream()
                .filter(provider -> provider.id().equalsIgnoreCase(providerId.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Channel provider is not installed: " + providerId));
    }

    private static Map<String, Object> parseSettings(
            ChannelProviderDescriptor provider,
            Map<String, String> supplied,
            boolean promptRequired) {
        Map<String, ChannelProviderDescriptor.Field> fields = new LinkedHashMap<>();
        provider.settings().forEach(field -> fields.put(field.name(), field));
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            ChannelProviderDescriptor.Field field = fields.get(entry.getKey());
            if (field == null) {
                throw new IllegalArgumentException("Unknown channel setting: " + entry.getKey());
            }
            parsed.put(field.name(), parse(field, entry.getValue()));
        }
        if (promptRequired) {
            Console console = System.console();
            for (ChannelProviderDescriptor.Field field : provider.settings()) {
                if (!field.required() || parsed.containsKey(field.name()) || field.defaultValue() != null) {
                    continue;
                }
                if (console == null) {
                    throw new IllegalArgumentException(
                            "Missing required setting " + field.name() + "; use --set "
                                    + field.name() + "=VALUE");
                }
                String value = console.readLine(field.label() + ": ");
                parsed.put(field.name(), parse(field, value));
            }
        }
        return Map.copyOf(parsed);
    }

    private static Object parse(ChannelProviderDescriptor.Field field, String value) {
        String text = value == null ? "" : value.trim();
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
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid value for " + field.name(), e);
        }
    }

    private static List<String> commaSeparated(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                values.add(item.trim());
            }
        }
        return List.copyOf(values);
    }

    private static void printConnection(ChannelConnectionView connection, boolean json) throws Exception {
        if (json) {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(connection));
            return;
        }
        System.out.println("Name:       " + connection.name());
        System.out.println("Provider:   " + connection.providerId());
        System.out.println("Engine:     " + connection.engine());
        System.out.println("Agent:      " + connection.agentId());
        if (connection.model() != null) {
            System.out.println("Model:      " + connection.model());
        }
        System.out.println("Enabled:    " + connection.enabled());
        System.out.println("State:      " + connection.runtimeState());
        System.out.println("Secrets:    " + String.join(", ", connection.configuredSecrets()));
        if (connection.lastError() != null) {
            System.out.println("Last error: " + connection.lastError());
        }
    }

    private static boolean confirm(String prompt) {
        Console console = System.console();
        if (console == null) {
            System.err.println("No interactive console. Re-run with --yes.");
            return false;
        }
        String answer = console.readLine(prompt);
        return answer != null && ("y".equalsIgnoreCase(answer.trim())
                || "yes".equalsIgnoreCase(answer.trim()));
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
