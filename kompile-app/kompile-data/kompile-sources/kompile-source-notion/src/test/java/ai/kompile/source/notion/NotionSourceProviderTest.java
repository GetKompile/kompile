/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.notion;

import ai.kompile.oauth.service.OAuthConnectionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotionSourceProviderTest {
    @Test
    void usableOauthMakesManualIntegrationTokenOptional() {
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.isConnectionUsable("notion")).thenReturn(true);
        NotionSourceProvider provider = new NotionSourceProvider(oauth);

        assertFalse(provider.requiresAuth());
        assertFalse(provider.getFormFields().stream()
                .filter(field -> "apiToken".equals(field.getId()))
                .findFirst().orElseThrow().isRequired());
    }

    @Test
    void missingUsableOauthStillRequiresAuthentication() {
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.isConnectionUsable("notion")).thenReturn(false);

        assertTrue(new NotionSourceProvider(oauth).requiresAuth());
        assertTrue(new NotionSourceProvider(null).requiresAuth());
    }
}
