/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalExternalSourceLoaderRegistryTest {
    @TempDir Path tempDir;

    @Test
    void advertisesManagedConnectorTypesForLocalExecution() {
        assertTrue(LocalExternalSourceLoaderRegistry.supports("jira"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("reddit"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("notion"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("slack-history"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("google_workspace"));
        assertFalse(LocalExternalSourceLoaderRegistry.supports("file"));
        assertEquals(
                LocalExternalSourceLoaderRegistry.identityKey(
                        "NOTION", "01234567-89ab-cdef-0123-456789abcdef"),
                LocalExternalSourceLoaderRegistry.identityKey(
                        "notion", "https://www.notion.so/Project-0123456789abcdef0123456789abcdef?pvs=4"));
    }

    @Test
    void recursivelyRemovesCredentialMetadata() {
        Map<String, Object> sanitized = LocalExternalSourceLoaderRegistry.sanitizeMetadata(Map.of(
                "title", "Visible",
                "accessToken", "secret",
                "nested", Map.of("apiToken", "also-secret", "channel", "general"),
                "items", List.of(Map.of("password", "hidden", "name", "kept"))));

        assertEquals("Visible", sanitized.get("title"));
        assertFalse(sanitized.containsKey("accessToken"));
        assertEquals(Map.of("channel", "general"), sanitized.get("nested"));
        assertEquals(List.of(Map.of("name", "kept")), sanitized.get("items"));
    }

    @Test
    void locatorOptionalTypesAndMetadataIdentitySkipTheLocatorGate() {
        // Loader never reads the locator (mailbox/account-wide types).
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator("GMAIL", Map.of()));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator("IMAP", null));
        // Locator-centric types need either the locator or identity metadata.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator("GDRIVE", Map.of()));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("fileIds", List.of("file-1", "file-2"))));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "NOTION", Map.of("pageIds", "page-1")));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "DISCORD", Map.of("guildId", "guild-9")));
        // A folder id identifies a Drive/OneDrive source without explicit item ids.
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("folderId", "folder-1")));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "ONEDRIVE", Map.of("folderId", "root")));
        // An empty metadata list does not constitute identity.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("fileIds", List.of())));
        // Unknown identifier keys never substitute for the locator.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "CONFLUENCE", Map.of("spaceKey", "DEV")));
    }

    @Test
    void failedRefreshKeepsTheLastCompleteMaterializedSnapshot() throws Exception {
        Path export = Files.createDirectories(tempDir.resolve("export"));
        Files.writeString(export.resolve("page.html"),
                "<html><body>last-complete-snapshot</body></html>", StandardCharsets.UTF_8);
        Path target = tempDir.resolve("materialized");
        LocalExternalSourceLoaderRegistry.materialize(
                "CONFLUENCE", export.toString(), Map.of(), target, 0,
                new com.fasterxml.jackson.databind.ObjectMapper());
        List<Path> original;
        try (var files = Files.list(target)) {
            original = files.toList();
        }
        assertEquals(1, original.size());
        assertFalse(original.get(0).getFileName().toString().startsWith("00001-"));

        Files.delete(export.resolve("page.html"));
        assertThrows(IllegalStateException.class, () ->
                LocalExternalSourceLoaderRegistry.materialize(
                        "CONFLUENCE", export.toString(), Map.of(), target, 0,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        assertTrue(Files.isRegularFile(original.get(0)));
        assertTrue(Files.readString(original.get(0)).contains("last-complete-snapshot"));
    }
}
