/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeContextPolicyTest {
    @TempDir Path directory;

    private Path settings(String name, String json) throws Exception {
        return Files.writeString(directory.resolve(name), json);
    }

    @Test
    void sharedSettingsUseNativeCwdWhileLocalSettingsUseTheRepositoryRoot() throws Exception {
        Path home = Files.createDirectories(directory.resolve("home"));
        Path repository = Files.createDirectories(directory.resolve("repository"));
        Files.createDirectories(repository.resolve(".git"));
        Path cwd = Files.createDirectories(repository.resolve("nested"));
        Files.createDirectories(repository.resolve(".claude"));
        Files.createDirectories(cwd.resolve(".claude"));
        Files.writeString(repository.resolve(".claude/settings.json"), "{\"autoCompactWindow\":900000}");
        Files.writeString(cwd.resolve(".claude/settings.json"), "{\"autoCompactWindow\":256000}");
        var files = ClaudeContextPolicy.settingsFiles(cwd, Map.of(), home);
        assertTrue(files.contains(cwd.resolve(".claude/settings.json")));
        assertFalse(files.contains(repository.resolve(".claude/settings.json")));
        assertEquals(256_000, ClaudeContextPolicy.read(files, Map.of(), "m").autoCompactWindow());
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Files.writeString(cwd.resolve(".claude/settings.local.json"), "{\"autoCompactWindow\":300000}");
            Files.writeString(repository.resolve(".claude/settings.local.json"), "{\"autoCompactWindow\":400000}");
            assertEquals(400_000, ClaudeContextPolicy.read(files, Map.of(), "m").autoCompactWindow(),
                    "the root local file takes precedence over legacy cwd-local settings");
        }
    }

    @Test
    void compactWindowIsNotTheModelCapacityOrAnExactTrigger() throws Exception {
        var file = settings("user.json", """
                {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"256000","CLAUDE_CODE_DISABLE_1M_CONTEXT":"0"}}
                """);
        var policy = ClaudeContextPolicy.read(List.of(file), Map.of(), "claude-opus-5-5");
        assertEquals(1_000_000, policy.modelWindow(1_000_000));
        assertEquals(256_000, policy.budgetWindow(1_000_000));
        assertEquals(200_000, policy.budgetWindow(200_000), "cannot enlarge a smaller native context");
    }

    @Test
    void localAndManagedEnvOverrideInheritedAndUserSettings() throws Exception {
        var user = settings("user.json", """
                {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"256000"}}
                """);
        var project = settings("project.json", """
                {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"300000"}}
                """);
        var local = settings("local.json", """
                {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"400000"}}
                """);
        var managed = settings("managed.json", """
                {"env":{"CLAUDE_CODE_AUTO_COMPACT_WINDOW":"150000"}}
                """);
        var inherited = Map.of("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "500000");
        assertEquals(400_000, ClaudeContextPolicy.read(List.of(user, project, local), inherited, "m").autoCompactWindow());
        assertEquals(150_000, ClaudeContextPolicy.read(List.of(user, project, local, managed), inherited, "m").autoCompactWindow());
    }

    @Test
    void disabledCompactionDoesNotMakeTheWindowAHardContextCap() throws Exception {
        var file = settings("disabled.json", """
                {"autoCompactWindow":256000,"autoCompactEnabled":false}
                """);
        var policy = ClaudeContextPolicy.read(List.of(file), Map.of(), "m");
        assertFalse(policy.autoCompactEnabled());
        assertEquals(1_000_000, policy.budgetWindow(1_000_000));
        policy = ClaudeContextPolicy.read(List.of(), Map.of("DISABLE_AUTO_COMPACT", "1",
                "CLAUDE_CODE_AUTO_COMPACT_WINDOW", "256000", "CLAUDE_CODE_DISABLE_1M_CONTEXT", "true"), "m");
        assertFalse(policy.autoCompactEnabled());
        assertEquals(200_000, policy.budgetWindow(1_000_000));
    }

    @Test
    void modelSettingsAndEnvPrecedenceAndInvalidFiles() throws Exception {
        var file = settings("model.json", """
                {"autoCompactWindow":300000,"modelSettings":{"m":{"autoCompactWindow":400000}}}
                """);
        var broken = settings("broken.json", "{");
        assertEquals(400_000, ClaudeContextPolicy.read(List.of(file, broken), Map.of(), "m").autoCompactWindow());
        assertEquals(300_000, ClaudeContextPolicy.read(List.of(file), Map.of(), "other").autoCompactWindow());
        assertEquals(256_000, ClaudeContextPolicy.read(List.of(file),
                Map.of("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "256000"), "m").autoCompactWindow());
        assertEquals(100_000, ClaudeContextPolicy.read(List.of(file),
                Map.of("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "500k"), "m").autoCompactWindow());
        assertEquals(1_000_000, ClaudeContextPolicy.read(List.of(file),
                Map.of("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "2000000"), "m").autoCompactWindow());
        assertEquals(0, ClaudeContextPolicy.read(List.of(file),
                Map.of("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "auto"), "m").autoCompactWindow());
        assertEquals(1_000_000, ClaudeContextPolicy.read(List.of(broken), Map.of(), "m").budgetWindow(1_000_000));
    }
}
