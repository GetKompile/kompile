package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelCredentialResolverTest {

    @Test
    void slackOAuthTokenStaysServerSideAndAppTokenComesFromEnvironment() {
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.getRequiredScopes("slack", "channel"))
                .thenReturn(List.of("chat:write", "channels:read"));
        when(oauth.getGrantedScopes("slack"))
                .thenReturn(List.of("channels:read", "chat:write"));
        when(oauth.getCurrentAccessToken("slack")).thenReturn("xoxb-oauth-token");
        ChannelCredentialResolver resolver = new ChannelCredentialResolver(
                new ChannelProviderCatalog(), oauth,
                name -> "SLACK_APP_TOKEN".equals(name) ? "xapp-server-token" : null);

        var resolved = resolver.resolve(slackConnection(), Map.of());

        assertEquals("xoxb-oauth-token", resolved.secrets().get("botToken"));
        assertEquals("xapp-server-token", resolved.secrets().get("appToken"));
        assertEquals("oauth:slack", resolved.sources().get("botToken"));
        assertFalse(resolved.revision().contains("xoxb-oauth-token"));
        assertTrue(resolver.auth("slack").credentialReady());
    }

    @Test
    void oldSourceOnlySlackGrantFailsClosedForConversationRuntime() {
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.getRequiredScopes("slack", "channel"))
                .thenReturn(List.of("channels:read", "chat:write"));
        when(oauth.getGrantedScopes("slack")).thenReturn(List.of("channels:read"));
        when(oauth.getCurrentAccessToken("slack")).thenReturn("xoxb-source-token");
        ChannelCredentialResolver resolver = new ChannelCredentialResolver(
                new ChannelProviderCatalog(), oauth, name -> "xapp" );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(slackConnection(), Map.of()));

        assertTrue(error.getMessage().contains("chat:write"));
        assertFalse(resolver.auth("slack").channelScopesGranted());
    }

    @Test
    void manualOnlyProvidersReturnTruthfulGuidance() {
        ChannelCredentialResolver resolver = ChannelCredentialResolver.explicitOnly(
                new ChannelProviderCatalog());

        var discord = resolver.auth("discord");

        assertFalse(discord.loginSupported());
        assertTrue(discord.guidance().contains("never returns its bot token"));
        assertTrue(discord.missingCredentialFields().contains("botToken"));
    }

    private static StoredChannelConnection slackConnection() {
        Instant now = Instant.now();
        return new StoredChannelConnection(
                UUID.randomUUID(), "ops", "slack", ChannelChatEngine.REACT,
                "jarvis", null, false,
                Map.of("useOAuth", true, "allowedChannelIds", List.of("C01"),
                        "respondToAllMessages", false, "allowAllInbound", false,
                        "allowHarnessSend", true),
                Map.of(), now, now);
    }
}
