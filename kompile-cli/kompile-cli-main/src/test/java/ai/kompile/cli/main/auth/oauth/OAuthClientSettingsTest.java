/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthClientSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsCredentialsAndMasksNothingOnDisk() throws IOException {
        OAuthClientSettings settings = new OAuthClientSettings(
                tempDir.resolve("config").resolve("oauth-clients.json"));

        settings.put("google", new OAuthClientSettings.ClientCredentials(
                "client-1", null, null));
        settings.put("notion", new OAuthClientSettings.ClientCredentials(
                "notion-id", "notion-secret", null));
        settings.put("microsoft", new OAuthClientSettings.ClientCredentials(
                "ms-id", "ms-secret", "contoso.onmicrosoft.com"));

        assertEquals("client-1", settings.read("google").clientId());
        assertNull(settings.read("google").clientSecret());
        assertEquals("notion-secret", settings.read("notion").clientSecret());
        assertEquals("contoso.onmicrosoft.com", settings.read("microsoft").tenantId());
        assertEquals(List.of("google", "microsoft", "notion"), settings.listProviders());

        String onDisk = Files.readString(Path.of(
                settings.getSettingsPath().toString()));
        assertTrue(onDisk.contains("notion-secret"));
        assertTrue(onDisk.contains("contoso.onmicrosoft.com"));
    }

    @Test
    void putWithBlankValuesThrows() {
        OAuthClientSettings settings = new OAuthClientSettings(
                tempDir.resolve("oauth-clients.json"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> settings.put("google",
                        new OAuthClientSettings.ClientCredentials("  ", null, null)));
    }

    @Test
    void removeReportsWhetherAnythingWasStored() throws IOException {
        OAuthClientSettings settings = new OAuthClientSettings(
                tempDir.resolve("oauth-clients.json"));

        assertFalse(settings.remove("google"));
        settings.put("google", new OAuthClientSettings.ClientCredentials("id", null, null));
        assertTrue(settings.remove("google"));
        assertFalse(settings.remove("google"));
        assertNull(settings.read("google"));
    }

    @Test
    void invalidJsonYieldsEmptyStore() throws IOException {
        Path path = tempDir.resolve("oauth-clients.json");
        Files.writeString(path, "not json at all {{{");
        OAuthClientSettings settings = new OAuthClientSettings(path);

        assertNull(settings.read("google"));
        assertTrue(settings.listProviders().isEmpty());
        // And the store recovers on the next write.
        settings.put("google", new OAuthClientSettings.ClientCredentials("id", null, null));
        assertEquals("id", settings.read("google").clientId());
    }
}
