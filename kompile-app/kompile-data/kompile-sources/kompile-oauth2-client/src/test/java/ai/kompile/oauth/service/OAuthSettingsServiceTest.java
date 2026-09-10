/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.oauth.service;

import ai.kompile.oauth.dto.OAuthProviderSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthSettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsClientSecretEncryptedAndRestoresItAfterRestart() throws Exception {
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        when(encryption.encrypt("super-secret-value")).thenReturn("ciphertext");
        when(encryption.decrypt("ciphertext")).thenReturn("super-secret-value");
        OAuthSettingsService first = new OAuthSettingsService(
                encryption, new ObjectMapper(), tempDir.toString());
        first.init();

        first.saveSettings(OAuthProviderSettings.builder()
                .providerId("notion")
                .clientId("client-id")
                .clientSecret("super-secret-value")
                .build());

        Path sidecar = tempDir.resolve("config/secrets/oauth-secrets.json");
        String stored = Files.readString(sidecar);
        assertFalse(stored.contains("super-secret-value"));
        OAuthSettingsService restarted = new OAuthSettingsService(
                encryption, new ObjectMapper(), tempDir.toString());
        restarted.init();
        assertEquals("super-secret-value", restarted.getClientSecret("notion"));

        restarted.saveSettings(OAuthProviderSettings.builder()
                .providerId("notion")
                .clientId("updated-client")
                .clientSecret("********")
                .build());
        assertEquals("super-secret-value", restarted.getClientSecret("notion"));
    }

    @Test
    void migratesLegacyPlaintextSecretsAndRejectsWhitespaceCredentials() throws Exception {
        Path sidecar = tempDir.resolve("config/secrets/oauth-secrets.json");
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"slack.client-secret\":\"legacy-secret\"}");
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        when(encryption.encrypt("legacy-secret")).thenReturn("migrated-ciphertext");
        when(encryption.decrypt("migrated-ciphertext")).thenReturn("legacy-secret");

        OAuthSettingsService service = new OAuthSettingsService(
                encryption, new ObjectMapper(), tempDir.toString());
        service.init();

        assertEquals("legacy-secret", service.getClientSecret("slack"));
        String migrated = Files.readString(sidecar);
        assertFalse(migrated.contains("legacy-secret"));
        assertTrue(migrated.contains("enc:migrated-ciphertext"));
        assertFalse(OAuthProviderSettings.builder()
                .clientId("   ").clientSecret("\t").build().hasValidCredentials());
    }

    @Test
    void advertisesRedditAndJiraScopesThroughCentralOAuthSettings() {
        OAuthSettingsService service = new OAuthSettingsService(
                mock(TokenEncryptionService.class), new ObjectMapper(), tempDir.toString());

        assertTrue(service.getProviderIds().contains("reddit"));
        assertTrue(service.getScopes("reddit").contains("read"));
        assertTrue(service.getScopes("atlassian").contains("read:jira-work"));
    }
}
