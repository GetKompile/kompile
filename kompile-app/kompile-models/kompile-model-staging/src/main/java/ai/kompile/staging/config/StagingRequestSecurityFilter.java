/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.staging.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

/**
 * Enforces the staging server's local-first request boundary.
 *
 * <p>Loopback requests are protected from DNS rebinding, cross-site browser
 * requests, and simple-request CSRF. Explicit LAN mode additionally requires a
 * session-supplied API token on every API request.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class StagingRequestSecurityFilter extends OncePerRequestFilter {

    public static final String REQUEST_HEADER = "X-Kompile-Staging-Request";
    public static final String TOKEN_HEADER = "X-Kompile-Staging-Token";
    public static final String PAIRING_REQUIRED_HEADER =
            "X-Kompile-Staging-Pairing-Required";

    private static final String ALLOWED_HEADERS =
            "Accept, Authorization, Content-Type, "
                    + REQUEST_HEADER + ", " + TOKEN_HEADER;
    private static final String ALLOWED_METHODS =
            "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS";

    private final StagingSecurityProperties security;

    public StagingRequestSecurityFilter(StagingSecurityProperties security) {
        this.security = security;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = requestPath(request);
        return !(path.equals("/api")
                || path.startsWith("/api/")
                || path.equals("/v1")
                || path.startsWith("/v1/")
                || path.equals("/actuator")
                || path.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (containsTokenOutsideHeader(request)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Staging tokens are accepted only in the "
                            + TOKEN_HEADER + " header");
            return;
        }

        if (!security.isLanMode()
                && !StagingSecurityProperties.isLoopbackHost(request.getServerName())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "Loopback staging rejected an untrusted Host header");
            return;
        }

        String origin = singleHeader(request, "Origin");
        String normalizedOrigin = null;
        if (origin != null) {
            try {
                normalizedOrigin = StagingSecurityProperties.normalizeOrigin(origin);
            } catch (IllegalArgumentException e) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "Request origin is not allowed");
                return;
            }
            if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "Cross-site requests are not allowed");
                return;
            }
            String requestOrigin = requestOrigin(request);
            if (!normalizedOrigin.equals(requestOrigin)
                    && !security.isAllowedOrigin(normalizedOrigin)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "Request origin is not allowed");
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", normalizedOrigin);
            response.addHeader("Vary", "Origin");
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            if (normalizedOrigin == null) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "CORS preflight requires an allowed Origin");
                return;
            }
            response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
            response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
            response.setHeader("Access-Control-Max-Age", "600");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        if (security.isLanMode()
                && !security.matchesApiToken(request.getHeader(TOKEN_HEADER))) {
            response.setHeader(PAIRING_REQUIRED_HEADER, "true");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "A valid staging pairing token is required");
            return;
        }

        if (isMutation(request.getMethod())
                && !"1".equals(request.getHeader(REQUEST_HEADER))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "State-changing requests require the "
                            + REQUEST_HEADER + " header");
            return;
        }

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            return "";
        }
        return value;
    }

    private static boolean containsTokenOutsideHeader(HttpServletRequest request) {
        for (String name : request.getParameterMap().keySet()) {
            if (isTokenName(name)) {
                return true;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie != null && isTokenName(cookie.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTokenName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
        return normalized.equals("token")
                || normalized.equals("apitoken")
                || normalized.equals("stagingtoken")
                || normalized.equals("kompilestagingtoken");
    }

    private static boolean isMutation(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static String requestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme().toLowerCase(Locale.ROOT);
        String host = request.getServerName().toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        int port = request.getServerPort();
        String origin = scheme + "://" + host;
        if (!(("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443))) {
            origin += ":" + port;
        }
        return StagingSecurityProperties.normalizeOrigin(origin);
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private static void reject(
            HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
