/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.config;

import ai.kompile.oauth.service.TokenEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoteSyncConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsWebhookSecretAtRestAndRestoresIt() throws Exception {
        TokenEncryptionService encryption = encryption();
        NoteSyncConfigService service = new NoteSyncConfigService(encryption, tempDir.toString());
        service.loadConfig();
        service.updateConfiguration(NoteSyncConfig.builder()
                .notionEnabled(true).notionWebhookSecret("webhook-secret").build());

        Path config = tempDir.resolve("config/note-sync-config.json");
        String persisted = Files.readString(config);
        assertFalse(persisted.contains("webhook-secret"));
        assertTrue(persisted.contains("enc:ciphertext"));

        NoteSyncConfigService restarted = new NoteSyncConfigService(encryption, tempDir.toString());
        restarted.loadConfig();
        assertEquals("webhook-secret", restarted.getConfiguration().getNotionWebhookSecret());
        assertEquals("http://localhost:8082",
                NoteSyncConfig.defaults().getNotionCallbackBaseUrl());
    }

    @Test
    void migratesLegacyPlaintextWebhookSecret() throws Exception {
        Path config = tempDir.resolve("config/note-sync-config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"notionWebhookSecret\":\"webhook-secret\"}");

        NoteSyncConfigService service = new NoteSyncConfigService(encryption(), tempDir.toString());
        service.loadConfig();

        assertEquals("webhook-secret", service.getConfiguration().getNotionWebhookSecret());
        assertFalse(Files.readString(config).contains("webhook-secret"));
    }

    private static TokenEncryptionService encryption() {
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        when(encryption.encrypt("webhook-secret")).thenReturn("ciphertext");
        when(encryption.decrypt("ciphertext")).thenReturn("webhook-secret");
        return encryption;
    }
}
