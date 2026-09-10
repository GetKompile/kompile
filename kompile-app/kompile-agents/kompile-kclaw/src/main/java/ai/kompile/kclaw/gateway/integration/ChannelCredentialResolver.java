/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Resolves runtime credentials without copying OAuth or environment secrets into channel storage. */
public final class ChannelCredentialResolver {

    public static final String CHANNEL_PURPOSE = "channel";
    public static final String USE_OAUTH_SETTING = "useOAuth";

    private final ChannelProviderCatalog catalog;
    private final OAuthConnectionService oauth;
    private final Function<String, String> environment;

    public ChannelCredentialResolver(
            ChannelProviderCatalog catalog,
            OAuthConnectionService oauth,
            Function<String, String> environment) {
        this.catalog = catalog;
        this.oauth = oauth;
        this.environment = environment == null ? ignored -> null : environment;
    }

    public static ChannelCredentialResolver explicitOnly(ChannelProviderCatalog catalog) {
        return new ChannelCredentialResolver(catalog, null, ignored -> null);
    }

    public ResolvedCredentials resolve(
            StoredChannelConnection connection,
            Map<String, String> explicitSecrets) {
        ChannelProviderDescriptor provider = catalog.require(connection.providerId());
        Map<String, String> effective = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, String> explicit = explicitSecrets == null ? Map.of() : explicitSecrets;

        for (ChannelProviderDescriptor.Field field : provider.secrets()) {
            String value = clean(explicit.get(field.name()));
            if (value != null) {
                effective.put(field.name(), value);
                sources.put(field.name(), "stored");
                continue;
            }
            String environmentName = clean(field.environmentHint());
            String environmentValue = environmentName == null ? null : clean(environment.apply(environmentName));
            if (environmentValue != null) {
                effective.put(field.name(), environmentValue);
                sources.put(field.name(), "environment:" + environmentName);
            }
        }

        if (usesOAuth(connection.providerId(), connection.settings())) {
            if (!"slack".equals(connection.providerId())) {
                throw new IllegalStateException(
                        "OAuth-backed conversational credentials are not supported for "
                                + connection.providerId());
            }
            OAuthState state = slackOAuthState();
            if (!state.connected()) {
                throw new IllegalStateException(
                        "Slack OAuth is not connected; run `kompile auth channel login slack`");
            }
            if (!state.channelScopesGranted()) {
                throw new IllegalStateException(
                        "Slack OAuth is missing channel scopes: "
                                + String.join(", ", state.missingScopes()));
            }
            effective.put("botToken", state.accessToken());
            sources.put("botToken", "oauth:slack");
        }

        catalog.validateRuntimeSecrets(connection.providerId(), effective);
        return new ResolvedCredentials(effective, sources, revision(effective));
    }

    public ChannelProviderAuthView auth(String providerId) {
        ChannelProviderDescriptor descriptor = catalog.require(providerId);
        Set<String> missing = new LinkedHashSet<>(catalog.runtimeRequiredSecrets(providerId));
        Map<String, String> sources = new LinkedHashMap<>();

        for (ChannelProviderDescriptor.Field field : descriptor.secrets()) {
            String environmentName = clean(field.environmentHint());
            if (environmentName != null && clean(environment.apply(environmentName)) != null) {
                missing.remove(field.name());
                sources.put(field.name(), "environment:" + environmentName);
            }
        }

        if ("slack".equals(providerId)) {
            OAuthState state = slackOAuthState();
            if (state.connected() && state.channelScopesGranted()) {
                missing.remove("botToken");
                sources.put("botToken", "oauth:slack");
            }
            return new ChannelProviderAuthView(
                    providerId,
                    ChannelProviderAuthView.Mode.OAUTH_PARTIAL,
                    true,
                    state.connected(),
                    state.channelScopesGranted(),
                    state.requiredScopes(),
                    state.grantedScopes(),
                    missing,
                    sources,
                    "Slack OAuth supplies the workspace bot token. Socket Mode still requires "
                            + "a server-side SLACK_APP_TOKEN or encrypted appToken. Only one "
                            + "OAuth-backed Slack connection is supported per project.");
        }

        return new ChannelProviderAuthView(
                providerId,
                mode(providerId),
                false,
                false,
                false,
                List.of(),
                List.of(),
                missing,
                sources,
                guidance(providerId));
    }

    public boolean usesOAuth(String providerId, Map<String, Object> settings) {
        return "slack".equals(providerId)
                && settings != null
                && Boolean.TRUE.equals(settings.get(USE_OAUTH_SETTING));
    }

    private OAuthState slackOAuthState() {
        List<String> required = oauth == null
                ? defaultSlackChannelScopes()
                : oauth.getRequiredScopes("slack", CHANNEL_PURPOSE);
        List<String> granted = oauth == null ? List.of() : oauth.getGrantedScopes("slack");
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(granted);
        String accessToken = oauth == null ? null : clean(oauth.getCurrentAccessToken("slack"));
        return new OAuthState(
                accessToken,
                required,
                granted,
                List.copyOf(missing));
    }

    private static List<String> defaultSlackChannelScopes() {
        return List.of(
                "app_mentions:read",
                "channels:history",
                "channels:read",
                "chat:write",
                "groups:history",
                "groups:read",
                "im:history",
                "mpim:history",
                "users:read");
    }

    private static ChannelProviderAuthView.Mode mode(String providerId) {
        return switch (providerId) {
            case "discord" -> ChannelProviderAuthView.Mode.BOT_INSTALL;
            case "telegram" -> ChannelProviderAuthView.Mode.MANUAL_SECRET;
            case "whatsapp" -> ChannelProviderAuthView.Mode.EXTERNAL_SETUP;
            case "email" -> ChannelProviderAuthView.Mode.PASSWORD;
            default -> ChannelProviderAuthView.Mode.MANUAL_SECRET;
        };
    }

    private static String guidance(String providerId) {
        return switch (providerId) {
            case "discord" -> "Discord OAuth can install a bot into a guild but never returns its "
                    + "bot token. Configure the bot token securely; user/self-bot tokens are forbidden.";
            case "telegram" -> "Create the bot with BotFather, store its bot token securely, then "
                    + "use the Telegram pairing lifecycle to approve an exact chat.";
            case "whatsapp" -> "WhatsApp currently requires Cloud API access token, phone number ID, "
                    + "verify token, app secret, and a public signed webhook.";
            case "email" -> "The live email channel currently uses certificate-verified IMAP/SMTP "
                    + "credentials. Gmail and Microsoft XOAUTH2 are not yet implemented here.";
            default -> "Configure the provider credential through a write-only channel secret input.";
        };
    }

    private static String revision(Map<String, String> secrets) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(secrets).forEach((name, value) -> {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0xff);
            });
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not fingerprint channel credentials", impossible);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ResolvedCredentials(
            Map<String, String> secrets,
            Map<String, String> sources,
            String revision) {
        public ResolvedCredentials {
            secrets = Map.copyOf(secrets);
            sources = Map.copyOf(sources);
        }
    }

    private record OAuthState(
            String accessToken,
            List<String> requiredScopes,
            List<String> grantedScopes,
            List<String> missingScopes) {
        boolean connected() {
            return accessToken != null;
        }

        boolean channelScopesGranted() {
            return connected() && missingScopes.isEmpty();
        }
    }
}
