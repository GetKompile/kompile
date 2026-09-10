/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowControllerTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void requiredSkillsAreExpandedBeforeTheTurnAndMissingSkillsFailClearly() {
        SkillRegistry skills = skills();
        ToolRegistry tools = tools();
        WorkflowController controller = new WorkflowController(skills, tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED,
                        List.of("workflow-check"), true, 2));

        controller.beginTurn();
        String prompt = controller.activeSystemPrompt();
        controller.completeTurn();

        assertTrue(prompt.contains("Host-Applied Workflow Profile (ENFORCED)"));
        assertTrue(prompt.contains("<skill name=\"workflow-check\" source=\"workflow\">"));
        assertTrue(prompt.contains("FULL_WORKFLOW_SKILL_INSTRUCTIONS"));
        assertTrue(prompt.contains("host-enforced completion monitor"));
        assertTrue(prompt.contains("code index current"));
        assertTrue(prompt.contains("graph query/reasoning tools"));

        WorkflowController missingController = new WorkflowController(skills, tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED,
                        List.of("missing-skill"), true, 2));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                missingController::beginTurn);
        assertTrue(missing.getMessage().contains("missing-skill"));

        WorkflowController globallyDisabled = new WorkflowController(skills, tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED,
                        List.of("missing-skill"), true, 2));
        globallyDisabled.setGlobalEnabled(false);
        globallyDisabled.beginTurn();
        assertTrue(globallyDisabled.activeSystemPrompt().isBlank());
        globallyDisabled.completeTurn();
    }

    @Test
    void enforcedModeRequiresPlanInAnEarlierAssistantBatch() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED,
                        List.of("workflow-check"), true, 2));
        JsonNode mutation = mapper.createObjectNode().put("value", "x");
        JsonNode todo = mapper.createObjectNode()
                .put("action", "add")
                .put("subject", "Implement the change");

        controller.beginTurn();
        controller.beginToolBatch();
        assertFalse(controller.beforeTool("write_fixture", mutation).allowed());
        assertTrue(controller.beforeTool("todowrite", todo).allowed());
        controller.afterTool("todowrite", todo,
                ToolResult.success("Added task #1: Implement the change"));

        assertFalse(controller.beforeTool("write_fixture", mutation).allowed(),
                "a sibling call was generated before the model saw the todo result");

        controller.beginToolBatch();
        assertTrue(controller.beforeTool("write_fixture", mutation).allowed());
        JsonNode completed = mapper.createObjectNode()
                .put("action", "update").put("task_id", "1").put("status", "completed");
        controller.afterTool("todowrite", completed, ToolResult.success("updated"));
        assertTrue(controller.beforeFinalResponse().allowed());
        controller.completeTurn();
    }

    @Test
    void explicitFirstStageCheckpointBlocksMutationButAllowsResearchAndCompletion() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        controller.beginTurn("List the design first before proceeding", "checkpoint-session");
        controller.beginToolBatch();

        WorkflowController.Decision mutation = controller.beforeTool(
                "write_fixture", mapper.createObjectNode());
        assertFalse(mutation.allowed());
        assertTrue(mutation.reason().contains("read-only first stage"));
        assertTrue(controller.beforeTool("read_fixture", mapper.createObjectNode()).allowed());
        assertTrue(controller.activeSystemPrompt().contains("USER CHECKPOINT"));
        assertTrue(controller.beforeFinalResponse().allowed());
        assertEquals(WorkflowController.Phase.COMPLETE,
                controller.activeTurnStatus().phase());
        controller.completeTurn();

        controller.beginTurn("Implement the first design option", "implementation-session");
        controller.beginToolBatch();
        assertTrue(controller.beforeTool("write_fixture", mapper.createObjectNode()).allowed(),
                "mentioning a numbered/first design is not a read-only checkpoint");
        controller.completeTurn();
    }

    @Test
    void shellSubstitutesAndSleepPollingAreRejectedButSystemCommandsRemainAvailable() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        controller.beginTurn();
        controller.beginToolBatch();

        WorkflowController.Decision grep = controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "cd src && grep -n TODO Main.java"));
        assertFalse(grep.allowed());
        assertTrue(grep.correctionPrompt().contains("grep/grep_batch"));

        WorkflowController.Decision wait = controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "sleep 30; ps -ef"));
        assertFalse(wait.allowed());
        assertTrue(wait.correctionPrompt().contains("host-backgrounded and monitored"));

        WorkflowController.Decision reset = controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "git reset --mixed HEAD"));
        assertFalse(reset.allowed());
        assertTrue(reset.correctionPrompt().contains("git reset is not allowed"));

        WorkflowController.Decision gitGrep = controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "git grep WorkflowController"));
        assertFalse(gitGrep.allowed());
        assertTrue(gitGrep.correctionPrompt().contains("grep/grep_batch"));

        assertTrue(controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "git status --short")).allowed());
        controller.completeTurn();
    }

    @Test
    void processMonitoringIsHostEnforcedAndProductionCrawlsRequireAsyncLifecycle() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        controller.beginTurn();
        controller.beginToolBatch();

        ObjectNode launch = mapper.createObjectNode()
                .put("action", "launch").put("command", "mvn test");
        assertTrue(controller.beforeTool("process", launch).allowed());
        launch.put("monitor", false);
        assertTrue(controller.beforeTool("process", launch).allowed(),
                "the process host forces monitoring instead of making the model retry");

        ObjectNode crawl = mapper.createObjectNode().put("async", false);
        assertFalse(controller.beforeTool("crawl_documents", crawl).allowed());
        crawl.put("async", true);
        assertTrue(controller.beforeTool("crawl_documents", crawl).allowed());

        ObjectNode preview = mapper.createObjectNode().put("dryRun", true).put("async", false);
        assertTrue(controller.beforeTool("crawl_source", preview).allowed());
        controller.completeTurn();
    }

    @Test
    void coordinationCheckMustCompleteInAnEarlierAssistantBatch() {
        ToolRegistry tools = tools();
        tools.register(tool("edit_coordinator", McpToolAnnotations.READ_ONLY));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        JsonNode mutation = mapper.createObjectNode();
        JsonNode awareness = mapper.createObjectNode().put("action", "awareness");

        controller.beginTurn();
        controller.beginToolBatch();
        assertFalse(controller.beforeTool("write_fixture", mutation).allowed());
        controller.afterTool("edit_coordinator", awareness, ToolResult.success("checked"));
        assertFalse(controller.beforeTool("write_fixture", mutation).allowed(),
                "a sibling mutation was generated before seeing the coordination result");

        controller.beginToolBatch();
        assertTrue(controller.beforeTool("write_fixture", mutation).allowed());
        controller.completeTurn();
    }

    @Test
    void explicitResourcePreflightSatisfiesCoordinationCheckpoint() {
        ToolRegistry tools = tools();
        tools.register(tool("edit_coordinator", McpToolAnnotations.READ_ONLY));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        ObjectNode preflight = mapper.createObjectNode()
                .put("action", "preflight_activity")
                .put("activity_kind", "test");

        controller.beginTurn();
        controller.beginToolBatch();
        controller.afterTool("edit_coordinator", preflight,
                ToolResult.success("resource preflight admitted"));
        controller.beginToolBatch();

        assertTrue(controller.beforeTool("write_fixture", mapper.createObjectNode()).allowed());
        controller.completeTurn();
    }

    @Test
    void acquiredEditLocksMustBeReleasedAndPartialAcquisitionIsNotReadiness() {
        ToolRegistry tools = tools();
        tools.register(tool("edit_coordinator", McpToolAnnotations.READ_ONLY));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        ObjectNode register = mapper.createObjectNode().put("action", "register_edits");

        controller.beginTurn("Implement safely", "lock-session");
        controller.beginToolBatch();
        controller.afterTool("edit_coordinator", register,
                ToolResult.success("partial", "one conflict", Map.of(
                        "status", "partial", "acquired", 1, "conflicts", 1,
                        "lockIds", Map.of("A.java", "lock-1"))));

        WorkflowController.TurnStatus partial = controller.activeTurnStatus();
        assertFalse(partial.coordinationChecked());
        assertEquals(List.of("lock-1"), partial.activeEditLockIds());
        controller.beginToolBatch();
        assertFalse(controller.beforeTool("write_fixture", mapper.createObjectNode()).allowed());
        WorkflowController.FinalDecision cleanup = controller.beforeFinalResponse();
        assertTrue(cleanup.retry());
        assertTrue(cleanup.reason().contains("lock-1"));

        ObjectNode release = mapper.createObjectNode().put("action", "release_edits");
        release.putArray("lock_ids").add("lock-1");
        controller.afterTool("edit_coordinator", release, ToolResult.success("released"));
        assertTrue(controller.activeTurnStatus().activeEditLockIds().isEmpty());

        ObjectNode awareness = mapper.createObjectNode().put("action", "awareness");
        controller.afterTool("edit_coordinator", awareness, ToolResult.success("checked"));
        controller.beginToolBatch();
        assertTrue(controller.beforeTool("write_fixture", mapper.createObjectNode()).allowed());
        controller.completeTurn();
    }

    @Test
    void identicalFailedCallsAreBoundedUntilInputsChange() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        ObjectNode original = mapper.createObjectNode().put("query", "missing");
        controller.beginTurn();
        controller.beginToolBatch();

        controller.afterTool("read_fixture", original, ToolResult.error("not found"));
        assertTrue(controller.beforeTool("read_fixture", original).allowed());
        controller.afterTool("read_fixture", original, ToolResult.error("not found"));
        WorkflowController.Decision repeated = controller.beforeTool("read_fixture", original);
        assertFalse(repeated.allowed());
        assertTrue(repeated.reason().contains("failed 2 times"));
        assertTrue(controller.beforeTool("read_fixture",
                mapper.createObjectNode().put("query", "different")).allowed());
        controller.completeTurn();
    }

    @Test
    void artifactMutationRequiresSuccessfulValidationAndClosedTodos() {
        ToolRegistry tools = tools();
        tools.register(tool("validate_fixture", McpToolAnnotations.READ_ONLY));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), true, 2));

        ArrayNode todoItems = mapper.createArrayNode();
        todoItems.addObject().put("id", "change").put("subject", "Change code")
                .put("status", "in_progress");
        ObjectNode plan = mapper.createObjectNode().put("action", "set");
        plan.set("todos", todoItems);

        controller.beginTurn("Implement the change", "validation-session");
        controller.beginToolBatch();
        controller.afterTool("todowrite", plan, ToolResult.success("planned"));
        controller.beginToolBatch();
        JsonNode mutation = mapper.createObjectNode();
        assertTrue(controller.beforeTool("write_fixture", mutation).allowed());
        controller.afterTool("write_fixture", mutation, ToolResult.success("changed"));

        assertEquals(WorkflowController.Phase.EXECUTION,
                controller.activeTurnStatus().phase());
        WorkflowController.FinalDecision needsValidation = controller.beforeFinalResponse();
        assertTrue(needsValidation.retry());
        assertTrue(needsValidation.reason().contains("not been validated"));

        controller.afterTool("validate_fixture", mapper.createObjectNode(),
                ToolResult.success("validation passed"));
        WorkflowController.FinalDecision needsTodoClosure = controller.beforeFinalResponse();
        assertTrue(needsTodoClosure.retry());
        assertTrue(needsTodoClosure.reason().contains("todos remain open"));

        ObjectNode completed = mapper.createObjectNode().put("action", "update")
                .put("task_id", "change").put("status", "completed");
        controller.afterTool("todowrite", completed, ToolResult.success("completed"));
        assertTrue(controller.beforeFinalResponse().allowed());
        WorkflowController.TurnStatus status = controller.activeTurnStatus();
        assertEquals(WorkflowController.Phase.COMPLETE, status.phase());
        assertFalse(status.artifactDirty());
        assertTrue(status.validationRecorded());
        assertTrue(status.openTodoIds().isEmpty());
        controller.completeTurn();
    }

    @Test
    void distinctPrerequisitesHaveIndependentCorrectionBudgets() {
        ToolRegistry tools = tools();
        tools.register(tool("validate_fixture", McpToolAnnotations.READ_ONLY));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 1));
        ObjectNode plan = mapper.createObjectNode().put("action", "add")
                .put("subject", "Finish change").put("status", "in_progress");

        controller.beginTurn();
        controller.beginToolBatch();
        controller.afterTool("todowrite", plan,
                ToolResult.success("Added task #1: Finish change"));
        controller.afterTool("write_fixture", mapper.createObjectNode(),
                ToolResult.success("changed"));

        assertTrue(controller.beforeFinalResponse().retry());
        controller.afterTool("validate_fixture", mapper.createObjectNode(),
                ToolResult.success("passed"));
        assertTrue(controller.beforeFinalResponse().retry(),
                "todo closure gets its own bounded correction budget");

        ObjectNode completed = mapper.createObjectNode().put("action", "update")
                .put("task_id", "1").put("status", "completed");
        controller.afterTool("todowrite", completed, ToolResult.success("completed"));
        assertTrue(controller.beforeFinalResponse().allowed());
        assertEquals(2, controller.activeTurnStatus().corrections());
        controller.completeTurn();
    }

    @Test
    void monitoredValidationCountsOnlyAfterSuccessfulTerminalState() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        JsonNode mutation = mapper.createObjectNode();
        ObjectNode todo = mapper.createObjectNode().put("action", "add")
                .put("subject", "Validate the change").put("status", "in_progress");
        ObjectNode launch = mapper.createObjectNode().put("action", "launch")
                .put("command", "/home/agibsonccc/dev-apps/mvn/bin/mvn test");
        ObjectNode status = mapper.createObjectNode().put("action", "status")
                .put("process_id", "proc-123");

        controller.beginTurn("Implement and validate", "async-session");
        controller.beginToolBatch();
        controller.afterTool("todowrite", todo,
                ToolResult.success("Added task #1: Validate the change"));
        controller.afterTool("write_fixture", mutation, ToolResult.success("changed"));
        controller.afterTool("process", launch, ToolResult.success(
                "launched", "running", Map.of("processId", "proc-123", "monitored", true)));
        assertTrue(controller.activeTurnStatus().artifactDirty());
        assertTrue(controller.beforeFinalResponse().allowed(),
                "a monitored validation may hand off to its completion wake-up");
        assertEquals(WorkflowController.Phase.WAITING,
                controller.activeTurnStatus().phase());
        controller.completeTurn();

        controller.beginTurn("The monitored validation completed", "async-session");
        controller.beginToolBatch();

        controller.afterTool("process", status, ToolResult.success(
                "status", "still running", Map.of("processId", "proc-123", "state", "RUNNING")));
        assertTrue(controller.activeTurnStatus().artifactDirty());

        controller.afterTool("process", status, ToolResult.success(
                "status", "done", Map.of("processId", "proc-123", "state", "COMPLETED")));
        assertFalse(controller.activeTurnStatus().artifactDirty());
        assertTrue(controller.activeTurnStatus().validationRecorded());
        WorkflowController.FinalDecision openTodo = controller.beforeFinalResponse();
        assertTrue(openTodo.retry());
        assertTrue(openTodo.reason().contains("todos remain open"));
        ObjectNode completed = mapper.createObjectNode().put("action", "update")
                .put("task_id", "1").put("status", "completed");
        controller.afterTool("todowrite", completed, ToolResult.success("completed"));
        assertTrue(controller.beforeFinalResponse().allowed());
        controller.completeTurn();

        controller.beginTurn("Follow-up question", "async-session");
        assertFalse(controller.activeTurnStatus().artifactDirty());
        assertTrue(controller.activeTurnStatus().openTodoIds().isEmpty());
        controller.completeTurn();
    }

    @Test
    void partialMutationRemainsDirtyAndLaterMutationInvalidatesEarlierValidation() {
        ToolRegistry tools = tools();
        tools.register(tool("validate_fixture", McpToolAnnotations.READ_ONLY));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        JsonNode args = mapper.createObjectNode();

        controller.beginTurn();
        controller.beginToolBatch();
        ToolResult partial = new ToolResult("partial", "one file failed",
                Map.of("filesEdited", 1, "filesFailed", 1), false);
        controller.afterTool("write_fixture", args, partial);
        assertTrue(controller.activeTurnStatus().artifactDirty());

        controller.afterTool("validate_fixture", args, ToolResult.success("passed"));
        assertEquals(WorkflowController.Phase.VALIDATION,
                controller.activeTurnStatus().phase());
        controller.afterTool("write_fixture", args, ToolResult.success("changed again"));
        assertEquals(WorkflowController.Phase.EXECUTION,
                controller.activeTurnStatus().phase());
        assertTrue(controller.activeTurnStatus().artifactDirty());
        assertFalse(controller.activeTurnStatus().validationRecorded());
        controller.completeTurn();
    }

    @Test
    void semanticValidationMustPassRatherThanOnlyReturnSuccessfully() {
        ToolRegistry tools = tools();
        tools.register(tool("pipeline", McpToolAnnotations.WRITE));
        tools.register(tool("ask_graph_verify", McpToolAnnotations.READ_ONLY));
        tools.register(tool("lsp", McpToolAnnotations.WRITE));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));

        controller.beginTurn();
        controller.beginToolBatch();
        controller.afterTool("write_fixture", mapper.createObjectNode(),
                ToolResult.success("changed"));
        ObjectNode validate = mapper.createObjectNode().put("action", "validate");
        controller.afterTool("pipeline", validate,
                ToolResult.success("pipeline validate", "{ \"valid\" : false }"));
        assertTrue(controller.activeTurnStatus().artifactDirty());

        controller.afterTool("ask_graph_verify", mapper.createObjectNode(),
                ToolResult.success("verify", "REFUTED", Map.of("verdict", "REFUTED")));
        assertTrue(controller.activeTurnStatus().artifactDirty());
        controller.afterTool("lsp", mapper.createObjectNode().put("action", "diagnostics"),
                ToolResult.success("diagnostics", "one error", Map.of("count", 1)));
        assertTrue(controller.activeTurnStatus().artifactDirty());
        controller.afterTool("ask_graph_verify", mapper.createObjectNode(),
                ToolResult.success("verify", "SUPPORTED", Map.of("verdict", "SUPPORTED")));
        assertFalse(controller.activeTurnStatus().artifactDirty());
        controller.completeTurn();
    }

    @Test
    void multipleValidationProcessesMustAllComplete() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        ObjectNode launch = mapper.createObjectNode().put("action", "launch")
                .put("command", "mvn test");

        controller.beginTurn();
        controller.beginToolBatch();
        controller.afterTool("write_fixture", mapper.createObjectNode(),
                ToolResult.success("changed"));
        controller.afterTool("process", launch, ToolResult.success(
                "launch", "one", Map.of("processId", "proc-1", "monitored", true)));
        controller.afterTool("process", launch.deepCopy(), ToolResult.success(
                "launch", "two", Map.of("processId", "proc-2", "monitored", true)));

        ObjectNode firstStatus = mapper.createObjectNode().put("action", "status")
                .put("process_id", "proc-1");
        controller.afterTool("process", firstStatus, ToolResult.success(
                "status", "done", Map.of("processId", "proc-1", "state", "COMPLETED")));
        assertTrue(controller.activeTurnStatus().artifactDirty(),
                "one completed process must not validate work while another is pending");
        assertEquals(List.of("proc-2"),
                controller.activeTurnStatus().pendingValidationProcessIds());

        ObjectNode secondStatus = mapper.createObjectNode().put("action", "status")
                .put("process_id", "proc-2");
        controller.afterTool("process", secondStatus, ToolResult.success(
                "status", "done", Map.of("processId", "proc-2", "state", "COMPLETED")));
        assertFalse(controller.activeTurnStatus().artifactDirty());
        assertTrue(controller.activeTurnStatus().validationRecorded());
        assertTrue(controller.activeTurnStatus().pendingValidationProcessIds().isEmpty());
        controller.completeTurn();
    }

    @Test
    void failedMonitoredValidationCannotBeMaskedByAnotherRunningJob() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
        ObjectNode launchOne = mapper.createObjectNode().put("action", "launch")
                .put("command", "mvn test");
        ObjectNode launchTwo = launchOne.deepCopy();

        controller.beginTurn();
        controller.beginToolBatch();
        controller.afterTool("write_fixture", mapper.createObjectNode(),
                ToolResult.success("changed"));
        controller.afterTool("process", launchOne, ToolResult.success(
                "launch", "one", Map.of("processId", "proc-1", "monitored", true)));
        controller.afterTool("process", launchTwo, ToolResult.success(
                "launch", "two", Map.of("processId", "proc-2", "monitored", true)));

        ObjectNode failedStatus = mapper.createObjectNode().put("action", "status")
                .put("process_id", "proc-1");
        controller.afterTool("process", failedStatus, ToolResult.success(
                "status", "failed", Map.of("processId", "proc-1", "state", "FAILED")));

        WorkflowController.TurnStatus status = controller.activeTurnStatus();
        assertTrue(status.artifactDirty());
        assertTrue(status.pendingValidationProcessIds().isEmpty());
        WorkflowController.FinalDecision decision = controller.beforeFinalResponse();
        assertTrue(decision.retry());
        assertTrue(decision.reason().contains("proc-1"));
        assertTrue(decision.reason().contains("FAILED"));
        controller.completeTurn();
    }

    @Test
    void readOnlyBashAndBookkeepingRemainAvailableBeforePlan() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED,
                        List.of(), true, 1));
        controller.beginTurn();
        controller.beginToolBatch();

        assertTrue(controller.beforeTool("read_fixture", mapper.createObjectNode()).allowed());
        assertTrue(controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "git status --short")).allowed());
        assertFalse(controller.beforeTool("bash",
                mapper.createObjectNode().put("command", "git commit -m test")).allowed());
        assertTrue(controller.beforeTool("mcp__kompile__todoread",
                mapper.createObjectNode()).allowed());
        assertTrue(controller.beforeTool("skill_manager",
                mapper.createObjectNode().put("action", "get_skill")).allowed());
        assertTrue(controller.beforeTool("process",
                mapper.createObjectNode().put("action", "monitor")
                        .put("process_id", "proc-1")).allowed());
        controller.completeTurn();
    }

    @Test
    void mixedAndNetworkToolsUseActionLevelMutationClassification() {
        ToolRegistry tools = tools();
        tools.register(tool("websearch", McpToolAnnotations.NETWORK));
        tools.register(tool("channel", McpToolAnnotations.NETWORK));
        tools.register(tool("crawl_control", McpToolAnnotations.WRITE));
        tools.register(tool("pipeline", McpToolAnnotations.WRITE));
        tools.register(tool("knowledge_graph", null));
        tools.register(tool("task", McpToolAnnotations.WRITE));
        WorkflowController controller = new WorkflowController(skills(), tools,
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), true, 2));
        controller.beginTurn();
        controller.beginToolBatch();

        assertTrue(controller.beforeTool("websearch",
                mapper.createObjectNode().put("query", "docs")).allowed());
        assertTrue(controller.beforeTool("channel",
                mapper.createObjectNode().put("action", "status")).allowed());
        assertFalse(controller.beforeTool("channel",
                mapper.createObjectNode().put("action", "send")).allowed());
        assertTrue(controller.beforeTool("crawl_control",
                mapper.createObjectNode().put("operation", "status")).allowed());
        assertFalse(controller.beforeTool("crawl_control",
                mapper.createObjectNode().put("operation", "start")).allowed());
        assertTrue(controller.beforeTool("pipeline",
                mapper.createObjectNode().put("action", "list")).allowed());
        assertTrue(controller.beforeTool("pipeline",
                mapper.createObjectNode().put("action", "validate")).allowed());
        assertFalse(controller.beforeTool("pipeline",
                mapper.createObjectNode().put("action", "create")).allowed());
        assertTrue(controller.beforeTool("knowledge_graph",
                mapper.createObjectNode().put("action", "overview")).allowed());
        assertTrue(controller.beforeTool("knowledge_graph",
                mapper.createObjectNode().put("action", "cypher").put("read_only", true)).allowed());
        assertFalse(controller.beforeTool("knowledge_graph",
                mapper.createObjectNode().put("action", "add_node")).allowed());
        assertFalse(controller.beforeTool("knowledge_graph",
                mapper.createObjectNode().put("action", "cypher").put("read_only", false)).allowed());
        assertTrue(controller.beforeTool("task",
                mapper.createObjectNode().put("agent_type", "explore-quick")).allowed());
        assertFalse(controller.beforeTool("task",
                mapper.createObjectNode().put("agent_type", "general")).allowed());
        controller.completeTurn();
    }

    @Test
    void advisoryAndOffModesNeverBlockTools() {
        for (WorkflowPolicy.Mode mode : List.of(
                WorkflowPolicy.Mode.ADVISORY, WorkflowPolicy.Mode.OFF)) {
            WorkflowController controller = new WorkflowController(skills(), tools(),
                    new WorkflowPolicy(mode, List.of(), true, 2));
            controller.beginTurn();
            controller.beginToolBatch();
            assertTrue(controller.beforeTool(
                    "write_fixture", mapper.createObjectNode()).allowed());
            controller.completeTurn();
        }
    }

    @Test
    void finalCorrectionIsBoundedWhenMutationWasBlocked() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED,
                        List.of(), true, 1));
        controller.beginTurn();
        controller.beginToolBatch();
        assertFalse(controller.beforeTool(
                "write_fixture", mapper.createObjectNode()).allowed());

        WorkflowController.FinalDecision retry = controller.beforeFinalResponse();
        WorkflowController.FinalDecision stop = controller.beforeFinalResponse();

        assertFalse(retry.allowed());
        assertTrue(retry.retry());
        assertFalse(stop.allowed());
        assertFalse(stop.retry());
        controller.completeTurn();
    }

    @Test
    void policyParsingRejectsUnknownModesAndNormalizesSkills() {
        assertEquals(WorkflowPolicy.Mode.ENFORCED, WorkflowPolicy.Mode.parse("strict"));
        WorkflowPolicy policy = new WorkflowPolicy(WorkflowPolicy.Mode.ADVISORY,
                List.of("Review", "review", " workflow-check "), true, 99);
        assertEquals(List.of("Review", "workflow-check"), policy.requiredSkills());
        assertEquals(WorkflowPolicy.HARD_MAX_CORRECTIONS, policy.maxCorrections());
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowPolicy.Mode.parse("sometimes"));
    }

    @Test
    void sessionAndGlobalControlsChangeEffectiveModeWithoutLosingConfiguration() {
        WorkflowController controller = new WorkflowController(skills(), tools(),
                new WorkflowPolicy(WorkflowPolicy.Mode.ADVISORY,
                        List.of("workflow-check"), true, 2));

        controller.setSessionMode(WorkflowPolicy.Mode.ENFORCED, null);
        assertEquals(WorkflowPolicy.Mode.ENFORCED,
                controller.status().effectiveMode());
        assertTrue(controller.status().sessionOverride());

        controller.setSessionEnabled(false);
        assertEquals(WorkflowPolicy.Mode.OFF, controller.status().effectiveMode());
        assertEquals(List.of("workflow-check"), controller.status().requiredSkills());

        controller.setSessionEnabled(true);
        controller.setGlobalEnabled(false);
        assertEquals(WorkflowPolicy.Mode.OFF, controller.status().effectiveMode());
        assertFalse(controller.status().globalEnabled());
    }

    @Test
    void resourcePauseKeepsTodosAndValidationPendingUntilWakeTurn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var manager = new ai.kompile.cli.main.coordination.CoordinationStateManager(
                root, "owner", mapper, root.resolve("system"));
        try {
            ToolRegistry registry = tools();
            registry.register(new ai.kompile.cli.main.chat.tools.EditCoordinatorTool(manager));
            WorkflowController controller = new WorkflowController(skills(), registry,
                    new WorkflowPolicy(WorkflowPolicy.Mode.ENFORCED, List.of(), false, 2));
            manager.activityWaits().setWakeHandler("owner", ignored -> { });
            var wait = manager.activityWaits().watch("owner", "build", "BUILD", "build", () -> false);
            controller.beginTurn("implement and test", "owner");
            controller.afterTool("todowrite", mapper.createObjectNode().put("action", "add").put("subject", "test"),
                    ToolResult.success("Added task #test: test"));
            controller.afterTool("write_fixture", mapper.createObjectNode(), ToolResult.success("changed"));
            controller.afterTool("process", mapper.createObjectNode().put("action", "launch").put("command", "mvn test"),
                    new ToolResult("blocked", "blocked", Map.of("admitted", false,
                            "resourceWaitId", wait.waitId(), "wakeSupported", true), true));
            assertTrue(controller.beforeFinalResponse().allowed(), "real wake registration allows an idle pause");
            assertEquals(WorkflowController.Phase.WAITING, controller.activeTurnStatus().phase());
            assertTrue(controller.activeTurnStatus().artifactDirty());
            assertFalse(controller.activeTurnStatus().validationRecorded());
            controller.completeTurn();
            controller.beginTurn("[System resource availability] retry", "owner");
            assertFalse(controller.beforeFinalResponse().allowed(), "wake is not successful validation");
            assertTrue(controller.activeTurnStatus().openTodoIds().contains("test"));
            controller.completeTurn();

            manager.activityWaits().cancel("owner", wait.waitId());
            var coordination = (ai.kompile.cli.main.chat.tools.EditCoordinatorTool) registry.get("edit_coordinator");
            assertFalse(coordination.canPauseForActivity("owner", wait.waitId()));
            var external = manager.activityWaits().watch("external", "build", "BUILD", "build", () -> false);
            assertFalse(coordination.canPauseForActivity("external", external.waitId()),
                    "external client must hold wait_for_activity rather than ending its turn");
        } finally {
            manager.shutdown();
        }
    }

    private SkillRegistry skills() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(SkillConfig.builder("workflow-check")
                .description("Workflow test skill")
                .promptTemplate("FULL_WORKFLOW_SKILL_INSTRUCTIONS {{args}}")
                .build());
        return registry;
    }

    private ToolRegistry tools() {
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(tool("read_fixture", McpToolAnnotations.READ_ONLY));
        registry.register(tool("write_fixture", McpToolAnnotations.WRITE));
        return registry;
    }

    private CliTool tool(String name, McpToolAnnotations annotations) {
        return new CliTool() {
            @Override public String id() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public McpToolAnnotations mcpAnnotations() { return annotations; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                return ToolResult.success("ok");
            }
        };
    }
}
