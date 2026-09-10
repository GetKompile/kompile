/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.source;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSourceCommandTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void registersOauthAndSyncLifecycleCommands() {
        CommandLine command = new CommandLine(new AuthSourceCommand());
        assertTrue(command.getSubcommands().keySet().containsAll(java.util.Set.of(
                "providers", "web-login", "oauth-status", "oauth-settings", "oauth-setup",
                "configure-oauth", "validate-oauth", "login", "oauth-health", "refresh",
                "disconnect-oauth", "reset-oauth",
                "ingest", "connect", "list", "status", "test", "enable", "disable", "pull",
                "sync", "delete")));
        assertEquals(2, command.execute("reset-oauth", "slack"));
    }

    @Test
    void parsesTypedSourcePropertiesForLoaderContracts() {
        assertEquals(Boolean.TRUE, AuthSourceCommand.parsePropertyValue("TRUE"));
        assertEquals(30, AuthSourceCommand.parsePropertyValue("30"));
        assertInstanceOf(java.util.List.class,
                AuthSourceCommand.parsePropertyValue("[\"gmail\",\"drive\"]"));
        assertEquals("C01234567,C07654321",
                AuthSourceCommand.parsePropertyValue("C01234567,C07654321"));
        assertTrue(AuthSourceCommand.sourceSecretFields("NOTION").containsAll(
                java.util.Set.of("apiToken", "accessToken")));
    }

    @Test
    void channelCredentialMappingCoversSlackDiscordAndEmailAndRejectsMismatch() {
        ChannelCredentialView discord = new ChannelCredentialView("discord",
                Map.of("botToken", "runtime-only"), Map.of("allowedChannelIds", java.util.List.of("C01")));
        assertEquals(Map.of("botToken", "runtime-only"),
                AuthSourceCommand.mappedChannelCredentials(discord, "DISCORD"));

        ChannelCredentialView slack = new ChannelCredentialView("slack",
                Map.of("botToken", "xoxb-1"), Map.of());
        assertEquals(Map.of("slackToken", "xoxb-1"),
                AuthSourceCommand.mappedChannelCredentials(slack, "SLACK_HISTORY"));

        ChannelCredentialView email = new ChannelCredentialView("email",
                Map.of("password", "app-pw"),
                Map.of("username", "ops@example.com", "imapHost", "imap.example.com",
                        "imapPort", 993));
        assertEquals(Map.of("password", "app-pw", "username", "ops@example.com",
                        "host", "imap.example.com", "port", 993),
                AuthSourceCommand.mappedChannelCredentials(email, "EMAIL"));

        assertThrows(IllegalArgumentException.class,
                () -> AuthSourceCommand.mappedChannelCredentials(discord, "SLACK"));
        assertThrows(IllegalArgumentException.class,
                () -> AuthSourceCommand.mappedChannelCredentials(
                        new ChannelCredentialView("telegram", Map.of("botToken", "t"), Map.of()),
                        "TELEGRAM"));
    }

    @Test
    void oauthProviderMappingCoversTokenBasedLoadersOnly() {
        assertEquals("google", AuthSourceCommand.oauthProviderFor("GMAIL"));
        assertEquals("google", AuthSourceCommand.oauthProviderFor("GDOCS"));
        assertEquals("google", AuthSourceCommand.oauthProviderFor("GDRIVE"));
        assertEquals("google", AuthSourceCommand.oauthProviderFor("GOOGLE_WORKSPACE"));
        assertEquals("microsoft", AuthSourceCommand.oauthProviderFor("ONEDRIVE"));
        assertEquals("google", AuthSourceCommand.oauthProviderFor("EMAIL"));
        assertEquals("google", AuthSourceCommand.oauthProviderFor("IMAP"));
        assertEquals("google", AuthSourceCommand.oauthProviderFor("POP3"));
        assertEquals("notion", AuthSourceCommand.oauthProviderFor("NOTION"));
        assertEquals("reddit", AuthSourceCommand.oauthProviderFor("REDDIT"));
        assertEquals("atlassian", AuthSourceCommand.oauthProviderFor("JIRA"));
        assertEquals("atlassian", AuthSourceCommand.oauthProviderFor("CONFLUENCE"));
        assertNull(AuthSourceCommand.oauthProviderFor("DISCORD"));
        assertNull(AuthSourceCommand.oauthProviderFor("SLACK"));
        assertNull(AuthSourceCommand.oauthProviderFor("S3"));
    }

    @Test
    void oauthMetadataKeysCopyCloudIdForAtlassianTypesOnly() {
        assertEquals(java.util.List.of("cloudId"),
                AuthSourceCommand.oauthMetadataKeysFor("JIRA"));
        assertEquals(java.util.List.of("cloudId"),
                AuthSourceCommand.oauthMetadataKeysFor("CONFLUENCE"));
        assertTrue(AuthSourceCommand.oauthMetadataKeysFor("NOTION").isEmpty());
        assertTrue(AuthSourceCommand.oauthMetadataKeysFor("GMAIL").isEmpty());
    }

    @Test
    void oneOffIngestRequiresExplicitFactSheetScope() {
        CommandLine command = new CommandLine(new AuthSourceCommand());

        // Default (no pinned --url/--port) is folder-local: no fact sheet needed.
        CommandLine.ParseResult local = command.parseArgs(
                "ingest", "reddit", "--path", "r/java");
        AuthSourceCommand.Ingest defaultIngest =
                (AuthSourceCommand.Ingest) local.subcommand().commandSpec().userObject();
        assertNull(defaultIngest.factSheetId);
        assertFalse(defaultIngest.local);

        // A pinned server without a fact sheet is rejected before any connection.
        assertEquals(2, command.execute(
                "ingest", "reddit", "--path", "r/java", "--url", "http://localhost:8080"));

        // --local forces folder-local even with a pinned server.
        CommandLine.ParseResult forced = command.parseArgs(
                "ingest", "reddit", "--path", "r/java", "--url", "http://localhost:8080",
                "--local");
        assertTrue(((AuthSourceCommand.Ingest) forced.subcommand().commandSpec().userObject()).local);
    }

    @Test
    void locatorIdentityFollowsTheLoaderContractNotCliInventedDefaults() {
        CommandLine command = new CommandLine(new AuthSourceCommand());

        // Account-wide types ingest without a locator (loader declares them optional).
        assertNull(parseIngestLocator(command, "gmail"));
        assertNull(parseIngestLocator(command, "google-workspace"));
        assertNull(parseIngestLocator(command, "email"));
        // GDOCS discovers its own document ids (folderId/driveQuery) — no path required.
        assertNull(parseIngestLocator(command, "gdocs"));
        // Identity metadata substitutes for the locator (loader mirrors this too).
        assertNull(parseIngestLocator(command, "discord", Map.of("guildId", "g-1")));
        assertNull(parseIngestLocator(command, "gdrive", Map.of("fileIds", "f-1,f-2")));

        // Locator-centric types are refused before any connection — with no invented
        // content default (no "r/all"); the user names their subreddit/site/folder.
        assertEquals(2, command.execute("ingest", "reddit"));
        assertEquals(2, command.execute("ingest", "jira"));
        assertEquals(2, command.execute("ingest", "gdrive"));
    }

    private static String parseIngestLocator(
            CommandLine command, String type, Map<String, String>... sets) {
        java.util.List<String> args = new java.util.ArrayList<>(
                java.util.List.of("ingest", type));
        for (Map<String, String> set : sets) {
            set.forEach((key, value) -> {
                args.add("--set");
                args.add(key + "=" + value);
            });
        }
        CommandLine.ParseResult parsed = command.parseArgs(args.toArray(String[]::new));
        return ((AuthSourceCommand.Ingest)
                parsed.subcommand().commandSpec().userObject()).pathOrUrl;
    }

    @Test
    void serverModeIngestSendsTheDefaultedLocatorForAccountWideTypes() throws Exception {
        // EMAIL without an OAUTH2 authMode skips the OAuth fill-in. This regression used to
        // NPE: the server-mode payload used the raw --path option (null when omitted)
        // instead of the defaulted locator for account-wide types.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> crawlBody = new AtomicReference<>();
        server.createContext("/api/fact-sheets/7", exchange -> respond(
                exchange, "{\"id\":7,\"name\":\"Research\"}"));
        server.createContext("/api/unified-crawl/source-types", exchange -> respond(
                exchange, "[{\"type\":\"EMAIL\",\"available\":true,\"requiredProperties\":[]}]"));
        server.createContext("/api/unified-crawl/start", exchange -> {
            crawlBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"jobId\":\"job-loc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AuthSourceCommand.Ingest ingest = new AuthSourceCommand.Ingest();
        java.lang.reflect.Field appField = AuthSourceCommand.Base.class.getDeclaredField("app");
        appField.setAccessible(true);
        AppClientMixin app = new AppClientMixin();
        java.lang.reflect.Field urlField = AppClientMixin.class.getDeclaredField("url");
        urlField.setAccessible(true);
        urlField.set(app, "http://127.0.0.1:" + server.getAddress().getPort());
        java.lang.reflect.Field jsonField = AppClientMixin.class.getDeclaredField("jsonOutput");
        jsonField.setAccessible(true);
        jsonField.set(app, true);
        appField.set(ingest, app);
        java.lang.reflect.Field factSheetField =
                AuthSourceCommand.Ingest.class.getDeclaredField("factSheetId");
        factSheetField.setAccessible(true);
        factSheetField.set(ingest, Long.valueOf(7L));

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("name", "Ingest email");
        request.put("factSheetId", 7L);
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("label", "email");
        source.put("sourceType", "EMAIL");
        source.put("pathOrUrl", "");
        source.put("maxDepth", 0);
        source.put("maxDocuments", 0);
        source.put("properties", Map.of());
        request.put("sources", List.of(source));

        var client = new SourceControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);
        assertEquals("job-loc", client.startSourceCrawl(request).path("jobId").asText());

        String body = crawlBody.get();
        assertTrue(body.contains("\"sourceType\":\"EMAIL\""), body);
        assertTrue(body.contains("\"pathOrUrl\":\"\""), body);
        assertFalse(body.contains("null"), body);
    }

    @Test
    void oneOffIngestExposesFolderLocalExecutionMode() {
        CommandLine command = new CommandLine(new AuthSourceCommand());

        CommandLine.ParseResult parsed = command.parseArgs(
                "ingest", "notion", "--path", "0123456789abcdef0123456789abcdef",
                "--fact-sheet-id", "7", "--local", "--skip-final-learning");

        assertTrue(parsed.subcommand().hasMatchedOption("--local"));
        AuthSourceCommand.Ingest ingest =
                (AuthSourceCommand.Ingest) parsed.subcommand().commandSpec().userObject();
        assertTrue(ingest.local);
        assertTrue(ingest.skipFinalLearning);
    }

    @Test
    void sourceClientVerifiesFactSheetExistenceBeforeIngest() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fact-sheets/7", exchange -> {
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            byte[] response = "{\"id\":7,\"name\":\"Research\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        SourceControlPlaneClient client = new SourceControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        assertEquals(7L, client.factSheet(7L).path("id").asLong());
    }

    @Test
    void sourceClientUsesBearerAndMutationProof() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/sync/connections", exchange -> {
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            assertEquals("1",
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.REQUEST_HEADER));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"provider\":\"OBSIDIAN\""));
            byte[] response = "{\"id\":42,\"provider\":\"OBSIDIAN\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        SourceControlPlaneClient client = new SourceControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        assertEquals(42L, client.createSync(Map.of(
                "factSheetId", 7,
                "provider", "OBSIDIAN",
                "externalScope", "/vault",
                "direction", "BIDIRECTIONAL")).path("id").asLong());
    }

    @Test
    void sourceClientExposesCompleteWebOauthManagementWithoutLeakingSecrets() throws Exception {
        AtomicInteger settingsRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/oauth/settings/slack", exchange -> {
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            int request = settingsRequests.incrementAndGet();
            String body;
            if ("GET".equals(exchange.getRequestMethod())) {
                assertNull(exchange.getRequestHeaders().getFirst(ChannelControlHeaders.REQUEST_HEADER));
                body = "{\"providerId\":\"slack\",\"clientSecret\":\"********\",\"configured\":true}";
            } else {
                assertEquals("DELETE", exchange.getRequestMethod());
                assertEquals("1",
                        exchange.getRequestHeaders().getFirst(ChannelControlHeaders.REQUEST_HEADER));
                body = "{\"success\":true}";
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            assertTrue(request <= 2);
        });
        server.createContext("/api/oauth/settings/slack/validate", exchange -> respond(
                exchange, "{\"providerId\":\"slack\",\"configured\":true}"));
        server.createContext("/api/oauth/settings/setup-info", exchange -> respond(
                exchange, "[{\"providerId\":\"slack\"}]"));
        server.createContext("/api/oauth/slack/health", exchange -> respond(
                exchange, "{\"providerId\":\"slack\",\"healthy\":true}"));
        server.start();
        SourceControlPlaneClient client = new SourceControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        assertEquals("********", client.oauthSettings("slack").path("clientSecret").asText());
        assertTrue(client.validateOAuthSettings("slack").path("configured").asBoolean());
        assertEquals("slack", client.oauthSetupInfo().get(0).path("providerId").asText());
        assertTrue(client.oauthHealth("slack").path("healthy").asBoolean());
        assertTrue(client.deleteOAuthSettings("slack").path("success").asBoolean());
        assertEquals(2, settingsRequests.get());
    }

    @Test
    void sourceClientRefusesToSendAdminBearerOverRemotePlaintextHttp() {
        SourceControlPlaneClient client = new SourceControlPlaneClient(
                new KompileHttpClient("http://oauth.example.test:8082"), TOKEN);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.oauthSettings("slack"));

        assertTrue(error.getMessage().contains("remote plaintext HTTP"));
    }

    @Test
    void authenticatedIngestStartsLiveSourceCrawl() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/unified-crawl/start", exchange -> {
            assertEquals(TOKEN,
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
            assertEquals("1",
                    exchange.getRequestHeaders().getFirst(ChannelControlHeaders.REQUEST_HEADER));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"sourceType\":\"DISCORD\""));
            assertTrue(request.contains("\"botToken\":\"runtime-only\""));
            byte[] response = "{\"jobId\":\"job-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        SourceControlPlaneClient client = new SourceControlPlaneClient(
                new KompileHttpClient("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);

        assertEquals("job-1", client.startSourceCrawl(Map.of(
                "name", "Discord history",
                "sources", java.util.List.of(Map.of(
                        "sourceType", "DISCORD",
                        "pathOrUrl", "guild-1",
                        "properties", Map.of("botToken", "runtime-only")))))
                .path("jobId").asText());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        assertEquals(TOKEN,
                exchange.getRequestHeaders().getFirst(ChannelControlHeaders.TOKEN_HEADER));
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
