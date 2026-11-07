/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
class CompactionWorkingSetTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    @TempDir
    Path project;
    private Path memory;

    @BeforeEach
    void setUp() throws Exception {
        memory = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memory);
    }

    @Test
    void historyWithoutKompileMemoryOrSkillsReloadsNothing() {
        List<ConversationEntry> covered = List.of(
                ConversationEntry.user("hello"),
                ConversationEntry.toolCall("read", "c1", "{\"file_path\":\"/x\"}"),
                ConversationEntry.toolResult("read", "c1", "x"),
                ConversationEntry.assistant("done"));
        assertNull(CompactionWorkingSet.reloadFor(covered, project, 4_000, mapper));
    }

    @Test
    void skillsAndMemoryToolPairsInTheCoveredHistoryAreReloaded() throws Exception {
        Files.writeString(memory.resolve("read_rule.md"), "read body\n");
        Files.writeString(memory.resolve("recalled_rule.md"), "recalled body\n");
        List<ConversationEntry> covered = List.of(
                ConversationEntry.user("<skill name=\"fix\">\nFix build or test failures\n</skill>"),
                ConversationEntry.toolCall("memory", "c1", "{\"action\":\"read\",\"file\":\"read_rule.md\"}"),
                ConversationEntry.toolResult("memory", "c1", "read body"),
                ConversationEntry.toolCall("mcp__kompile__memory", "c2", "{\"action\":\"recall\",\"query\":\"rule\"}"),
                ConversationEntry.toolResult("mcp__kompile__memory", "c2",
                        "## recalled rule (feedback, recalled_rule.md)\n\nrecalled body\n"),
                ConversationEntry.toolCall("skill_manager", "c3", "{\"action\":\"expand_template\",\"name\":\"review\"}"),
                ConversationEntry.toolResult("skill_manager", "c3", "Review the diff carefully"));

        String reload = CompactionWorkingSet.reloadFor(covered, project, 4_000, mapper);
        assertTrue(reload.startsWith("<kompile_compaction_reload>"), reload);
        assertTrue(reload.contains("### project/read_rule.md\nread body"), reload);
        assertTrue(reload.contains("### project/recalled_rule.md\nrecalled body"), reload);
        assertTrue(reload.contains("<skill name=\"fix\">\nFix build or test failures\n</skill>"), reload);
        assertTrue(reload.contains("<skill name=\"review\">\nReview the diff carefully\n</skill>"), reload);
        assertFalse(reload.contains("[Persistent memory]"), "every turn re-delivers the index: " + reload);
    }

    @Test
    void aSecondCompactionCarriesTheFirstCheckpointsReloadForward() throws Exception {
        Files.writeString(memory.resolve("kept_rule.md"), "kept body, edited since\n");
        String earlier = ConversationLedger.SUMMARY_MARKER + "older summary\n\n"
                + "<kompile_compaction_reload>\n[Memory files used earlier this session]\n"
                + "### project/kept_rule.md\nkept body\n\n"
                + "[Skills in use this session — keep following them]\n"
                + "<skill name=\"fix\">\nFix build or test failures\n</skill>\n\n"
                + "</kompile_compaction_reload>";
        List<ConversationEntry> covered = List.of(
                ConversationEntry.system(earlier),
                ConversationEntry.user("next request"),
                ConversationEntry.assistant("next answer"));

        String reload = CompactionWorkingSet.reloadFor(covered, project, 4_000, mapper);
        assertTrue(reload.contains("### project/kept_rule.md\nkept body, edited since"), reload);
        assertTrue(reload.contains("<skill name=\"fix\">\nFix build or test failures\n</skill>"), reload);
    }

    @Test
    void aToolResultWithoutItsCallAndAMalformedCallAreIgnored() {
        List<ConversationEntry> covered = List.of(
                ConversationEntry.toolResult("memory", "orphan", "## x (feedback, orphan.md)"),
                ConversationEntry.toolCall("memory", "bad", "{not json"),
                ConversationEntry.toolResult("memory", "bad", "body"));
        assertNull(CompactionWorkingSet.reloadFor(covered, project, 4_000, mapper));
    }

    @Test
    void theBudgetIsRespectedAndOverflowIsNamed() throws Exception {
        Files.writeString(memory.resolve("big.md"), "x".repeat(5_000));
        List<ConversationEntry> covered = List.of(
                ConversationEntry.toolCall("memory", "c1", "{\"action\":\"read\",\"file\":\"big.md\"}"),
                ConversationEntry.toolResult("memory", "c1", "x"));
        String reload = CompactionWorkingSet.reloadFor(covered, project, 1_000, mapper);
        assertTrue(reload.length() < 1_000, "length " + reload.length());
        assertTrue(reload.contains("memory project/big.md (memory action=read scope=project file=big.md)"), reload);
    }
}
