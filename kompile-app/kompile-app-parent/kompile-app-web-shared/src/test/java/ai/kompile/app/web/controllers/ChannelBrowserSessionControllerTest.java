/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.app.web.security.IntegrationControlSecurityFilter;
import ai.kompile.channel.api.ChannelControlHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChannelBrowserSessionControllerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void onePersonaMintsCodeThatAnotherPersonaExchangesForScopedCookies() throws Exception {
        IntegrationControlCredentials issuerCredentials =
                new IntegrationControlCredentials(TOKEN, tempDir.toString());
        MockMvc issuer = mvc(issuerCredentials);
        String issued = issuer.perform(post("/api/channel-integrations/browser-sessions")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = new ObjectMapper().readTree(issued).path("code").asText();

        IntegrationControlCredentials crawlPersonaCredentials =
                new IntegrationControlCredentials(TOKEN, tempDir.toString());
        MockMvc crawlPersona = mvc(crawlPersonaCredentials);
        var exchange = crawlPersona.perform(post("/api/channel-integrations/browser-sessions/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        var cookies = exchange.getHeaders("Set-Cookie");
        assertEquals(8, cookies.size());
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/channel-integrations")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/kclaw")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/ws/kclaw")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/sync")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/oauth")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/source-providers")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/unified-crawl")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("Path=/api/documents")));
        assertTrue(cookies.stream().noneMatch(value -> value.contains("Path=/;")));
        crawlPersona.perform(post("/api/channel-integrations/browser-sessions/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private MockMvc mvc(IntegrationControlCredentials credentials) {
        return MockMvcBuilders.standaloneSetup(new ChannelBrowserSessionController(credentials))
                .addFilters(new IntegrationControlSecurityFilter(credentials))
                .build();
    }
}
