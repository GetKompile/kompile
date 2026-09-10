/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.reddit;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.domain.ConnectionStatus;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.dto.OAuthProviderInfo;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedditDocumentLoaderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void loadsSubredditPostsAndCommentThreadsWithBearerAuth() throws Exception {
        AtomicInteger authorizedRequests = new AtomicInteger();
        AtomicInteger commentRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r/java/new.json", exchange -> {
            if ("Bearer access-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && exchange.getRequestHeaders().getFirst("User-Agent").contains("Kompile")) {
                authorizedRequests.incrementAndGet();
            }
            respond(exchange, """
                    {"data":{"after":null,"children":[{"kind":"t3","data":{
                      "id":"post1","name":"t3_post1","subreddit":"java","author":"dev",
                      "title":"JVM news","selftext":"A new runtime is available.","score":42,
                      "num_comments":1,"over_18":false,"created_utc":1700000000,
                      "permalink":"/r/java/comments/post1/jvm_news/","url":"https://example.test/news"
                    }}]}}
                    """);
        });
        server.createContext("/comments/post1.json", exchange -> {
            authorizedRequests.incrementAndGet();
            if (commentRequests.incrementAndGet() == 1) {
                byte[] retry = "rate limited".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, retry.length);
                exchange.getResponseBody().write(retry);
                exchange.close();
                return;
            }
            respond(exchange, """
                    [{"data":{"children":[]}}, {"data":{"children":[
                      {"kind":"t1","data":{"id":"c1","author":"reader","body":"Useful update","score":5,"replies":""}}
                    ]}}]
                    """);
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        RedditDocumentLoader loader = new RedditDocumentLoader(
                null, new ObjectMapper(), HttpClient.newHttpClient(), base);

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.REDDIT)
                .pathOrUrl("r/java")
                .metadata(Map.of(
                        "accessToken", "access-token",
                        "sortType", "new",
                        "postLimit", 10,
                        "includeComments", true,
                        "commentLimit", 20))
                .build());

        assertEquals(1, documents.size());
        assertEquals(3, authorizedRequests.get());
        assertEquals(2, commentRequests.get());
        assertTrue(documents.get(0).getText().contains("A new runtime is available"));
        assertTrue(documents.get(0).getText().contains("reader (5): Useful update"));
        assertEquals("post1", documents.get(0).getMetadata().get("reddit.postId"));
    }

    @Test
    void normalizesSupportedSubredditLocators() {
        assertEquals("java", RedditDocumentLoader.normalizeSubreddit("r/java"));
        assertEquals("MachineLearning", RedditDocumentLoader.normalizeSubreddit(
                "https://www.reddit.com/r/MachineLearning/top/"));
        assertEquals("java", RedditDocumentLoader.normalizeSubreddit("R/java"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RedditDocumentLoader.normalizeSubreddit("https://example.test/r/java"));
    }

    @Test
    void providerRequiresAUsableManagedOauthConnection() {
        OAuthConnectionService oauthService = mock(OAuthConnectionService.class);
        when(oauthService.getProviderInfo("reddit")).thenReturn(Optional.of(
                OAuthProviderInfo.builder().providerId("reddit").configured(true).build()));
        when(oauthService.getConnectionStatus("reddit")).thenReturn(
                OAuthConnectionStatus.builder().providerId("reddit")
                        .status(ConnectionStatus.CONNECTED).connected(true).build());
        when(oauthService.isConnectionUsable("reddit")).thenReturn(false, true);
        RedditSourceProvider provider = new RedditSourceProvider(oauthService);

        assertTrue(provider.requiresAuth());
        org.junit.jupiter.api.Assertions.assertFalse(provider.requiresAuth());
        assertEquals(Boolean.TRUE, provider.getConfiguration().get("oauthConfigured"));
    }

    @Test
    void managedTokenIsRefreshedAndRetriedAfterUnauthorizedPage() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r/java/new.json", exchange -> {
            attempts.incrementAndGet();
            if ("Bearer old-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                byte[] unauthorized = "expired".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, unauthorized.length);
                exchange.getResponseBody().write(unauthorized);
                exchange.close();
                return;
            }
            respond(exchange, """
                    {"data":{"after":null,"children":[{"kind":"t3","data":{
                      "id":"post2","subreddit":"java","title":"Refreshed", "selftext":"ok",
                      "score":1,"over_18":false,"permalink":"/r/java/comments/post2/refreshed/"
                    }}]}}
                    """);
        });
        server.start();
        OAuthConnectionService oauthService = mock(OAuthConnectionService.class);
        when(oauthService.getValidAccessToken("reddit"))
                .thenReturn("old-token", "old-token", "new-token");
        RedditDocumentLoader loader = new RedditDocumentLoader(
                oauthService, new ObjectMapper(), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort());

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.REDDIT)
                .pathOrUrl("java")
                .metadata(Map.of("sortType", "new", "includeComments", false))
                .build());

        assertEquals(1, documents.size());
        assertEquals(2, attempts.get());
        verify(oauthService).refreshConnection("reddit");
    }

    @Test
    void repeatedUnauthorizedResponseForcesOnlyOneManagedRefresh() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r/java/new.json", exchange -> {
            attempts.incrementAndGet();
            byte[] unauthorized = "still expired".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, unauthorized.length);
            exchange.getResponseBody().write(unauthorized);
            exchange.close();
        });
        server.start();
        OAuthConnectionService oauthService = mock(OAuthConnectionService.class);
        when(oauthService.getValidAccessToken("reddit"))
                .thenReturn("old-token", "old-token", "new-token", "new-token");
        RedditDocumentLoader loader = new RedditDocumentLoader(
                oauthService, new ObjectMapper(), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort());

        assertThrows(java.io.IOException.class, () -> loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.REDDIT)
                .pathOrUrl("java")
                .metadata(Map.of("sortType", "new", "includeComments", false))
                .build()));
        assertEquals(2, attempts.get());
        verify(oauthService, times(1)).refreshConnection("reddit");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
