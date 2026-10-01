/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct, network-free (aside from loopback fake servers) coverage of the SSRF guard shared by
 * single-URL documents and WEB_CRAWL. {@link RemoteFetchGuard} is package-private so these tests
 * call {@link RemoteFetchGuard#validate} and {@link RemoteFetchGuard#fetch} directly with an
 * explicit opt-in flag, exactly as the class's own javadoc describes.
 */
class RemoteFetchGuardTest {

    // --- validate(): exhaustive blocked-address-class rejections ---------------------------

    @Test
    void validateRejectsIpv4Loopback() {
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://127.0.0.1/"), false));
        assertTrue(failure.getMessage().contains("loopback"), failure.getMessage());
    }

    @Test
    void validateRejectsIpv4SiteLocalPrivateAddress() {
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://10.0.0.1/"), false));
        assertTrue(failure.getMessage().contains("site-local/private"), failure.getMessage());
    }

    @Test
    void validateRejectsLinkLocalCloudMetadataAddress() {
        // 169.254.169.254 is the cloud-provider instance-metadata address: the canonical SSRF
        // target this guard exists to block.
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://169.254.169.254/"), false));
        assertTrue(failure.getMessage().contains("link-local"), failure.getMessage());
    }

    @Test
    void validateRejectsIpv6Loopback() {
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://[::1]/"), false));
        assertTrue(failure.getMessage().contains("loopback"), failure.getMessage());
    }

    @Test
    void validateRejectsIpv6UniqueLocalAddress() {
        // fc00::/7: the JDK does not classify this as site-local, so RemoteFetchGuard carries
        // its own isUniqueLocalIpv6 check.
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://[fc00::1]/"), false));
        assertTrue(failure.getMessage().contains("unique-local"), failure.getMessage());
    }

    @Test
    void validateRejectsIpv4MappedIpv6Loopback() {
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://[::ffff:127.0.0.1]/"), false));
        assertTrue(failure.getMessage().contains("loopback"), failure.getMessage());
    }

    @Test
    void validateRejectsCarrierGradeNatSharedAddressSpace() {
        // 100.64.0.0/10 (RFC 6598): the JDK does not classify this as site-local, and it is the
        // address space Tailscale and similar overlay networks assign tailnet peers from, so on
        // a dev machine it IS a private network.
        for (String host : new String[] {"100.64.0.1", "100.127.255.254"}) {
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteFetchGuard.validate(URI.create("http://" + host + "/"), false));
            assertTrue(failure.getMessage().contains("carrier-grade NAT/shared"), failure.getMessage());
        }
    }

    @Test
    void validateAllowsAddressesJustOutsideCarrierGradeNatRange() {
        assertDoesNotThrow(() -> RemoteFetchGuard.validate(URI.create("http://100.63.255.255/"), false));
        assertDoesNotThrow(() -> RemoteFetchGuard.validate(URI.create("http://100.128.0.0/"), false));
    }

    @Test
    void validateRejectsThisNetworkAddressSpace() {
        // 0.0.0.0/8 (RFC 1122 "this network"): isAnyLocalAddress() only covers 0.0.0.0 itself.
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://0.1.2.3/"), false));
        assertTrue(failure.getMessage().contains("this-network"), failure.getMessage());
    }

    @Test
    void validateRejectsNat64EmbeddedPrivateAddress() {
        // 64:ff9b::a00:1 is the RFC 6052 NAT64 well-known prefix embedding 10.0.0.1: on an
        // IPv6-only network with a NAT64 gateway this resolves straight through to the same
        // private host.
        IOException failure = assertThrows(IOException.class,
                () -> RemoteFetchGuard.validate(URI.create("http://[64:ff9b::a00:1]/"), false));
        assertTrue(failure.getMessage().contains("site-local/private"), failure.getMessage());
    }

    @Test
    void validateAllowsNat64EmbeddedPublicAddress() {
        // 64:ff9b::808:808 embeds the public 8.8.8.8 and must not be blocked.
        assertDoesNotThrow(() -> RemoteFetchGuard.validate(URI.create("http://[64:ff9b::808:808]/"), false));
    }

    // --- validate(): opt-in and unconditional checks ----------------------------------------

    @Test
    void validateAllowsBlockedAddressClassesOnlyWhenProjectOptsIn() {
        String[] blockedLiterals = {
                "http://127.0.0.1/",
                "http://10.0.0.1/",
                "http://169.254.169.254/",
                "http://[::1]/",
                "http://[fc00::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://100.64.0.1/",
                "http://100.127.255.254/",
                "http://0.1.2.3/",
                "http://[64:ff9b::a00:1]/",
        };
        for (String literal : blockedLiterals) {
            assertDoesNotThrow(() -> RemoteFetchGuard.validate(URI.create(literal), true), literal);
        }
    }

    @Test
    void validateRejectsNonHttpSchemeRegardlessOfOptIn() {
        for (boolean allowPrivateNetworkUrls : new boolean[] {false, true}) {
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteFetchGuard.validate(URI.create("ftp://example.com/file"), allowPrivateNetworkUrls));
            assertTrue(failure.getMessage().contains("Only http and https"), failure.getMessage());
        }
    }

    @Test
    void validateRejectsMissingHostRegardlessOfOptIn() {
        // An opaque http: URI (no "//" authority) parses with a null host.
        URI hostless = URI.create("http:opaque");
        for (boolean allowPrivateNetworkUrls : new boolean[] {false, true}) {
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteFetchGuard.validate(hostless, allowPrivateNetworkUrls));
            assertTrue(failure.getMessage().contains("no host"), failure.getMessage());
        }
    }

    // --- fetch(): manual redirect handling ---------------------------------------------------

    @Test
    void fetchFollowsRedirectsToASuccessfulResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            byte[] bytes = "landed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            URI seed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/start");
            RemoteFetchGuard.FetchResult result = RemoteFetchGuard.fetch(seed, target -> HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(10)).GET().build(), true);
            assertTrue(result.uri().toString().endsWith("/final"), result.uri().toString());
            assertEquals(200, result.response().statusCode());
            result.response().body().close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchRevalidatesEveryRedirectHopNotJustTheSeed() throws Exception {
        // A private-IP redirect target can't be exercised in-process without the target itself
        // being loopback (the same class the opted-in seed already is), since opt-in applies
        // uniformly to every hop. What IS provable in-process: fetch() pipes each hop's
        // Location back through the same validate() call rather than trusting it blindly, so a
        // redirect to a disallowed scheme is still caught even though the seed's fetch was
        // opted in to skip the address-class blocklist.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "ftp://evil.example/payload");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            URI seed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/start");
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteFetchGuard.fetch(seed, target -> HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(10)).GET().build(), true));
            assertTrue(failure.getMessage().contains("Only http and https"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchRejectsRedirectWithNoLocationHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            URI seed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/start");
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteFetchGuard.fetch(seed, target -> HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(10)).GET().build(), true));
            assertTrue(failure.getMessage().contains("no Location header"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchThrowsAfterExceedingMaxRedirects() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loop", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            URI seed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/loop");
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteFetchGuard.fetch(seed, target -> HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(10)).GET().build(), true));
            assertTrue(failure.getMessage().contains("Exceeded " + RemoteFetchGuard.MAX_REDIRECTS + " redirects"),
                    failure.getMessage());
            assertEquals(RemoteFetchGuard.MAX_REDIRECTS + 1, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
