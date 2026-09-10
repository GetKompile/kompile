package ai.kompile.oauth.service.providers;

import ai.kompile.oauth.service.OAuthSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlackOAuthHandlerTest {

    @Test
    void channelPurposeAddsFixedConversationScopesWithoutBroadeningSourceDefault() {
        SlackOAuthHandler handler = new SlackOAuthHandler(
                mock(RestTemplate.class), new ObjectMapper());
        OAuthSettingsService settings = mock(OAuthSettingsService.class);
        when(settings.getClientId("slack")).thenReturn("client-id");
        when(settings.getScopes("slack"))
                .thenReturn("channels:history channels:read users:read");
        handler.setSettingsService(settings);

        assertFalse(handler.getRequiredScopes().contains("chat:write"));
        assertTrue(handler.getRequiredScopes("channel").contains("chat:write"));
        assertTrue(handler.getRequiredScopes("channel").contains("app_mentions:read"));
        assertTrue(handler.getRequiredScopes("channel").contains("groups:history"));

        String authorization = URLDecoder.decode(handler.buildAuthorizationUrl(
                "https://example.test/api/oauth/slack/callback", "state", "channel"),
                StandardCharsets.UTF_8);
        assertTrue(authorization.contains("chat:write"));
        assertTrue(authorization.contains("channels:history"));
    }
}
