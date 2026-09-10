/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.notion;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotionDocumentLoaderTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void loadsPageBlocksWithExplicitToken() throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        String nestedId = "11111111111111111111111111111111";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/pages/" + id, exchange -> respond(exchange, """
                {"id":"%s","url":"https://notion.so/%s","properties":{
                  "Name":{"type":"title","title":[{"plain_text":"Local source parity"}]}
                }}
                """.formatted(id, id)));
        server.createContext("/v1/blocks/" + id + "/children", exchange -> respond(exchange, """
                {"has_more":false,"results":[
                  {"id":"b1","type":"heading_2","heading_2":{"rich_text":[{"plain_text":"Status"}]}},
                  {"id":"%s","type":"toggle","has_children":true,"toggle":{"rich_text":[{"plain_text":"Details"}]}}
                ]}
                """.formatted(nestedId)));
        server.createContext("/v1/blocks/" + nestedId + "/children", exchange -> respond(exchange, """
                {"has_more":false,"results":[
                  {"id":"22222222222222222222222222222222","type":"paragraph","paragraph":{"rich_text":[{"plain_text":"Notion works locally"}]}}
                ]}
                """));
        server.start();
        NotionDocumentLoader loader = new NotionDocumentLoader(null, new ObjectMapper(),
                HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.NOTION)
                .pathOrUrl(id)
                .metadata(Map.of("apiToken", "secret-token"))
                .build());

        assertEquals(1, documents.size());
        assertTrue(documents.get(0).getText().contains("Notion works locally"));
        assertEquals(id, documents.get(0).getMetadata().get("notion.pageId"));
        assertEquals("notion:" + id,
                documents.get(0).getMetadata().get(GraphConstants.META_SOURCE_PATH));
        assertEquals("https://notion.so/" + id, documents.get(0).getMetadata().get("notion.url"));
        assertFalse(documents.get(0).getMetadata().containsValue("secret-token"));
    }

    @Test
    void loadsDatabaseLocatorWithPaginationAndBoundedPageCount() throws Exception {
        String databaseId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String firstPage = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String secondPage = "cccccccccccccccccccccccccccccccc";
        AtomicInteger queries = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/databases/" + databaseId + "/query", exchange -> {
            int query = queries.getAndIncrement();
            respond(exchange, query == 0
                    ? "{\"has_more\":true,\"next_cursor\":\"next\",\"results\":[{\"id\":\"" + firstPage + "\"}]}"
                    : "{\"has_more\":false,\"results\":[{\"id\":\"" + secondPage + "\"}]}");
        });
        for (String pageId : List.of(firstPage, secondPage)) {
            server.createContext("/v1/pages/" + pageId, exchange -> respond(exchange,
                    "{\"id\":\"" + pageId + "\",\"properties\":{"
                            + "\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Page " + pageId.charAt(0) + "\"}]},"
                            + "\"Status\":{\"type\":\"status\",\"status\":{\"name\":\"In progress\"}}}}"));
            server.createContext("/v1/blocks/" + pageId + "/children", exchange -> respond(exchange,
                    "{\"has_more\":false,\"results\":[]}"));
        }
        server.start();
        NotionDocumentLoader loader = new NotionDocumentLoader(null, new ObjectMapper(),
                HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.NOTION)
                .pathOrUrl(databaseId)
                .metadata(Map.of("apiToken", "secret-token", "resourceType", "database", "maxPages", 2))
                .build());

        assertEquals(2, documents.size());
        assertEquals(2, queries.get());
        assertTrue(documents.get(0).getText().contains("**Status:** In progress"));
        assertTrue(documents.get(0).getMetadata().get("notion.properties").toString()
                .contains("In progress"));
    }

    @Test
    void retriesRateLimitedNotionRequests() throws Exception {
        String id = "dddddddddddddddddddddddddddddddd";
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/pages/" + id, exchange -> {
            if (calls.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
            } else {
                respond(exchange, "{\"id\":\"" + id
                        + "\",\"properties\":{\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Retry page\"}]}}}");
            }
        });
        server.createContext("/v1/blocks/" + id + "/children", exchange ->
                respond(exchange, "{\"has_more\":false,\"results\":[]}"));
        server.start();
        NotionDocumentLoader loader = new NotionDocumentLoader(null, new ObjectMapper(),
                HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.NOTION)
                .pathOrUrl(id)
                .metadata(Map.of("apiToken", "secret-token"))
                .build());

        assertEquals(1, documents.size());
        assertEquals(2, calls.get());
    }

    @Test
    void changingEmptyCursorsCannotExhaustUnboundedRequests() throws Exception {
        String databaseId = "ffffffffffffffffffffffffffffffff";
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/databases/" + databaseId + "/query", exchange -> {
            int call = calls.incrementAndGet();
            respond(exchange, "{\"has_more\":true,\"next_cursor\":\"cursor-" + call
                    + "\",\"results\":[]}");
        });
        server.start();
        NotionDocumentLoader loader = new NotionDocumentLoader(null, new ObjectMapper(),
                HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        assertThrows(IllegalStateException.class, () -> loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.NOTION)
                .pathOrUrl(databaseId)
                .metadata(Map.of("apiToken", "secret-token", "resourceType", "database",
                        "maxApiRequests", 3))
                .build()));

        assertEquals(3, calls.get());
    }

    @Test
    void managedAuthenticationDoesNotAttemptUnsupportedNotionRefresh() throws Exception {
        String id = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        AtomicInteger pageCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/pages/" + id, exchange -> {
            pageCalls.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        OAuthConnectionService oauth = mock(OAuthConnectionService.class);
        when(oauth.getValidAccessToken("notion")).thenReturn("managed-token");
        NotionDocumentLoader loader = new NotionDocumentLoader(oauth, new ObjectMapper(),
                HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        assertThrows(IOException.class, () -> loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.NOTION)
                .pathOrUrl(id)
                .metadata(Map.of())
                .build()));

        assertEquals(1, pageCalls.get());
        verify(oauth, never()).refreshConnection("notion");
    }

    @Test
    void convertsCommonBlockTypes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String markdown = NotionDocumentLoader.blocksToMarkdown(List.of(
                mapper.readTree("{\"type\":\"to_do\",\"to_do\":{\"checked\":true,\"rich_text\":[{\"plain_text\":\"Ship it\"}]}}"),
                mapper.readTree("{\"type\":\"quote\",\"quote\":{\"rich_text\":[{\"plain_text\":\"Grounded\"}]}}")));
        assertTrue(markdown.contains("- [x] Ship it"));
        assertTrue(markdown.contains("> Grounded"));
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
