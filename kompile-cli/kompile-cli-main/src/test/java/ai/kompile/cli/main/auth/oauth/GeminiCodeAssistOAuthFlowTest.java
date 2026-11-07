/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@link GeminiCodeAssistOAuthFlow} uses the user's own client registration; Kompile ships none. */
@TemporaryUserHome
class GeminiCodeAssistOAuthFlowTest {
    private static final OAuthProviderFlow.LoginOptions MANUAL =
            new OAuthProviderFlow.LoginOptions("browser", true, null, null);

    @BeforeEach
    void noRegistrationFromTheEnvironment() throws IOException {
        assumeTrue(System.getenv(GeminiCodeAssistOAuthFlow.CLIENT_ID_ENV) == null
                        && System.getenv(GeminiCodeAssistOAuthFlow.CLIENT_SECRET_ENV) == null,
                "a registration in the environment would mask the stored one");
        OAuthClientSettings.create().remove(GeminiCodeAssistOAuthFlow.PROVIDER_ID);
    }

    @Test
    void withoutARegistrationLoginStopsBeforeContactingGoogle() {
        List<OAuthSupport.Request> requests = new ArrayList<>();
        GeminiCodeAssistOAuthFlow flow = new GeminiCodeAssistOAuthFlow(request -> {
            requests.add(request);
            return new OAuthSupport.Response(500, "");
        });
        Interaction interaction = new Interaction("unused");

        IOException failure = assertThrows(IOException.class, () -> flow.login(MANUAL, interaction));

        assertTrue(failure.getMessage().contains("kompile auth login gemini"), failure.getMessage());
        assertTrue(interaction.authorizationUrls.isEmpty(), "no consent page without a registration");
        assertTrue(requests.isEmpty(), "no request to Google without a registration");
    }

    @Test
    void theStoredRegistrationIsTheOneSentToGoogle() throws Exception {
        OAuthClientSettings.create().put(GeminiCodeAssistOAuthFlow.PROVIDER_ID,
                new OAuthClientSettings.ClientCredentials("test-client.apps.googleusercontent.com",
                        "test-client-secret", null));
        List<OAuthSupport.Request> requests = new ArrayList<>();
        GeminiCodeAssistOAuthFlow flow = new GeminiCodeAssistOAuthFlow(request -> {
            requests.add(request);
            if (request.uri().toString().contains("/token")) {
                return new OAuthSupport.Response(200, "{\"access_token\":\"access-1\","
                        + "\"refresh_token\":\"refresh-1\",\"expires_in\":3600}");
            }
            return new OAuthSupport.Response(200, "{\"email\":\"someone@example.com\"}");
        });
        Interaction interaction = new Interaction("auth-code-1");

        ManagedCredential credential = flow.login(MANUAL, interaction);

        assertEquals(1, interaction.authorizationUrls.size());
        assertTrue(interaction.authorizationUrls.get(0).getQuery()
                .contains("client_id=test-client.apps.googleusercontent.com"));
        OAuthSupport.Request token = requests.get(0);
        assertTrue(token.uri().toString().contains("/token"), token.uri().toString());
        assertTrue(token.body().contains("client_id=test-client.apps.googleusercontent.com"), token.body());
        assertTrue(token.body().contains("client_secret=test-client-secret"), token.body());
        assertTrue(token.body().contains("code=auth-code-1"), token.body());
        assertEquals("access-1", credential.getAccess());
        assertEquals("test-client.apps.googleusercontent.com", credential.getMetadata("clientId"));
    }

    /** Captures the consent URL and answers the manual-code prompt. */
    private static final class Interaction implements OAuthProviderFlow.Interaction {
        final List<URI> authorizationUrls = new ArrayList<>();
        private final String code;

        Interaction(String code) {
            this.code = code;
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
            authorizationUrls.add(url);
        }

        @Override
        public void deviceCode(String userCode, URI verificationUri,
                               Integer intervalSeconds, Integer expiresInSeconds) {
        }

        @Override
        public String prompt(String message) {
            return code;
        }
    }
}
