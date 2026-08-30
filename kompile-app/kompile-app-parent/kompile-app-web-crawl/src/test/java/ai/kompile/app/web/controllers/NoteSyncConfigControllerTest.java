/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.sync.config.NoteSyncConfig;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoteSyncConfigControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistedConfigDeserializesAllSettings() throws Exception {
        NoteSyncConfig config = objectMapper.readValue("""
                {
                  "notionEnabled": true,
                  "notionWebhookSecret": "secret",
                  "notionCallbackBaseUrl": "https://callback.example",
                  "obsidianEnabled": true,
                  "obsidianFileWatchEnabled": true,
                  "schedulerEnabled": true,
                  "schedulerCheckIntervalMs": 15000
                }
                """, NoteSyncConfig.class);

        assertAll(
                () -> assertEquals(Boolean.TRUE, config.getNotionEnabled()),
                () -> assertEquals("secret", config.getNotionWebhookSecret()),
                () -> assertEquals("https://callback.example", config.getNotionCallbackBaseUrl()),
                () -> assertEquals(Boolean.TRUE, config.getObsidianEnabled()),
                () -> assertEquals(Boolean.TRUE, config.getObsidianFileWatchEnabled()),
                () -> assertEquals(Boolean.TRUE, config.getSchedulerEnabled()),
                () -> assertEquals(15_000L, config.getSchedulerCheckIntervalMs()));
    }

    @Test
    void configResponsesExposeOnlySecretPresence() throws Exception {
        NoteSyncConfigService service = mock(NoteSyncConfigService.class);
        NoteSyncConfig configured = NoteSyncConfig.builder()
                .notionEnabled(true)
                .notionWebhookSecret("never-return-this-value")
                .notionCallbackBaseUrl("https://callback.example")
                .build();
        when(service.getConfiguration()).thenReturn(configured);
        when(service.updateConfiguration(any(NoteSyncConfig.class))).thenReturn(configured);
        when(service.resetConfiguration()).thenReturn(configured);
        NoteSyncConfigController controller = new NoteSyncConfigController(service);

        NoteSyncConfigController.NoteSyncConfigUpdateRequest update = objectMapper.readValue(
                "{\"notionEnabled\":false,\"notionWebhookSecretConfigured\":true}",
                NoteSyncConfigController.NoteSyncConfigUpdateRequest.class);

        assertSafe(controller.getConfig().getBody());
        assertSafe(controller.updateConfig(update).getBody());
        assertSafe(controller.resetConfig().getBody());

        ArgumentCaptor<NoteSyncConfig> submitted = ArgumentCaptor.forClass(NoteSyncConfig.class);
        verify(service).updateConfiguration(submitted.capture());
        assertEquals(Boolean.FALSE, submitted.getValue().getNotionEnabled());
        assertNull(submitted.getValue().getNotionWebhookSecret(),
                "omitting the write-only secret must preserve its existing value");
    }

    private void assertSafe(NoteSyncConfigController.NoteSyncConfigResponse response)
            throws Exception {
        assertNotNull(response);
        assertTrue(response.notionWebhookSecretConfigured());
        String json = objectMapper.writeValueAsString(response);
        assertFalse(json.contains("never-return-this-value"));
        assertFalse(json.contains("\"notionWebhookSecret\":"));
        assertTrue(json.contains("\"notionWebhookSecretConfigured\":true"));
    }
}
