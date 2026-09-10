/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticChatLoopWorkflowTest {

    @TempDir
    Path workingDirectory;

    @Test
    void requiredSkillLoadsBeforeFirstRequestAndMutationWaitsForPlan() throws Exception {
        EnforcerConfig config = new EnforcerConfig();
        config.setWorkflowMode("enforced");
        config.setWorkflowRequiredSkills(List.of("workflow-check"));
        config.setWorkflowRequirePlanBeforeMutation(true);
        config.setWorkflowMaxCorrections(2);
        config.save(workingDirectory);

        ObjectMapper mapper = JsonUtils.standardMapper();
        SkillRegistry skills = new SkillRegistry();
        skills.register(SkillConfig.builder("workflow-check")
                .description("Workflow integration skill")
                .promptTemplate("FULL_WORKFLOW_SKILL_INSTRUCTIONS")
                .build());

        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(new TodoWriteTool());
        AtomicInteger mutations = new AtomicInteger();
        tools.register(writeFixture(mapper, mutations));
        tools.register(validateFixture(mapper));

        WorkflowClient client = new WorkflowClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null, skills);

        String output = loop.chat("implement the requested change",
                "workflow-" + UUID.randomUUID(), "coder", "default", false);

        assertTrue(client.firstSystemPrompt.contains(
                "Host-Applied Workflow Profile (ENFORCED)"));
        assertTrue(client.firstSystemPrompt.contains("FULL_WORKFLOW_SKILL_INSTRUCTIONS"));
        assertEquals(1, mutations.get(), "only the post-plan mutation may execute");
        assertEquals(6, client.calls);
        assertTrue(client.toolResultsByCall.get(0).get(0).isError);
        assertTrue(client.toolResultsByCall.get(0).get(0).output.contains("Blocked by workflow"));
        assertFalse(client.toolResultsByCall.get(1).get(0).isError);
        assertFalse(client.toolResultsByCall.get(2).get(0).isError);
        assertFalse(client.toolResultsByCall.get(3).get(0).isError);
        assertFalse(client.toolResultsByCall.get(4).get(0).isError);
        assertTrue(output.contains("workflow complete"));
    }

    @Test
    void repeatedUnknownToolFailuresAreBlockedBeforeAThirdExecutionAttempt() throws Exception {
        EnforcerConfig config = new EnforcerConfig();
        config.setWorkflowMode("enforced");
        config.setWorkflowRequirePlanBeforeMutation(false);
        config.save(workingDirectory);

        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        RepeatedFailureClient client = new RepeatedFailureClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null, new SkillRegistry());

        String output = loop.chat("Try the unavailable tool, but do not loop forever",
                "failure-" + UUID.randomUUID(), "coder", "default", false);

        assertEquals(4, client.calls);
        assertEquals(3, client.toolResultsByCall.size());
        assertTrue(client.toolResultsByCall.get(0).get(0).output.contains("Unknown tool"));
        assertTrue(client.toolResultsByCall.get(1).get(0).output.contains("Unknown tool"));
        assertTrue(client.toolResultsByCall.get(2).get(0).output
                .contains("identical tool call already failed 2 times"));
        assertTrue(output.contains("stopped retrying"));
    }

    private static CliTool writeFixture(ObjectMapper mapper, AtomicInteger mutations) {
        return new CliTool() {
            @Override public String id() { return "write_fixture"; }
            @Override public String description() { return "mutating workflow fixture"; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                mutations.incrementAndGet();
                return ToolResult.success("mutation executed");
            }
        };
    }

    private static CliTool validateFixture(ObjectMapper mapper) {
        return new CliTool() {
            @Override public String id() { return "validate_fixture"; }
            @Override public String description() { return "workflow validation fixture"; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                return ToolResult.success("validation passed");
            }
            @Override public ai.kompile.cli.main.chat.tools.McpToolAnnotations mcpAnnotations() {
                return ai.kompile.cli.main.chat.tools.McpToolAnnotations.READ_ONLY;
            }
        };
    }

    private static final class WorkflowClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        private final List<List<ToolCallResultInput>> toolResultsByCall = new ArrayList<>();
        private int calls;
        private String firstSystemPrompt = "";

        private WorkflowClient(ObjectMapper mapper) {
            super(new ChatConfig(
                    "custom", null, "workflow-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
        }

        @Override
        public StreamResult streamChat(
                String userMessage,
                String systemPrompt,
                ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults,
                String modelOverride,
                List<AttachmentInput> attachments) {
            calls++;
            if (calls == 1) firstSystemPrompt = systemPrompt;
            if (toolResults != null) toolResultsByCall.add(List.copyOf(toolResults));

            StreamResult result = new StreamResult();
            if (calls == 1 || calls == 3) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "write-" + calls;
                call.name = "write_fixture";
                call.arguments = mapper.createObjectNode().put("value", "changed");
                result.toolCalls.add(call);
                return result;
            }
            if (calls == 2) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "todo-2";
                call.name = "todowrite";
                call.arguments = mapper.createObjectNode()
                        .put("action", "add")
                        .put("subject", "Implement the requested change")
                        .put("status", "in_progress");
                result.toolCalls.add(call);
                return result;
            }
            if (calls == 4) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "validate-4";
                call.name = "validate_fixture";
                call.arguments = mapper.createObjectNode();
                result.toolCalls.add(call);
                return result;
            }
            if (calls == 5) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "todo-5";
                call.name = "todowrite";
                call.arguments = mapper.createObjectNode()
                        .put("action", "update")
                        .put("task_id", "1")
                        .put("status", "completed");
                result.toolCalls.add(call);
                return result;
            }

            Consumer<String> output = getOutputConsumer();
            if (output != null) output.accept("workflow complete");
            result.text = "workflow complete";
            return result;
        }
    }

    private static final class RepeatedFailureClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        private final List<List<ToolCallResultInput>> toolResultsByCall = new ArrayList<>();
        private int calls;

        private RepeatedFailureClient(ObjectMapper mapper) {
            super(new ChatConfig(
                    "custom", null, "workflow-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
        }

        @Override
        public StreamResult streamChat(
                String userMessage,
                String systemPrompt,
                ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults,
                String modelOverride,
                List<AttachmentInput> attachments) {
            calls++;
            if (toolResults != null) toolResultsByCall.add(List.copyOf(toolResults));
            StreamResult result = new StreamResult();
            if (calls <= 3) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "missing-" + calls;
                call.name = "missing_fixture";
                call.arguments = mapper.createObjectNode().put("query", "same");
                result.toolCalls.add(call);
            } else {
                Consumer<String> output = getOutputConsumer();
                if (output != null) output.accept("stopped retrying");
                result.text = "stopped retrying";
            }
            return result;
        }
    }
}
