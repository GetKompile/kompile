/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.security;

import ai.kompile.channel.api.ChannelControlHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/** Shared bearer/browser-session boundary for all secret-bearing integration APIs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class IntegrationControlSecurityFilter extends OncePerRequestFilter {

    private final IntegrationControlCredentials credentials;

    /**
     * Servlet containers may instantiate Filter beans reflectively while assembling the servlet
     * initializer set from a packaged Boot JAR. Keep that path equivalent to the Spring-injected
     * constructor instead of failing before Tomcat starts.
     */
    public IntegrationControlSecurityFilter() {
        this(System.getenv(ChannelControlHeaders.TOKEN_ENVIRONMENT),
                System.getProperty("kompile.data.dir", System.getProperty("user.home") + "/.kompile"));
    }

    @Autowired
    public IntegrationControlSecurityFilter(IntegrationControlCredentials credentials) {
        this.credentials = credentials;
    }

    public IntegrationControlSecurityFilter(String configuredToken, String dataDir) {
        this(new IntegrationControlCredentials(configuredToken, dataDir));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = canonicalPath(requestPath(request));
        return !(under(path, "/api/channel-integrations")
                || under(path, "/api/kclaw")
                || under(path, "/api/sync")
                || under(path, "/api/oauth")
                || under(path, "/api/source-providers")
                || isProtectedSourceMutation(path, request.getMethod())
                || path.equals("/ws/kclaw"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String rawPath = requestPath(request);
        String path = canonicalPath(rawPath);
        if (hasMatrixParameters(rawPath)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Matrix parameters are not allowed on integration endpoints");
            return;
        }
        // OAuth callbacks and signed provider webhooks authenticate themselves and may arrive over
        // an internal HTTP hop after TLS termination. They do not consume request-derived callback
        // origins: OAuth reuses the URI bound into persisted state. Keep admin APIs behind the HTTPS
        // gate instead of trusting spoofable Forwarded/X-Forwarded-* headers globally.
        if (mayTraverseTlsTerminatingProxy(path)) {
            noStore(response);
            chain.doFilter(request, response);
            return;
        }
        boolean loopback = isLoopback(request.getRemoteAddr()) && isLoopback(request.getServerName());
        if (!loopback && !request.isSecure()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "Remote integration administration requires HTTPS");
            return;
        }
        if (loopback && isProtectedSourceMutation(path, request.getMethod())) {
            noStore(response);
            chain.doFilter(request, response);
            return;
        }
        if (usesExternalAuthentication(path)) {
            noStore(response);
            chain.doFilter(request, response);
            return;
        }
        boolean mutation = isMutation(request.getMethod());
        boolean bearer = credentials.matches(
                request.getHeader(ChannelControlHeaders.TOKEN_HEADER));
        boolean browser = matchesBrowserSession(
                sessionCookie(request, path),
                request.getHeader(ChannelControlHeaders.CSRF_HEADER), mutation);
        if (!bearer && !browser) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "A valid integration admin token or browser session is required");
            return;
        }
        if (mutation && bearer
                && !"1".equals(request.getHeader(ChannelControlHeaders.REQUEST_HEADER))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "State-changing integration requests require "
                            + ChannelControlHeaders.REQUEST_HEADER);
            return;
        }
        noStore(response);
        chain.doFilter(request, response);
    }

    private boolean matchesBrowserSession(
            String credential, String csrfToken, boolean mutation) {
        try {
            return credentials.browserSessions().verifySession(
                    credential, csrfToken, mutation);
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean usesExternalAuthentication(String path) {
        return path.equals("/api/kclaw/channels/webhook/whatsapp")
                || path.equals("/api/channel-integrations/browser-sessions/exchange")
                || path.matches("/api/oauth/[^/]+/callback")
                || path.equals("/api/sync/webhook/notion")
                || path.equals("/api/sync/webhook/notion/verify");
    }

    private static boolean mayTraverseTlsTerminatingProxy(String path) {
        return path.matches("/api/oauth/[^/]+/callback")
                || path.equals("/api/kclaw/channels/webhook/whatsapp")
                || path.equals("/api/sync/webhook/notion")
                || path.equals("/api/sync/webhook/notion/verify");
    }

    private static String sessionCookie(HttpServletRequest request, String path) {
        if (request.getCookies() == null) return null;
        String expected = path.startsWith("/api/channel-integrations")
                ? ChannelControlHeaders.CHANNEL_SESSION_COOKIE
                : path.startsWith("/api/kclaw")
                        ? ChannelControlHeaders.KCLAW_SESSION_COOKIE
                        : path.startsWith("/api/sync")
                                ? ChannelControlHeaders.SOURCE_SYNC_SESSION_COOKIE
                                : path.startsWith("/api/oauth")
                                        ? ChannelControlHeaders.OAUTH_SESSION_COOKIE
                                        : path.startsWith("/api/source-providers")
                                                ? ChannelControlHeaders.SOURCE_PROVIDER_SESSION_COOKIE
                                                : path.startsWith("/api/unified-crawl")
                                                        ? ChannelControlHeaders.CRAWL_SESSION_COOKIE
                                                        : path.startsWith("/api/documents")
                                                                ? ChannelControlHeaders.DOCUMENT_SESSION_COOKIE
                                                : ChannelControlHeaders.KCLAW_WS_SESSION_COOKIE;
        for (Cookie cookie : request.getCookies()) {
            if (expected.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private static boolean isMutation(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static boolean isProtectedSourceMutation(String path, String method) {
        return isMutation(method)
                && (under(path, "/api/unified-crawl") || under(path, "/api/documents"));
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
    }

    private static String canonicalPath(String path) {
        return path.replaceAll(";[^/]*", "").replaceAll("(?i)%3b[^/]*", "");
    }

    private static boolean hasMatrixParameters(String path) {
        return path.indexOf(';') >= 0 || path.toLowerCase(Locale.ROOT).contains("%3b");
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    private static void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
