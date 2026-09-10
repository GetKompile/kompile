/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.channel.api.BrowserSessionCredentials;
import ai.kompile.channel.api.ChannelBrowserLoginView;
import ai.kompile.channel.api.ChannelBrowserSessionRequest;
import ai.kompile.channel.api.ChannelBrowserSessionView;
import ai.kompile.channel.api.ChannelControlHeaders;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Exchanges CLI-authorized one-time codes for scoped sessions on every application persona. */
@RestController
@RequestMapping("/api/channel-integrations/browser-sessions")
public class ChannelBrowserSessionController {

    private final IntegrationControlCredentials credentials;

    public ChannelBrowserSessionController(IntegrationControlCredentials credentials) {
        this.credentials = credentials;
    }

    /** The long-lived bearer is required here; browser sessions cannot mint more login codes. */
    @PostMapping
    public ChannelBrowserLoginView issue(HttpServletRequest request) {
        if (!credentials.matches(request.getHeader(ChannelControlHeaders.TOKEN_HEADER))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Browser login codes require the integration admin bearer");
        }
        BrowserSessionCredentials.BrowserLogin login = credentials.browserSessions().issueLogin();
        return new ChannelBrowserLoginView(login.code(), login.expiresAt());
    }

    /** Public only in the sense that the 256-bit, one-time, five-minute code is its credential. */
    @PostMapping("/exchange")
    public ResponseEntity<ChannelBrowserSessionView> exchange(
            @RequestBody ChannelBrowserSessionRequest request,
            HttpServletRequest servletRequest) {
        try {
            BrowserSessionCredentials.BrowserSession session = credentials.browserSessions()
                    .exchangeLogin(request == null ? null : request.code());
            String[] cookies = sessionCookies(
                    session.credential(), session.expiresAt(), servletRequest.isSecure())
                    .stream().map(ResponseCookie::toString).toArray(String[]::new);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookies)
                    .body(new ChannelBrowserSessionView(session.csrfToken(), session.expiresAt()));
        } catch (IllegalArgumentException invalidCode) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, invalidCode.getMessage());
        }
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> revoke(
            HttpServletRequest request,
            HttpServletResponse response) {
        credentials.browserSessions().revokeSession(sessionCredential(request));
        expiredCookies(request.isSecure()).forEach(cookie ->
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
        return ResponseEntity.noContent().build();
    }

    private static List<ResponseCookie> sessionCookies(
            String value, Instant expiresAt, boolean secure) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        Duration bounded = maxAge.isNegative() ? Duration.ZERO : maxAge;
        return List.of(
                cookie(ChannelControlHeaders.CHANNEL_SESSION_COOKIE, value,
                        "/api/channel-integrations", secure, bounded),
                cookie(ChannelControlHeaders.KCLAW_SESSION_COOKIE, value,
                        "/api/kclaw", secure, bounded),
                cookie(ChannelControlHeaders.KCLAW_WS_SESSION_COOKIE, value,
                        "/ws/kclaw", secure, bounded),
                cookie(ChannelControlHeaders.SOURCE_SYNC_SESSION_COOKIE, value,
                        "/api/sync", secure, bounded),
                cookie(ChannelControlHeaders.OAUTH_SESSION_COOKIE, value,
                        "/api/oauth", secure, bounded),
                cookie(ChannelControlHeaders.SOURCE_PROVIDER_SESSION_COOKIE, value,
                        "/api/source-providers", secure, bounded),
                cookie(ChannelControlHeaders.CRAWL_SESSION_COOKIE, value,
                        "/api/unified-crawl", secure, bounded),
                cookie(ChannelControlHeaders.DOCUMENT_SESSION_COOKIE, value,
                        "/api/documents", secure, bounded));
    }

    private static List<ResponseCookie> expiredCookies(boolean secure) {
        return sessionCookies("", Instant.now(), secure);
    }

    private static ResponseCookie cookie(
            String name, String value, String path, boolean secure, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true).secure(secure).sameSite("Strict")
                .path(path).maxAge(maxAge).build();
    }

    private static String sessionCredential(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (ChannelControlHeaders.CHANNEL_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.KCLAW_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.KCLAW_WS_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.SOURCE_SYNC_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.OAUTH_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.SOURCE_PROVIDER_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.CRAWL_SESSION_COOKIE.equals(cookie.getName())
                    || ChannelControlHeaders.DOCUMENT_SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
