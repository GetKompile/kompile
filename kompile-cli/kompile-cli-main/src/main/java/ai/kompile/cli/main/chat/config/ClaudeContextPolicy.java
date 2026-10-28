/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.chat.sources.adapters.ClaudeCodeAdapter;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of Claude Code's local context policy, not a replacement for its
 * compaction engine. modelUsage.contextWindow is the model limit, NOT the
 * auto-compact window. Claude Code reserves headroom and decides when to compact;
 * neither Kompile's trigger ratio nor the last request's input is that threshold.
 * See https://code.claude.com/docs/en/model-config#set-the-auto-compact-window.
 */
record ClaudeContextPolicy(int autoCompactWindow, boolean disable1m, boolean autoCompactEnabled) {
    static ClaudeContextPolicy read(Path workingDirectory, String model) {
        Map<String, String> environment = System.getenv();
        Path home = Path.of(System.getProperty("user.home"));
        return read(settingsFiles(workingDirectory, environment, home), environment, model);
    }

    static List<Path> settingsFiles(Path workingDirectory, Map<String, String> environment, Path home) {
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Path project = cwd;
        for (Path parent = project; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent.resolve(".git"))) {
                project = parent;
                break;
            }
        }
        // Shared settings stay at Claude's primary cwd. Only the local file
        // normally moves to the repository root; older cwd-local files still apply.
        List<Path> settings = new ArrayList<>(List.of(
                ClaudeCodeAdapter.configDirectory(environment, home).resolve("settings.json"),
                cwd.resolve(".claude/settings.json"),
                cwd.resolve(".claude/settings.local.json")));
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!project.equals(cwd) && !project.equals(home.toAbsolutePath().normalize()) && !os.contains("win")) {
            settings.add(project.resolve(".claude/settings.local.json"));
        }
        Path managed = os.contains("mac") ? Path.of("/Library/Application Support/ClaudeCode")
                : os.contains("win") ? Path.of(environment.getOrDefault("ProgramFiles", "C:\\Program Files"), "ClaudeCode")
                : Path.of("/etc/claude-code");
        settings.add(managed.resolve("managed-settings.json"));
        // Enterprise remote/registry policy and live /autocompact changes are not
        // exposed by modelUsage. Do not pretend to know a native trigger from this view.
        return settings;
    }

    /** Settings env entries override inherited env; settings scopes are low to high. */
    static ClaudeContextPolicy read(List<Path> settings, Map<String, String> inherited, String model) {
        Map<String, String> env = new HashMap<>();
        List<String> keys = List.of("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "CLAUDE_CODE_DISABLE_1M_CONTEXT",
                "DISABLE_AUTO_COMPACT", "DISABLE_COMPACT");
        for (String key : keys) if (inherited.containsKey(key)) env.put(key, inherited.get(key));
        int window = 0;
        boolean enabled = true;
        for (Path path : settings) {
            try {
                if (!Files.isRegularFile(path)) continue;
                JsonNode root = JsonUtils.standardMapper().readTree(path.toFile());
                for (String key : keys) {
                    JsonNode value = root.path("env").path(key);
                    if (value.isTextual()) env.put(key, value.asText());
                }
                // A model-specific setting takes precedence within its own scope.
                JsonNode selected = root.path("modelSettings").path(model == null ? "" : model)
                        .path("autoCompactWindow");
                if (selected.isMissingNode()) selected = root.path("autoCompactWindow");
                if (!selected.isMissingNode()) window = window(selected.asText());
                if (root.path("autoCompactEnabled").isBoolean()) enabled = root.path("autoCompactEnabled").asBoolean();
            } catch (Exception ignored) {
                // Invalid/unreadable settings must not break chat or invent a capacity.
            }
        }
        String override = env.get("CLAUDE_CODE_AUTO_COMPACT_WINDOW");
        if (override != null && !override.isBlank()) window = window(override);
        return new ClaudeContextPolicy(window, truthy(env.get("CLAUDE_CODE_DISABLE_1M_CONTEXT")),
                enabled && !truthy(env.get("DISABLE_AUTO_COMPACT")) && !truthy(env.get("DISABLE_COMPACT")));
    }

    /** Effective local model cap; the provider's smaller reported limit always wins. */
    int modelWindow(int modelWindow) {
        return disable1m ? Math.min(modelWindow, 200_000) : modelWindow;
    }

    int budgetWindow(int modelWindow) {
        int capacity = modelWindow(modelWindow);
        return autoCompactEnabled && autoCompactWindow > 0 ? Math.min(capacity, autoCompactWindow) : capacity;
    }

    private static int window(String value) {
        if (value == null) return 0;
        // Claude's env knob accepts a plain integer (parseInt semantics), not k/M.
        var matcher = java.util.regex.Pattern.compile("^[+-]?[0-9]+").matcher(value.strip());
        if (!matcher.find()) return 0;
        try {
            long parsed = Long.parseLong(matcher.group());
            return parsed > 0 ? (int) Math.max(100_000, Math.min(1_000_000, parsed)) : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean truthy(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }
}
