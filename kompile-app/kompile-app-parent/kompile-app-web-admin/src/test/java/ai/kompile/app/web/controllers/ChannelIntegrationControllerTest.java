package ai.kompile.app.web.controllers;

import ai.kompile.channel.api.ChannelChatEngine;
import ai.kompile.channel.api.ChannelConnectionView;
import ai.kompile.channel.api.ChannelConnectionView.RuntimeState;
import ai.kompile.channel.api.ChannelProviderAuthView;
import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.TelegramPairingStartView;
import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.app.web.security.IntegrationControlSecurityFilter;
import ai.kompile.kclaw.gateway.integration.ChannelIntegrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChannelIntegrationControllerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void createReturnsSecretFreeConnectionView() throws Exception {
        ChannelIntegrationService service = mock(ChannelIntegrationService.class);
        ChannelConnectionView view = view();
        when(service.create(any())).thenReturn(view);
        MockMvc mvc = securedMvc(service);

        mvc.perform(post("/api/channel-integrations/connections")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ops","providerId":"telegram","agentId":"jarvis",
                                 "settings":{},"secrets":{"botToken":"never-return-this"},"enabled":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ops"))
                .andExpect(jsonPath("$.configuredSecrets[0]").value("botToken"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("never-return-this"))));
        verify(service).create(any());
    }

    @Test
    void validationFailuresUseStableBadRequestEnvelope() throws Exception {
        ChannelIntegrationService service = mock(ChannelIntegrationService.class);
        when(service.get("missing")).thenThrow(
                new ChannelIntegrationService.ChannelConnectionNotFoundException("does not exist"));
        MockMvc mvc = securedMvc(service);

        mvc.perform(get("/api/channel-integrations/connections/missing")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("channel_connection_not_found"));
    }

    @Test
    void rejectsUnauthenticatedRequestsBeforeControllerInvocation() throws Exception {
        ChannelIntegrationService service = mock(ChannelIntegrationService.class);
        securedMvc(service).perform(get("/api/channel-integrations/connections"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesAuthenticatedTelegramPairingLifecycle() throws Exception {
        ChannelIntegrationService service = mock(ChannelIntegrationService.class);
        when(service.startTelegramPairing("ops")).thenReturn(new TelegramPairingStartView(
                "6b4bc3a0-8d76-4a38-96dd-f4df63d3c3ff",
                "one-time-code",
                "/pair@support_bot one-time-code",
                Instant.parse("2026-01-01T00:10:00Z")));
        MockMvc mvc = securedMvc(service);

        mvc.perform(post("/api/channel-integrations/connections/ops/telegram/pairings")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairingId")
                        .value("6b4bc3a0-8d76-4a38-96dd-f4df63d3c3ff"))
                .andExpect(jsonPath("$.command").value("/pair@support_bot one-time-code"));
        verify(service).startTelegramPairing("ops");
    }

    @Test
    void exposesNonSecretProviderAuthAndConstrainedHarnessDelivery() throws Exception {
        ChannelIntegrationService service = mock(ChannelIntegrationService.class);
        when(service.providerAuth("slack")).thenReturn(new ChannelProviderAuthView(
                "slack", ChannelProviderAuthView.Mode.OAUTH_PARTIAL,
                true, true, true,
                List.of("chat:write"), List.of("chat:write"),
                Set.of("appToken"), Map.of("botToken", "oauth:slack"),
                "Socket Mode app token required"));
        when(service.deliver(any(), any())).thenReturn(
                new ai.kompile.channel.api.ChannelTestResult(true, "accepted"));
        MockMvc mvc = securedMvc(service);

        mvc.perform(get("/api/channel-integrations/providers/slack/auth")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauthConnected").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("xoxb-"))));

        mvc.perform(post("/api/channel-integrations/connections/ops/deliver")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"C01\",\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        verify(service).deliver(any(), any());
    }

    private MockMvc securedMvc(ChannelIntegrationService service) {
        IntegrationControlCredentials security =
                new IntegrationControlCredentials(TOKEN, tempDir.toString());
        return MockMvcBuilders.standaloneSetup(new ChannelIntegrationController(service))
                .addFilters(new IntegrationControlSecurityFilter(security))
                .build();
    }

    private static ChannelConnectionView view() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new ChannelConnectionView(
                UUID.randomUUID(), "ops", "telegram", ChannelChatEngine.REACT,
                "jarvis", null, false,
                RuntimeState.DISABLED, Map.of(), Set.of("botToken"), now, now, null);
    }
}
