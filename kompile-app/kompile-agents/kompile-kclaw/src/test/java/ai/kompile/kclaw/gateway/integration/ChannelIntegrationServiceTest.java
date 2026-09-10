package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelConnectionRequest;
import ai.kompile.channel.api.ChannelConnectionUpdate;
import ai.kompile.channel.api.ChannelConnectionView.RuntimeState;
import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.model.AgentDefinition;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.oauth.service.TokenEncryptionService;
import ai.kompile.oauth.service.OAuthConnectionService;
import ai.kompile.kclaw.gateway.telegram.TelegramPairingRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelIntegrationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsOnlyEncryptedSecretsAndReturnsSecretFreeViews() throws Exception {
        Fixture fixture = fixture();

        var created = fixture.service.create(new ChannelConnectionRequest(
                "Support_Bot",
                "telegram",
                ChannelChatEngine.REACT,
                "jarvis",
                null,
                Map.of("allowedChatIds", List.of(42L)),
                Map.of("botToken", "telegram-plain-text"),
                false));

        assertEquals("support_bot", created.name());
        assertEquals(RuntimeState.DISABLED, created.runtimeState());
        assertEquals(Map.of("allowedChatIds", List.of(42L), "allowAllInbound", false,
                        "allowHarnessSend", false),
                created.settings());
        assertEquals(java.util.Set.of("botToken"), created.configuredSecrets());

        String persisted = Files.readString(fixture.store.storePath());
        assertFalse(persisted.contains("telegram-plain-text"));
        assertTrue(persisted.contains("cipher:"));

        fixture.service.update("support_bot", new ChannelConnectionUpdate(
                null, null, null, Map.of(), Map.of("botToken", "rotated-token"), null));
        String rotated = Files.readString(fixture.store.storePath());
        assertFalse(rotated.contains("telegram-plain-text"));
        assertFalse(rotated.contains("\"rotated-token\""));
        assertTrue(rotated.contains("cipher:"));
    }

    @Test
    void enableRestoresNamedRuntimeAndTestDeliveryReportsTruthfully() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(new ChannelConnectionRequest(
                "ops", "telegram", ChannelChatEngine.REACT, "jarvis", null,
                Map.of("allowedChatIds", List.of(42L), "allowHarnessSend", true),
                Map.of("botToken", "token"), false));

        ChannelAdapter adapter = mock(ChannelAdapter.class);
        AtomicBoolean running = new AtomicBoolean();
        when(adapter.getChannelName()).thenReturn("telegram");
        when(adapter.getAdapterConfig()).thenReturn(ChannelAdapter.AdapterConfig.defaults("*", "jarvis"));
        when(adapter.isRunning()).thenAnswer(ignored -> running.get());
        when(adapter.isReady()).thenAnswer(ignored -> running.get());
        doAnswer(ignored -> {
            running.set(true);
            return null;
        }).when(adapter).start();
        doAnswer(ignored -> {
            running.set(false);
            return null;
        }).when(adapter).stop();
        when(adapter.send("42", "hello"))
                .thenReturn(ChannelAdapter.DeliveryResult.accepted("accepted"));
        when(fixture.runtimeFactory.create(any(), any())).thenReturn(adapter);

        assertEquals(RuntimeState.RUNNING, fixture.service.enable("ops").runtimeState());
        assertEquals(RuntimeState.RUNNING, fixture.service.enable("ops").runtimeState());
        assertTrue(fixture.service.test("ops", new ChannelTestRequest("42", "hello")).accepted());
        assertTrue(fixture.service.deliver(
                "ops", new ChannelTestRequest("42", "hello")).accepted());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> fixture.service.deliver(
                        "ops", new ChannelTestRequest("99", "blocked")));
        assertEquals(RuntimeState.DISABLED, fixture.service.disable("ops").runtimeState());
        verify(adapter).stop();
        org.mockito.Mockito.verify(fixture.runtimeFactory, org.mockito.Mockito.times(1))
                .create(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void supervisorAllowsAsyncStartupButReplacesAConnectionThatNeverBecomesReady() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(new ChannelConnectionRequest(
                "community", "discord", ChannelChatEngine.WEB_CHAT, null, null,
                Map.of("allowedGuildIds", List.of("guild-1")),
                Map.of("botToken", "token"), false));
        ChannelAdapter starting = mock(ChannelAdapter.class);
        AtomicBoolean startingRunning = new AtomicBoolean();
        when(starting.getChannelName()).thenReturn("discord");
        when(starting.isRunning()).thenAnswer(ignored -> startingRunning.get());
        when(starting.isReady()).thenReturn(false);
        doAnswer(ignored -> { startingRunning.set(true); return null; }).when(starting).start();
        doAnswer(ignored -> { startingRunning.set(false); return null; }).when(starting).stop();
        ChannelAdapter replacement = mock(ChannelAdapter.class);
        when(replacement.getChannelName()).thenReturn("discord");
        when(replacement.isRunning()).thenReturn(true);
        when(replacement.isReady()).thenReturn(true);
        when(fixture.runtimeFactory.create(any(), any())).thenReturn(starting, replacement);

        assertEquals(RuntimeState.STARTING, fixture.service.enable("community").runtimeState());
        fixture.service.restoreEnabledConnections();
        org.mockito.Mockito.verify(fixture.runtimeFactory).create(any(), any());

        java.lang.reflect.Field starts = ChannelIntegrationService.class
                .getDeclaredField("runtimeStartedAt");
        starts.setAccessible(true);
        ((Map<String, Instant>) starts.get(fixture.service)).put(
                "community", Instant.now().minus(Duration.ofMinutes(2)));
        fixture.service.restoreEnabledConnections();

        verify(starting).stop();
        org.mockito.Mockito.verify(fixture.runtimeFactory, org.mockito.Mockito.times(2))
                .create(any(), any());
        assertEquals(RuntimeState.RUNNING, fixture.service.get("community").runtimeState());
    }

    @Test
    void failedPersistenceDoesNotPublishAnInMemoryMutation() throws Exception {
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied");
        ChannelConnectionStore store = new ChannelConnectionStore(
                parentFile.resolve("connections.json"), JsonUtils.standardMapper());
        StoredChannelConnection record = new StoredChannelConnection(
                java.util.UUID.randomUUID(), "ops", "telegram", ChannelChatEngine.REACT,
                "jarvis", null, false,
                Map.of(), Map.of("botToken", "cipher"),
                java.time.Instant.now(), java.time.Instant.now());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> store.save(record));
        assertTrue(store.get("ops").isEmpty());
    }

    @Test
    void failedEnableRollsBackTheDurableDesiredState() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(new ChannelConnectionRequest(
                "ops", "telegram", ChannelChatEngine.REACT, "jarvis", null,
                Map.of(), Map.of("botToken", "token"), false));
        when(fixture.runtimeFactory.create(any(), any()))
                .thenThrow(new IllegalStateException("invalid credentials"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> fixture.service.enable("ops"));

        assertEquals(RuntimeState.DISABLED, fixture.service.get("ops").runtimeState());
        assertFalse(fixture.service.get("ops").enabled());
    }

    @Test
    void disconnectRemovesTelegramCheckpointAlongsideCredentials() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(new ChannelConnectionRequest(
                "ops", "telegram", ChannelChatEngine.REACT, "jarvis", null,
                Map.of(), Map.of("botToken", "token"), false));

        assertTrue(fixture.service.disconnect("ops"));

        verify(fixture.runtimeFactory).deleteTelegramState("ops");
        assertTrue(fixture.store.get("ops").isEmpty());
    }

    @Test
    void failedRuntimeReplacementRestartsPreviousConnection() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(new ChannelConnectionRequest(
                "ops", "telegram", ChannelChatEngine.REACT, "jarvis", null,
                Map.of(), Map.of("botToken", "token"), false));
        AtomicBoolean previousRunning = new AtomicBoolean();
        ChannelAdapter previous = mock(ChannelAdapter.class);
        when(previous.getChannelName()).thenReturn("telegram");
        when(previous.getAdapterConfig()).thenReturn(ChannelAdapter.AdapterConfig.defaults("*", "jarvis"));
        when(previous.isRunning()).thenAnswer(ignored -> previousRunning.get());
        when(previous.isReady()).thenAnswer(ignored -> previousRunning.get());
        doAnswer(ignored -> { previousRunning.set(true); return null; }).when(previous).start();
        doAnswer(ignored -> { previousRunning.set(false); return null; }).when(previous).stop();
        ChannelAdapter replacement = mock(ChannelAdapter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("replacement failed"))
                .when(replacement).start();
        when(fixture.runtimeFactory.create(any(), any()))
                .thenReturn(previous)
                .thenReturn(replacement);
        fixture.service.enable("ops");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> fixture.service.update("ops", new ChannelConnectionUpdate(
                        null, null, null, Map.of("allowedChatIds", List.of(42L)), Map.of(), null)));

        assertTrue(previousRunning.get());
        verify(previous).stop();
        org.mockito.Mockito.verify(previous, org.mockito.Mockito.times(2)).start();
        assertEquals(RuntimeState.RUNNING, fixture.service.get("ops").runtimeState());
    }

    @Test
    void credentialHandoffResolvesRuntimeSecretsAndStaysOutOfListingsAndViews() throws Exception {
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.getRequiredScopes("slack", "channel")).thenReturn(List.of("channels:read"));
        when(oauth.getGrantedScopes("slack")).thenReturn(List.of("channels:read"));
        when(oauth.getCurrentAccessToken("slack")).thenReturn("xoxb-derived");
        ChannelProviderCatalog catalog = new ChannelProviderCatalog();
        ChannelCredentialResolver resolver = new ChannelCredentialResolver(
                catalog, oauth, name -> null);
        Fixture fixture = fixture(resolver);
        fixture.service.create(new ChannelConnectionRequest(
                "support_bot", "discord", ChannelChatEngine.REACT, "jarvis", null,
                Map.of("allowedChannelIds", List.of("C01")),
                Map.of("botToken", "discord-plain"), false));

        ChannelCredentialView credential = fixture.service.credential("support_bot");

        assertEquals("discord", credential.providerId());
        assertEquals("discord-plain", credential.secrets().get("botToken"));
        assertEquals("C01", ((java.util.List<?>) credential.properties().get("allowedChannelIds")).get(0));
        assertTrue(fixture.service.list().stream()
                .noneMatch(view -> String.valueOf(view).contains("discord-plain")));

        // OAuth-backed Slack resolves its derived bot token for ingestion
        // (Socket Mode still requires the separate appToken secret).
        fixture.service.create(new ChannelConnectionRequest(
                "slack-ops", "slack", ChannelChatEngine.REACT, "jarvis", null,
                Map.of("useOAuth", true), Map.of("appToken", "xapp-server"), false));
        assertEquals("xoxb-derived", fixture.service.credential("slack-ops").secrets().get("botToken"));

        // The derived OAuth token must not be persisted in the store file.
        assertFalse(Files.readString(fixture.store.storePath()).contains("xoxb-derived"));
    }

    @Test
    void unknownReactAgentIsRejectedInsteadOfFallingBackToDefault() throws Exception {
        Fixture fixture = fixture();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> fixture.service.create(new ChannelConnectionRequest(
                        "ops", "telegram", ChannelChatEngine.REACT, "typo-agent", null,
                        Map.of(), Map.of("botToken", "token"), false)));

        assertTrue(fixture.store.get("ops").isEmpty());
    }

    @Test
    void oauthBackedSlackUsesDerivedTokenWithoutPersistingItAndAllowsOnlyOneBinding()
            throws Exception {
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.getRequiredScopes("slack", "channel"))
                .thenReturn(List.of("chat:write", "channels:read"));
        when(oauth.getGrantedScopes("slack"))
                .thenReturn(List.of("channels:read", "chat:write"));
        when(oauth.getCurrentAccessToken("slack")).thenReturn("xoxb-derived");
        ChannelProviderCatalog catalog = new ChannelProviderCatalog();
        ChannelCredentialResolver resolver = new ChannelCredentialResolver(
                catalog, oauth,
                name -> "SLACK_APP_TOKEN".equals(name) ? "xapp-server" : null);
        Fixture fixture = fixture(resolver);

        var created = fixture.service.create(new ChannelConnectionRequest(
                "ops", "slack", ChannelChatEngine.REACT, "jarvis", null,
                Map.of("useOAuth", true, "allowedChannelIds", List.of("C01")),
                Map.of(), false));

        assertTrue(created.configuredSecrets().isEmpty());
        assertFalse(Files.readString(fixture.store.storePath()).contains("xoxb-derived"));
        assertTrue(fixture.service.providerAuth("slack").channelScopesGranted());
        org.junit.jupiter.api.Assertions.assertThrows(
                ChannelIntegrationService.ChannelConnectionConflictException.class,
                () -> fixture.service.create(new ChannelConnectionRequest(
                        "other", "slack", ChannelChatEngine.REACT, "jarvis", null,
                        Map.of("useOAuth", true, "allowedChannelIds", List.of("C02")),
                        Map.of(), false)));
    }

    private Fixture fixture() throws Exception {
        return fixture(null);
    }

    private Fixture fixture(ChannelCredentialResolver credentialResolver) throws Exception {
        ChannelConnectionStore store = new ChannelConnectionStore(
                tempDir.resolve("channel-connections.json"), JsonUtils.standardMapper());
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(invocation -> "cipher:"
                + java.util.Base64.getEncoder().encodeToString(
                        ((String) invocation.getArgument(0)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(encryption.decrypt(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return new String(
                    java.util.Base64.getDecoder().decode(value.substring("cipher:".length())),
                    java.nio.charset.StandardCharsets.UTF_8);
        });
        ChannelRuntimeFactory runtimeFactory = mock(ChannelRuntimeFactory.class);
        ChannelEngineRegistry engines = mock(ChannelEngineRegistry.class);
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        when(agentRegistry.getAgent("jarvis")).thenReturn(java.util.Optional.of(
                AgentDefinition.builder().name("jarvis").build()));
        ChannelProviderCatalog catalog = new ChannelProviderCatalog();
        ChannelIntegrationService service = credentialResolver == null
                ? new ChannelIntegrationService(
                        store, catalog, engines, agentRegistry, new TelegramPairingRegistry(),
                        runtimeFactory, new ChannelManager(), encryption)
                : new ChannelIntegrationService(
                        store, catalog, engines, agentRegistry, new TelegramPairingRegistry(),
                        runtimeFactory, new ChannelManager(), encryption, credentialResolver);
        return new Fixture(store, runtimeFactory, service);
    }

    private record Fixture(
            ChannelConnectionStore store,
            ChannelRuntimeFactory runtimeFactory,
            ChannelIntegrationService service) {
    }
}
