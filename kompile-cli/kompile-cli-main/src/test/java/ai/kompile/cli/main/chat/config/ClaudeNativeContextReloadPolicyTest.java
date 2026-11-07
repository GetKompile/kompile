/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeNativeContextReloadPolicyTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    @TempDir
    Path project;
    private Path memory;
    private ClaudeNativeContextReloadPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        memory = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memory);
        policy = new ClaudeNativeContextReloadPolicy(mapper, project);
    }

    private JsonNode toolUse(String tool, String input, String responseText) {
        ObjectNode event = mapper.createObjectNode();
        event.put("hook_event_name", "PostToolUse");
        event.put("tool_name", tool);
        try {
            event.set("tool_input", mapper.readTree(input));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        event.putArray("tool_response").addObject().put("type", "text").put("text", responseText);
        return event;
    }

    private String reloaded() throws Exception {
        JsonNode out = policy.reload(mapper.readTree(
                "{\"hook_event_name\":\"SessionStart\",\"source\":\"compact\"}"));
        assertEquals("SessionStart", out.path("hookSpecificOutput").path("hookEventName").asText());
        return out.path("hookSpecificOutput").path("additionalContext").asText();
    }

    @Test
    void anUnusedSessionReloadsNothing() throws Exception {
        assertNull(policy.reloadContext());
        JsonNode out = policy.reload(mapper.readTree(
                "{\"hook_event_name\":\"SessionStart\",\"source\":\"compact\"}"));
        assertFalse(out.has("hookSpecificOutput"));
    }

    @Test
    void onlyACompactionStartReloads() throws Exception {
        policy.recordPrompt("<kompile_reminders>\n1. Ask first.\n</kompile_reminders>\n\nhello", null);
        for (String source : new String[]{"startup", "resume", "clear"}) {
            JsonNode out = policy.reload(mapper.readTree(
                    "{\"hook_event_name\":\"SessionStart\",\"source\":\"" + source + "\"}"));
            assertFalse(out.has("hookSpecificOutput"), source);
        }
        assertTrue(reloaded().contains("Ask first."));
    }

    @Test
    void readSavedAndRecalledMemoryFilesAreReloadedFromDiskNewestFirst() throws Exception {
        Files.writeString(memory.resolve("old_rule.md"), "old body\n");
        Files.writeString(memory.resolve("saved_rule.md"), "saved body\n");
        Files.writeString(memory.resolve("recalled_rule.md"), "recalled body\n");
        policy.record(toolUse("mcp__kompile__memory", "{\"action\":\"read\",\"file\":\"old_rule.md\"}", "old body"));
        policy.record(toolUse("mcp__kompile__memory",
                "{\"action\":\"save\",\"name\":\"saved rule\",\"memoryType\":\"feedback\"}",
                "Saved typed memory 'saved rule' (type=feedback) to " + memory.resolve("saved_rule.md")));
        policy.record(toolUse("mcp__kompile__memory", "{\"action\":\"recall\",\"query\":\"rule\"}",
                "## recalled rule (feedback, recalled_rule.md)\nwhy\n\nrecalled body\n"));
        Files.writeString(memory.resolve("old_rule.md"), "edited since the read\n");

        String context = reloaded();
        assertTrue(context.contains("edited since the read"), "bodies are re-read at reload: " + context);
        int recalled = context.indexOf("### project/recalled_rule.md");
        int saved = context.indexOf("### project/saved_rule.md");
        int old = context.indexOf("### project/old_rule.md");
        assertTrue(recalled >= 0 && recalled < saved && saved < old, context);
    }

    @Test
    void forgottenIndexAndEscapingFilesAreNotReloaded() throws Exception {
        Files.writeString(memory.resolve("gone.md"), "gone\n");
        Files.writeString(memory.resolve("MEMORY.md"), "- index\n");
        Files.writeString(project.resolve("secret.md"), "outside memory\n");
        policy.record(toolUse("mcp__kompile__memory", "{\"action\":\"read\",\"file\":\"gone.md\"}", "gone"));
        policy.record(toolUse("mcp__kompile__memory", "{\"action\":\"forget\",\"file\":\"gone.md\"}", "removed"));
        policy.record(toolUse("mcp__kompile__memory", "{\"action\":\"read\",\"file\":\"MEMORY.md\"}", "- index"));
        policy.record(toolUse("mcp__kompile__memory", "{\"action\":\"read\",\"file\":\"../../secret.md\"}", "x"));
        assertNull(policy.reloadContext());
    }

    @Test
    void skillsFromMessagesAndTheSkillToolAreReloadedAndTheResultIsNeverRewritten() throws Exception {
        policy.recordPrompt("<skill name=\"fix\">\nFix build or test failures\n</skill>", null);
        JsonNode out = policy.record(toolUse("mcp__kompile__skill_manager",
                "{\"action\":\"expand_template\",\"name\":\"review\"}", "Review the diff carefully"));
        assertFalse(out.has("hookSpecificOutput"));
        policy.record(toolUse("mcp__kompile__skill_manager", "{\"action\":\"list_skills\"}", "everything"));

        String context = reloaded();
        assertTrue(context.contains("<skill name=\"fix\">\nFix build or test failures\n</skill>"), context);
        assertTrue(context.contains("<skill name=\"review\">\nReview the diff carefully\n</skill>"), context);
        assertFalse(context.contains("everything"), context);
    }

    @Test
    void theLastDeliveredPersistentMemoryAndRemindersSurviveSuppressedTurns() throws Exception {
        policy.recordPrompt("<kompile_reminders>\n1. Plan first.\n</kompile_reminders>\n\nturn one",
                "- [rule](rule.md) — [feedback] never guess\n");
        // A later turn whose unchanged memory was suppressed carries no snapshot.
        policy.recordPrompt("turn two", null);
        String context = reloaded();
        assertTrue(context.contains("[Persistent memory]\n- [rule](rule.md) — [feedback] never guess"), context);
        assertTrue(context.contains("Plan first."), context);
        assertFalse(context.contains("turn two"), context);
    }

    @Test
    void overBudgetItemsAreNamedWithHowToRereadThem() throws Exception {
        ClaudeNativeContextReloadPolicy small = new ClaudeNativeContextReloadPolicy(mapper, project, 400);
        Files.writeString(memory.resolve("big.md"), "x".repeat(2_000));
        small.record(toolUse("mcp__kompile__memory", "{\"action\":\"read\",\"file\":\"big.md\"}", "x"));
        small.recordPrompt("<skill name=\"fix\">\n" + "y".repeat(2_000) + "\n</skill>", null);
        String context = small.reloadContext();
        assertFalse(context.contains("xxxxxxxxxx"), context);
        assertTrue(context.contains("memory project/big.md (memory action=read scope=project file=big.md)"), context);
        assertTrue(context.contains("skill fix (skill_manager action=expand_template name=fix)"), context);
    }

    @Test
    void otherToolsAndEventsAreIgnored() throws Exception {
        assertFalse(policy.record(mapper.readTree(
                "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"mcp__kompile__memory\"}")).has("hookSpecificOutput"));
        policy.record(toolUse("Read", "{\"file_path\":\"/x.md\"}", "x"));
        assertNull(policy.reloadContext());
    }
}
