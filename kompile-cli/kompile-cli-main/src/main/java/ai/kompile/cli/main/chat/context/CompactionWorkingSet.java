/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.tools.MemoryTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The memory files, skills and reminders a session used, re-delivered after a
 * compaction summarizes away the messages and tool results that carried them.
 * Kompile delivers expanded {@code <skill>} blocks and its reminder block inside
 * user messages, and memory files through the memory tool, so a summary keeps at
 * best a mention of them. Memory files are re-read from disk at reload, so an edit
 * since the read wins.
 *
 * <p>Used by Claude Code's post-compaction hook ({@code ClaudeNativeContextReloadPolicy},
 * fed as the session runs), by the OpenCode and agy transports
 * ({@code NativeCompactionReload}, re-delivered with the prompt after the provider
 * compacts), and by the native chat loop's checkpoints ({@link #reloadFor}, derived
 * from the history being compacted).</p>
 */
public final class CompactionWorkingSet {
    static final String OPEN = "<kompile_compaction_reload>";
    static final String CLOSE = "</kompile_compaction_reload>";

    private static final int MAX_TRACKED = 32;
    private static final int MAX_SKILL_CHARS = 6_000;
    private static final String INDEX_FILE = "MEMORY.md";
    /**
     * Kompile's MCP server prefix as the agents name its tools: {@code mcp__kompile__}
     * (Claude Code), {@code kompile_} (OpenCode), and the separators other CLIs use.
     */
    private static final Pattern KOMPILE_TOOL_PREFIX = Pattern.compile("^(?:mcp[_:]+)?kompile[_:/.]+");
    private static final Pattern SKILL_BLOCK = Pattern.compile(
            "<skill name=\"([A-Za-z][A-Za-z0-9_-]*)\">\\n(.*?)\\n</skill>", Pattern.DOTALL);
    /** A recall result heading: {@code ## name (type, file.md)}. */
    private static final Pattern RECALLED = Pattern.compile(
            "^## .*\\((?:user|feedback|project|reference), ([^()\\s/\\\\]+\\.md)\\)$", Pattern.MULTILINE);
    private static final Pattern SAVED = Pattern.compile("Saved typed memory .* to (\\S+\\.md)");
    /** A memory section of an earlier reload, so a second compaction carries it forward. */
    private static final Pattern RELOADED_MEMORY = Pattern.compile(
            "^### (project|global)/([^\\s/\\\\]+\\.md)$", Pattern.MULTILINE);

    private final Path workingDirectory;
    private final int maxChars;
    /** Memory files the session used, oldest first; a re-use moves one to the end. */
    private final LinkedHashSet<Path> memories = new LinkedHashSet<>();
    /** Expanded skill bodies by name, oldest first. */
    private final LinkedHashMap<String, String> skills = new LinkedHashMap<>();
    private String persistentMemory;
    private String reminders;

    public CompactionWorkingSet(Path workingDirectory, int maxChars) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.maxChars = maxChars;
    }

    /**
     * The reload section for a checkpoint that summarizes {@code covered}: the memory
     * files its tool calls read, saved or recalled and the skills its messages
     * expanded, including those an earlier checkpoint carried. Reminders and the
     * persistent memory index are left out: every turn delivers them again. Null when
     * nothing was used.
     */
    public static String reloadFor(List<CompactionService.ConversationEntry> covered,
                                   Path workingDirectory, int maxChars, ObjectMapper mapper) {
        CompactionWorkingSet set = new CompactionWorkingSet(workingDirectory, maxChars);
        Map<String, CompactionService.ConversationEntry> calls = new HashMap<>();
        for (CompactionService.ConversationEntry entry : covered) {
            if (entry == null || entry.content == null) continue;
            switch (entry.type) {
                case USER -> set.recordSkills(entry.content);
                case SYSTEM -> {
                    if (entry.content.contains(OPEN)) set.carryForward(entry.content);
                }
                case TOOL_CALL -> {
                    if (entry.toolCallId != null) calls.put(entry.toolCallId, entry);
                }
                case TOOL_RESULT -> {
                    CompactionService.ConversationEntry call =
                            entry.toolCallId == null ? null : calls.get(entry.toolCallId);
                    if (call != null) set.recordToolUse(call.toolName, parse(mapper, call.content), entry.content);
                }
                default -> { }
            }
        }
        return set.reloadContext();
    }

    /**
     * Records what an outbound user message carried: its reminder block, the
     * persistent memory it delivered, and any expanded skills. A message without
     * memory keeps the last one; it was suppressed as unchanged, not withdrawn.
     */
    public synchronized void recordPrompt(String message, String persistentMemorySnapshot) {
        if (message == null) return;
        if (ReminderManager.stripReminderBlock(message).length() < message.strip().length()) {
            String block = ReminderManager.reminderBlockContent(message);
            if (block != null) reminders = block;
        }
        if (persistentMemorySnapshot != null && !persistentMemorySnapshot.isBlank()) {
            persistentMemory = persistentMemorySnapshot.strip();
        }
        recordSkills(message);
    }

    /** Records a Kompile memory or skill tool use; any other tool is ignored. */
    public synchronized void recordToolUse(String toolName, JsonNode input, String response) {
        if (toolName == null) return;
        String tool = KOMPILE_TOOL_PREFIX.matcher(toolName).replaceFirst("");
        JsonNode arguments = input == null ? MissingNode.getInstance() : input;
        String action = arguments.path("action").asText("");
        String text = response == null ? "" : response;
        if (tool.equals("memory")) {
            recordMemory(action, arguments, text);
        } else if (tool.equals("skill_manager")
                && ("expand_template".equals(action) || "get_skill".equals(action))) {
            String name = arguments.path("name").asText("").strip();
            if (!name.isEmpty() && !text.isBlank()) recordSkill(name, text.strip());
        }
    }

    /** The reload section, or null when the session used nothing to restore. */
    public String reloadContext() {
        return reloadContext(true, true);
    }

    /**
     * The reload section without the parts the message it rides with delivers again;
     * null when nothing is left to restore.
     */
    public synchronized String reloadContext(boolean withReminders, boolean withPersistentMemory) {
        List<String> omitted = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        if (withReminders && reminders != null) {
            appendBounded(body, "[Kompile reminders the user configured — still in force]\n" + reminders + "\n\n",
                    "reminders (repeated on the next user message)", omitted);
        }
        if (withPersistentMemory && persistentMemory != null) {
            appendBounded(body, "[Persistent memory]\n" + persistentMemory + "\n\n",
                    "persistent memory index (memory action=read file=MEMORY.md)", omitted);
        }
        List<Path> used = new ArrayList<>(memories);
        StringBuilder files = new StringBuilder();
        for (int i = used.size() - 1; i >= 0; i--) {
            Path file = used.get(i);
            String content = read(file);
            if (content == null) continue;
            String label = label(file);
            String section = "### " + label + "\n" + content.strip() + "\n\n";
            if (body.length() + files.length() + section.length() + 64 <= maxChars) {
                files.append(section);
            } else {
                omitted.add("memory " + label + " (memory action=read scope=" + label.substring(0, label.indexOf('/'))
                        + " file=" + file.getFileName() + ")");
            }
        }
        if (!files.isEmpty()) body.append("[Memory files used earlier this session]\n").append(files);
        List<Map.Entry<String, String>> skillList = new ArrayList<>(skills.entrySet());
        StringBuilder skillText = new StringBuilder();
        for (int i = skillList.size() - 1; i >= 0; i--) {
            Map.Entry<String, String> skill = skillList.get(i);
            String section = "<skill name=\"" + skill.getKey() + "\">\n" + skill.getValue() + "\n</skill>\n\n";
            if (body.length() + skillText.length() + section.length() + 64 <= maxChars) {
                skillText.append(section);
            } else {
                omitted.add("skill " + skill.getKey()
                        + " (skill_manager action=expand_template name=" + skill.getKey() + ")");
            }
        }
        if (!skillText.isEmpty()) body.append("[Skills in use this session — keep following them]\n").append(skillText);
        if (body.isEmpty() && omitted.isEmpty()) return null;
        StringBuilder out = new StringBuilder(OPEN + "\n"
                + "This session was compacted. Kompile re-loaded the context below; it keeps the "
                + "authority it had before the summary.\n\n");
        out.append(body);
        if (!omitted.isEmpty()) {
            out.append("[Not re-loaded (size budget) — re-read before relying on them]\n");
            for (String item : omitted) out.append("- ").append(item).append('\n');
        }
        return out.append(CLOSE).toString();
    }

    private void recordSkills(String message) {
        Matcher skill = SKILL_BLOCK.matcher(message);
        while (skill.find()) recordSkill(skill.group(1), skill.group(2));
    }

    /** An earlier checkpoint's reload is part of what this one summarizes. */
    private void carryForward(String summary) {
        Matcher memory = RELOADED_MEMORY.matcher(summary);
        while (memory.find()) {
            track(memoryFile(MemoryTool.memoryDirectory(memory.group(1), workingDirectory), memory.group(2)));
        }
        recordSkills(summary);
    }

    private void appendBounded(StringBuilder body, String section, String omittedLabel, List<String> omitted) {
        if (body.length() + section.length() + 64 <= maxChars) body.append(section);
        else omitted.add(omittedLabel);
    }

    private void recordMemory(String action, JsonNode input, String response) {
        Path directory = MemoryTool.memoryDirectory(input.path("scope").asText("project"), workingDirectory);
        switch (action) {
            case "read", "write", "append" -> track(memoryFile(directory, input.path("file").asText("")));
            case "save" -> {
                Matcher saved = SAVED.matcher(response);
                if (saved.find()) track(memoryFile(directory, saved.group(1)));
            }
            case "recall" -> {
                Matcher recalled = RECALLED.matcher(response);
                while (recalled.find()) track(memoryFile(directory, recalled.group(1)));
            }
            case "forget" -> {
                Path file = memoryFile(directory, input.path("file").asText(""));
                if (file != null) memories.remove(file);
            }
            default -> { }
        }
    }

    /** The named file inside the scope's memory directory, or null for the index or an escape. */
    private static Path memoryFile(Path directory, String name) {
        if (name == null || name.isBlank()) return null;
        Path leaf = Paths.get(name.strip()).getFileName();
        if (leaf == null) return null;
        String file = leaf.toString().endsWith(".md") ? leaf.toString() : leaf + ".md";
        if (INDEX_FILE.equalsIgnoreCase(file)) return null;
        Path resolved = directory.resolve(file).toAbsolutePath().normalize();
        return resolved.startsWith(directory.toAbsolutePath().normalize()) ? resolved : null;
    }

    private void track(Path file) {
        if (file == null) return;
        memories.remove(file);
        memories.add(file);
        while (memories.size() > MAX_TRACKED) memories.remove(memories.iterator().next());
    }

    private void recordSkill(String name, String body) {
        String text = body.length() <= MAX_SKILL_CHARS ? body
                : body.substring(0, MAX_SKILL_CHARS) + "\n...[skill truncated; re-expand it]";
        skills.remove(name);
        skills.put(name, text);
        while (skills.size() > MAX_TRACKED) skills.remove(skills.keySet().iterator().next());
    }

    private String label(Path file) {
        Path global = MemoryTool.memoryDirectory("global", workingDirectory).toAbsolutePath().normalize();
        return (file.startsWith(global) ? "global/" : "project/") + file.getFileName();
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (IOException malformed) {
            return mapper.createObjectNode();
        }
    }
}
