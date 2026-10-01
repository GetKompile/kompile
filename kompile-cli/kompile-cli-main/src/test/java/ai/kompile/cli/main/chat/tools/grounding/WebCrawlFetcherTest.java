/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage of the bounded, same-origin, breadth-first WEB_CRAWL fetcher. {@link
 * WebCrawlFetcher} is package-private so these tests call {@link WebCrawlFetcher#crawl} directly
 * against loopback fake servers, with {@code allowPrivateNetworkUrls=true} (the project opt-in
 * this class always threads through to {@link RemoteFetchGuard}) since a fake test server is
 * necessarily a loopback address.
 */
class WebCrawlFetcherTest {

    @TempDir
    Path tempDir;

    private static void respondHtml(com.sun.net.httpserver.HttpExchange exchange, String html) throws java.io.IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    @Test
    void crawlStopsAtMaxDepthAndDoesNotFollowLinksBeyondIt() throws Exception {
        AtomicInteger tooDeepHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respondHtml(exchange, "<a href=\"/a.html\">a</a>"));
        server.createContext("/a.html", exchange -> respondHtml(exchange, "<a href=\"/b.html\">b</a>"));
        server.createContext("/b.html", exchange -> {
            tooDeepHits.incrementAndGet();
            respondHtml(exchange, "leaf");
        });
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertEquals(0, tooDeepHits.get(), "depth-2 page must never be fetched when maxDepth=1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlCapsTotalPagesAtMaxDocuments() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respondHtml(exchange, "<a href=\"/1.html\">1</a>"
                + "<a href=\"/2.html\">2</a><a href=\"/3.html\">3</a><a href=\"/4.html\">4</a>"
                + "<a href=\"/5.html\">5</a>"));
        for (int i = 1; i <= 5; i++) {
            server.createContext("/" + i + ".html", exchange -> respondHtml(exchange, "leaf"));
        }
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 3, List.of(), List.of(), tempDir.resolve("pages"), true);

            assertEquals(3, result.pages().size(), describePages(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlIgnoresLinksToAnotherOrigin() throws Exception {
        AtomicInteger externalHits = new AtomicInteger();
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        external.createContext("/external.html", exchange -> {
            externalHits.incrementAndGet();
            respondHtml(exchange, "leaf");
        });
        external.start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String externalUrl = "http://127.0.0.1:" + external.getAddress().getPort() + "/external.html";
            server.createContext("/", exchange -> respondHtml(exchange,
                    "<a href=\"/internal.html\">internal</a><a href=\"" + externalUrl + "\">external</a>"));
            server.createContext("/internal.html", exchange -> respondHtml(exchange, "leaf"));
            server.start();

            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertEquals(0, externalHits.get(), "a same-origin crawl must never fetch another origin");
        } finally {
            server.stop(0);
            external.stop(0);
        }
    }

    @Test
    void crawlExcludePatternDropsMatchingLinks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/index.html", exchange -> respondHtml(exchange,
                "<a href=\"/skip-me.html\">skip</a><a href=\"/keep-me.html\">keep</a>"));
        server.createContext("/skip-me.html", exchange -> respondHtml(exchange, "leaf"));
        server.createContext("/keep-me.html", exchange -> respondHtml(exchange, "leaf"));
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/index.html";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of("skip-*"), tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertFalse(result.pages().stream().anyMatch(p -> p.url().contains("skip-me")), describePages(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlIncludePatternKeepsOnlyMatchingLinks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/index.html", exchange -> respondHtml(exchange,
                "<a href=\"/keep-me.html\">keep</a><a href=\"/other.html\">other</a>"));
        server.createContext("/keep-me.html", exchange -> respondHtml(exchange, "leaf"));
        server.createContext("/other.html", exchange -> respondHtml(exchange, "leaf"));
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/index.html";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of("index.html", "keep-me.html"), List.of(),
                    tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertFalse(result.pages().stream().anyMatch(p -> p.url().contains("other.html")), describePages(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlDeduplicatesRepeatedAndFragmentOnlyLinks() throws Exception {
        AtomicInteger dupHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respondHtml(exchange,
                "<a href=\"/dup.html\">one</a><a href=\"/dup.html#section2\">two</a>"));
        server.createContext("/dup.html", exchange -> {
            dupHits.incrementAndGet();
            respondHtml(exchange, "leaf");
        });
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertEquals(1, dupHits.get(), "the fragment-only duplicate must not be fetched twice");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlFollowsARedirectedSeedAndCrawlsItsLinkedPage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/site/index.html");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/site/index.html", exchange -> respondHtml(exchange, "<a href=\"a.html\">a</a>"));
        server.createContext("/site/a.html", exchange -> respondHtml(exchange, "leaf"));
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/start";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertTrue(result.pages().stream().anyMatch(p -> p.url().endsWith("/site/a.html")), describePages(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlAdoptsRedirectedSeedOriginSoLinksOnTheNewOriginAreCrawled() throws Exception {
        // The seed redirects to a DIFFERENT origin (different port on the same loopback host).
        // Before the fix, `origin` stayed pinned to the seed's pre-redirect origin, so every
        // link discovered on the redirected page looked cross-origin and was silently dropped.
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/index.html", exchange -> respondHtml(exchange, "<a href=\"/page.html\">page</a>"));
        target.createContext("/page.html", exchange -> respondHtml(exchange, "leaf"));
        target.start();
        HttpServer seedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String redirectTarget = "http://127.0.0.1:" + target.getAddress().getPort() + "/index.html";
            seedServer.createContext("/start", exchange -> {
                exchange.getResponseHeaders().set("Location", redirectTarget);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            seedServer.start();

            String seed = "http://127.0.0.1:" + seedServer.getAddress().getPort() + "/start";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), tempDir.resolve("pages"), true);

            assertEquals(2, result.pages().size(), describePages(result));
            assertTrue(result.pages().stream().anyMatch(p -> p.url().endsWith("/page.html")), describePages(result));
        } finally {
            seedServer.stop(0);
            target.stop(0);
        }
    }

    @Test
    void crawlSkipsAndWarnsWhenANonSeedLinkRedirectsOffOrigin() throws Exception {
        AtomicInteger externalHits = new AtomicInteger();
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        external.createContext("/external.html", exchange -> {
            externalHits.incrementAndGet();
            respondHtml(exchange, "leaf");
        });
        external.start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String externalUrl = "http://127.0.0.1:" + external.getAddress().getPort() + "/external.html";
            server.createContext("/index.html", exchange -> respondHtml(exchange, "<a href=\"/go\">go</a>"));
            server.createContext("/go", exchange -> {
                exchange.getResponseHeaders().set("Location", externalUrl);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.start();

            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/index.html";
            WebCrawlFetcher.Result result = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), tempDir.resolve("pages"), true);

            // The redirect chain is followed all the way through (RemoteFetchGuard has no notion
            // of origin), so the external server really is hit once...
            assertEquals(1, externalHits.get());
            // ...but WebCrawlFetcher must discard that off-origin result instead of saving it or
            // extracting links from it.
            assertEquals(1, result.pages().size(), describePages(result));
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("redirected off-origin")),
                    result.warnings().toString());
        } finally {
            server.stop(0);
            external.stop(0);
        }
    }

    @Test
    void crawlStagingReplaceDropsStalePagesFromAnEarlierCrawl() throws Exception {
        AtomicReference<String> seedBody = new AtomicReference<>(
                "<a href=\"/first.html\">first</a><a href=\"/second.html\">second</a>");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respondHtml(exchange, seedBody.get()));
        server.createContext("/first.html", exchange -> respondHtml(exchange, "first-page-marker"));
        server.createContext("/second.html", exchange -> respondHtml(exchange, "second-page-marker"));
        server.start();
        try {
            String seed = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Path target = tempDir.resolve("pages");
            WebCrawlFetcher.Result first = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), target, true);
            assertEquals(3, first.pages().size(), describePages(first));

            // Re-crawl the same seed URL and target directory, now with no outgoing links.
            seedBody.set("no links here");
            WebCrawlFetcher.Result second = WebCrawlFetcher.crawl(
                    seed, 1, 10, List.of(), List.of(), target, true);
            assertEquals(1, second.pages().size(), describePages(second));

            try (var listing = Files.list(target)) {
                List<Path> remaining = listing.toList();
                assertEquals(1, remaining.size(), remaining.toString());
            }
            String remainingContent = Files.readString(second.pages().get(0).file());
            assertFalse(remainingContent.contains("first-page-marker"), remainingContent);
            assertFalse(remainingContent.contains("second-page-marker"), remainingContent);
        } finally {
            server.stop(0);
        }
    }

    private static String describePages(WebCrawlFetcher.Result result) {
        return result.pages().toString() + " warnings=" + result.warnings();
    }
}
