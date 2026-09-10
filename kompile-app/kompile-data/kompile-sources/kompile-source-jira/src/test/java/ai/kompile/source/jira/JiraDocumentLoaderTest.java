/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.jira;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.dto.OAuthProviderInfo;
import ai.kompile.oauth.domain.ConnectionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class JiraDocumentLoaderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void loadsIssuesWithApiTokenAndConvertsAdfAndComments() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/search/jql", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"isLast":true,"issues":[{
                      "id":"10001","key":"APP-7","fields":{
                        "summary":"Fix source auth",
                        "description":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Use OAuth safely"}]}]},
                        "status":{"name":"In Progress"},"issuetype":{"name":"Task"},
                        "project":{"key":"APP","name":"Application"},"priority":{"name":"High"},
                        "labels":["integration"],
                        "comment":{"total":2,"comments":[{"author":{"displayName":"Alex"},"body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Ready to test"}]}]}}]},
                        "attachment":[]
                      }}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/rest/api/3/issue/APP-7/comment", exchange -> {
            byte[] response = """
                    {"startAt":1,"maxResults":100,"total":2,"comments":[
                      {"id":"c2","author":{"displayName":"Sam"},"body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Pagination works"}]}]}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        OAuthConnectionService oauthService = mock(OAuthConnectionService.class);
        when(oauthService.getValidAccessToken("atlassian")).thenReturn("connected-oauth-token");
        JiraDocumentLoader loader = new JiraDocumentLoader(
                oauthService, new ObjectMapper(), HttpClient.newHttpClient());

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.JIRA)
                .pathOrUrl(baseUrl)
                .metadata(Map.of(
                        "email", "operator@example.com",
                        "apiToken", "secret-token",
                        "projectKey", "APP",
                        "includeComments", true))
                .build());

        assertEquals(1, documents.size());
        assertTrue(authorization.get().startsWith("Basic "),
                "explicit API-token credentials must override the stored OAuth connection");
        assertTrue(body.get().contains("project = \\\"APP\\\""));
        assertTrue(documents.get(0).getText().contains("Use OAuth safely"));
        assertTrue(documents.get(0).getText().contains("Alex: Ready to test"));
        assertTrue(documents.get(0).getText().contains("Sam: Pagination works"));
        assertEquals(2, documents.get(0).getMetadata().get("jira.commentsLoaded"));
        assertEquals("APP-7", documents.get(0).getMetadata().get("jira.issueKey"));
    }

    @Test
    void extractsNestedAtlassianDocumentFormatText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("First line\nSecond line", JiraDocumentLoader.adfText(mapper.readTree("""
                {"type":"doc","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"First line"}]},
                  {"type":"paragraph","content":[{"type":"text","text":"Second line"}]}
                ]}
                """)));
    }

    @Test
    void productionLoaderRejectsPlaintextAndUntrustedJiraSitesBeforeAuthentication() {
        JiraDocumentLoader loader = new JiraDocumentLoader(null, new ObjectMapper());
        Map<String, Object> credentials = Map.of(
                "email", "operator@example.com", "apiToken", "secret-token");

        assertThrows(IllegalArgumentException.class, () -> loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.JIRA)
                .pathOrUrl("http://company.atlassian.net")
                .metadata(credentials).build()));
        assertThrows(IllegalArgumentException.class, () -> loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.JIRA)
                .pathOrUrl("https://attacker.example")
                .metadata(credentials).build()));
    }

    @Test
    void providerUsesManagedCredentialCompletenessAndTokenReadiness() {
        OAuthConnectionService oauthService = mock(OAuthConnectionService.class);
        when(oauthService.getProviderInfo("atlassian")).thenReturn(Optional.of(
                OAuthProviderInfo.builder().providerId("atlassian").configured(true).build()));
        when(oauthService.getConnectionStatus("atlassian")).thenReturn(
                OAuthConnectionStatus.builder().providerId("atlassian")
                        .status(ConnectionStatus.CONNECTED).connected(true).build());
        when(oauthService.isConnectionUsable("atlassian")).thenReturn(false, true);
        JiraSourceProvider provider = new JiraSourceProvider(oauthService);

        assertEquals("oauth2", provider.getAuthType());
        assertTrue(provider.requiresAuth());
        org.junit.jupiter.api.Assertions.assertFalse(provider.requiresAuth());
        assertEquals(Boolean.TRUE, provider.getConfiguration().get("oauthConfigured"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedTokenIsRefreshedAndRetriedAfterUnauthorizedPage() throws Exception {
        OAuthConnectionService oauthService = mock(OAuthConnectionService.class);
        when(oauthService.getValidAccessToken("atlassian"))
                .thenReturn("old-token", "old-token", "new-token");
        when(oauthService.getProviderData("atlassian")).thenReturn(
                "[{\"id\":\"cloud-team\",\"url\":\"https://team.atlassian.net\"}]");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> unauthorized = mock(HttpResponse.class);
        when(unauthorized.statusCode()).thenReturn(401);
        when(unauthorized.body()).thenReturn("expired");
        HttpResponse<String> success = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(success.body()).thenReturn("""
                {"total":1,"issues":[{"id":"1","key":"APP-1","fields":{
                  "summary":"Refreshed","description":"ok","comment":{"total":0,"comments":[]}
                }}]}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(unauthorized, success);
        JiraDocumentLoader loader = new JiraDocumentLoader(
                oauthService, new ObjectMapper(), httpClient);

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.JIRA)
                .pathOrUrl("https://team.atlassian.net")
                .metadata(Map.of("includeComments", false))
                .build());

        assertEquals(1, documents.size());
        verify(oauthService).refreshConnection("atlassian");
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesRateLimitedJiraSearchUsingRetryAfter() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> rateLimited = mock(HttpResponse.class);
        when(rateLimited.statusCode()).thenReturn(429);
        when(rateLimited.body()).thenReturn("slow down");
        when(rateLimited.headers()).thenReturn(HttpHeaders.of(
                Map.of("Retry-After", List.of("0")), (name, value) -> true));
        HttpResponse<String> success = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(success.body()).thenReturn("""
                {"total":1,"issues":[{"id":"2","key":"APP-2","fields":{
                  "summary":"Retried","description":"ok","comment":{"total":0,"comments":[]}
                }}]}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rateLimited, success);
        JiraDocumentLoader loader = new JiraDocumentLoader(null, new ObjectMapper(), httpClient);

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.JIRA)
                .pathOrUrl("http://127.0.0.1")
                .metadata(Map.of("email", "user@example.com", "apiToken", "token",
                        "includeComments", false))
                .build());

        assertEquals(1, documents.size());
        verify(httpClient, times(2)).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void followsEnhancedSearchNextPageTokens() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/search/jql", exchange -> {
            int request = requests.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request == 2) assertTrue(requestBody.contains("\"nextPageToken\":\"page-2\""));
            String json = request == 1
                    ? "{\"nextPageToken\":\"page-2\",\"isLast\":false,\"issues\":[{\"id\":\"1\",\"key\":\"APP-1\",\"fields\":{\"summary\":\"One\"}}]}"
                    : "{\"isLast\":true,\"issues\":[{\"id\":\"2\",\"key\":\"APP-2\",\"fields\":{\"summary\":\"Two\"}}]}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        JiraDocumentLoader loader = new JiraDocumentLoader(
                null, new ObjectMapper(), HttpClient.newHttpClient());

        var documents = loader.load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.JIRA)
                .pathOrUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .metadata(Map.of("email", "user@example.com", "apiToken", "token",
                        "maxIssues", 2, "includeComments", false))
                .build());

        assertEquals(2, documents.size());
        assertEquals(2, requests.get());
    }
}
