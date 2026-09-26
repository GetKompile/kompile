/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit coverage for {@link ManagedBlockOwnership}'s pid parsing. The owner
 * token is followed immediately by a closing {@code " -->"} in real managed-block
 * markers (e.g. skills blocks: {@code pid=12345 -->}), so the parser must read only
 * the contiguous digits after {@code pid=} rather than the rest of the line — a
 * naive "rest of line" parse would fail to parse "12345 -->" as a number and treat
 * a live owner's block as dead.
 */
class ManagedBlockOwnershipTest {

    private static final String BEGIN = "<!-- BEGIN TEST BLOCK ";
    private static final String END = "<!-- END TEST BLOCK ";

    @Test
    void pidImmediatelyFollowedByClosingMarkerIsParsedAsLive() {
        long livePid = ProcessHandle.current().pid();
        String block = BEGIN + "x pid=" + livePid + " -->\nBODY\n" + END + "x -->\n";

        String kept = ManagedBlockOwnership.reclaimOrphanedBlocks(block, BEGIN, END);

        assertTrue(kept.contains("BODY"), "a block owned by a live pid must survive reclaim");
    }

    @Test
    void pidImmediatelyFollowedByClosingMarkerIsParsedAsDeadWhenOwnerIsGone() throws Exception {
        long deadPid = spawnDeadProcess();
        String block = BEGIN + "x pid=" + deadPid + " -->\nBODY\n" + END + "x -->\n";

        String kept = ManagedBlockOwnership.reclaimOrphanedBlocks(block, BEGIN, END);

        assertFalse(kept.contains("BODY"), "a block owned by a dead pid must be reclaimed");
    }

    private static long spawnDeadProcess() throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("Current Java command unavailable"));
        Process p = new ProcessBuilder(java, "-version").start();
        p.waitFor();
        return p.pid();
    }
}
