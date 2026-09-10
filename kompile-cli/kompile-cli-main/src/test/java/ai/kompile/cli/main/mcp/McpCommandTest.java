/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.mcp;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.McpServerAuthManager;
import ai.kompile.cli.main.chat.mcp.McpConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import picocli.CommandLine;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("user.home")
class McpCommandTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mainCliRegistersCustomMcpLifecycle() {
        CommandLine cli = new CommandLine(new MainCommand());
        assertTrue(cli.getSubcommands().containsKey("mcp"));
        CommandLine mcp = cli.getSubcommands().get("mcp");
        assertTrue(mcp.getSubcommands().keySet().containsAll(
                java.util.Set.of("add", "list", "get", "remove", "enable", "disable")));
    }

    @Test
    void nativeImageMetadataIncludesCustomMcpCommandTree() throws Exception {
        Set<String> required = Set.of(
                "ai.kompile.cli.main.mcp.McpCommand",
                "ai.kompile.cli.main.mcp.McpCommand$WorkspaceCommand",
                "ai.kompile.cli.main.mcp.McpCommand$ScopedMutation",
                "ai.kompile.cli.main.mcp.McpCommand$Add",
                "ai.kompile.cli.main.mcp.McpCommand$ListServers",
                "ai.kompile.cli.main.mcp.McpCommand$Get",
                "ai.kompile.cli.main.mcp.McpCommand$Remove",
                "ai.kompile.cli.main.mcp.McpCommand$SetEnabled",
                "ai.kompile.cli.main.mcp.McpCommand$Enable",
                "ai.kompile.cli.main.mcp.McpCommand$Disable");

        try (InputStream input = getClass().getResourceAsStream(
                "/META-INF/native-image/ai.kompile/kompile-cli/reflect-config.json")) {
            assertNotNull(input, "missing canonical native-image reflection configuration");
            assertContainsNames(mapper.readTree(input), required, "canonical native config");
        }

        Path generated = Path.of("src/main/java/META-INF/native-image/picocli-generated/reflect-config.json");
        assertTrue(Files.isRegularFile(generated), "missing generated Picocli reflection configuration");
        assertContainsNames(mapper.readTree(generated.toFile()), required,
                "generated Picocli native config");
    }

    @Test
    void installsDisablesEnablesAndRemovesProjectStdioServer() throws Exception {
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
            CommandLine cli = new CommandLine(new McpCommand());

            assertEquals(0, cli.execute("add", "--work-dir", workspace.toString(),
                    "--env", "API_TOKEN=$CUSTOM_MCP_TOKEN", "--required",
                    "example", "--", "npx", "-y", "@example/mcp"));

            Path config = workspace.resolve(".mcp.json");
            JsonNode server = mapper.readTree(config.toFile())
                    .path("mcpServers").path("example");
            assertEquals("stdio", server.path("type").asText());
            assertEquals("npx", server.path("command").asText());
            assertEquals("-y", server.path("args").get(0).asText());
            assertEquals("$CUSTOM_MCP_TOKEN", server.path("env").path("API_TOKEN").asText());
            assertTrue(server.path("required").asBoolean());
            assertTrue(server.path("enabled").asBoolean());

            assertEquals(0, cli.execute("disable", "--work-dir", workspace.toString(), "example"));
            assertFalse(mapper.readTree(config.toFile()).path("mcpServers")
                    .path("example").path("enabled").asBoolean(true));

            assertEquals(0, cli.execute("enable", "--work-dir", workspace.toString(), "example"));
            assertTrue(mapper.readTree(config.toFile()).path("mcpServers")
                    .path("example").path("enabled").asBoolean());

            assertEquals(0, cli.execute("remove", "--work-dir", workspace.toString(), "example"));
            assertFalse(mapper.readTree(config.toFile()).path("mcpServers").has("example"));
        });
    }

    @Test
    void installsUserScopedStreamableHttpWithSecretReferencesAndToolFilters() throws Exception {
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("http-workspace"));
            CommandLine cli = new CommandLine(new McpCommand());

            assertEquals(0, cli.execute("install", "--work-dir", workspace.toString(),
                    "--scope", "user", "--transport", "http",
                    "--header-env", "X-Api-Key=MCP_API_KEY",
                    "--bearer-token-env-var", "MCP_BEARER_TOKEN",
                    "--include-tool", "search,read", "--exclude-tool", "delete",
                    "remote-docs", "https://mcp.example.test/mcp"));

            Path userConfig = tempDir.resolve("home/.kompile/config/mcp-servers.json");
            JsonNode server = mapper.readTree(userConfig.toFile())
                    .path("mcpServers").path("remote-docs");
            assertEquals("http", server.path("type").asText());
            assertEquals("https://mcp.example.test/mcp", server.path("url").asText());
            assertEquals("MCP_API_KEY", server.path("envHeaders").path("X-Api-Key").asText());
            assertEquals("MCP_BEARER_TOKEN", server.path("bearerTokenEnvVar").asText());
            assertEquals(2, server.path("includeTools").size());
            assertEquals("delete", server.path("excludeTools").get(0).asText());
            assertFalse(Files.exists(workspace.resolve(".mcp.json")));
        });
    }

    @Test
    void rejectsReservedNamesAndTransportSpecificOptionMixes() throws Exception {
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("invalid-workspace"));
            CommandLine cli = new CommandLine(new McpCommand());

            assertEquals(1, cli.execute("add", "--work-dir", workspace.toString(),
                    "kompile", "--", "custom-server"));
            assertEquals(1, cli.execute("add", "--work-dir", workspace.toString(),
                    "--transport", "http", "--env", "TOKEN=$TOKEN",
                    "remote", "https://example.test/mcp"));
            assertEquals(1, cli.execute("add", "--work-dir", workspace.toString(),
                    "--transport", "http", "embedded-secret",
                    "https://user:password@example.test/mcp"));
            assertFalse(Files.exists(workspace.resolve(".mcp.json")));
        });
    }

    @Test
    void displayCopyRedactsEndpointQueriesAndConfiguredSecrets() {
        ObjectNode config = mapper.createObjectNode();
        config.put("type", "http");
        config.put("url", "https://example.test/mcp?token=super-secret#access_token=fragment-secret");
        config.putObject("headers").put("Authorization", "Bearer super-secret");
        config.putObject("env").put("API_TOKEN", "super-secret");

        String displayed = McpConfigStore.redacted(config).toString();
        assertFalse(displayed.contains("super-secret"));
        assertFalse(displayed.contains("fragment-secret"));
        assertTrue(displayed.contains("redacted"));
    }

    @Test
    void mcpAuthReportsMissingAndStdioServers() throws Exception {
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("auth-workspace"));
            CommandLine cli = new CommandLine(new McpCommand());
            assertEquals(1, cli.execute("auth", "status", "--work-dir",
                    workspace.toString(), "missing-server"));

            assertEquals(0, cli.execute("add", "--work-dir", workspace.toString(),
                    "stdio-server", "--", "npx", "-y", "@example/mcp"));
            assertEquals(1, cli.execute("auth", "status", "--work-dir",
                    workspace.toString(), "stdio-server"));
        });
    }

    @Test
    void mcpServerAuthManagerRoundTripsTokensThroughCredentialStore() throws Exception {
        withIsolatedHome(() -> {
            Path home = Files.createDirectories(tempDir.resolve("auth-home"));
            CredentialStore store = CredentialStore.create();
            McpServerAuthManager manager = new McpServerAuthManager(store);
            assertFalse(manager.hasCredential("round-trip"));
            assertFalse(manager.hasValidCredential("round-trip"));
            assertNull(manager.resolveAccessToken("round-trip"));

            store.put(McpServerAuthManager.providerIdFor("round-trip"),
                    ai.kompile.cli.common.auth.ManagedCredential.oauth(
                            "access-token-value", "refresh-token-value",
                            System.currentTimeMillis() + java.time.Duration.ofHours(1).toMillis(),
                            java.util.Map.of(
                                    "clientId", "client-123",
                                    "registrationEndpoint", "https://as.example.test/token")));
            assertTrue(manager.hasCredential("round-trip"));
            assertTrue(manager.hasValidCredential("round-trip"));
            assertEquals("access-token-value", manager.resolveAccessToken("round-trip"));
            assertEquals(McpServerAuthManager.providerIdFor("round-trip"),
                    "mcp-server-round-trip");
            assertTrue(manager.logout("round-trip"));
            assertFalse(manager.hasCredential("round-trip"));
        });
    }

    private static void assertNull(Object value) throws AssertionError {
        if (value != null) {
            throw new AssertionError("expected null but was: " + value);
        }
    }

    private void withIsolatedHome(ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty("user.home");
        Path home = Files.createDirectories(tempDir.resolve("home"));
        try {
            System.setProperty("user.home", home.toString());
            runnable.run();
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }

    private static void assertContainsNames(JsonNode config, Set<String> required, String label) {
        Set<String> registered = new HashSet<>();
        config.forEach(entry -> registered.add(entry.path("name").asText()));
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(registered);
        assertTrue(missing.isEmpty(), () -> label + " missing: " + missing);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
