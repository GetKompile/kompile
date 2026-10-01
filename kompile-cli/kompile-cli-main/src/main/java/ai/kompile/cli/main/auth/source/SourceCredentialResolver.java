/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.source;

import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.channel.ChannelControlPlaneClient;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Credential logic shared by {@code kompile auth source ingest} and the folder-local crawl
 * registry: the OAuth provider/metadata-key mapping for source types, the channel-connection
 * credential bridge, and the secret-field allowlists that gate --set/properties input.
 *
 * <p>The static mapping helpers and {@link #channelCredentials} / {@link #fillOAuthAccessToken}
 * are a verbatim extraction from {@code AuthSourceCommand}: same behavior, so
 * {@code kompile auth source ingest} is unaffected by the refactor. {@link #resolveStoredCredentials}
 * is new: it backs the folder-local crawl registry's stored-only credential fill used by
 * chat-driven crawls ({@code crawl_documents}), which have no --secret-* input at all, so unlike
 * {@link #fillOAuthAccessToken} a control-plane connectivity failure there is surfaced as a clear
 * thrown error instead of being silently swallowed.</p>
 */
public final class SourceCredentialResolver {

    /** Resolves a locally stored OAuth access token for {@code provider}, or null when absent. */
    @FunctionalInterface
    public interface LocalOAuthLookup {
        String resolve(String provider) throws Exception;
    }

    /** Reads one stored OAuth metadata value (e.g. {@code cloudId}) for {@code provider}. */
    @FunctionalInterface
    public interface LocalOAuthMetadataLookup {
        String metadata(String provider, String key) throws Exception;
    }

    /** Looks up a connected OAuth credential from the app control plane. */
    @FunctionalInterface
    public interface RemoteOAuthLookup {
        JsonNode lookup(String provider) throws Exception;
    }

    /** Looks up a named channel connection's credential from the app control plane. */
    @FunctionalInterface
    public interface ChannelConnectionLookup {
        ChannelCredentialView lookup(String connectionName) throws Exception;
    }

    private static final Set<String> GOOGLE_EMAIL_HOSTS = Set.of("imap.gmail.com", "pop.gmail.com");

    private final LocalOAuthLookup localOAuthLookup;
    private final LocalOAuthMetadataLookup localOAuthMetadataLookup;
    private final RemoteOAuthLookup remoteOAuthLookup;
    private final ChannelConnectionLookup channelLookup;

    public SourceCredentialResolver() {
        this(
                provider -> {
                    OAuthProviderFlow.RequestAuth auth = new OAuthCredentialManager(
                            CredentialStore.create(), new OAuthProviderRegistry()).resolve(provider);
                    return auth == null ? null : auth.token();
                },
                (provider, key) -> {
                    ManagedCredential credential = CredentialStore.create().read(provider);
                    Object value = credential == null ? null : credential.getMetadata(key);
                    return value instanceof String text ? text : null;
                },
                provider -> new SourceControlPlaneClient(KompileHttpClient.routed()).oauthCredential(provider),
                name -> new ChannelControlPlaneClient("").credential(name));
    }

    /**
     * Test/production seam: every collaborator is a small functional interface. Public so
     * callers outside this package (e.g. the folder-local crawl registry's tests) can inject
     * fakes without ever touching a real credential store or network client.
     */
    public SourceCredentialResolver(
            LocalOAuthLookup localOAuthLookup,
            LocalOAuthMetadataLookup localOAuthMetadataLookup,
            RemoteOAuthLookup remoteOAuthLookup,
            ChannelConnectionLookup channelLookup) {
        this.localOAuthLookup = localOAuthLookup;
        this.localOAuthMetadataLookup = localOAuthMetadataLookup;
        this.remoteOAuthLookup = remoteOAuthLookup;
        this.channelLookup = channelLookup;
    }

    public static SourceCredentialResolver create() {
        return new SourceCredentialResolver();
    }

    // ── Extracted verbatim from AuthSourceCommand: provider/metadata-key mapping ──

    /**
     * Source types whose loaders resolve credentials from metadata.accessToken and fall back to
     * the connected OAuth account server-side. Maps each to its OAuth provider id so local
     * crawls can bridge the locally connected token. Gmail/M365 mail presets are also honored
     * by the IMAP loader via the same accessToken key. Jira/Confluence additionally copy the
     * Atlassian cloudId from credential metadata when present.
     */
    public static String oauthProviderFor(String sourceType) {
        return switch (sourceType) {
            case "GMAIL", "GDOCS", "GDRIVE", "GOOGLE_WORKSPACE" -> "google";
            case "ONEDRIVE" -> "microsoft";
            case "EMAIL", "IMAP", "POP3" -> "google"; // XOAUTH2 for Gmail-hosted mailboxes
            case "NOTION" -> "notion";
            case "REDDIT" -> "reddit";
            case "JIRA", "CONFLUENCE" -> "atlassian";
            default -> null;
        };
    }

    /** Credential metadata keys copied into crawl properties alongside accessToken. */
    public static List<String> oauthMetadataKeysFor(String sourceType) {
        return switch (sourceType) {
            case "JIRA", "CONFLUENCE" -> List.of("cloudId");
            default -> List.of();
        };
    }

    /**
     * Runtime credential bridge: resolves secrets plus non-secret connection settings from a
     * named channel connection and maps them onto the loader contract of the requested source
     * type.
     */
    public static Map<String, Object> mappedChannelCredentials(ChannelCredentialView credential, String type) {
        String provider = credential.providerId();
        Map<String, String> secrets = credential.secrets();
        Map<String, Object> settings = credential.properties();
        Map<String, Object> mapped = new LinkedHashMap<>();
        switch (provider) {
            case "slack" -> {
                requireSupported(type, "SLACK", "SLACK_HISTORY");
                copyIfPresent(secrets, "botToken", mapped, "slackToken");
            }
            case "discord" -> {
                requireSupported(type, "DISCORD", "DISCORD_HISTORY");
                copyIfPresent(secrets, "botToken", mapped, "botToken");
            }
            case "email" -> {
                requireSupported(type, "EMAIL", "IMAP", "POP3");
                copyIfPresent(secrets, "password", mapped, "password");
                copyIfPresent(settings, "username", mapped, "username");
                copyIfPresent(settings, "imapHost", mapped, "host");
                copyIfPresent(settings, "imapPort", mapped, "port");
                if (settings.containsKey("smtpHost") && !mapped.containsKey("host")) {
                    copyIfPresent(settings, "smtpHost", mapped, "host");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Channel provider '" + provider
                            + "' has no crawl credential mapping (supported: slack, discord, email)");
        }
        if (mapped.isEmpty()) {
            throw new IllegalArgumentException("Channel connection has no runtime credential for " + type);
        }
        return Map.copyOf(mapped);
    }

    private static void requireSupported(String type, String... supported) {
        for (String candidate : supported) {
            if (candidate.equals(type)) return;
        }
        throw new IllegalArgumentException("Source type " + type
                + " does not match the channel connection provider (expected one of "
                + String.join(", ", supported) + ")");
    }

    private static void copyIfPresent(
            Map<String, ?> source, String from, Map<String, Object> target, String to) {
        Object value = source.get(from);
        if (value instanceof String text) {
            if (!text.isBlank()) target.put(to, text.trim());
        } else if (value != null) {
            target.put(to, value);
        }
    }

    public static Set<String> sourceSecretFields(String sourceType) {
        return switch (sourceType) {
            case "DISCORD", "DISCORD_HISTORY" -> Set.of("botToken");
            case "SLACK", "SLACK_HISTORY" -> Set.of("slackToken");
            case "CONFLUENCE" -> Set.of("apiToken", "accessToken");
            case "JIRA" -> Set.of("apiToken", "accessToken");
            case "REDDIT" -> Set.of("accessToken");
            case "NOTION" -> Set.of("apiToken", "accessToken");
            case "EMAIL", "IMAP", "POP3" -> Set.of("password", "accessToken");
            case "SFTP", "SMB", "SQL" -> Set.of("password");
            case "SAP_NETWEAVER", "ODATA", "DYNAMICS365", "NETSUITE", "ODOO", "SALESFORCE" -> Set.of("password", "accessToken");
            case "GMAIL", "GDOCS", "GDRIVE", "GOOGLE_WORKSPACE", "ONEDRIVE" -> Set.of("accessToken");
            case "S3" -> Set.of("accessKey", "secretKey");
            default -> Set.of();
        };
    }

    public static boolean isSensitiveProperty(String name) {
        String normalized = name == null ? ""
                : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.endsWith("password") || normalized.endsWith("token")
                || normalized.endsWith("secret") || normalized.equals("accesskey")
                || normalized.equals("secretkey") || normalized.equals("apikey");
    }

    // ── Extracted instance behavior used by AuthSourceCommand: no behavior change ──

    /** Same behavior as AuthSourceCommand's original {@code bridgeChannelCredentials}. */
    public Map<String, Object> channelCredentials(String connectionName, String type) {
        if (connectionName == null || connectionName.isBlank()) {
            return Map.of();
        }
        String name = connectionName.trim();
        try {
            return mappedChannelCredentials(channelLookup.lookup(name), type);
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not resolve credentials from channel connection '"
                    + name + "': "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                    error);
        }
    }

    /**
     * Same behavior as AuthSourceCommand's original {@code fillOAuthAccessToken}: local store
     * first, falling back to the control plane; every failure (no local credential, control
     * plane unreachable or not authenticated) is swallowed so explicit --secret-* input still
     * works for source types that need it.
     */
    public void fillOAuthAccessToken(String type, Map<String, Object> bridged, Map<String, String> rawProperties) {
        String provider = oauthProviderFor(type);
        if (provider == null) {
            return;
        }
        // EMAIL/IMAP/POP3 use the token only with an explicit XOAUTH2 authMode.
        if (type.equals("EMAIL") || type.equals("IMAP") || type.equals("POP3")) {
            Object authMode = rawProperties.get("authMode");
            if (authMode == null || !authMode.toString().startsWith("OAUTH2")) {
                return;
            }
        }
        OAuthFill fill = attemptOAuthFill(provider, type);
        if (fill.accessToken() != null) {
            bridged.put("accessToken", fill.accessToken());
            for (Map.Entry<String, Object> entry : fill.metadata().entrySet()) {
                bridged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        // A "not found" or control-plane failure result leaves bridged untouched: local crawls
        // still work when the user supplies explicit secrets, and connectivity errors must not
        // turn into spurious bridge failures for types that may not need OAuth at all.
    }

    // ── New: stored-only resolution for the folder-local crawl registry ──

    /**
     * Fills missing credential fields on {@code properties} from stored sources only: first a
     * named channel connection ({@code properties.fromChannelConnection}, removed either way via
     * the channel-credential mapping), then the connected OAuth account for the type's provider
     * (local store, then the control plane). Chat-driven crawls have no other way to supply a
     * secret, so unlike {@link #fillOAuthAccessToken} a control-plane connectivity failure here
     * is a clear thrown error rather than a silent skip, and a missing credential fails closed
     * naming the fix. Explicit values already present in {@code properties} are never
     * overwritten, except that a stored channel connection's host/username take precedence over
     * a request value and a conflict between the two fails closed rather than silently picking
     * one.
     */
    public void resolveStoredCredentials(String type, Map<String, Object> properties) {
        if (ErpConnectionCredentials.isErp(type)) {
            try {
                ErpConnectionCredentials.resolve(CredentialStore.create(), type, properties);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not read the named ERP connection. Run 'kompile auth source erp'.");
            }
            return;
        }
        Object requestedChannel = properties.remove("fromChannelConnection");
        if (requestedChannel != null && !requestedChannel.toString().isBlank()) {
            Map<String, Object> mapped = channelCredentials(requestedChannel.toString(), type);
            for (Map.Entry<String, Object> entry : mapped.entrySet()) {
                applyChannelValue(properties, entry.getKey(), entry.getValue());
            }
        }
        String provider = oauthProviderFor(type);
        if (provider == null || hasSecret(type, properties)) {
            return;
        }
        if (type.equals("EMAIL") || type.equals("IMAP") || type.equals("POP3")) {
            Object authMode = properties.get("authMode");
            if (authMode == null || !authMode.toString().toUpperCase(Locale.ROOT).startsWith("OAUTH2")) {
                return; // BASIC/APP_PASSWORD auth: an explicit password is the loader's own contract.
            }
            Object host = properties.get("host");
            if (host == null
                    || !GOOGLE_EMAIL_HOSTS.contains(host.toString().trim().toLowerCase(Locale.ROOT))) {
                return; // Never send a Google token to a non-Google host.
            }
        }
        OAuthFill fill = attemptOAuthFill(provider, type);
        if (fill.accessToken() != null) {
            properties.put("accessToken", fill.accessToken());
            for (Map.Entry<String, Object> entry : fill.metadata().entrySet()) {
                properties.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return;
        }
        if (fill.controlPlaneFailure() != null) {
            throw new IllegalStateException("Could not reach the Kompile control plane to check the "
                    + "connected " + provider + " account: " + causeMessage(fill.controlPlaneFailure())
                    + ". Fix connectivity and retry.", fill.controlPlaneFailure());
        }
        throw new IllegalStateException(type + " needs a credential and none is connected. Run "
                + "'kompile auth login " + provider + "', or connect it via 'kompile auth source' and pass "
                + "properties.fromChannelConnection with that connection's name.");
    }

    private void applyChannelValue(Map<String, Object> properties, String key, Object value) {
        if (!"host".equals(key) && !"username".equals(key)) {
            properties.putIfAbsent(key, value);
            return;
        }
        Object requested = properties.get(key);
        if (requested == null || requested.toString().isBlank()) {
            properties.put(key, value);
        } else if (!requested.toString().trim().equalsIgnoreCase(value.toString().trim())) {
            throw new IllegalArgumentException("properties." + key + " (" + requested
                    + ") does not match the connected channel's " + key + " (" + value
                    + "); use the channel's own value or drop properties.fromChannelConnection.");
        }
    }

    private static boolean hasSecret(String type, Map<String, Object> properties) {
        for (String field : sourceSecretFields(type)) {
            Object value = properties.get(field);
            if (value != null && !value.toString().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private OAuthFill attemptOAuthFill(String provider, String type) {
        try {
            String token = localOAuthLookup.resolve(provider);
            if (token != null) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                for (String key : oauthMetadataKeysFor(type)) {
                    String value = localOAuthMetadataLookup.metadata(provider, key);
                    if (value != null && !value.isBlank()) {
                        metadata.put(key, value);
                    }
                }
                return OAuthFill.found(token, metadata);
            }
        } catch (Exception ignoredLocal) {
            // No locally stored credential (or refresh failed); fall through to the control plane.
        }
        try {
            JsonNode credential = remoteOAuthLookup.lookup(provider);
            if (credential.path("hasToken").asBoolean(false)) {
                return OAuthFill.found(credential.path("accessToken").asText(), Map.of());
            }
            return OAuthFill.notFound();
        } catch (Exception error) {
            return OAuthFill.failure(error);
        }
    }

    private static String causeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record OAuthFill(String accessToken, Map<String, Object> metadata, Exception controlPlaneFailure) {
        static OAuthFill found(String token, Map<String, Object> metadata) {
            return new OAuthFill(token, metadata, null);
        }

        static OAuthFill notFound() {
            return new OAuthFill(null, Map.of(), null);
        }

        static OAuthFill failure(Exception error) {
            return new OAuthFill(null, Map.of(), error);
        }
    }
}
