/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.incidents;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IncidentDirectorySafetyTest {
    @TempDir Path project;

    @Test void replacingAnOpenedAncestorCannotRedirectRecordReadsOrWrites() throws Exception {
        var draft = JsonUtils.standardMapper().createObjectNode().put("title", "Original")
                .put("symptom", "Test symptom").put("invariant", "Test invariant");
        var store = new IncidentStore(project);
        var original = store.mutate("add", null, 0, draft, null, "test");
        String filename = original.path("id").asText() + ".json";
        Path metadata = project.resolve(".kompile");
        Path external = Files.createDirectory(project.resolve("external"));
        Files.createDirectory(external.resolve("incidents"));
        Path sentinel = Files.writeString(external.resolve("incidents").resolve(filename), "external-untouched");
        try (var handle = store.openDirectory(metadata.resolve("incidents"))) {
            Files.move(metadata, project.resolve("parked-metadata"));
            Files.createSymbolicLink(metadata, external);
            assertEquals("Original", IncidentStore.readRecord(handle, Path.of(filename)).path("title").asText());
            var changed = original.deepCopy().put("title", "Anchored write");
            IncidentStore.writeRecord(handle, Path.of(filename), changed);
            assertEquals("Anchored write", IncidentStore.readRecord(handle, Path.of(filename)).path("title").asText());
            assertEquals("external-untouched", Files.readString(sentinel));
            assertThrows(java.io.IOException.class, () -> store.get(original.path("id").asText(), null));
        }
        assertTrue(Files.readString(project.resolve("parked-metadata/incidents").resolve(filename)).contains("Anchored write"));
    }
}
