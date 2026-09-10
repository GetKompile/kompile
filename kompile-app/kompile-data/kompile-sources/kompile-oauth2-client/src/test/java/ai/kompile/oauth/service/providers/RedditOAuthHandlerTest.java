/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.oauth.service.providers;

import ai.kompile.oauth.service.OAuthSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RedditOAuthHandlerTest {

    @Test
    void buildsPermanentAuthorizationAndUsesBasicAuthForTokenExchange() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        OAuthSettingsService settings = mock(OAuthSettingsService.class);
        when(settings.getClientId("reddit")).thenReturn("client-id");
        when(settings.getClientSecret("reddit")).thenReturn("client-secret");
        when(settings.getScopes("reddit")).thenReturn("identity read");
        RedditOAuthHandler handler = new RedditOAuthHandler(restTemplate, new ObjectMapper());
        handler.setSettingsService(settings);

        String authorization = handler.buildAuthorizationUrl(
                "https://example.test/api/oauth/reddit/callback", "csrf-state");
        assertTrue(authorization.contains("duration=permanent"));
        assertTrue(authorization.contains("scope=identity+read"));
        assertEquals(java.util.List.of("reddit"), handler.getRelatedSources());

        server.expect(requestTo("https://www.reddit.com/api/v1/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ="))
                .andExpect(header("User-Agent", "Kompile/0.1 (Reddit source integration)"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600,\"scope\":\"identity read\"}",
                        MediaType.APPLICATION_JSON));

        var token = handler.exchangeCodeForTokens("code", "https://example.test/api/oauth/reddit/callback");
        assertTrue(token.isSuccess());
        assertEquals("refresh", token.getRefreshToken());
        server.verify();
    }
}
