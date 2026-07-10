/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphSnapshotServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private UnifiedGraphBridge bridge;

    private GraphSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new GraphSnapshotService(bridge);
        ReflectionTestUtils.setField(service, "dataDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxPerSheet", 20);
    }

    @Test
    void sameSecondSnapshotsNeverOverwriteEachOther() throws Exception {
        stubExport();
        GraphSnapshotService.SnapshotMetadata first = service.createSnapshot(42L, "Release Candidate");
        GraphSnapshotService.SnapshotMetadata second = service.createSnapshot(42L, "Release Candidate");

        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertEquals("release-candidate", first.label());
        assertEquals("release-candidate", second.label());
        assertEquals(2, service.listSnapshots(42L).size());
    }

    @Test
    void restoreKeepsSourceUntilImportEvenAtRetentionLimit() throws Exception {
        stubExport();
        GraphSnapshotService.SnapshotMetadata source = service.createSnapshot(42L, "source");
        ReflectionTestUtils.setField(service, "maxPerSheet", 1);
        UnifiedGraphBridge.ImportSummary summary =
                new UnifiedGraphBridge.ImportSummary(3, 2, 1, 4, true);
        when(bridge.importFromFile(any(Path.class), eq(42L))).thenAnswer(invocation -> {
            assertTrue(Files.isRegularFile(invocation.getArgument(0)));
            return summary;
        });

        GraphSnapshotService.RestoreResult result =
                service.restoreSnapshot(42L, source.snapshotId());

        assertEquals(source.snapshotId(), result.restoredSnapshotId());
        assertNotEquals(source.snapshotId(), result.preRestoreSnapshotId());
        assertEquals(summary, result.importSummary());
        verify(bridge).importFromFile(any(Path.class), eq(42L));
    }

    @Test
    void rejectsSnapshotPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteSnapshot(42L, "../outside.kgraph"));
    }

    private void stubExport() throws Exception {
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(0);
            Files.write(destination, new byte[]{0x4b, 0x47, 0x52, 0x46});
            return null;
        }).when(bridge).exportToFile(any(Path.class), eq(42L));
    }
}
