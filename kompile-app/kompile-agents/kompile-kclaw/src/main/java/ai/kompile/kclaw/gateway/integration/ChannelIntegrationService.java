/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelConnectionUpdate;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.channel.api.ChannelConnectionView.RuntimeState;
import ai.kompile.channel.api.ChannelEngineDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.channel.api.ChannelTestResult;
import ai.kompile.channel.api.TelegramDiagnosticsView;
import ai.kompile.channel.api.TelegramPairingApprovalRequest;
import ai.kompile.channel.api.TelegramPairingStartView;
import ai.kompile.channel.api.TelegramPairingView;
import ai.kompile.channel.api.TelegramWebhookInfoView;
import ai.kompile.gateway.core.gateway.channel.DefaultTelegramApiClient;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.oauth.service.TokenEncryptionService;
import ai.kompile.kclaw.gateway.channel.TelegramChannelAdapter;
import ai.kompile.kclaw.gateway.telegram.TelegramPairingRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Owns channel credential persistence and one supervised runtime per named connection. */
public final class ChannelIntegrationService {

    private static final Pattern CONNECTION_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");
    private static final Duration STARTUP_GRACE = Duration.ofSeconds(60);

    private final ChannelConnectionStore store;
    private final ChannelProviderCatalog catalog;
    private final ChannelEngineRegistry engines;
    private final AgentRegistry agentRegistry;
    private final TelegramPairingRegistry pairings;
    private final ChannelRuntimeFactory runtimeFactory;
    private final ChannelManager channelManager;
    private final TokenEncryptionService encryption;
    private final ChannelCredentialResolver credentialResolver;
    private final Map<String, String> runtimeErrors = new ConcurrentHashMap<>();
    private final Map<String, Instant> runtimeStartedAt = new ConcurrentHashMap<>();
    private final Map<String, String> runtimeCredentialRevisions = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService supervisor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "channel-runtime-supervisor");
                thread.setDaemon(true);
                return thread;
            });
    private final java.util.concurrent.atomic.AtomicBoolean supervisorStarted =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ChannelIntegrationService(
            ChannelConnectionStore store,
            ChannelProviderCatalog catalog,
            ChannelEngineRegistry engines,
            AgentRegistry agentRegistry,
            TelegramPairingRegistry pairings,
            ChannelRuntimeFactory runtimeFactory,
            ChannelManager channelManager,
            TokenEncryptionService encryption) {
        this(store, catalog, engines, agentRegistry, pairings, runtimeFactory,
                channelManager, encryption, ChannelCredentialResolver.explicitOnly(catalog));
    }

    public ChannelIntegrationService(
            ChannelConnectionStore store,
            ChannelProviderCatalog catalog,
            ChannelEngineRegistry engines,
            AgentRegistry agentRegistry,
            TelegramPairingRegistry pairings,
            ChannelRuntimeFactory runtimeFactory,
            ChannelManager channelManager,
            TokenEncryptionService encryption,
            ChannelCredentialResolver credentialResolver) {
        this.store = store;
        this.catalog = catalog;
        this.engines = engines;
        this.agentRegistry = agentRegistry;
        this.pairings = pairings;
        this.runtimeFactory = runtimeFactory;
        this.channelManager = channelManager;
        this.encryption = encryption;
        this.credentialResolver = credentialResolver;
    }

    public synchronized void restoreEnabledConnections() {
        store.list().stream()
                .filter(StoredChannelConnection::enabled)
                .forEach(connection -> start(connection, false));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSupervisor() {
        if (!supervisorStarted.compareAndSet(false, true)) return;
        restoreEnabledConnections();
        supervisor.scheduleWithFixedDelay(() -> {
            try {
                restoreEnabledConnections();
            } catch (RuntimeException ignored) {
                // Per-connection errors are retained in runtimeErrors and exposed through status.
            }
        }, 15L, 15L, java.util.concurrent.TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopSupervisor() {
        supervisor.shutdownNow();
    }

    public List<ChannelProviderDescriptor> providers() {
        return catalog.providers();
    }

    public List<ChannelEngineDescriptor> engines() {
        return engines.descriptors();
    }

    /**
     * Runtime credential handoff for authenticated source ingestion. Returns exactly the named
     * connection with the same runtime-resolved secrets (stored, environment, or OAuth-derived)
     * the channel runtime itself uses. Listings and views never carry secrets; this endpoint is
     * the single write-only-input exception, gated by the admin control plane like every other
     * connection operation.
     */
    public ChannelCredentialView credential(String name) {
        StoredChannelConnection connection = require(name);
        return new ChannelCredentialView(
                connection.providerId(),
                resolveCredentials(normalizedForRuntime(connection), decrypt(connection.encryptedSecrets()))
                        .secrets(),
                connection.settings());
    }

    public ChannelProviderAuthView providerAuth(String providerId) {
        return credentialResolver.auth(catalog.normalizeProviderId(providerId));
    }

    public synchronized ChannelConnectionView create(ChannelConnectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Connection request is required");
        }
        String name = normalizeName(request.name());
        if (store.get(name).isPresent()) {
            throw new ChannelConnectionConflictException(
                    "Channel connection already exists: " + name);
        }
        String providerId = catalog.normalizeProviderId(request.providerId());
        Map<String, Object> settings = catalog.normalizeSettings(providerId, request.settings());
        catalog.validateSecrets(providerId, request.secrets());
        String agentId = normalizeAgentId(request.agentId());
        validateAgent(request.engine(), agentId);
        String model = normalizeModel(request.model());
        validateModel(request.engine(), model);
        Instant now = Instant.now();
        StoredChannelConnection connection = new StoredChannelConnection(
                UUID.randomUUID(),
                name,
                providerId,
                request.engine(),
                agentId,
                model,
                request.enabled(),
                settings,
                encrypt(request.secrets()),
                now,
                now);
        validateOAuthBinding(connection);
        resolveCredentials(connection, request.secrets());
        store.save(connection);
        if (connection.enabled() && !start(connection, false)) {
            String error = runtimeErrors.remove(connection.name());
            store.delete(connection.name());
            throw new IllegalStateException(error == null
                    ? "Channel connection could not start"
                    : error);
        }
        return view(connection);
    }

    public synchronized ChannelConnectionView update(String name, ChannelConnectionUpdate update) {
        StoredChannelConnection current = require(name);
        if (update == null) {
            throw new IllegalArgumentException("Connection update is required");
        }
        Map<String, Object> settings = new LinkedHashMap<>(current.settings());
        settings.putAll(update.settings());
        settings = catalog.normalizeSettings(current.providerId(), settings);

        Map<String, String> encryptedSecrets = new LinkedHashMap<>(current.encryptedSecrets());
        if (!update.secrets().isEmpty()) {
            catalog.validateSecretsForUpdate(current.providerId(), update.secrets());
            encryptedSecrets.putAll(encrypt(update.secrets()));
        }
        boolean enabled = update.enabled() == null ? current.enabled() : update.enabled();
        StoredChannelConnection replacement = new StoredChannelConnection(
                current.id(),
                current.name(),
                current.providerId(),
                update.engine() == null ? current.engine() : update.engine(),
                update.agentId() == null ? current.agentId() : normalizeAgentId(update.agentId()),
                update.model() == null ? current.model() : normalizeModel(update.model()),
                enabled,
                settings,
                encryptedSecrets,
                current.createdAt(),
                Instant.now());
        validateAgent(replacement.engine(), replacement.agentId());
        validateModel(replacement.engine(), replacement.model());
        validateOAuthBinding(replacement);
        if (!Boolean.FALSE.equals(update.enabled())) {
            resolveCredentials(replacement, decrypt(encryptedSecrets));
        }
        store.save(replacement);
        if (replacement.enabled()) {
            if (!start(replacement, true)) {
                String error = runtimeErrors.remove(replacement.name());
                store.save(current);
                throw new IllegalStateException(error == null
                        ? "Updated channel connection could not start"
                        : error);
            }
        } else {
            stop(replacement.name());
        }
        return view(replacement);
    }

    public List<ChannelConnectionView> list() {
        return store.list().stream().map(this::view).toList();
    }

    public ChannelConnectionView get(String name) {
        return view(require(name));
    }

    public synchronized ChannelConnectionView enable(String name) {
        StoredChannelConnection current = require(name);
        if (current.enabled() && channelManager.getConnectionAdapter(current.name())
                .filter(ChannelAdapter::isRunning)
                .map(adapter -> adapter.getLastError() == null)
                .orElse(false)) {
            return view(current);
        }
        StoredChannelConnection enabled = withEnabled(current, true);
        store.save(enabled);
        if (!start(enabled, false)) {
            String error = runtimeErrors.remove(enabled.name());
            store.save(current);
            throw new IllegalStateException(error == null
                    ? "Channel connection could not start"
                    : error);
        }
        return view(enabled);
    }

    public synchronized ChannelConnectionView disable(String name) {
        StoredChannelConnection current = require(name);
        StoredChannelConnection disabled = withEnabled(current, false);
        store.save(disabled);
        stop(current.name());
        return view(disabled);
    }

    public synchronized boolean disconnect(String name) {
        StoredChannelConnection current = require(name);
        stop(current.name());
        boolean deleted = store.delete(current.name());
        if ("telegram".equals(current.providerId())) {
            pairings.cancelAll(current.name());
            runtimeFactory.deleteTelegramState(current.name());
        }
        runtimeErrors.remove(current.name());
        return deleted;
    }

    public ChannelTestResult test(String name, ChannelTestRequest request) {
        if (request == null || request.target() == null || request.target().isBlank()) {
            throw new IllegalArgumentException("A test target is required");
        }
        String message = request.message() == null || request.message().isBlank()
                ? "Kompile channel connection test"
                : request.message();
        ChannelAdapter adapter = channelManager.getConnectionAdapter(normalizeName(name))
                .orElseThrow(() -> new IllegalStateException("Channel connection is not running: " + name));
        ChannelAdapter.DeliveryResult result = adapter.send(request.target(), message);
        return new ChannelTestResult(result.accepted(), result.message());
    }

    /** Harness delivery is opt-in and cannot address a target outside the explicit allowlist. */
    public ChannelTestResult deliver(String name, ChannelTestRequest request) {
        StoredChannelConnection connection = require(name);
        if (!Boolean.TRUE.equals(connection.settings().get("allowHarnessSend"))) {
            throw new IllegalStateException(
                    "Harness delivery is disabled for channel connection: " + connection.name());
        }
        if (request == null || !isAllowlistedTarget(connection, request.target())) {
            throw new IllegalArgumentException(
                    "Harness target is not explicitly allowlisted for " + connection.name());
        }
        return test(connection.name(), request);
    }

    public TelegramPairingStartView startTelegramPairing(String name) {
        TelegramChannelAdapter adapter = telegramAdapter(name);
        if (!adapter.isReady()) throw new IllegalStateException("Telegram connection is not ready");
        return pairings.start(normalizeName(name), adapter.botUsername());
    }

    public TelegramPairingView telegramPairing(String name, String pairingId) {
        requireTelegram(name);
        return pairings.get(normalizeName(name), pairingId);
    }

    public synchronized ChannelConnectionView approveTelegramPairing(
            String name, String pairingId, TelegramPairingApprovalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Telegram pairing approval is required");
        }
        StoredChannelConnection current = requireTelegram(name);
        TelegramPairingView.Candidate candidate = pairings.candidateForApproval(
                current.name(), pairingId, request.expectedChatId());
        Map<String, Object> settings = new LinkedHashMap<>(current.settings());
        List<Long> allowed = new java.util.ArrayList<>();
        Object existing = settings.get("allowedChatIds");
        if (existing instanceof java.util.Collection<?> values) {
            values.forEach(value -> allowed.add(value instanceof Number number
                    ? number.longValue() : Long.parseLong(value.toString())));
        }
        if (!allowed.contains(candidate.chatId())) allowed.add(candidate.chatId());
        settings.put("allowedChatIds", List.copyOf(allowed));
        ChannelConnectionView updated = update(current.name(), new ChannelConnectionUpdate(
                null, null, null, settings, Map.of(), null));
        pairings.markApproved(current.name(), pairingId, request.expectedChatId());
        return updated;
    }

    public void cancelTelegramPairing(String name, String pairingId) {
        requireTelegram(name);
        pairings.cancel(normalizeName(name), pairingId);
    }

    public TelegramDiagnosticsView telegramDiagnostics(String name) {
        return telegramAdapter(name).diagnostics();
    }

    public TelegramWebhookInfoView telegramWebhookInfo(String name) {
        StoredChannelConnection connection = requireTelegram(name);
        return channelManager.getConnectionAdapter(connection.name())
                .filter(TelegramChannelAdapter.class::isInstance)
                .map(TelegramChannelAdapter.class::cast)
                .map(TelegramChannelAdapter::webhookInfo)
                .orElseGet(() -> webhookView(telegramClient(connection).getWebhookInfo()));
    }

    public TelegramWebhookInfoView deleteTelegramWebhook(
            String name, boolean dropPendingUpdates) {
        StoredChannelConnection connection = requireTelegram(name);
        java.util.Optional<TelegramWebhookInfoView> running = channelManager
                .getConnectionAdapter(connection.name())
                .filter(TelegramChannelAdapter.class::isInstance)
                .map(TelegramChannelAdapter.class::cast)
                .map(adapter -> adapter.deleteWebhook(dropPendingUpdates));
        if (running.isPresent()) {
            return running.get();
        }
        DefaultTelegramApiClient client = telegramClient(connection);
        client.deleteWebhook(dropPendingUpdates);
        runtimeFactory.initializeTelegramPolling(connection.name(), client.getMe().id());
        return webhookView(client.getWebhookInfo());
    }

    private TelegramWebhookInfoView webhookView(
            ai.kompile.gateway.core.gateway.channel.TelegramApiClient.TelegramWebhookInfo info) {
        return new TelegramWebhookInfoView(
                info.configured(), info.redactedHost(), info.pendingUpdateCount(), info.lastErrorMessage());
    }

    private DefaultTelegramApiClient telegramClient(StoredChannelConnection connection) {
        return new DefaultTelegramApiClient(
                decrypt(connection.encryptedSecrets()).get("botToken"));
    }

    private StoredChannelConnection requireTelegram(String name) {
        StoredChannelConnection connection = require(name);
        if (!"telegram".equals(connection.providerId())) {
            throw new IllegalArgumentException("Connection is not a Telegram provider: " + name);
        }
        return connection;
    }

    private TelegramChannelAdapter telegramAdapter(String name) {
        StoredChannelConnection connection = requireTelegram(name);
        return channelManager.getConnectionAdapter(connection.name())
                .filter(TelegramChannelAdapter.class::isInstance)
                .map(TelegramChannelAdapter.class::cast)
                .orElseThrow(() -> new IllegalStateException("Telegram connection is not running"));
    }

    private boolean start(StoredChannelConnection connection, boolean replaceHealthy) {
        StoredChannelConnection normalized;
        ChannelCredentialResolver.ResolvedCredentials resolved;
        try {
            normalized = normalizedForRuntime(connection);
            resolved = resolveCredentials(normalized, decrypt(connection.encryptedSecrets()));
        } catch (RuntimeException error) {
            runtimeErrors.put(connection.name(), safeError(error));
            return false;
        }
        ChannelAdapter existing = channelManager.getConnectionAdapter(connection.name()).orElse(null);
        if (!replaceHealthy && existing != null && existing.isRunning()
                && existing.getLastError() == null
                && resolved.revision().equals(runtimeCredentialRevisions.get(connection.name()))) {
            Instant startedAt = runtimeStartedAt.get(connection.name());
            boolean withinStartupGrace = startedAt != null
                    && startedAt.plus(STARTUP_GRACE).isAfter(Instant.now());
            if (existing.isReady() || withinStartupGrace) {
                runtimeErrors.remove(connection.name());
                return true;
            }
        }
        runtimeErrors.remove(connection.name());
        ChannelAdapter candidate = null;
        ChannelAdapter previous = replaceHealthy
                ? existing
                : null;
        boolean previousStopped = false;
        try {
            if (!replaceHealthy && existing != null) {
                channelManager.unregisterAdapter(connection.name());
                runtimeStartedAt.remove(connection.name());
            }
            candidate = runtimeFactory.create(normalized, resolved.secrets());
            if (previous != null && previous.isRunning()) {
                previous.stop();
                previousStopped = true;
            }
            candidate.start();
            if (!candidate.isRunning()) {
                throw new IllegalStateException("Channel adapter did not reach RUNNING state");
            }
            channelManager.registerAdapter(connection.name(), candidate);
            runtimeStartedAt.put(connection.name(), Instant.now());
            runtimeCredentialRevisions.put(connection.name(), resolved.revision());
            return true;
        } catch (RuntimeException e) {
            if (candidate != null) {
                try {
                    candidate.stop();
                } catch (RuntimeException ignored) {
                    // Preserve the original startup failure.
                }
            }
            String error = safeError(e);
            if (previousStopped) {
                try {
                    previous.start();
                    channelManager.registerAdapter(connection.name(), previous);
                    runtimeStartedAt.put(connection.name(), Instant.now());
                } catch (RuntimeException restoreError) {
                    error += "; previous runtime could not be restored: " + safeError(restoreError);
                }
            }
            runtimeErrors.put(connection.name(), error);
            return false;
        }
    }

    private void stop(String name) {
        channelManager.unregisterAdapter(normalizeName(name));
        runtimeErrors.remove(normalizeName(name));
        runtimeStartedAt.remove(normalizeName(name));
        runtimeCredentialRevisions.remove(normalizeName(name));
    }

    private ChannelConnectionView view(StoredChannelConnection connection) {
        boolean ready = channelManager.getConnectionAdapter(connection.name())
                .map(ChannelAdapter::isReady)
                .orElse(false);
        String error = runtimeErrors.get(connection.name());
        if (error == null) {
            error = channelManager.getConnectionAdapter(connection.name())
                    .map(ChannelAdapter::getLastError)
                    .orElse(null);
        }
        RuntimeState state = !connection.enabled()
                ? RuntimeState.DISABLED
                : error != null
                        ? RuntimeState.ERROR
                        : ready ? RuntimeState.RUNNING : RuntimeState.STARTING;
        return new ChannelConnectionView(
                connection.id(),
                connection.name(),
                connection.providerId(),
                connection.engine(),
                connection.agentId(),
                connection.model(),
                connection.enabled(),
                state,
                connection.settings(),
                connection.encryptedSecrets().keySet(),
                connection.createdAt(),
                connection.updatedAt(),
                error);
    }

    private StoredChannelConnection require(String name) {
        String normalized = normalizeName(name);
        return store.get(normalized)
                .orElseThrow(() -> new ChannelConnectionNotFoundException(
                        "Channel connection does not exist: " + normalized));
    }

    private StoredChannelConnection withEnabled(StoredChannelConnection current, boolean enabled) {
        return new StoredChannelConnection(
                current.id(), current.name(), current.providerId(), current.engine(),
                current.agentId(), current.model(), enabled,
                current.settings(), current.encryptedSecrets(), current.createdAt(), Instant.now());
    }

    private StoredChannelConnection normalizedForRuntime(StoredChannelConnection connection) {
        Map<String, Object> settings = catalog.normalizeSettings(
                connection.providerId(), connection.settings());
        return new StoredChannelConnection(
                connection.id(), connection.name(), connection.providerId(), connection.engine(),
                connection.agentId(), connection.model(), connection.enabled(), settings,
                connection.encryptedSecrets(),
                connection.createdAt(), connection.updatedAt());
    }

    private ChannelCredentialResolver.ResolvedCredentials resolveCredentials(
            StoredChannelConnection connection,
            Map<String, String> explicitSecrets) {
        ChannelCredentialResolver.ResolvedCredentials resolved =
                credentialResolver.resolve(connection, explicitSecrets);
        catalog.validateRuntimeSecrets(connection.providerId(), resolved.secrets());
        return resolved;
    }

    private void validateOAuthBinding(StoredChannelConnection connection) {
        if (!credentialResolver.usesOAuth(connection.providerId(), connection.settings())) return;
        boolean duplicate = store.list().stream()
                .filter(existing -> !existing.name().equals(connection.name()))
                .anyMatch(existing -> credentialResolver.usesOAuth(
                        existing.providerId(), existing.settings()));
        if (duplicate) {
            throw new ChannelConnectionConflictException(
                    "Only one OAuth-backed Slack connection is supported per project");
        }
    }

    private static boolean isAllowlistedTarget(
            StoredChannelConnection connection,
            String target) {
        if (target == null || target.isBlank()) return false;
        String setting = switch (connection.providerId()) {
            case "telegram" -> "allowedChatIds";
            case "slack", "discord" -> "allowedChannelIds";
            case "whatsapp" -> "allowedPhoneNumbers";
            case "email" -> "allowedSenders";
            default -> null;
        };
        if (setting == null || !(connection.settings().get(setting) instanceof java.util.Collection<?> values)) {
            return false;
        }
        return values.stream().map(String::valueOf).anyMatch(value ->
                "email".equals(connection.providerId())
                        ? value.equalsIgnoreCase(target.trim())
                        : value.equals(target.trim()));
    }

    private Map<String, String> encrypt(Map<String, String> secrets) {
        Map<String, String> encrypted = new LinkedHashMap<>();
        secrets.forEach((name, value) -> encrypted.put(name, encryption.encrypt(value)));
        return Map.copyOf(encrypted);
    }

    private Map<String, String> decrypt(Map<String, String> encryptedSecrets) {
        Map<String, String> decrypted = new LinkedHashMap<>();
        encryptedSecrets.forEach((name, value) -> decrypted.put(name, encryption.decrypt(value)));
        return Map.copyOf(decrypted);
    }

    static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Connection name is required");
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (!CONNECTION_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Connection name must match " + CONNECTION_NAME.pattern());
        }
        return normalized;
    }

    private static String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "jarvis" : agentId.trim();
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() ? null : model.trim();
    }

    private void validateAgent(ChannelChatEngine engine, String agentId) {
        if (engine == ChannelChatEngine.REACT && agentRegistry.getAgent(agentId).isEmpty()) {
            throw new IllegalArgumentException("Unknown ReAct agent: " + agentId);
        }
    }

    private static void validateModel(ChannelChatEngine engine, String model) {
        if (model != null && !model.isBlank() && engine != ChannelChatEngine.KOMPILE_CLI) {
            throw new IllegalArgumentException(
                    "Model overrides are supported only by the KOMPILE_CLI channel engine");
        }
    }

    private static String safeError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    public static final class ChannelConnectionNotFoundException extends RuntimeException {
        public ChannelConnectionNotFoundException(String message) {
            super(message);
        }
    }

    public static final class ChannelConnectionConflictException extends RuntimeException {
        public ChannelConnectionConflictException(String message) {
            super(message);
        }
    }
}
