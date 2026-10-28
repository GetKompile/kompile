/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.auth.oauth.ClaudeCodeOAuthFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ONE Anthropic vendor contract: a single wizard entry offering BOTH
 * API key and OAuth (managed setup token). No separate Claude vendor exists.
 */
class AnthropicVendorLoginKindsTest {

    @Test
    void anthropicIsOneVendorOfferingBothApiKeyAndOauth() throws Exception {
        OAuthProviderRegistry registry = new OAuthProviderRegistry();
        assertTrue(registry.find(ClaudeCodeOAuthFlow.PROVIDER_ID).isPresent(),
                "ClaudeCodeOAuthFlow must be registered");

        String oauthProviderId =
                registry.oauthProviderForVendor("anthropic").orElse(null);
        assertEquals(ClaudeCodeOAuthFlow.PROVIDER_ID, oauthProviderId,
                "the anthropic vendor must resolve to the setup-token flow");

        // Mirror AuthWizard.loginKinds decisioning for vendor "anthropic":
        List<AuthWizard.LoginKind> kinds =
                AuthWizard.loginKinds(registry, "anthropic", oauthProviderId);
        assertTrue(kinds.contains(AuthWizard.LoginKind.OAUTH),
                "anthropic must offer the OAuth setup-token method");
        assertTrue(kinds.contains(AuthWizard.LoginKind.API_KEY),
                "anthropic must keep offering API-key login");
        assertFalse(kinds.contains(AuthWizard.LoginKind.NATIVE),
                "native delegation must not shadow Kompile's own flow");
        assertEquals(AuthWizard.LoginKind.OAUTH, kinds.get(0),
                "OAuth is the first offered method");
    }

    @Test
    void noSeparateClaudeVendorEntryExists() throws Exception {
        OAuthProviderRegistry registry = new OAuthProviderRegistry();
        // The flow answers for "anthropic"; "claude" as a VENDOR label must not
        // resolve to its own entry (agent command matching is not vendor listing).
        assertTrue(registry.oauthProviderForVendor("claude").isEmpty(),
                "no separate claude vendor may be registered");
    }
}
