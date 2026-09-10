/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.telegram;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramRuntimeStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void checkpointSurvivesRestartAndIsBoundToBotIdentity() throws Exception {
        Path path = tempDir.resolve("telegram.json");
        TelegramRuntimeStateStore first = new TelegramRuntimeStateStore(path, JsonUtils.standardMapper());
        assertFalse(first.forBot(17L).initialized());
        first.initialize(17L, 41L);
        first.advance(17L, 42L, Instant.parse("2026-01-01T00:00:00Z"));

        TelegramRuntimeStateStore restarted = new TelegramRuntimeStateStore(path, JsonUtils.standardMapper());
        assertTrue(restarted.forBot(17L).initialized());
        assertEquals(42L, restarted.forBot(17L).nextOffset());
        assertThrows(IllegalStateException.class, () -> restarted.forBot(18L));

        restarted.delete();
        assertFalse(Files.exists(path));
    }
}
