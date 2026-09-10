/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.sync.config.NoteSyncConfig;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.service.NoteSyncConnectionService;
import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotionWebhookControllerTest {

    @Test
    void failsClosedWithoutConfiguredSecret() throws Exception {
        Fixture fixture = fixture("");

        fixture.mvc.perform(post("/api/sync/webhook/notion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void acceptsOnlyValidHmacAndTriggersEnabledNotionConnections() throws Exception {
        String body = "{\"type\":\"page.updated\",\"entity\":{\"id\":\"page-1\"}}";
        Fixture fixture = fixture("webhook-secret");
        NoteSyncConnection connection = NoteSyncConnection.builder()
                .id(7L).factSheetId(3L).provider(SyncProvider.NOTION)
                .externalScope("page-1").enabled(true).build();
        when(fixture.repository.findByEnabledTrue()).thenReturn(List.of(connection));

        fixture.mvc.perform(post("/api/sync/webhook/notion")
                        .header("X-Notion-Signature", "sha256=" + hmac(body, "webhook-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(fixture.service).triggerSync(7L);
    }

    private Fixture fixture(String secret) {
        NoteSyncConfigService config = mock(NoteSyncConfigService.class);
        when(config.isNotionEnabled()).thenReturn(true);
        when(config.getConfiguration()).thenReturn(new NoteSyncConfig(
                true, secret, null, true, false, false, 60_000L));
        NoteSyncConnectionRepository repository = mock(NoteSyncConnectionRepository.class);
        NoteSyncConnectionService service = mock(NoteSyncConnectionService.class);
        NotionWebhookController controller = new NotionWebhookController();
        ReflectionTestUtils.setField(controller, "configService", config);
        ReflectionTestUtils.setField(controller, "connectionRepository", repository);
        ReflectionTestUtils.setField(controller, "connectionService", service);
        ReflectionTestUtils.setField(controller, "objectMapper", JsonUtils.standardMapper());
        return new Fixture(MockMvcBuilders.standaloneSetup(controller).build(), repository, service);
    }

    private static String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(
            MockMvc mvc,
            NoteSyncConnectionRepository repository,
            NoteSyncConnectionService service) {
    }
}
