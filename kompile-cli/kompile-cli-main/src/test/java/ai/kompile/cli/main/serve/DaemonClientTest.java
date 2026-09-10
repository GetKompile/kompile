/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DaemonClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mcpHeaderCarriesProfileAndStableResolvedAllowlist(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("quoted-\"-workspace");
        JsonNode header = mapper.readTree(DaemonClient.protocolHeader(
                "mcp", workDir, "explore",
                Set.of("file_context", "local_code_index", "read")));

        assertEquals("mcp", header.path("type").asText());
        assertEquals(workDir.toAbsolutePath().toString(), header.path("workDir").asText());
        assertEquals("explore", header.path("profile").asText());
        assertEquals(List.of("file_context", "local_code_index", "read"),
                values(header.path("allowedTools")));
    }

    @Test
    void legacyCallersOmitProfileFiltering() throws Exception {
        JsonNode header = mapper.readTree(DaemonClient.protocolHeader(
                "chat", Path.of("."), null, null));

        assertFalse(header.has("profile"));
        assertFalse(header.has("allowedTools"));
    }

    @Test
    void protocolHeaderEscapesControlCharactersThroughJsonSerialization(@TempDir Path tempDir)
            throws Exception {
        String profile = "explore\nprofile";
        String tool = "file_context\tvariant";
        JsonNode header = mapper.readTree(DaemonClient.protocolHeader(
                "mcp", tempDir, profile, Set.of(tool)));

        assertEquals(profile, header.path("profile").asText());
        assertEquals(tool, header.path("allowedTools").get(0).asText());
    }

    private static List<String> values(JsonNode array) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }
}
