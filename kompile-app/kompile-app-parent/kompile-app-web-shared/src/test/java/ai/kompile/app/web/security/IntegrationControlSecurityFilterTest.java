/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.security;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.ChannelInternalAuthentication;
import ai.kompile.channel.api.BrowserSessionCredentials;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IntegrationControlSecurityFilterTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void rejectsWeakConfiguredIntegrationCredential() {
        assertThrows(IllegalStateException.class,
                () -> new IntegrationControlCredentials("short", tempDir.toString()));
    }

    @Test
    void corsPreflightPassesToTheManagedOriginFilterWithoutBearerAuthentication() throws Exception {
        IntegrationControlSecurityFilter filter =
                new IntegrationControlSecurityFilter(KEY, tempDir.toString());
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/api/sync/connections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void protectsSourceOauthAndSyncAcrossPersonas() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new IntegrationControlSecurityFilter(KEY, tempDir.toString()))
                .build();

        mvc.perform(get("/api/oauth/providers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sync/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/source-providers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/oauth/providers")
                        .header(ChannelControlHeaders.TOKEN_HEADER, KEY))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sync/connections")
                        .header(ChannelControlHeaders.TOKEN_HEADER, KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectsExactProvisionedRuntimePathWithBearerAndMutationProof() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new IntegrationControlSecurityFilter(KEY, tempDir.toString()))
                .build();

        mvc.perform(post("/api/kclaw/runtime/context"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/kclaw/runtime/context")
                        .header(ChannelControlHeaders.TOKEN_HEADER, KEY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/kclaw/runtime/context")
                        .header(ChannelControlHeaders.TOKEN_HEADER, KEY)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/kclaw/runtime/context;owner=foreign")
                        .header(ChannelControlHeaders.TOKEN_HEADER, KEY)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oauthCallbackMayTraverseAnInternalHttpProxyHopWithoutTrustingForwardedHeaders() throws Exception {
        IntegrationControlSecurityFilter filter =
                new IntegrationControlSecurityFilter(KEY, tempDir.toString());
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/oauth/reddit/callback");
        request.setRemoteAddr("203.0.113.10");
        request.setServerName("crawl-manager.internal");
        request.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void browserSessionExchangeStillRequiresHttpsOnRemoteRequests() throws Exception {
        IntegrationControlSecurityFilter filter =
                new IntegrationControlSecurityFilter(KEY, tempDir.toString());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/channel-integrations/browser-sessions/exchange");
        request.setRemoteAddr("203.0.113.10");
        request.setServerName("crawl-manager.internal");
        request.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatus());
    }

    @Test
    void portableSessionAndCsrfAuthenticateSyncMutation() throws Exception {
        String csrf = "csrf-token";
        byte[] csrfHash = MessageDigest.getInstance("SHA-256")
                .digest(csrf.getBytes(StandardCharsets.UTF_8));
        long expiry = Instant.now().plusSeconds(600).getEpochSecond();
        String nonce = "0123456789abcdef0123456789abcdef";
        String credential = "v1." + expiry + "." + nonce + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(csrfHash)
                + "." + ChannelInternalAuthentication.sign(KEY, expiry, nonce, csrfHash);
        Cookie cookie = new Cookie(ChannelControlHeaders.SOURCE_SYNC_SESSION_COOKIE, credential);
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new IntegrationControlSecurityFilter(KEY, tempDir.toString()))
                .build();

        mvc.perform(post("/api/sync/connections").cookie(cookie))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sync/connections")
                        .cookie(cookie)
                        .header(ChannelControlHeaders.CSRF_HEADER, csrf))
                .andExpect(status().isOk());
    }

    @Test
    void revocationFromOnePersonaIsEnforcedByAnotherPersona() throws Exception {
        BrowserSessionCredentials issuer = new BrowserSessionCredentials(KEY, tempDir);
        BrowserSessionCredentials.BrowserSession session =
                issuer.exchangeLogin(issuer.issueLogin().code());
        Cookie cookie = new Cookie(
                ChannelControlHeaders.SOURCE_SYNC_SESSION_COOKIE, session.credential());
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new IntegrationControlSecurityFilter(KEY, tempDir.toString()))
                .build();

        mvc.perform(get("/api/sync/connections").cookie(cookie))
                .andExpect(status().isOk());
        issuer.revokeSession(session.credential());
        mvc.perform(get("/api/sync/connections").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void localCrawlsRemainLocalFirstButRemoteMutationsRequireSessionAndCsrf() throws Exception {
        BrowserSessionCredentials sessions = new BrowserSessionCredentials(KEY, tempDir);
        BrowserSessionCredentials.BrowserSession session =
                sessions.exchangeLogin(sessions.issueLogin().code());
        Cookie cookie = new Cookie(ChannelControlHeaders.CRAWL_SESSION_COOKIE, session.credential());
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .addFilters(new IntegrationControlSecurityFilter(KEY, tempDir.toString()))
                .build();

        mvc.perform(post("/api/unified-crawl/start"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/unified-crawl/start").secure(true).with(request -> {
                    request.setRemoteAddr("203.0.113.10");
                    request.setServerName("crawl.example.com");
                    return request;
                }))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/unified-crawl/start").secure(true).cookie(cookie)
                        .header(ChannelControlHeaders.CSRF_HEADER, session.csrfToken())
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            request.setServerName("crawl.example.com");
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    @RestController
    static class FixtureController {
        @GetMapping({"/api/oauth/providers", "/api/sync/connections", "/api/source-providers"})
        String get() { return "ok"; }
        @PostMapping({"/api/sync/connections", "/api/kclaw/runtime/context"})
        String post() { return "ok"; }
        @PostMapping("/api/unified-crawl/start")
        String crawl() { return "ok"; }
    }
}
