/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.service.impl;

import ai.kompile.orchestrator.api.WorkflowService;
import ai.kompile.orchestrator.model.workflow.ActionProposal;
import ai.kompile.orchestrator.model.workflow.ActionType;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.context.impl.DefaultToolkit;
import ai.kompile.react.context.impl.InMemoryMemory;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.ToolDefinition;
import ai.kompile.react.service.ReActAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter that integrates the ReAct agent with the existing Orchestrator workflow system.
 * Allows ReAct agent execution within orchestrator workflows.
 *
 * <p>This adapter:
 * <ul>
 *   <li>Converts orchestrator workflows to ReAct agent contexts</li>
 *   <li>Converts workflow tools to ReAct tool definitions</li>
 *   <li>Maps ReAct results back to workflow action proposals</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class ReActWorkflowAdapter {

    private final ReActAgentService agentService;
    private final WorkflowService workflowService;

    /**
     * Execute a workflow step using the ReAct agent.
     *
     * @param workflow The workflow
     * @param step The current step
     * @param tools Available tools for the agent
     * @return A future containing the action proposal
     */
    public CompletableFuture<ActionProposal> executeStep(
            Workflow workflow,
            WorkflowStep step,
            List<ToolDefinition> tools
    ) {
        log.info("Executing workflow step {} using ReAct agent", step.getStepNumber());

        // Create toolkit from tools
        Toolkit toolkit = new DefaultToolkit(tools);

        // Add final_answer tool for workflow completion
        toolkit.registerTool(createWorkflowCompleteToolDefinition());

        // Build the query from workflow context
        String query = buildStepQuery(workflow, step);

        // Create options
        ReActAgentService.AgentOptions options = new ReActAgentService.AgentOptions(
                buildWorkflowSystemPrompt(workflow),
                workflow.getMaxSteps() - step.getStepNumber(),
                true,
                Map.of(
                        "workflow_id", workflow.getId(),
                        "step_number", step.getStepNumber()
                )
        );

        // Run the agent
        return agentService.run(query, toolkit, options)
                .thenApply(result -> convertToActionProposal(result, step));
    }

    /**
     * Execute a full workflow using the ReAct agent as the reasoning engine.
     *
     * @param workflowName The workflow name
     * @param initialPrompt The initial user prompt
     * @param orchestratorId The orchestrator instance ID
     * @param tools Available tools
     * @return A future containing the workflow result
     */
    public CompletableFuture<ReActResult> executeWorkflow(
            String workflowName,
            String initialPrompt,
            String orchestratorId,
            List<ToolDefinition> tools
    ) {
        log.info("Starting ReAct-driven workflow: {}", workflowName);

        // Create the workflow in the orchestrator
        Workflow workflow = workflowService.startWorkflow(
                workflowName,
                "ReAct agent driven workflow",
                initialPrompt,
                orchestratorId,
                false, // Don't auto-advance - we control advancement
                10,
                null
        );

        // Create toolkit
        Toolkit toolkit = new DefaultToolkit(tools);

        // Add workflow control tools
        toolkit.registerTool(createWorkflowAdvanceTool(workflow.getId()));
        toolkit.registerTool(createWorkflowCompleteToolDefinition());

        // Create agent context
        AgentContext context = AgentContext.builder()
                .executionId("workflow-" + workflow.getId())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxSteps(workflow.getMaxSteps())
                .systemPrompt(buildWorkflowSystemPrompt(workflow))
                .metadata(Map.of("workflow_id", workflow.getId()))
                .build();

        // Add initial prompt
        context.addMessage(ReActMessage.user(initialPrompt));

        // Run the agent
        return agentService.run(context)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Workflow {} failed: {}", workflow.getId(), error.getMessage());
                        workflowService.cancelWorkflow(workflow.getId());
                    }
                });
    }

    private String buildStepQuery(Workflow workflow, WorkflowStep step) {
        StringBuilder query = new StringBuilder();
        query.append("Continue the workflow: ").append(workflow.getName()).append("\n\n");
        query.append("Original goal: ").append(workflow.getInitialPrompt()).append("\n\n");

        if (step.getStepNumber() > 0) {
            query.append("Previous steps completed: ").append(step.getStepNumber()).append("\n");
            if (step.getInputContextJson() != null) {
                query.append("Previous context: ").append(step.getInputContextJson()).append("\n");
            }
        }

        query.append("\nDetermine the next action to take toward completing the goal.");
        return query.toString();
    }

    private String buildWorkflowSystemPrompt(Workflow workflow) {
        return """
                You are a workflow execution agent. Your goal is to complete the following workflow:

                Workflow: %s
                Description: %s

                Guidelines:
                1. Break down complex tasks into smaller steps
                2. Use available tools to gather information and take actions
                3. Report progress clearly
                4. When the workflow goal is achieved, call the workflow_complete tool
                5. If you encounter an error, try to recover or report the issue

                Available actions:
                - Use tools to gather information or perform operations
                - Call workflow_complete when the goal is achieved
                - Call final_answer if you need to provide information to the user
                """.formatted(workflow.getName(), workflow.getDescription());
    }

    private ActionProposal convertToActionProposal(ReActResult result, WorkflowStep step) {
        if (result.getStatus() == ReActResult.Status.COMPLETED) {
            // Check if there were tool calls
            List<ReActMessage> messages = result.getMessages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ReActMessage msg = messages.get(i);
                if (msg.hasToolCalls()) {
                    ToolCall lastCall = msg.getToolCalls().get(msg.getToolCalls().size() - 1);

                    if ("workflow_complete".equals(lastCall.getName())) {
                        return ActionProposal.builder()
                                .actionType(ActionType.COMPLETE)
                                .reasoning(result.getAnswer())
                                .build();
                    }

                    // Convert tool call to action type
                    return ActionProposal.builder()
                            .actionType(mapToActionType(lastCall.getName()))
                            .command(lastCall.getName())
                            .reasoning(msg.getThought())
                            .build();
                }
            }

            // No tool calls, just a completion
            return ActionProposal.builder()
                    .actionType(ActionType.COMPLETE)
                    .reasoning(result.getAnswer())
                    .build();
        }

        if (result.getStatus() == ReActResult.Status.ERROR) {
            return ActionProposal.builder()
                    .actionType(ActionType.FAIL)
                    .reasoning(result.getErrorMessage())
                    .build();
        }

        // Max steps or cancelled
        return ActionProposal.builder()
                .actionType(ActionType.CUSTOM)
                .reasoning(result.getAnswer())
                .build();
    }

    private ActionType mapToActionType(String toolName) {
        return switch (toolName) {
            case "execute_command", "run_shell" -> ActionType.EXECUTE_COMMAND;
            case "invoke_llm", "ask_llm" -> ActionType.INVOKE_LLM;
            case "workflow_complete", "final_answer" -> ActionType.COMPLETE;
            default -> ActionType.CUSTOM;
        };
    }

    private ToolDefinition createWorkflowCompleteToolDefinition() {
        return ToolDefinition.builder()
                .name("workflow_complete")
                .description("Call this when the workflow goal has been achieved. " +
                        "Provide a summary of what was accomplished.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of(
                                        "type", "string",
                                        "description", "Summary of what was accomplished"
                                )
                        ),
                        "required", List.of("summary")
                ))
                .executor(args -> "Workflow marked as complete: " + args.get("summary"))
                .build();
    }

    private ToolDefinition createWorkflowAdvanceTool(Long workflowId) {
        return ToolDefinition.builder()
                .name("workflow_advance")
                .description("Advance to the next step in the workflow. " +
                        "Use this after completing the current step's actions.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "step_output", Map.of(
                                        "type", "string",
                                        "description", "Output from the current step"
                                )
                        )
                ))
                .executor(args -> {
                    workflowService.advanceWorkflow(workflowId);
                    return "Advanced to next workflow step";
                })
                .build();
    }
}
