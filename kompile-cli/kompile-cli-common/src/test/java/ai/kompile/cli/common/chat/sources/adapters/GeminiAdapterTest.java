/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.common.chat.sources.adapters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void listsAndReadsProjectScopedSession() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        try {
            Path cwd = tempDir.resolve("project").toAbsolutePath().normalize();
            Path chats = Path.of(System.getProperty("user.home"), ".gemini", "tmp",
                    GeminiAdapter.projectHash(cwd), "chats");
            Files.createDirectories(chats);
            Files.writeString(chats.resolve("session-2026-08-20T01-02-03-test.json"), """
                    {
                      "sessionId": "gemini-session",
                      "projectHash": "ignored",
                      "startTime": "2026-08-20T01:02:03Z",
                      "lastUpdated": "2026-08-20T01:02:03Z",
                      "workingDirectory": "%s",
                      "messages": [
                        {"id":"u1","timestamp":"2026-08-20T01:02:03Z","type":"user","content":"hello"},
                        {"id":"a1","timestamp":"2026-08-20T01:02:04Z","type":"gemini","content":"hi"}
                      ]
                    }
                    """.formatted(cwd));

            GeminiAdapter adapter = new GeminiAdapter();
            assertEquals(1, adapter.list(cwd).size());
            assertEquals("gemini-session", adapter.list(cwd).get(0).sessionId());

            List<ai.kompile.cli.common.chat.sources.ChatTurn> turns =
                    adapter.readTurns("gemini-session");
            assertEquals(2, turns.size());
            assertTrue(turns.get(0).isUser());
            assertTrue(turns.get(1).isAssistant());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
