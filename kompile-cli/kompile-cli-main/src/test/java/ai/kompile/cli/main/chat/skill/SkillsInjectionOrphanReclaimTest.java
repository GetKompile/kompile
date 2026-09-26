/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the owner-pid orphan-reclaim behavior in {@link SkillsInjection}: a skills
 * block left behind by a session that died without calling {@link SkillsInjection#cleanup()}
 * (crash, SIGKILL) must not accumulate in AGENTS.md forever. The next install reclaims
 * blocks whose owner process has exited, plus legacy blocks with no owner token, while a
 * block owned by a still-live process is left untouched. Mirrors
 * {@code SystemPromptManagerInjectionTest}'s equivalent coverage for the shared
 * {@link ManagedBlockOwnership} logic.
 */
class SkillsInjectionOrphanReclaimTest {

    @TempDir
    Path tempDir;

    @Test
    void installReclaimsSkillsBlocksLeftByExitedSessions() throws Exception {
        Path agents = tempDir.resolve("AGENTS.md");
        long livePid = ProcessHandle.current().parent().orElseThrow().pid();
        String liveBlock = managedSkillsBlock(
                "live-session " + ManagedBlockOwnership.OWNER_TOKEN + livePid, "LIVE_SKILL");
        Files.writeString(agents, "ORIGINAL\n\n"
                + managedSkillsBlock("dead-session " + ManagedBlockOwnership.OWNER_TOKEN
                        + spawnDeadProcess(), "DEAD_SKILL")
                + "\n\n" + managedSkillsBlock("legacy-session", "LEGACY_SKILL")
                + "\n\n" + liveBlock + "\n");

        SkillsInjection injection = new SkillsInjection(new SkillRegistry(), tempDir);
        assertTrue(injection.installSkills("opencode") > 0);

        String injected = Files.readString(agents);
        assertFalse(injected.contains("DEAD_SKILL"));
        assertFalse(injected.contains("LEGACY_SKILL"));
        assertTrue(injected.contains(liveBlock));
        assertTrue(injected.contains(ManagedBlockOwnership.OWNER_TOKEN
                + ProcessHandle.current().pid() + " -->"));

        injection.cleanup();
        assertEquals("ORIGINAL\n\n" + liveBlock + "\n", Files.readString(agents));
    }

    @Test
    void cleanupDeletesFileThatHeldOnlyOrphanedSkillsBlocks() throws Exception {
        Path agents = tempDir.resolve("AGENTS.md");
        Files.writeString(agents, managedSkillsBlock("dead-session "
                + ManagedBlockOwnership.OWNER_TOKEN + spawnDeadProcess(), "DEAD_SKILL") + "\n");

        SkillsInjection injection = new SkillsInjection(new SkillRegistry(), tempDir);
        assertTrue(injection.installSkills("opencode") > 0);
        assertFalse(Files.readString(agents).contains("DEAD_SKILL"));
        injection.cleanup();

        assertFalse(Files.exists(agents));
    }

    private static String managedSkillsBlock(String header, String body) {
        return SkillsInjection.SKILLS_BEGIN_PREFIX + header + " -->\n" + body + "\n"
                + SkillsInjection.SKILLS_END_PREFIX + "x -->";
    }

    private static long spawnDeadProcess() {
        try {
            String java = ProcessHandle.current().info().command()
                    .orElseThrow(() -> new IllegalStateException("Current Java command unavailable"));
            Process process = new ProcessBuilder(java, "-version").start();
            process.waitFor();
            return process.pid();
        } catch (Exception e) {
            throw new IllegalStateException("Could not spawn a dead process", e);
        }
    }
}
