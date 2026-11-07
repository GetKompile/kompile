/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolContextSelectionTest {

    @Test
    void anIndexWithinBudgetIsInjectedVerbatim() {
        String index = "# Kompile Memory Index\n\nFree-form note kept as written.\n- [a](a.md) — [project] a\n";
        assertEquals(index.strip(), MemoryTool.selectIndexForContext(index, 6_000));
    }

    @Test
    void aLargeIndexKeepsStandingRulesAndTheNewestEntriesNotTheOldest() {
        StringBuilder index = new StringBuilder("# Kompile Memory Index\n\n## Standing guidance\n");
        index.append("- [plan-first](plan.md) — [reference] plan before changes\n");
        index.append("\n## Current entries (new saves append below)\n");
        for (int i = 0; i < 400; i++) {
            index.append("- [entry-").append(i).append("](entry_").append(i).append(".md) — [project] note ")
                    .append(i).append('\n');
        }
        index.append("- [late-feedback](late.md) — [feedback] never guess\n");
        index.append("- [newest](newest.md) — [project] the memory saved last\n");

        String selected = MemoryTool.selectIndexForContext(index.toString(), 3_000);
        assertTrue(selected.length() <= 3_000, "length " + selected.length());
        assertTrue(selected.startsWith("(403 entries, selected"), selected);
        assertTrue(selected.contains("plan before changes"), "standing section is pinned");
        assertTrue(selected.contains("never guess"), "feedback entries are pinned wherever they sit");
        assertTrue(selected.contains("the memory saved last"), "the newest save is visible");
        assertTrue(selected.contains("note 399"), selected);
        assertFalse(selected.contains("note 0\n"), "the oldest entries give way first");
        // Selected entries keep their index order.
        assertTrue(selected.indexOf("note 399") < selected.indexOf("the memory saved last"));
    }

    @Test
    void anOversizedFreeFormFileIsCutWithANotice() {
        String text = "word ".repeat(2_000);
        String selected = MemoryTool.selectIndexForContext(text, 500);
        assertTrue(selected.endsWith("... (index truncated)"), selected);
        assertTrue(selected.length() <= 500 + "\n... (index truncated)".length());
    }

    @Test
    void longEntriesAreClipped() {
        StringBuilder index = new StringBuilder();
        for (int i = 0; i < 50; i++) index.append("- [e").append(i).append("](e.md) — ").append("z".repeat(500)).append('\n');
        String selected = MemoryTool.selectIndexForContext(index.toString(), 2_000);
        for (String line : selected.split("\n")) assertTrue(line.length() <= 240, line);
    }
}
