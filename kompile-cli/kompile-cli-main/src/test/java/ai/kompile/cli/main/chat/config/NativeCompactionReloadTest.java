/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
class NativeCompactionReloadTest {
    private static final String REMINDERS = "<kompile_reminders>\n1. Plan first.\n</kompile_reminders>\n\n";
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    @TempDir
    Path project;
    private Path memory;
    private NativeCompactionReload reload;

    @BeforeEach
    void setUp() throws Exception {
        memory = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memory);
        reload = new NativeCompactionReload(project, mapper);
    }

    @Test
    void openCodeNamedToolsAreRecordedAndTheReloadRidesOnlyTheFirstPromptAfterCompaction() throws Exception {
        Files.writeString(memory.resolve("build_rule.md"), "Run reactor builds from the repo root.\n");
        reload.recordPrompt(REMINDERS + "<skill name=\"fix\">\nFix build or test failures\n</skill>\n\nfix it");
        reload.recordToolUse("kompile_memory", "{\"action\":\"read\",\"file\":\"build_rule.md\"}", "Run reactor builds");
        reload.recordToolUse("kompile_skill_manager", "{\"action\":\"expand_template\",\"name\":\"review\"}",
                "Review the diff carefully");
        reload.recordToolUse("read", "{\"file_path\":\"/x\"}", "ignored");

        assertEquals("next", reload.withPendingReload("next"), "no compaction yet");
        reload.compacted();
        String sent = reload.withPendingReload("next");
        assertTrue(sent.startsWith("<kompile_compaction_reload>"), sent);
        assertTrue(sent.endsWith("</kompile_compaction_reload>\n\nnext"), sent);
        assertTrue(sent.contains("### project/build_rule.md\nRun reactor builds from the repo root."), sent);
        assertTrue(sent.contains("<skill name=\"fix\">\nFix build or test failures\n</skill>"), sent);
        assertTrue(sent.contains("<skill name=\"review\">\nReview the diff carefully\n</skill>"), sent);
        assertTrue(sent.contains("Plan first."), sent);
        assertEquals("after", reload.withPendingReload("after"), "the reload rides one prompt");
    }

    @Test
    void partsTheMessageDeliversItselfAreNotRepeated() {
        reload.recordPrompt(REMINDERS + "<memory_context>\n[Persistent memory]\n- standing rule\n</memory_context>\n\nhi");
        reload.compacted();
        String sent = reload.withPendingReload(
                REMINDERS + "<memory_context>\n[Persistent memory]\n- standing rule\n</memory_context>\n\nagain");
        assertTrue(sent.startsWith(REMINDERS), "nothing else left to reload: " + sent);

        reload.compacted();
        String bare = reload.withPendingReload("plain");
        assertTrue(bare.contains("Plan first.") && bare.contains("- standing rule"), bare);
    }

    @Test
    void aGenericMcpDispatcherCallIsUnwrappedAndOtherServersAreIgnored() throws Exception {
        Files.writeString(memory.resolve("kept.md"), "kept body\n");
        Files.writeString(memory.resolve("foreign.md"), "foreign body\n");
        reload.recordToolUse("call_mcp_tool", "{\"server_name\":\"other\",\"tool_name\":\"memory\","
                + "\"arguments\":{\"action\":\"read\",\"file\":\"foreign.md\"}}", "x");
        reload.recordToolUse("call_mcp_tool", "{\"ServerName\":\"kompile\",\"ToolName\":\"memory\","
                + "\"Arguments\":\"{\\\"action\\\":\\\"read\\\",\\\"file\\\":\\\"kept.md\\\"}\"}", "kept body");
        String context = reload.reloadContext();
        assertTrue(context.contains("### project/kept.md\nkept body"), context);
        assertFalse(context.contains("foreign"), context);
    }

    @Test
    void theMirrorFileTracksTheReloadAsJson() throws Exception {
        Files.writeString(memory.resolve("rule.md"), "rule body\n");
        Path file = project.resolve("reload.json");
        reload.mirrorTo(file);
        assertFalse(Files.exists(file), "nothing to reload yet");
        reload.recordToolUse("kompile_memory", "{\"action\":\"read\",\"file\":\"rule.md\"}", "rule body");
        String context = mapper.readTree(Files.readString(file)).path("context").asText();
        assertEquals(reload.reloadContext(), context);
        assertTrue(context.contains("rule body"), context);
    }

    @Test
    void anUnusedSessionPrependsNothingAfterCompaction() {
        reload.compacted();
        assertEquals("hello", reload.withPendingReload("hello"));
        assertNull(reload.reloadContext());
    }

    @Test
    void agyStreamFeedsTheReloadFromKompileToolStepsAndItsCheckpointStep() throws Exception {
        Files.writeString(memory.resolve("agy_rule.md"), "agy rule body\n");
        PassthroughStreamParser parser = new PassthroughStreamParser();
        AgyCliClient client = new AgyCliClient(project.toString());
        Map<String, ArrayDeque<String>> inputs = new HashMap<>();
        String[] lines = {
                "{\"event\":\"step_update\",\"step_update\":{\"step_index\":1,\"state\":\"ACTIVE\","
                        + "\"step_type\":\"tool\",\"tool_name\":\"mcp_kompile_memory\","
                        + "\"tool_info\":{\"parameters\":{\"action\":\"read\",\"file\":\"agy_rule.md\"}}}}",
                "{\"event\":\"step_update\",\"step_update\":{\"step_index\":1,\"state\":\"DONE\","
                        + "\"step_type\":\"tool\",\"tool_name\":\"mcp_kompile_memory\","
                        + "\"tool_info\":{\"output\":\"agy rule body\"}}}",
                "{\"event\":\"step_update\",\"step_update\":{\"step_index\":2,\"state\":\"DONE\","
                        + "\"step_type\":\"checkpoint\"}}"};
        boolean sawCompaction = false;
        for (String line : lines) {
            PassthroughStreamParser.PassthroughEvent event = parser.parseAgyLine(line);
            if (event instanceof PassthroughStreamParser.ContextCompacted) sawCompaction = true;
            client.observeForReload(event, inputs);
        }
        assertTrue(sawCompaction);
        assertTrue(client.compactionReload().pending());
        String sent = client.compactionReload().withPendingReload("go on");
        assertTrue(sent.contains("### project/agy_rule.md\nagy rule body"), sent);
    }

    @Test
    void anActiveAgyCheckpointStepIsNotACompactionYet() {
        PassthroughStreamParser parser = new PassthroughStreamParser();
        assertNull(parser.parseAgyLine("{\"event\":\"step_update\",\"step_update\":{\"state\":\"ACTIVE\","
                + "\"step_type\":\"checkpoint\"}}"));
        assertInstanceOf(PassthroughStreamParser.ContextCompacted.class, parser.parseAgyLine(
                "{\"event\":\"step_update\",\"step_update\":{\"state\":\"DONE\",\"step_type\":\"compaction\"}}"));
    }
}
