/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.service.impl;

import ai.kompile.react.model.ReActMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlSessionServiceSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void usesRestrictedOpaqueFilesAndRetainsListableSessionIds() throws Exception {
        JsonlSessionService service = new JsonlSessionService(tempDir.toString());
        String key = "telegram-ops:42:7";
        service.appendMessage(key, ReActMessage.user("private message"));

        Path directory = tempDir.resolve("sessions");
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertTrue(files.stream().anyMatch(path -> path.getFileName().toString()
                .equals("sessions-index.json")));
        assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().endsWith(".jsonl")));
        assertFalse(files.stream().anyMatch(path -> path.getFileName().toString().contains("telegram")));
        assertEquals(List.of(key), service.listSessions());

        JsonlSessionService restarted = new JsonlSessionService(tempDir.toString());
        assertEquals("private message", restarted.loadSession(key).get(0).getContent());
        assertEquals(List.of(key), restarted.listSessions());

        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(directory));
            for (Path file : files) {
                assertEquals(Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
            }
        }
    }
}
