/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SubagentOutputRoutingTest {

    @Test
    void taskAndRunnerEventsUseTheCallerOwnedOutputChannel() throws Exception {
        AgentRegistry agents = new AgentRegistry();
        PermissionService permissions = new PermissionService();
        List<String> renderedAboveInput = new ArrayList<>();

        ToolContext context = new ToolContext(
                "render-session",
                agents.getDefault(),
                permissions,
                Path.of(".").toAbsolutePath(),
                null);
        context.setAutoApproveAll(true);
        context.setOutputConsumer(renderedAboveInput::add);

        TaskTool task = new TaskTool(agents, (agent, prompt, parentContext) -> {
            parentContext.emitOutput("  runner lifecycle event");
            return "finished";
        });

        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put("description", "Trace model launch contract");
        request.put("prompt", "Inspect the launch contract.");
        request.put("agent_type", "explore-quick");

        ToolResult result = task.execute(request, context);

        assertFalse(result.isError());
        assertEquals(List.of(
                "  [Spawning explore-quick subagent: Trace model launch contract]",
                "  runner lifecycle event"), renderedAboveInput);
    }

    @Test
    void linkedAbortSignalIsSharedWithChildToolContexts() {
        AtomicBoolean shared = new AtomicBoolean(false);
        ToolContext context = new ToolContext(
                "cancel-session", null, null,
                Path.of(".").toAbsolutePath(), null);

        context.linkAbortSignal(shared);
        shared.set(true);

        assertEquals(shared, context.getAbortSignal());
        assertEquals(true, context.isAborted());
    }

    @Test
    void blankAndNullEntriesArePreservedForTranscriptSpacing() {
        List<String> output = new ArrayList<>();
        ToolContext context = new ToolContext(
                "render-session",
                null,
                null,
                Path.of(".").toAbsolutePath(),
                null);
        context.setOutputConsumer(output::add);

        context.emitOutput("");
        context.emitOutput(null);

        assertEquals(List.of("", ""), output);
    }
}
