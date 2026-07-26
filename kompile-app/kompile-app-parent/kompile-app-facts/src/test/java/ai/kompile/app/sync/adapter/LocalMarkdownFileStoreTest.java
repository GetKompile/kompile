/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.adapter;

import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalMarkdownFileStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void unavailableFolderIsNotCreatedOrTreatedAsAnEmptySnapshot() {
        Path missingBinding = temporaryDirectory.resolve("unmounted-vault");
        NoteSyncConnection connection = NoteSyncConnection.builder()
                .id(1L)
                .factSheetId(2L)
                .provider(SyncProvider.LOCAL_FOLDER)
                .externalScope(missingBinding.toString())
                .build();
        LocalMarkdownFileStore store = new LocalMarkdownFileStore();

        assertThrows(IllegalStateException.class,
                () -> store.fetchChangedSince(connection, Instant.EPOCH));
        assertThrows(IllegalStateException.class,
                () -> store.listExternalIds(connection));
        assertFalse(Files.exists(missingBinding));
    }
}
