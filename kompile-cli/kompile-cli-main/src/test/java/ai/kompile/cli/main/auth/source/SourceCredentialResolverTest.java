/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.source;

import ai.kompile.channel.api.ChannelCredentialView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the instance-level behaviors of {@link SourceCredentialResolver} that {@code
 * AuthSourceCommandTest} does not reach through {@code AuthSourceCommand}'s thin delegating
 * wrappers: the four-collaborator test seam is exercised directly with fakes so none of these
 * tests ever touches a real {@code CredentialStore}, OAuth flow, or network client.
 */
class SourceCredentialResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SourceCredentialResolver.LocalOAuthLookup NEVER_LOCAL =
            provider -> { throw new AssertionError("local OAuth lookup should not be called for " + provider); };
    private static final SourceCredentialResolver.LocalOAuthMetadataLookup NEVER_LOCAL_METADATA =
            (provider, key) -> {
                throw new AssertionError("local OAuth metadata lookup should not be called for " + provider);
            };
    private static final SourceCredentialResolver.RemoteOAuthLookup NEVER_REMOTE =
            provider -> { throw new AssertionError("remote OAuth lookup should not be called for " + provider); };
    private static final SourceCredentialResolver.ChannelConnectionLookup NEVER_CHANNEL =
            name -> { throw new AssertionError("channel lookup should not be called for " + name); };

    private static final SourceCredentialResolver.LocalOAuthLookup NOT_STORED_LOCAL =
            provider -> { throw new IOException("no local credential for " + provider); };
    private static final SourceCredentialResolver.RemoteOAuthLookup REMOTE_NOT_FOUND =
            provider -> MAPPER.createObjectNode(); // no "hasToken" field => asBoolean(false) == false

    private static SourceCredentialResolver.LocalOAuthLookup localToken(String expectedProvider, String token) {
        return provider -> {
            assertEquals(expectedProvider, provider);
            return token;
        };
    }

    private static SourceCredentialResolver.RemoteOAuthLookup remoteFound(String expectedProvider, String token) {
        return provider -> {
            assertEquals(expectedProvider, provider);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("hasToken", true);
            node.put("accessToken", token);
            return node;
        };
    }

    private static SourceCredentialResolver.RemoteOAuthLookup remoteFails(Exception error) {
        return provider -> { throw error; };
    }

    private static SourceCredentialResolver resolver(
            SourceCredentialResolver.LocalOAuthLookup local,
            SourceCredentialResolver.LocalOAuthMetadataLookup localMetadata,
            SourceCredentialResolver.RemoteOAuthLookup remote,
            SourceCredentialResolver.ChannelConnectionLookup channel) {
        return new SourceCredentialResolver(local, localMetadata, remote, channel);
    }

    // ── channelCredentials ──

    @Test
    void channelCredentialsReturnsEmptyForNullOrBlankConnectionNameWithoutLookup() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);

        assertEquals(Map.of(), resolver.channelCredentials(null, "SLACK"));
        assertEquals(Map.of(), resolver.channelCredentials("   ", "SLACK"));
    }

    @Test
    void channelCredentialsMapsSlackDiscordEmailThroughTheChannelLookup() {
        SourceCredentialResolver.ChannelConnectionLookup lookup = name -> switch (name) {
            case "slack-conn" -> new ChannelCredentialView("slack", Map.of("botToken", "xoxb-1"), Map.of());
            case "discord-conn" -> new ChannelCredentialView("discord",
                    Map.of("botToken", "runtime-only"), Map.of("allowedChannelIds", List.of("C01")));
            default -> throw new IllegalStateException("unexpected connection " + name);
        };
        SourceCredentialResolver resolver = resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, lookup);

        assertEquals(Map.of("slackToken", "xoxb-1"),
                resolver.channelCredentials("slack-conn", "SLACK_HISTORY"));
        assertEquals(Map.of("botToken", "runtime-only"),
                resolver.channelCredentials(" discord-conn ", "DISCORD"));
    }

    @Test
    void channelCredentialsWrapsLookupFailureNamingTheConnection() {
        SourceCredentialResolver resolver = resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE,
                name -> { throw new IllegalStateException("connection not found"); });

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.channelCredentials("my-conn", "SLACK"));
        assertTrue(error.getMessage().contains("my-conn"), error.getMessage());
        assertTrue(error.getMessage().contains("connection not found"), error.getMessage());
    }

    @Test
    void channelCredentialsWrapsProviderTypeMismatchFromMappedChannelCredentials() {
        SourceCredentialResolver resolver = resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE,
                name -> new ChannelCredentialView("discord", Map.of("botToken", "t"), Map.of()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.channelCredentials("my-conn", "SLACK"));
        assertTrue(error.getMessage().contains("my-conn"), error.getMessage());
    }

    // ── fillOAuthAccessToken (AuthSourceCommand's original permissive behavior) ──

    @Test
    void fillOAuthAccessTokenNoOpsForProvidersWithoutOauthMapping() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> bridged = new LinkedHashMap<>();

        resolver.fillOAuthAccessToken("DISCORD", bridged, Map.of());

        assertTrue(bridged.isEmpty());
    }

    @Test
    void fillOAuthAccessTokenEmailSkipsWithoutExactCaseOauth2Prefix() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);

        Map<String, Object> noAuthMode = new LinkedHashMap<>();
        resolver.fillOAuthAccessToken("EMAIL", noAuthMode, Map.of());
        assertTrue(noAuthMode.isEmpty());

        // Case-sensitive: unlike resolveStoredCredentials, this legacy path does not uppercase.
        Map<String, Object> lowerCaseAuthMode = new LinkedHashMap<>();
        resolver.fillOAuthAccessToken("EMAIL", lowerCaseAuthMode, Map.of("authMode", "oauth2"));
        assertTrue(lowerCaseAuthMode.isEmpty());
    }

    @Test
    void fillOAuthAccessTokenEmailFillsWithExactOauth2Prefix() {
        SourceCredentialResolver resolver =
                resolver(localToken("google", "tok-local"), NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> bridged = new LinkedHashMap<>();

        resolver.fillOAuthAccessToken("EMAIL", bridged, Map.of("authMode", "OAUTH2"));

        assertEquals("tok-local", bridged.get("accessToken"));
    }

    @Test
    void fillOAuthAccessTokenFillsFromLocalStoreWithoutTouchingRemote() {
        SourceCredentialResolver resolver =
                resolver(localToken("google", "tok-local"), NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> bridged = new LinkedHashMap<>();

        resolver.fillOAuthAccessToken("GMAIL", bridged, Map.of());

        assertEquals(Map.of("accessToken", "tok-local"), bridged);
    }

    @Test
    void fillOAuthAccessTokenFallsBackToRemoteWhenLocalLookupFails() {
        SourceCredentialResolver resolver =
                resolver(NOT_STORED_LOCAL, NEVER_LOCAL_METADATA, remoteFound("google", "tok-remote"), NEVER_CHANNEL);
        Map<String, Object> bridged = new LinkedHashMap<>();

        resolver.fillOAuthAccessToken("GMAIL", bridged, Map.of());

        assertEquals(Map.of("accessToken", "tok-remote"), bridged);
    }

    @Test
    void fillOAuthAccessTokenSwallowsRemoteFailureLeavingBridgedUntouched() {
        SourceCredentialResolver resolver = resolver(
                NOT_STORED_LOCAL, NEVER_LOCAL_METADATA, remoteFails(new IOException("down")), NEVER_CHANNEL);
        Map<String, Object> bridged = new LinkedHashMap<>();
        bridged.put("existing", "kept");

        assertDoesNotThrow(() -> resolver.fillOAuthAccessToken("GMAIL", bridged, Map.of()));

        assertEquals(Map.of("existing", "kept"), bridged);
    }

    @Test
    void fillOAuthAccessTokenCopiesMetadataKeysOnlyForAtlassianTypesAndNeverOverwritesExisting() {
        SourceCredentialResolver resolver = resolver(
                localToken("atlassian", "tok-1"),
                (provider, key) -> {
                    assertEquals("atlassian", provider);
                    assertEquals("cloudId", key);
                    return "new-cloud";
                },
                NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> bridged = new LinkedHashMap<>();
        bridged.put("cloudId", "existing-cloud");

        resolver.fillOAuthAccessToken("JIRA", bridged, Map.of());

        assertEquals("tok-1", bridged.get("accessToken"));
        assertEquals("existing-cloud", bridged.get("cloudId")); // putIfAbsent: never overwritten
    }

    // ── resolveStoredCredentials (new fail-closed path for chat-driven local crawls) ──

    @Test
    void resolveStoredCredentialsShortCircuitsWhenAnExplicitSecretIsAlreadyPresent() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("apiToken", "explicit-token"));

        resolver.resolveStoredCredentials("CONFLUENCE", properties);

        assertEquals("explicit-token", properties.get("apiToken"));
        assertEquals(1, properties.size());
    }

    @Test
    void resolveStoredCredentialsAppliesChannelConnectionCredentialsAndRemovesTheRequestKey() {
        SourceCredentialResolver resolver = resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE,
                name -> {
                    assertEquals("conn1", name);
                    return new ChannelCredentialView("discord", Map.of("botToken", "runtime-only"), Map.of());
                });
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("fromChannelConnection", "conn1"));

        resolver.resolveStoredCredentials("DISCORD", properties);

        assertFalse(properties.containsKey("fromChannelConnection"));
        assertEquals("runtime-only", properties.get("botToken"));
    }

    @Test
    void resolveStoredCredentialsChannelHostConflictFailsClosed() {
        SourceCredentialResolver resolver = resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE,
                name -> new ChannelCredentialView("email", Map.of("password", "app-pw"),
                        Map.of("username", "ops@example.com", "imapHost", "imap.example.com",
                                "imapPort", 993)));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("fromChannelConnection", "conn1");
        properties.put("host", "other.host.com");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveStoredCredentials("EMAIL", properties));
        assertTrue(error.getMessage().contains("properties.host"), error.getMessage());
        assertTrue(error.getMessage().contains("imap.example.com"), error.getMessage());
    }

    @Test
    void resolveStoredCredentialsChannelNonConflictingHostIsAcceptedCaseInsensitively() {
        SourceCredentialResolver resolver = resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE,
                name -> new ChannelCredentialView("email", Map.of("password", "app-pw"),
                        Map.of("username", "ops@example.com", "imapHost", "imap.example.com")));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("fromChannelConnection", "conn1");
        properties.put("host", "IMAP.EXAMPLE.COM");

        resolver.resolveStoredCredentials("EMAIL", properties);

        assertEquals("IMAP.EXAMPLE.COM", properties.get("host")); // request value kept, not replaced
        assertEquals("app-pw", properties.get("password"));
        assertEquals("ops@example.com", properties.get("username"));
    }

    @Test
    void resolveStoredCredentialsSkipsOauthFillWhenProviderIsUnmapped() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>();

        assertDoesNotThrow(() -> resolver.resolveStoredCredentials("DISCORD", properties));

        assertTrue(properties.isEmpty());
    }

    @Test
    void resolveStoredCredentialsEmailSkipsOauthFillWithoutOauth2AuthMode() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);

        Map<String, Object> noAuthMode = new LinkedHashMap<>(Map.of("host", "imap.gmail.com"));
        assertDoesNotThrow(() -> resolver.resolveStoredCredentials("EMAIL", noAuthMode));
        assertFalse(noAuthMode.containsKey("accessToken"));

        Map<String, Object> basicAuthMode = new LinkedHashMap<>(
                Map.of("host", "imap.gmail.com", "authMode", "BASIC"));
        assertDoesNotThrow(() -> resolver.resolveStoredCredentials("IMAP", basicAuthMode));
        assertFalse(basicAuthMode.containsKey("accessToken"));
    }

    @Test
    void resolveStoredCredentialsEmailSkipsOauthFillForNonGoogleHost() {
        SourceCredentialResolver resolver =
                resolver(NEVER_LOCAL, NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>(
                Map.of("host", "imap.other.example.com", "authMode", "OAUTH2"));

        assertDoesNotThrow(() -> resolver.resolveStoredCredentials("POP3", properties));

        assertFalse(properties.containsKey("accessToken"));
    }

    @Test
    void resolveStoredCredentialsEmailFillsOauthForGoogleHostCaseInsensitively() {
        SourceCredentialResolver resolver =
                resolver(localToken("google", "tok-gmail"), NEVER_LOCAL_METADATA, NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host", "IMAP.GMAIL.COM");
        properties.put("authMode", "oauth2-refresh"); // lower-case, non-exact: resolveStoredCredentials uppercases

        resolver.resolveStoredCredentials("EMAIL", properties);

        assertEquals("tok-gmail", properties.get("accessToken"));
    }

    @Test
    void resolveStoredCredentialsFillsAccessTokenAndMetadataNeverOverwritingExplicitMetadata() {
        SourceCredentialResolver resolver = resolver(
                localToken("atlassian", "tok-1"),
                (provider, key) -> {
                    assertEquals("atlassian", provider);
                    assertEquals("cloudId", key);
                    return "new-cloud";
                },
                NEVER_REMOTE, NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cloudId", "existing-cloud");

        resolver.resolveStoredCredentials("JIRA", properties);

        assertEquals("tok-1", properties.get("accessToken"));
        assertEquals("existing-cloud", properties.get("cloudId")); // putIfAbsent: never overwritten
    }

    @Test
    void resolveStoredCredentialsMissingCredentialFailsClosedWithLoginHint() {
        SourceCredentialResolver resolver =
                resolver(NOT_STORED_LOCAL, NEVER_LOCAL_METADATA, REMOTE_NOT_FOUND, NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resolver.resolveStoredCredentials("NOTION", properties));
        assertTrue(error.getMessage().contains("NOTION needs a credential and none is connected"),
                error.getMessage());
        assertTrue(error.getMessage().contains("kompile auth login notion"), error.getMessage());
    }

    @Test
    void resolveStoredCredentialsControlPlaneFailureFailsClosedWithWrappedCause() {
        IOException cause = new IOException("connection refused");
        SourceCredentialResolver resolver =
                resolver(NOT_STORED_LOCAL, NEVER_LOCAL_METADATA, remoteFails(cause), NEVER_CHANNEL);
        Map<String, Object> properties = new LinkedHashMap<>();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resolver.resolveStoredCredentials("NOTION", properties));
        assertTrue(error.getMessage().contains("Could not reach the Kompile control plane"), error.getMessage());
        assertTrue(error.getMessage().contains("connection refused"), error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    void createFactoryWiresRealCollaboratorsWithoutThrowing() {
        assertNull(null, "placeholder"); // keep static-analysis happy if create() ever needs a smoke test
        SourceCredentialResolver resolver = SourceCredentialResolver.create();
        // No stored credential/channel connection exists in the test environment, and CONFLUENCE
        // already has an explicit apiToken, so this must short-circuit before touching any real
        // CredentialStore, network client, or channel control plane.
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("apiToken", "explicit"));
        assertDoesNotThrow(() -> resolver.resolveStoredCredentials("CONFLUENCE", properties));
    }
}
