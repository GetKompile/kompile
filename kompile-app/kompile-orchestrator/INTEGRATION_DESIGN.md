# Kompile Orchestrator - Integration Design Document

## Executive Summary

This document describes the design of a **generalized state machine task orchestrator** for the Kompile platform. The orchestrator provides a flexible framework for defining custom states, executing tasks, orchestrating multi-step workflows, and triggering LLM interference at configurable points. This design is inspired by the build-orchestrator architecture but is **domain-agnostic** and suitable for any orchestration scenario.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Core Concepts](#2-core-concepts)
3. [State Machine Design](#3-state-machine-design)
4. [Task Execution System](#4-task-execution-system)
5. [Workflow Orchestration](#5-workflow-orchestration)
6. [LLM Integration & Triggers](#6-llm-integration--triggers)
7. [Event System](#7-event-system)
8. [Persistence & Recovery](#8-persistence--recovery)
9. [API Design](#9-api-design)
10. [Integration Points](#10-integration-points)
11. [Package Structure](#11-package-structure)
12. [Configuration](#12-configuration)
13. [Example Use Cases](#13-example-use-cases)

---

## 1. Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           REST API / WebSocket Layer                        │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────────────┐│
│  │OrchestratorCtrl │  │   TaskCtrl      │  │     WorkflowCtrl             ││
│  └────────┬────────┘  └────────┬────────┘  └──────────────┬───────────────┘│
└───────────┼────────────────────┼──────────────────────────┼─────────────────┘
            │                    │                          │
┌───────────▼────────────────────▼──────────────────────────▼─────────────────┐
│                            Service Layer                                     │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      OrchestratorService                               │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐  │  │
│  │  │StateMachine │  │TaskExecutor │  │ Workflow    │  │LlmIntegration│  │  │
│  │  │  Service    │  │  Service    │  │  Service    │  │   Service    │  │  │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬───────┘  │  │
│  └─────────┼────────────────┼────────────────┼────────────────┼──────────┘  │
│            │                │                │                │             │
│  ┌─────────▼────────────────▼────────────────▼────────────────▼──────────┐  │
│  │                        Event Bus (Spring Events)                       │  │
│  │                    + WebSocket Broadcasting Service                    │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
            │
┌───────────▼──────────────────────────────────────────────────────────────────┐
│                           Persistence Layer                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ State       │  │ Task        │  │ Workflow    │  │ LlmSession          │  │
│  │ Repository  │  │ Repository  │  │ Repository  │  │ Repository          │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Design Principles

1. **Pluggable State Definitions** - States can be defined via configuration or programmatically
2. **Strategy Pattern for Handlers** - Each state can have a custom handler implementation
3. **LLM-Aware Orchestration** - First-class support for LLM triggers at any state transition
4. **Event-Driven Architecture** - All state changes, task completions publish events
5. **Recovery-First Design** - State snapshots enable crash recovery
6. **Multi-Tenant Support** - Multiple orchestrator instances can run concurrently

---

## 2. Core Concepts

### 2.1 Orchestrator Instance

An **OrchestratorInstance** represents a single orchestration execution context with its own state machine, running tasks, and configuration.

```java
public class OrchestratorInstance {
    private String instanceId;           // Unique identifier
    private String name;                 // Human-readable name
    private OrchestratorStatus status;   // CREATED, RUNNING, PAUSED, COMPLETED, FAILED
    private String currentStateId;       // Current state in the state machine
    private Map<String, Object> context; // Shared context across states
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 2.2 State Definition

A **StateDefinition** describes a state in the state machine, including metadata, allowed transitions, and LLM trigger configuration.

```java
public class StateDefinition {
    private String stateId;              // Unique identifier (e.g., "PROCESSING_DOCUMENTS")
    private String name;                 // Display name
    private String description;          // Human-readable description
    private StateCategory category;      // INITIAL, PROCESSING, WAITING, TERMINAL, ERROR
    private Set<String> allowedNextStates;  // Valid transition targets
    private String handlerClassName;     // Handler implementation class
    private LlmTriggerConfig llmTrigger; // Optional LLM trigger configuration
    private Duration timeout;            // Optional state timeout
    private boolean autoAdvance;         // Auto-transition when handler completes
    private Map<String, Object> metadata; // Custom metadata
}

public enum StateCategory {
    INITIAL,      // Starting states
    PROCESSING,   // Active processing states
    WAITING,      // Waiting for external input/event
    TERMINAL,     // Final success states
    ERROR         // Error/failure states
}
```

### 2.3 Task Definition & Instance

A **TaskDefinition** is a reusable template; **TaskInstance** is an actual execution.

```java
public class TaskDefinition {
    private String taskId;               // Unique identifier
    private String name;                 // Display name
    private TaskType taskType;           // SHELL, HTTP, CODE, LLM_QUERY, CUSTOM
    private String command;              // Command template (supports ${variable} substitution)
    private String workingDirectory;     // Working directory for execution
    private Duration timeout;            // Execution timeout
    private String promptTemplate;       // For LLM_QUERY type
    private boolean autoInvokeLlmOnError; // Auto-invoke LLM on failure
    private Map<String, String> defaultVariables;
    private List<String> requiredVariables;
}

public class TaskInstance {
    private Long id;
    private String taskDefinitionId;
    private String orchestratorInstanceId;
    private TaskStatus status;           // PENDING, RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED
    private String command;              // Resolved command
    private String output;               // Task output (lazy loaded)
    private Integer exitCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage;
    private Long llmSessionId;           // If LLM was invoked
    private Map<String, String> variables;
}

public enum TaskType {
    SHELL,        // Shell command execution
    HTTP,         // HTTP request
    CODE,         // Java code execution (via reflection or scripting)
    LLM_QUERY,    // Direct LLM query
    CUSTOM        // Custom executor implementation
}

public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED
}
```

### 2.4 Workflow & Steps

A **Workflow** is a multi-step orchestration with LLM-driven step proposals.

```java
public class Workflow {
    private Long id;
    private String name;
    private String description;
    private WorkflowStatus status;       // IN_PROGRESS, COMPLETED, FAILED, CANCELLED, WAITING_APPROVAL
    private String initialPrompt;        // Initial context/goal
    private Integer currentStepNumber;
    private Integer completedSteps;
    private Integer maxSteps;            // Safety limit
    private boolean autoAdvance;         // Auto-execute LLM proposals
    private String workingDirectory;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<WorkflowStep> steps;
    private String summary;              // Final summary
}

public class WorkflowStep {
    private Long id;
    private Long workflowId;
    private Integer stepNumber;
    private String description;
    private WorkflowStepStatus status;   // PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    private Long taskInstanceId;         // Associated task (if any)
    private Long llmSessionId;           // LLM analysis session
    private String llmAnalysis;          // LLM's analysis of this step
    private ActionProposal nextAction;   // LLM's proposed next action
    private boolean userApproved;        // User approval (if autoAdvance=false)
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}

public class ActionProposal {
    private ActionType actionType;       // EXECUTE_COMMAND, RUN_TASK, INVOKE_LLM, WAIT, COMPLETE
    private String command;              // Command to execute (if applicable)
    private String taskDefinitionId;     // Task to run (if applicable)
    private String reasoning;            // LLM's reasoning
    private String expectedOutcome;      // Expected result
    private boolean isFinalStep;         // Is this the last step?
    private double confidence;           // LLM confidence (0-1)
    private Map<String, String> variables; // Variables for the action
}

public enum ActionType {
    EXECUTE_COMMAND,  // Run a shell command
    RUN_TASK,         // Execute a defined task
    INVOKE_LLM,       // Ask LLM a follow-up question
    WAIT,             // Wait for external event
    COMPLETE,         // Mark workflow complete
    FAIL              // Mark workflow failed
}
```

---

## 3. State Machine Design

### 3.1 State Machine Architecture

The state machine supports both **static** (enum-based) and **dynamic** (runtime-registered) states.

```java
public interface StateMachineService {
    // State registration
    void registerState(StateDefinition state);
    void registerStates(Collection<StateDefinition> states);
    void unregisterState(String stateId);

    // State queries
    StateDefinition getState(String stateId);
    Set<StateDefinition> getAllStates();
    Set<String> getAllowedTransitions(String fromStateId);

    // State transitions
    void transitionTo(String orchestratorInstanceId, String targetStateId);
    void transitionTo(String orchestratorInstanceId, String targetStateId, Map<String, Object> context);
    boolean canTransitionTo(String orchestratorInstanceId, String targetStateId);

    // State handler management
    void registerHandler(String stateId, StateHandler handler);
    StateHandler getHandler(String stateId);

    // Current state
    String getCurrentStateId(String orchestratorInstanceId);
    StateDefinition getCurrentState(String orchestratorInstanceId);
}
```

### 3.2 State Handler Interface

```java
public interface StateHandler {
    /**
     * Called when entering this state.
     */
    void onEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context);

    /**
     * Main handler logic. Called repeatedly while in this state (for polling states)
     * or once (for action states).
     */
    StateHandlerResult handle(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context);

    /**
     * Called when exiting this state.
     */
    void onExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context);

    /**
     * Whether this handler should be called repeatedly (polling) or once.
     */
    default boolean isPolling() {
        return false;
    }

    /**
     * Polling interval (if isPolling() returns true).
     */
    default Duration getPollingInterval() {
        return Duration.ofSeconds(2);
    }
}

public class StateHandlerResult {
    private boolean complete;            // Handler finished processing
    private String nextStateId;          // Suggested next state (null = stay in current)
    private Map<String, Object> contextUpdates; // Updates to orchestrator context
    private String message;              // Status message
    private boolean error;               // Error occurred
    private String errorMessage;         // Error details
}
```

### 3.3 Default States

The orchestrator provides a set of default states that can be extended:

```java
public enum DefaultState {
    IDLE("idle", StateCategory.INITIAL, "Orchestrator is idle"),
    INITIALIZING("initializing", StateCategory.PROCESSING, "Initializing orchestrator"),
    PROCESSING("processing", StateCategory.PROCESSING, "Processing tasks"),
    WAITING_INPUT("waiting_input", StateCategory.WAITING, "Waiting for user input"),
    WAITING_LLM("waiting_llm", StateCategory.WAITING, "Waiting for LLM response"),
    WAITING_TASK("waiting_task", StateCategory.WAITING, "Waiting for task completion"),
    ANALYZING("analyzing", StateCategory.PROCESSING, "Analyzing results"),
    SUCCESS("success", StateCategory.TERMINAL, "Orchestration completed successfully"),
    FAILED("failed", StateCategory.ERROR, "Orchestration failed"),
    CANCELLED("cancelled", StateCategory.TERMINAL, "Orchestration cancelled");

    // Each provides a default StateDefinition
}
```

### 3.4 State Transition Flow

```
┌─────────┐     start()      ┌──────────────┐
│  IDLE   │ ─────────────────▶│ INITIALIZING │
└─────────┘                   └──────┬───────┘
                                     │ handler complete
                                     ▼
┌──────────────┐             ┌───────────────┐
│WAITING_INPUT │◀────────────│  PROCESSING   │◀─────────┐
└──────┬───────┘  need input └───────┬───────┘          │
       │                             │                   │
       │ input received              │ task started      │
       │                             ▼                   │
       │                     ┌───────────────┐          │
       └────────────────────▶│ WAITING_TASK  │──────────┘
                             └───────┬───────┘ task complete
                                     │
                    ┌────────────────┼────────────────┐
                    │ success        │ error          │ needs LLM
                    ▼                ▼                ▼
             ┌──────────┐     ┌──────────┐    ┌─────────────┐
             │ SUCCESS  │     │  FAILED  │    │ WAITING_LLM │
             └──────────┘     └──────────┘    └──────┬──────┘
                                                     │ LLM complete
                                                     │
                                              (back to PROCESSING)
```

---

## 4. Task Execution System

### 4.1 Task Executor Interface

```java
public interface TaskExecutor {
    /**
     * Task types this executor handles.
     */
    Set<TaskType> getSupportedTypes();

    /**
     * Execute a task synchronously or asynchronously.
     */
    TaskInstance execute(TaskDefinition definition, Map<String, String> variables, TaskExecutionOptions options);

    /**
     * Cancel a running task.
     */
    void cancel(Long taskInstanceId);

    /**
     * Check if task is still running.
     */
    boolean isRunning(Long taskInstanceId);

    /**
     * Get current output (for streaming).
     */
    String getCurrentOutput(Long taskInstanceId);
}

public class TaskExecutionOptions {
    private boolean async;               // Run asynchronously
    private boolean streamOutput;        // Stream output to WebSocket
    private String workingDirectory;     // Override working directory
    private Duration timeout;            // Override timeout
    private Map<String, String> environment; // Environment variables
}
```

### 4.2 Built-in Executors

```
┌────────────────────────────────────────────────────────────────────┐
│                         TaskExecutorRegistry                        │
│                                                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐ │
│  │ShellTaskExecutor│  │HttpTaskExecutor │  │ LlmQueryExecutor    │ │
│  │  (SHELL)        │  │  (HTTP)         │  │  (LLM_QUERY)        │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────────┘ │
│                                                                     │
│  ┌─────────────────┐  ┌─────────────────┐                          │
│  │CodeTaskExecutor │  │CustomExecutor   │                          │
│  │  (CODE)         │  │  (CUSTOM)       │                          │
│  └─────────────────┘  └─────────────────┘                          │
└────────────────────────────────────────────────────────────────────┘
```

### 4.3 Shell Task Executor (Example)

```java
@Component
public class ShellTaskExecutor implements TaskExecutor {
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Override
    public Set<TaskType> getSupportedTypes() {
        return Set.of(TaskType.SHELL);
    }

    @Override
    public TaskInstance execute(TaskDefinition definition, Map<String, String> variables, TaskExecutionOptions options) {
        // 1. Resolve command with variable substitution
        String resolvedCommand = resolveVariables(definition.getCommand(), variables);

        // 2. Create task instance
        TaskInstance instance = createInstance(definition, resolvedCommand, variables);

        // 3. Execute (sync or async)
        if (options.isAsync()) {
            executeAsync(instance, options);
        } else {
            executeSync(instance, options);
        }

        return instance;
    }

    private void executeAsync(TaskInstance instance, TaskExecutionOptions options) {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(instance.getId(), cancelFlag);

        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", instance.getCommand());
                pb.directory(new File(options.getWorkingDirectory()));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                runningProcesses.put(instance.getId(), process);

                instance.setStatus(TaskStatus.RUNNING);
                instance.setStartTime(LocalDateTime.now());
                taskRepository.save(instance);

                // Stream output
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelFlag.get()) {
                            process.destroyForcibly();
                            instance.setStatus(TaskStatus.CANCELLED);
                            break;
                        }
                        output.append(line).append("\n");

                        // Broadcast to WebSocket
                        if (options.isStreamOutput()) {
                            eventPublisher.publishEvent(new TaskOutputEvent(instance.getId(), line));
                        }
                    }
                }

                // Wait for completion
                boolean completed = process.waitFor(
                    options.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    instance.setStatus(TaskStatus.TIMEOUT);
                } else if (instance.getStatus() != TaskStatus.CANCELLED) {
                    int exitCode = process.exitValue();
                    instance.setExitCode(exitCode);
                    instance.setStatus(exitCode == 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED);
                }

                instance.setOutput(output.toString());
                instance.setEndTime(LocalDateTime.now());
                taskRepository.save(instance);

                // Publish completion event
                eventPublisher.publishEvent(new TaskCompletedEvent(instance));

            } catch (Exception e) {
                instance.setStatus(TaskStatus.FAILED);
                instance.setErrorMessage(e.getMessage());
                instance.setEndTime(LocalDateTime.now());
                taskRepository.save(instance);
            } finally {
                runningProcesses.remove(instance.getId());
                cancelFlags.remove(instance.getId());
            }
        });
    }
}
```

---

## 5. Workflow Orchestration

### 5.1 Workflow Service Interface

```java
public interface WorkflowService {
    /**
     * Start a new workflow.
     */
    Workflow startWorkflow(WorkflowStartRequest request);

    /**
     * Advance workflow to next step (with user approval if required).
     */
    void advanceWorkflow(Long workflowId);

    /**
     * Approve a pending step (when autoAdvance=false).
     */
    void approveStep(Long workflowId, Integer stepNumber);

    /**
     * Reject a pending step with feedback.
     */
    void rejectStep(Long workflowId, Integer stepNumber, String feedback);

    /**
     * Cancel a workflow.
     */
    void cancelWorkflow(Long workflowId);

    /**
     * Get workflow status.
     */
    Workflow getWorkflow(Long workflowId);

    /**
     * Get step details.
     */
    WorkflowStep getStep(Long workflowId, Integer stepNumber);
}

public class WorkflowStartRequest {
    private String name;
    private String description;
    private String initialPrompt;        // Goal/context for the workflow
    private String workingDirectory;
    private boolean autoAdvance;         // Auto-execute LLM proposals
    private Integer maxSteps;            // Safety limit (default: 20)
    private String llmProviderId;        // Which LLM to use
    private Map<String, Object> initialContext;
}
```

### 5.2 Workflow Execution Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Workflow Execution                             │
└──────────────────────────────────────────────────────────────────────┘

1. Start Workflow
   │
   ▼
2. Create Initial Step (step 0)
   │
   ▼
3. Execute Step
   ├─▶ Run associated task (if any)
   │   │
   │   ▼
   ├─▶ Collect task output
   │   │
   │   ▼
   └─▶ Send to LLM for analysis
       │
       ▼
4. LLM Analysis
   ├─▶ Analyze step results
   ├─▶ Propose next action (ActionProposal)
   │   │
   │   ├── ActionType.EXECUTE_COMMAND → Create step with command
   │   ├── ActionType.RUN_TASK → Create step with task
   │   ├── ActionType.INVOKE_LLM → Ask follow-up question
   │   ├── ActionType.WAIT → Wait for external event
   │   └── ActionType.COMPLETE → Finish workflow
   │
   ▼
5. Handle Proposal
   │
   ├─▶ If autoAdvance=true:
   │   └─▶ Execute proposed action immediately
   │       └─▶ Go to step 3
   │
   └─▶ If autoAdvance=false:
       └─▶ Wait for user approval
           │
           ├── User approves → Execute action → Go to step 3
           └── User rejects → Go back to LLM with feedback
```

### 5.3 LLM Analysis Prompt Structure

```
## Context
You are helping orchestrate a multi-step workflow.

## Workflow Goal
{initialPrompt}

## Current Step
Step {stepNumber}: {stepDescription}

## Task Output (if any)
```
{taskOutput}
```

## Previous Steps Summary
{previousStepsSummary}

## Instructions
Analyze the current step results and propose the next action.

Respond in JSON format:
{
  "analysis": "Your analysis of the current step results",
  "actionType": "EXECUTE_COMMAND|RUN_TASK|INVOKE_LLM|WAIT|COMPLETE",
  "command": "Shell command to execute (if EXECUTE_COMMAND)",
  "taskDefinitionId": "Task ID to run (if RUN_TASK)",
  "reasoning": "Why you chose this action",
  "expectedOutcome": "What you expect to happen",
  "isFinalStep": true/false,
  "confidence": 0.0-1.0
}
```

---

## 6. LLM Integration & Triggers

### 6.1 LLM Provider Interface

```java
public interface LlmProvider {
    /**
     * Provider identifier.
     */
    String getId();

    /**
     * Human-readable name.
     */
    String getDisplayName();

    /**
     * Check if provider is available/configured.
     */
    boolean isAvailable();

    /**
     * Start a new LLM session.
     */
    LlmSession startSession(LlmSessionRequest request);

    /**
     * Send a message to an existing session.
     */
    LlmResponse sendMessage(Long sessionId, String message);

    /**
     * Cancel an active session.
     */
    void cancelSession(Long sessionId);

    /**
     * Check if session is still active.
     */
    boolean isSessionActive(Long sessionId);

    /**
     * Stream session output (for real-time display).
     */
    Flux<String> streamOutput(Long sessionId);
}

public class LlmSessionRequest {
    private String prompt;               // Initial prompt
    private String systemPrompt;         // System prompt (optional)
    private Map<String, Object> parameters; // Model-specific parameters
    private Duration timeout;            // Session timeout
    private String workingDirectory;     // For file-aware providers (Claude Code)
    private boolean fileAccess;          // Allow file system access
}

public class LlmSession {
    private Long id;
    private String providerId;
    private LlmSessionStatus status;     // STARTING, RUNNING, COMPLETED, FAILED, TIMEOUT, CANCELLED
    private String initialPrompt;
    private String output;               // Full output
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long processId;              // For subprocess-based providers
    private String errorMessage;
    private Map<String, Object> metadata;
}
```

### 6.2 LLM Trigger System

LLM triggers define when and how to invoke an LLM automatically.

```java
public class LlmTrigger {
    private String triggerId;
    private String name;
    private LlmTriggerType triggerType;
    private String targetStateId;        // For state-based triggers
    private String patternMatch;         // For pattern-based triggers (regex)
    private String promptTemplate;       // Prompt template with ${variable} support
    private String llmProviderId;        // Which provider to use
    private boolean autoExecuteProposal; // Auto-execute LLM's proposed action
    private boolean enabled;
    private int priority;                // For ordering multiple triggers
    private Map<String, Object> config;
}

public enum LlmTriggerType {
    ON_STATE_ENTER,      // Triggered when entering a specific state
    ON_STATE_EXIT,       // Triggered when exiting a specific state
    ON_TASK_ERROR,       // Triggered when any task fails
    ON_TASK_TIMEOUT,     // Triggered when any task times out
    ON_PATTERN_MATCH,    // Triggered when pattern found in task output
    ON_TASK_COMPLETE,    // Triggered when specific task completes
    ON_WORKFLOW_STEP,    // Triggered at each workflow step
    ON_ERROR_REPEATED,   // Triggered when same error occurs N times
    ON_SCHEDULE,         // Triggered on schedule (cron)
    MANUAL               // Only triggered via API
}
```

### 6.3 Trigger Evaluation

```java
@Component
public class LlmTriggerEvaluator {

    @EventListener
    public void onStateChange(StateChangeEvent event) {
        List<LlmTrigger> triggers = triggerRepository.findByTypeAndTarget(
            LlmTriggerType.ON_STATE_ENTER, event.getNewStateId());

        for (LlmTrigger trigger : triggers) {
            if (trigger.isEnabled()) {
                invokeLlm(trigger, buildContext(event));
            }
        }
    }

    @EventListener
    public void onTaskComplete(TaskCompletedEvent event) {
        TaskInstance task = event.getTaskInstance();

        // Check for error triggers
        if (task.getStatus() == TaskStatus.FAILED) {
            List<LlmTrigger> errorTriggers = triggerRepository.findByType(
                LlmTriggerType.ON_TASK_ERROR);
            errorTriggers.forEach(t -> invokeLlm(t, buildContext(event)));
        }

        // Check for pattern match triggers
        List<LlmTrigger> patternTriggers = triggerRepository.findByType(
            LlmTriggerType.ON_PATTERN_MATCH);

        for (LlmTrigger trigger : patternTriggers) {
            if (Pattern.compile(trigger.getPatternMatch())
                    .matcher(task.getOutput()).find()) {
                invokeLlm(trigger, buildContext(event));
            }
        }
    }

    private void invokeLlm(LlmTrigger trigger, Map<String, Object> context) {
        String prompt = resolvePromptTemplate(trigger.getPromptTemplate(), context);

        LlmSessionRequest request = LlmSessionRequest.builder()
            .prompt(prompt)
            .build();

        LlmProvider provider = providerRegistry.getProvider(trigger.getLlmProviderId());
        LlmSession session = provider.startSession(request);

        // Store session reference
        sessionRepository.save(session);

        // Handle response
        if (trigger.isAutoExecuteProposal()) {
            // Parse and execute LLM's proposed action
            executeProposal(session, trigger, context);
        }
    }
}
```

### 6.4 Built-in LLM Providers

```
┌────────────────────────────────────────────────────────────────────┐
│                      LlmProviderRegistry                           │
│                                                                    │
│  ┌─────────────────────┐  ┌─────────────────────────────────────┐ │
│  │  ClaudeCodeProvider │  │     SpringAiProvider                │ │
│  │  (Claude CLI)       │  │  (OpenAI, Anthropic API, Gemini)    │ │
│  └─────────────────────┘  └─────────────────────────────────────┘ │
│                                                                    │
│  ┌─────────────────────┐  ┌─────────────────────────────────────┐ │
│  │  OllamaProvider     │  │     CustomLlmProvider               │ │
│  │  (Local Ollama)     │  │  (User-defined)                     │ │
│  └─────────────────────┘  └─────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

---

## 7. Event System

### 7.1 Event Hierarchy

```java
public abstract class OrchestratorEvent extends ApplicationEvent {
    private final String eventId;
    private final LocalDateTime timestamp;
    private final String orchestratorInstanceId;
    private final OrchestratorEventType eventType;
}

public enum OrchestratorEventType {
    // Orchestrator lifecycle
    ORCHESTRATOR_CREATED,
    ORCHESTRATOR_STARTED,
    ORCHESTRATOR_PAUSED,
    ORCHESTRATOR_RESUMED,
    ORCHESTRATOR_COMPLETED,
    ORCHESTRATOR_FAILED,

    // State machine
    STATE_ENTERING,
    STATE_ENTERED,
    STATE_EXITING,
    STATE_EXITED,
    STATE_CHANGED,

    // Tasks
    TASK_CREATED,
    TASK_STARTED,
    TASK_OUTPUT,          // Real-time output line
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
    TASK_TIMEOUT,

    // Workflows
    WORKFLOW_STARTED,
    WORKFLOW_STEP_STARTED,
    WORKFLOW_STEP_COMPLETED,
    WORKFLOW_WAITING_APPROVAL,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    WORKFLOW_CANCELLED,

    // LLM
    LLM_SESSION_STARTED,
    LLM_SESSION_OUTPUT,   // Real-time output
    LLM_SESSION_COMPLETED,
    LLM_SESSION_FAILED,
    LLM_TRIGGER_FIRED,
    LLM_ACTION_PROPOSED,

    // Recovery
    SNAPSHOT_CREATED,
    RECOVERY_STARTED,
    RECOVERY_COMPLETED
}
```

### 7.2 Event Classes

```java
// State change event
public class StateChangeEvent extends OrchestratorEvent {
    private final String previousStateId;
    private final String newStateId;
    private final Map<String, Object> context;
}

// Task events
public class TaskEvent extends OrchestratorEvent {
    private final Long taskInstanceId;
    private final String taskDefinitionId;
    private final TaskStatus status;
}

public class TaskOutputEvent extends OrchestratorEvent {
    private final Long taskInstanceId;
    private final String outputLine;
}

// Workflow events
public class WorkflowEvent extends OrchestratorEvent {
    private final Long workflowId;
    private final WorkflowStatus status;
    private final Integer currentStep;
}

// LLM events
public class LlmSessionEvent extends OrchestratorEvent {
    private final Long sessionId;
    private final String providerId;
    private final LlmSessionStatus status;
}

public class LlmTriggerEvent extends OrchestratorEvent {
    private final String triggerId;
    private final LlmTriggerType triggerType;
    private final Map<String, Object> triggerContext;
}
```

### 7.3 WebSocket Broadcasting

```java
@Service
public class WebSocketBroadcastService {
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onOrchestratorEvent(OrchestratorEvent event) {
        // Broadcast to instance-specific topic
        String topic = "/topic/orchestrator/" + event.getOrchestratorInstanceId();
        messagingTemplate.convertAndSend(topic, event);

        // Broadcast to global topic for monitoring
        messagingTemplate.convertAndSend("/topic/orchestrator/events", event);
    }

    @EventListener
    public void onTaskOutput(TaskOutputEvent event) {
        // Stream task output
        String topic = "/topic/task/" + event.getTaskInstanceId() + "/output";
        messagingTemplate.convertAndSend(topic, event.getOutputLine());
    }

    @EventListener
    public void onLlmOutput(LlmOutputEvent event) {
        // Stream LLM output
        String topic = "/topic/llm/" + event.getSessionId() + "/output";
        messagingTemplate.convertAndSend(topic, event.getOutputLine());
    }
}
```

### 7.4 WebSocket Topics

| Topic | Description |
|-------|-------------|
| `/topic/orchestrator/{instanceId}` | All events for specific instance |
| `/topic/orchestrator/events` | Global event stream |
| `/topic/task/{taskId}/output` | Real-time task output |
| `/topic/llm/{sessionId}/output` | Real-time LLM output |
| `/topic/workflow/{workflowId}` | Workflow events |

---

## 8. Persistence & Recovery

### 8.1 State Snapshot

```java
@Entity
@Table(name = "orchestrator_snapshots")
public class OrchestratorSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "orchestrator_instance_id", nullable = false)
    private String orchestratorInstanceId;

    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    @Column(name = "current_state_id", nullable = false)
    private String currentStateId;

    @Column(name = "previous_state_id")
    private String previousStateId;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;  // Serialized context map

    @Column(name = "running_task_ids")
    private String runningTaskIds;  // Comma-separated

    @Column(name = "active_workflow_id")
    private Long activeWorkflowId;

    @Column(name = "active_llm_session_id")
    private Long activeLlmSessionId;

    @Column(name = "is_active")
    private boolean active;  // Is this the latest snapshot?

    @Column(name = "version")
    private Integer version;  // For optimistic locking
}
```

### 8.2 Snapshot Service

```java
@Service
public class SnapshotService {
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(30);

    @Scheduled(fixedRate = 30000)
    public void periodicSnapshot() {
        // Create snapshots for all running orchestrators
        orchestratorService.getRunningInstances().forEach(this::createSnapshot);
    }

    @EventListener
    public void onStateChange(StateChangeEvent event) {
        // Always snapshot on state change
        createSnapshot(event.getOrchestratorInstanceId());
    }

    @Transactional
    public void createSnapshot(String orchestratorInstanceId) {
        OrchestratorInstance instance = orchestratorService.getInstance(orchestratorInstanceId);

        // Mark previous snapshots as inactive
        snapshotRepository.deactivateSnapshots(orchestratorInstanceId);

        // Create new snapshot
        OrchestratorSnapshot snapshot = OrchestratorSnapshot.builder()
            .orchestratorInstanceId(orchestratorInstanceId)
            .snapshotTime(LocalDateTime.now())
            .currentStateId(instance.getCurrentStateId())
            .contextJson(objectMapper.writeValueAsString(instance.getContext()))
            .runningTaskIds(getRunningTaskIds(orchestratorInstanceId))
            .activeWorkflowId(getActiveWorkflowId(orchestratorInstanceId))
            .activeLlmSessionId(getActiveLlmSessionId(orchestratorInstanceId))
            .active(true)
            .build();

        snapshotRepository.save(snapshot);

        eventPublisher.publishEvent(new SnapshotCreatedEvent(snapshot));
    }
}
```

### 8.3 Recovery Service

```java
@Service
public class RecoveryService {

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        performRecovery();
    }

    @Transactional
    public void performRecovery() {
        log.info("Starting orchestrator recovery check...");

        List<OrchestratorSnapshot> activeSnapshots = snapshotRepository.findAllActive();

        for (OrchestratorSnapshot snapshot : activeSnapshots) {
            try {
                recoverOrchestrator(snapshot);
            } catch (Exception e) {
                log.error("Failed to recover orchestrator: {}", snapshot.getOrchestratorInstanceId(), e);
            }
        }

        log.info("Recovery complete. Recovered {} orchestrators.", activeSnapshots.size());
    }

    private void recoverOrchestrator(OrchestratorSnapshot snapshot) {
        log.info("Recovering orchestrator: {} from state: {}",
            snapshot.getOrchestratorInstanceId(), snapshot.getCurrentStateId());

        // 1. Recreate instance
        OrchestratorInstance instance = OrchestratorInstance.builder()
            .instanceId(snapshot.getOrchestratorInstanceId())
            .currentStateId(snapshot.getCurrentStateId())
            .context(objectMapper.readValue(snapshot.getContextJson(), Map.class))
            .status(OrchestratorStatus.RUNNING)
            .build();

        // 2. Register with state machine
        stateMachineService.registerInstance(instance);

        // 3. Recover running tasks
        if (snapshot.getRunningTaskIds() != null) {
            Arrays.stream(snapshot.getRunningTaskIds().split(","))
                .map(Long::parseLong)
                .forEach(taskId -> {
                    TaskInstance task = taskRepository.findById(taskId).orElse(null);
                    if (task != null && task.getStatus() == TaskStatus.RUNNING) {
                        // Mark as failed (was interrupted)
                        task.setStatus(TaskStatus.FAILED);
                        task.setErrorMessage("Interrupted by system restart");
                        taskRepository.save(task);
                    }
                });
        }

        // 4. Recover workflow
        if (snapshot.getActiveWorkflowId() != null) {
            Workflow workflow = workflowRepository.findById(snapshot.getActiveWorkflowId()).orElse(null);
            if (workflow != null && workflow.getStatus() == WorkflowStatus.IN_PROGRESS) {
                // Resume workflow
                workflowService.resumeWorkflow(workflow.getId());
            }
        }

        // 5. Resume orchestrator
        orchestratorService.resume(instance.getInstanceId());

        eventPublisher.publishEvent(new RecoveryCompletedEvent(snapshot));
    }
}
```

---

## 9. API Design

### 9.1 REST Endpoints

```
Orchestrator Management
-----------------------
POST   /api/orchestrator                     Create new orchestrator instance
GET    /api/orchestrator                     List all instances
GET    /api/orchestrator/{id}                Get instance details
POST   /api/orchestrator/{id}/start          Start orchestrator
POST   /api/orchestrator/{id}/pause          Pause orchestrator
POST   /api/orchestrator/{id}/resume         Resume orchestrator
POST   /api/orchestrator/{id}/stop           Stop orchestrator
DELETE /api/orchestrator/{id}                Delete instance

State Management
----------------
GET    /api/orchestrator/{id}/state          Get current state
POST   /api/orchestrator/{id}/state          Force state transition
GET    /api/orchestrator/{id}/states         Get all registered states
POST   /api/orchestrator/{id}/states         Register new state
GET    /api/orchestrator/{id}/transitions    Get allowed transitions

Task Management
---------------
POST   /api/orchestrator/{id}/tasks          Execute a task
GET    /api/orchestrator/{id}/tasks          List tasks
GET    /api/orchestrator/{id}/tasks/{taskId} Get task details
POST   /api/orchestrator/{id}/tasks/{taskId}/cancel Cancel task
GET    /api/tasks/definitions                List task definitions
POST   /api/tasks/definitions                Create task definition

Workflow Management
-------------------
POST   /api/orchestrator/{id}/workflows      Start workflow
GET    /api/orchestrator/{id}/workflows      List workflows
GET    /api/orchestrator/{id}/workflows/{wfId} Get workflow details
POST   /api/orchestrator/{id}/workflows/{wfId}/advance Advance workflow
POST   /api/orchestrator/{id}/workflows/{wfId}/approve Approve step
POST   /api/orchestrator/{id}/workflows/{wfId}/reject  Reject step
POST   /api/orchestrator/{id}/workflows/{wfId}/cancel  Cancel workflow

LLM Management
--------------
POST   /api/orchestrator/{id}/llm/invoke     Invoke LLM manually
GET    /api/orchestrator/{id}/llm/sessions   List LLM sessions
GET    /api/orchestrator/{id}/llm/sessions/{sessionId} Get session details
POST   /api/orchestrator/{id}/llm/sessions/{sessionId}/cancel Cancel session
GET    /api/llm/providers                    List available providers
GET    /api/llm/triggers                     List triggers
POST   /api/llm/triggers                     Create trigger
PUT    /api/llm/triggers/{triggerId}         Update trigger
DELETE /api/llm/triggers/{triggerId}         Delete trigger

Monitoring
----------
GET    /api/orchestrator/{id}/events         Get event history
GET    /api/orchestrator/{id}/snapshots      List snapshots
POST   /api/orchestrator/{id}/recover        Force recovery from snapshot
```

### 9.2 WebSocket Endpoints

```
/ws/orchestrator                             Main WebSocket endpoint

Subscribe to:
- /topic/orchestrator/{instanceId}           Instance-specific events
- /topic/orchestrator/events                 Global events
- /topic/task/{taskId}/output                Task output stream
- /topic/llm/{sessionId}/output              LLM output stream
- /topic/workflow/{workflowId}               Workflow events
```

### 9.3 DTO Examples

```java
// Create orchestrator request
public class CreateOrchestratorRequest {
    private String name;
    private String description;
    private List<StateDefinition> customStates;
    private List<TaskDefinition> taskDefinitions;
    private List<LlmTrigger> llmTriggers;
    private Map<String, Object> initialContext;
    private OrchestratorConfig config;
}

// Execute task request
public class ExecuteTaskRequest {
    private String taskDefinitionId;
    private Map<String, String> variables;
    private boolean async;
    private boolean streamOutput;
}

// Start workflow request
public class StartWorkflowRequest {
    private String name;
    private String description;
    private String initialPrompt;
    private boolean autoAdvance;
    private Integer maxSteps;
    private String llmProviderId;
}

// Force state transition request
public class TransitionStateRequest {
    private String targetStateId;
    private Map<String, Object> context;
    private boolean force;  // Skip validation
}
```

---

## 10. Integration Points

### 10.1 kompile-app-core Integration

The orchestrator integrates with existing core interfaces:

```java
// Use existing LLM interfaces
@Autowired
private LanguageModel languageModel;

// Use existing RAG service for context-aware LLM calls
@Autowired
private RagService ragService;

// Use existing document retriever for fetching context
@Autowired
private DocumentRetriever documentRetriever;

// Use existing MCP tool system
@Autowired
private McpToolDefinition mcpToolDefinition;
```

### 10.2 Spring AI Integration

```java
@Component
public class SpringAiLlmProvider implements LlmProvider {

    @Autowired
    private ChatModel chatModel;  // From Spring AI

    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        Prompt prompt = new Prompt(request.getPrompt());
        ChatResponse response = chatModel.call(prompt);

        return LlmSession.builder()
            .output(response.getResult().getOutput().getContent())
            .status(LlmSessionStatus.COMPLETED)
            .build();
    }
}
```

### 10.3 MCP Bridge Integration

```java
@Component
public class McpOrchestratorBridge {

    @EventListener
    public void onOrchestratorEvent(OrchestratorEvent event) {
        // Convert to MCP notification
        mcpNotificationService.notify(convertToMcpEvent(event));
    }

    // Expose orchestrator as MCP tool
    @McpTool(name = "orchestrator_execute_task")
    public TaskInstance executeTask(String taskDefinitionId, Map<String, String> variables) {
        return orchestratorService.executeTask(taskDefinitionId, variables);
    }

    @McpTool(name = "orchestrator_start_workflow")
    public Workflow startWorkflow(String name, String initialPrompt) {
        return workflowService.startWorkflow(WorkflowStartRequest.builder()
            .name(name)
            .initialPrompt(initialPrompt)
            .build());
    }
}
```

---

## 11. Package Structure

```
kompile-app/kompile-orchestrator/
├── pom.xml
└── src/main/java/ai/kompile/orchestrator/
    ├── OrchestratorAutoConfiguration.java
    ├── EnableOrchestrator.java              # @Enable annotation
    │
    ├── api/                                 # Public interfaces
    │   ├── OrchestratorService.java
    │   ├── StateMachineService.java
    │   ├── TaskExecutionService.java
    │   ├── WorkflowService.java
    │   ├── LlmIntegrationService.java
    │   ├── StateHandler.java
    │   ├── TaskExecutor.java
    │   └── LlmProvider.java
    │
    ├── config/                              # Configuration
    │   ├── OrchestratorProperties.java
    │   ├── WebSocketConfig.java
    │   └── OrchestratorConfig.java
    │
    ├── model/                               # Domain models
    │   ├── OrchestratorInstance.java
    │   ├── OrchestratorStatus.java
    │   │
    │   ├── state/
    │   │   ├── StateDefinition.java
    │   │   ├── StateCategory.java
    │   │   ├── DefaultState.java
    │   │   └── StateHandlerResult.java
    │   │
    │   ├── task/
    │   │   ├── TaskDefinition.java
    │   │   ├── TaskInstance.java
    │   │   ├── TaskType.java
    │   │   ├── TaskStatus.java
    │   │   └── TaskExecutionOptions.java
    │   │
    │   ├── workflow/
    │   │   ├── Workflow.java
    │   │   ├── WorkflowStep.java
    │   │   ├── WorkflowStatus.java
    │   │   ├── WorkflowStepStatus.java
    │   │   └── ActionProposal.java
    │   │
    │   ├── llm/
    │   │   ├── LlmSession.java
    │   │   ├── LlmSessionStatus.java
    │   │   ├── LlmSessionRequest.java
    │   │   ├── LlmTrigger.java
    │   │   └── LlmTriggerType.java
    │   │
    │   ├── snapshot/
    │   │   └── OrchestratorSnapshot.java
    │   │
    │   └── event/
    │       ├── OrchestratorEvent.java
    │       ├── OrchestratorEventType.java
    │       ├── StateChangeEvent.java
    │       ├── TaskEvent.java
    │       ├── TaskOutputEvent.java
    │       ├── WorkflowEvent.java
    │       ├── LlmSessionEvent.java
    │       └── LlmTriggerEvent.java
    │
    ├── service/                             # Service implementations
    │   ├── impl/
    │   │   ├── DefaultOrchestratorService.java
    │   │   ├── DefaultStateMachineService.java
    │   │   ├── DefaultTaskExecutionService.java
    │   │   ├── DefaultWorkflowService.java
    │   │   ├── DefaultLlmIntegrationService.java
    │   │   ├── EventBroadcastService.java
    │   │   ├── SnapshotService.java
    │   │   └── RecoveryService.java
    │   │
    │   └── registry/
    │       ├── StateRegistry.java
    │       ├── TaskDefinitionRegistry.java
    │       ├── TaskExecutorRegistry.java
    │       └── LlmProviderRegistry.java
    │
    ├── handler/                             # State handlers
    │   ├── AbstractStateHandler.java
    │   └── defaults/
    │       ├── IdleStateHandler.java
    │       ├── ProcessingStateHandler.java
    │       ├── WaitingTaskStateHandler.java
    │       └── WaitingLlmStateHandler.java
    │
    ├── executor/                            # Task executors
    │   ├── AbstractTaskExecutor.java
    │   ├── ShellTaskExecutor.java
    │   ├── HttpTaskExecutor.java
    │   ├── CodeTaskExecutor.java
    │   └── LlmQueryTaskExecutor.java
    │
    ├── llm/                                 # LLM providers
    │   ├── AbstractLlmProvider.java
    │   ├── ClaudeCodeProvider.java
    │   ├── SpringAiLlmProvider.java
    │   └── LlmTriggerEvaluator.java
    │
    ├── repository/                          # Data access
    │   ├── OrchestratorInstanceRepository.java
    │   ├── TaskInstanceRepository.java
    │   ├── WorkflowRepository.java
    │   ├── WorkflowStepRepository.java
    │   ├── LlmSessionRepository.java
    │   ├── LlmTriggerRepository.java
    │   └── OrchestratorSnapshotRepository.java
    │
    ├── web/                                 # Controllers
    │   ├── OrchestratorController.java
    │   ├── TaskController.java
    │   ├── WorkflowController.java
    │   ├── LlmController.java
    │   ├── MonitoringController.java
    │   └── dto/
    │       ├── CreateOrchestratorRequest.java
    │       ├── ExecuteTaskRequest.java
    │       ├── StartWorkflowRequest.java
    │       ├── TransitionStateRequest.java
    │       └── ... (other DTOs)
    │
    └── ws/                                  # WebSocket handlers
        ├── OrchestratorWebSocketHandler.java
        └── OrchestratorWebSocketConfig.java
```

---

## 12. Configuration

### 12.1 Application Properties

```properties
# Orchestrator Configuration
kompile.orchestrator.enabled=true
kompile.orchestrator.snapshot-interval=30s
kompile.orchestrator.recovery-on-startup=true

# Default timeouts
kompile.orchestrator.default-task-timeout=5m
kompile.orchestrator.default-state-timeout=30m
kompile.orchestrator.default-llm-timeout=10m

# Task execution
kompile.orchestrator.task.max-concurrent=10
kompile.orchestrator.task.output-buffer-size=10MB
kompile.orchestrator.task.stream-output=true

# Workflow
kompile.orchestrator.workflow.max-steps=50
kompile.orchestrator.workflow.auto-advance=false

# LLM Integration
kompile.orchestrator.llm.default-provider=spring-ai
kompile.orchestrator.llm.providers.claude-code.enabled=true
kompile.orchestrator.llm.providers.claude-code.command=claude
kompile.orchestrator.llm.providers.claude-code.skip-permissions=true
kompile.orchestrator.llm.providers.spring-ai.enabled=true

# WebSocket
kompile.orchestrator.websocket.enabled=true
kompile.orchestrator.websocket.heartbeat-interval=30s
```

### 12.2 Programmatic Configuration

```java
@Configuration
@EnableOrchestrator
public class MyOrchestratorConfig {

    @Bean
    public OrchestratorCustomizer orchestratorCustomizer() {
        return orchestrator -> {
            // Register custom states
            orchestrator.registerState(StateDefinition.builder()
                .stateId("my-custom-state")
                .name("My Custom State")
                .category(StateCategory.PROCESSING)
                .allowedNextStates(Set.of("success", "failed"))
                .handlerClassName(MyCustomStateHandler.class.getName())
                .build());

            // Register custom task executor
            orchestrator.registerTaskExecutor(new MyCustomTaskExecutor());

            // Register LLM trigger
            orchestrator.registerLlmTrigger(LlmTrigger.builder()
                .triggerId("error-fixer")
                .name("Auto-fix Errors")
                .triggerType(LlmTriggerType.ON_TASK_ERROR)
                .promptTemplate("Fix this error: ${errorMessage}")
                .llmProviderId("claude-code")
                .autoExecuteProposal(true)
                .build());
        };
    }

    @Bean
    public StateHandler myCustomStateHandler() {
        return new MyCustomStateHandler();
    }
}
```

---

## 13. Example Use Cases

### 13.1 Document Processing Pipeline

```java
// Define states
List<StateDefinition> states = List.of(
    StateDefinition.builder()
        .stateId("loading-documents")
        .name("Loading Documents")
        .category(StateCategory.PROCESSING)
        .allowedNextStates(Set.of("chunking", "failed"))
        .build(),
    StateDefinition.builder()
        .stateId("chunking")
        .name("Chunking Documents")
        .category(StateCategory.PROCESSING)
        .allowedNextStates(Set.of("embedding", "failed"))
        .build(),
    StateDefinition.builder()
        .stateId("embedding")
        .name("Generating Embeddings")
        .category(StateCategory.PROCESSING)
        .allowedNextStates(Set.of("indexing", "failed"))
        .llmTrigger(LlmTriggerConfig.builder()
            .onError(true)
            .promptTemplate("Embedding failed: ${errorMessage}. Suggest a fix.")
            .build())
        .build(),
    StateDefinition.builder()
        .stateId("indexing")
        .name("Indexing to Vector Store")
        .category(StateCategory.PROCESSING)
        .allowedNextStates(Set.of("success", "failed"))
        .build()
);

// Create orchestrator
CreateOrchestratorRequest request = CreateOrchestratorRequest.builder()
    .name("Document Processing Pipeline")
    .customStates(states)
    .initialContext(Map.of(
        "sourcePath", "/data/documents",
        "indexName", "my-index"
    ))
    .build();

OrchestratorInstance instance = orchestratorService.create(request);
orchestratorService.start(instance.getInstanceId());
```

### 13.2 Automated Code Review Workflow

```java
// Start a workflow that reviews code changes
Workflow workflow = workflowService.startWorkflow(WorkflowStartRequest.builder()
    .name("Code Review Workflow")
    .initialPrompt("""
        Review the code changes in the current git diff.
        1. Check for security vulnerabilities
        2. Check for code style issues
        3. Check for potential bugs
        4. Suggest improvements

        For each issue found, propose a fix.
        """)
    .autoAdvance(false)  // Require human approval
    .maxSteps(10)
    .llmProviderId("claude-code")
    .build());
```

### 13.3 Build & Test Orchestration

```java
// Define task definitions
TaskDefinition buildTask = TaskDefinition.builder()
    .taskId("maven-build")
    .name("Maven Build")
    .taskType(TaskType.SHELL)
    .command("mvn clean install -DskipTests")
    .timeout(Duration.ofMinutes(30))
    .autoInvokeLlmOnError(true)
    .build();

TaskDefinition testTask = TaskDefinition.builder()
    .taskId("maven-test")
    .name("Maven Tests")
    .taskType(TaskType.SHELL)
    .command("mvn test")
    .timeout(Duration.ofMinutes(15))
    .autoInvokeLlmOnError(true)
    .build();

// Define LLM trigger for test failures
LlmTrigger testFailureTrigger = LlmTrigger.builder()
    .triggerId("test-failure-fixer")
    .name("Auto-fix Test Failures")
    .triggerType(LlmTriggerType.ON_PATTERN_MATCH)
    .patternMatch("FAILURE|ERROR.*Test")
    .promptTemplate("""
        Test failed. Output:
        ${taskOutput}

        Analyze the failure and fix the test or the code being tested.
        """)
    .llmProviderId("claude-code")
    .autoExecuteProposal(false)  // Require approval
    .build();

// Create and start orchestrator
orchestratorService.create(CreateOrchestratorRequest.builder()
    .name("Build & Test Pipeline")
    .taskDefinitions(List.of(buildTask, testTask))
    .llmTriggers(List.of(testFailureTrigger))
    .build());
```

---

## Conclusion

This design provides a flexible, extensible state machine task orchestrator that:

1. **Supports custom states** - Define any state machine topology
2. **Integrates LLMs at any point** - Configurable triggers for automatic LLM interference
3. **Handles complex workflows** - Multi-step orchestration with LLM-driven proposals
4. **Provides recovery** - State snapshots enable crash recovery
5. **Is event-driven** - Real-time monitoring via WebSocket
6. **Follows existing patterns** - Integrates with kompile-app-core interfaces

The architecture mirrors the build-orchestrator's proven patterns while being completely domain-agnostic, suitable for document processing, code review, build automation, or any other orchestration scenario.
