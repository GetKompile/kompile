/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the activate_tools loop protection: repeated activation must be answered
 * honestly (no fresh progress reports for no-ops, no false "Unknown group" errors for
 * valid-but-empty groups) and must hard-stop after a bounded number of attempts.
 *
 * <p>Regression context: agents (notably kompile.task subagents) previously spun in
 * endless activate_tools loops. Confirmed causes were (1) {@code activateGroup}
 * returning an empty list for a known group, which the meta-tool reported as
 * "Unknown group: files" (see mcp-activity.log 2026-07-07), and (2) activation
 * succeeding repeatedly with "Activated N tools ... available on the next model step"
 * even when nothing changed — so the model kept re-attempting and never received a
 * signal to continue its task.
 */
class ActivateToolsLoopGuardTest {

    private final ObjectMapper om = new ObjectMapper();

    private ToolContext context(String sessionId) {
        // The meta-tool reads only the session id and the tool registry from the context.
        return new ToolContext(sessionId, null, null, Path.of("."), null);
    }

    private static JsonNode activate(String action, String group) {
        ObjectMapper om = new ObjectMapper();
        var params = om.createObjectNode();
        params.put("action", action);
        if (group != null) params.put("group", group);
        return params;
    }

    private ActivateToolsTool tool(DynamicToolManager manager) {
        return new ActivateToolsTool(manager);
    }

    /** A dynamic-mode manager whose "files" group contains exactly one registered tool. */
    private DynamicToolManager dynamicManager() {
        DynamicToolManager manager = new DynamicToolManager();
        manager.register("read", "read tool", om.createObjectNode().put("type", "object"));
        manager.register("file_note", "file note tool", om.createObjectNode().put("type", "object"));
        return manager;
    }

    @Test
    void firstActivationAddsToolsAndReportsThem() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);

        ToolResult result = tool.execute(activate("activate", "files"), context("s1"));

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Activated 1 tools"));
        assertTrue(result.getOutput().contains("file_note"));
    }

    @Test
    void repeatActivationIsAHonestNoOpNotAFreshProgressReport() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);
        ToolContext session = context("s2");

        tool.execute(activate("activate", "files"), session);
        ToolResult second = tool.execute(activate("activate", "files"), session);

        assertFalse(second.isError(), "a repeat must not be an error — errors feed retry loops");
        assertTrue(second.getOutput().contains("already active"));
        assertTrue(second.getOutput().contains("changes nothing"));
        assertFalse(second.getOutput().contains("available on the next model step"),
                "a no-op must never re-announce tools as newly available");
        assertEquals(0, second.getMetadata().get("toolsAdded"));
    }

    @Test
    void knownGroupWithNoRegisteredToolsIsNotReportedAsUnknown() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);
        // "web" is a real group but registers no tools in this manager.
        ToolResult result = tool.execute(activate("activate", "web"), context("s3"));

        assertFalse(result.isError(),
                "'Unknown group: web' was the false error that drove endless retries");
        assertTrue(result.getOutput().contains("registers no tools in this session"));
    }

    @Test
    void trulyUnknownGroupStillFailsOnceWithValidNames() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);

        ToolResult result = tool.execute(activate("activate", "warp_drive"), context("s4"));

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown group: warp_drive"));
        assertTrue(result.getOutput().contains("files"),
                "the error must list valid group names so the next attempt can succeed");
    }

    @Test
    void legacyGroupNamesActivateInsteadOfErroring() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);

        // 'search' and 'network' are legacy names seen in production logs.
        ToolResult network = tool.execute(activate("activate", "network"), context("s5"));
        assertFalse(network.isError(), "legacy group name must map, not error");

        ToolResult search = tool.execute(activate("activate", "search"), context("s5b"));
        assertFalse(search.isError(), "legacy group name must map, not error");
    }

    @Test
    void escalateAfterRepeatedReactivationWithExplicitStopInstruction() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);
        ToolContext session = context("s6");

        tool.execute(activate("activate", "files"), session);
        tool.execute(activate("activate", "files"), session);
        tool.execute(activate("activate", "files"), session);
        ToolResult fourth = tool.execute(activate("activate", "files"), session);

        assertFalse(fourth.isError());
        assertTrue(fourth.getOutput().contains("STOP activating tool groups"),
                "the escalation must tell the model explicitly to break out");
        assertTrue(fourth.getMetadata().get("loopGuard") == Boolean.TRUE);
    }

    @Test
    void hardBlockFreezesActivationAfterTheEscalationIsIgnored() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);
        ToolContext session = context("s7");

        for (int i = 0; i < 5; i++) {
            tool.execute(activate("activate", "files"), session);
        }
        ToolResult blocked = tool.execute(activate("activate", "files"), session);

        assertTrue(blocked.isError(), "after escalation is ignored, activation must be blocked");
        assertTrue(blocked.getOutput().contains("Blocked: this session has attempted tool activation"));
        assertTrue(blocked.getOutput().contains("Do not retry"));

        // Different group, same session: total-attempt budget still applies.
        ToolResult blockedOther = tool.execute(activate("activate", "web"), session);
        assertTrue(blockedOther.isError());
        assertTrue(blockedOther.getOutput().contains("Blocked:"));
    }

    @Test
    void otherGroupsRemainActivatableWhileOneGroupIsRepeated() throws Exception {
        DynamicToolManager manager = dynamicManager();
        manager.register("webfetch", "webfetch tool", om.createObjectNode().put("type", "object"));
        ActivateToolsTool tool = tool(manager);
        ToolContext session = context("s8");

        tool.execute(activate("activate", "files"), session);
        ToolResult web = tool.execute(activate("activate", "web"), session);

        assertTrue(web.getOutput().contains("Activated 1 tools: webfetch"),
                "loop protection is per repeated group, not a global freeze after first use");
    }

    @Test
    void otherSessionsAreNotBlockedByAnotherSessionsAttempts() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);
        ToolContext spammer = context("spammer");
        for (int i = 0; i < 6; i++) {
            tool.execute(activate("activate", "files"), spammer);
        }

        ToolContext fresh = context("fresh-session");
        ToolResult result = tool.execute(activate("activate", "files"), fresh);

        assertFalse(result.getOutput().contains("Blocked:"),
                "the per-session budget must never leak into another session");
        // Group activation state is shared per manager, so the honest answer for the
        // fresh session is already-active, without any escalation or block.
        assertTrue(result.getOutput().contains("already active"));
        assertFalse(result.getOutput().contains("STOP activating"));
    }

    @Test
    void mcpStaticModeReportsActivationAsNoOp() throws Exception {
        DynamicToolManager manager = dynamicManager();
        manager.setDynamicMode(false);
        ActivateToolsTool tool = tool(manager);
        ToolContext session = context("s9");

        ToolResult first = tool.execute(activate("activate", "files"), session);

        assertFalse(first.isError());
        assertTrue(first.getOutput().contains("already active"),
                "static mode always lists every tool, so activation can never add anything");
        assertEquals(0, first.getMetadata().get("toolsAdded"));

        ToolResult description = tool.execute(activate("list", null), session);
        assertTrue(description.getOutput().contains("Tool groups"));
    }

    @Test
    void staticModeDescriptionTellsAgentsActivationIsUnneeded() {
        DynamicToolManager manager = dynamicManager();
        manager.setDynamicMode(false);

        String description = tool(manager).description();

        assertTrue(description.contains("already listed in your toolset"));
        assertTrue(description.contains("no-op"));
        assertFalse(description.contains("next step"));
    }

    @Test
    void activationAllReportsNothingNewWhenEverythingIsActive() throws Exception {
        DynamicToolManager manager = dynamicManager();
        ActivateToolsTool tool = tool(manager);
        ToolContext session = context("s10");

        tool.execute(activate("activate", "all"), session);
        ToolResult repeat = tool.execute(activate("activate", "all"), session);

        assertFalse(repeat.isError());
        assertTrue(repeat.getOutput().contains("already active"));
        assertTrue(repeat.getOutput().contains("changes nothing"));
    }

    @Test
    void countersTrackPerSessionAndPerGroup() {
        DynamicToolManager manager = dynamicManager();

        manager.recordActivation("cx", "files");
        manager.recordActivation("cx", "files");
        manager.recordActivation("cx", "web");

        assertEquals(2, manager.activationCount("cx", "files"));
        assertEquals(1, manager.activationCount("cx", "web"));
        assertEquals(3, manager.totalActivations("cx"));
        assertEquals(0, manager.totalActivations("other-session"));
        assertEquals(0, manager.activationCount(null, "files"));
    }

    @Test
    void statusActivationResultDistinguishesAlreadyActiveFromActivated() {
        DynamicToolManager manager = dynamicManager();

        var first = manager.activateGroupWithStatus("files");
        assertEquals(DynamicToolManager.ActivationStatus.ACTIVATED, first.status());
        assertEquals(List.of("file_note"), first.added());

        var second = manager.activateGroupWithStatus("files");
        assertEquals(DynamicToolManager.ActivationStatus.ALREADY_ACTIVE, second.status());
        assertTrue(second.added().isEmpty());
    }
}
