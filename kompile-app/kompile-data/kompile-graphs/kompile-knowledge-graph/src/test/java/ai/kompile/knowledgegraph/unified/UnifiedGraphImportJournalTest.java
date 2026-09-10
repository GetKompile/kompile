/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphImportJournalTest {

    @TempDir
    Path dataDir;

    @Test
    void interruptedApplyingJournalBlocksDestinationAfterRestart() {
        UnifiedGraphImportJournal first = journal();
        first.recoverInterrupted();
        first.start("tx-1", List.of(new UnifiedGraphImportJournal.ScopeArchive(
                7L, graph("incoming"), graph("backup"))));
        first.markApplying("tx-1");

        UnifiedGraphImportJournal restarted = journal();
        restarted.recoverInterrupted();

        assertThrows(IllegalStateException.class, () -> restarted.assertAvailable(List.of(7L)));
        assertTrue(Files.isRegularFile(dataDir.resolve("graph-import-transactions/tx-1/manifest.json")));
        assertTrue(Files.isRegularFile(
                dataDir.resolve("graph-import-transactions/tx-1/factsheet-7-backup.kgraph")));

        List<UnifiedGraphImportJournal.ScopeArchive> recovery = restarted.beginRecovery("tx-1");
        assertEquals("backup", recovery.get(0).backup().entity("backup").orElseThrow().label());
        assertDoesNotThrow(() -> restarted.assertAvailable(List.of(7L)));
        restarted.recoveryFailed("tx-1");
        assertThrows(IllegalStateException.class, () -> restarted.assertAvailable(List.of(7L)));
    }

    @Test
    void completedJournalDeletesRecoveryPayloadAndUnblocksDestination() {
        UnifiedGraphImportJournal journal = journal();
        journal.start("tx-2", List.of(new UnifiedGraphImportJournal.ScopeArchive(
                8L, graph("incoming"), graph("backup"))));
        journal.markApplying("tx-2");

        journal.complete("tx-2", UnifiedGraphImportJournal.Phase.COMMITTED);

        journal.assertAvailable(List.of(8L));
        assertFalse(Files.exists(dataDir.resolve("graph-import-transactions/tx-2")));
    }

    private UnifiedGraphImportJournal journal() {
        UnifiedGraphImportJournal journal = new UnifiedGraphImportJournal();
        ReflectionTestUtils.setField(journal, "dataDir", dataDir.toString());
        return journal;
    }

    private static UnifiedGraph graph(String id) {
        return new UnifiedGraph().factSheetId(7L).addEntity(id, "ENTITY", id);
    }
}
