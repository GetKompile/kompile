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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs subagents directly using the configured LLM API (no kompile-app server needed).
 * Creates a fresh DirectLlmClient for each subagent invocation with its own conversation
 * history, ensuring context isolation between the parent and subagent.
 *
 * Each subagent runs a complete agentic loop: send prompt → receive response →
 * execute tool calls → send results → repeat until text-only response.
 */
public class DirectSubagentRunner implements SubagentRunner {

    private final ChatConfig chatConfig;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final TerminalRenderer renderer;
    private volatile LifecycleListener lifecycleListener;
    private volatile ReminderManager reminderManager;
    private final Map<String, DirectSession> sessions = new ConcurrentHashMap<>();
    private static final int MAX_RETAINED_SESSIONS = 16;

    private static final class DirectSession {
        private final String id;
        private final AgentConfig agent;
        private final ToolContext parentContext;
        private final DirectLlmClient client;
        private final String systemPrompt;
        private final String modelOverride;
        private final ConcurrentLinkedQueue<String> followUps = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean cancelled;
        private final AtomicBoolean terminalClaimed = new AtomicBoolean(false);
        private volatile Thread ownerThread;
        private volatile ToolContext activeToolContext;
        private volatile long lastTouched = System.currentTimeMillis();

        private DirectSession(String id, AgentConfig agent, ToolContext parentContext,
                              DirectLlmClient client, String systemPrompt,
                              String modelOverride, AtomicBoolean cancelled) {
            this.id = id;
            this.agent = agent;
            this.parentContext = parentContext;
            this.client = client;
            this.systemPrompt = systemPrompt;
            this.modelOverride = modelOverride;
            this.cancelled = cancelled;
        }
    }

    public DirectSubagentRunner(ChatConfig chatConfig, ObjectMapper objectMapper,
                                 ToolRegistry toolRegistry, PermissionService permissionService,
                                 TerminalRenderer renderer) {
        this.chatConfig = chatConfig;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.renderer = renderer;
    }

    @Override
    public void setLifecycleListener(LifecycleListener listener) {
        this.lifecycleListener = listener;
    }

    @Override
    public void setReminderManager(ReminderManager reminderManager) {
        this.reminderManager = reminderManager;
    }

    @Override
    public String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext) throws Exception {
        long startTime = System.currentTimeMillis();
        String subagentId = agent.getName() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        // Create an isolated, retained LLM history for this subagent while
        // sharing the parent turn's cancellation signal. Retention is what lets
        // the selected subagent accept later follow-up messages.
        DirectLlmClient subClient = new DirectLlmClient(chatConfig, objectMapper);
        AtomicBoolean subagentCancelled = new AtomicBoolean(false);
        subClient.setCancellationCheck(
                () -> subagentCancelled.get() || parentContext.isAborted());
        String systemPrompt = agent.getSystemPrompt();
        if (systemPrompt == null) systemPrompt = "";
        String projectPrompt = ProjectChatContext.load(parentContext.getWorkingDirectory())
                .renderSystemPrompt();
        if (!projectPrompt.isBlank()) {
            systemPrompt = systemPrompt.isBlank()
                    ? projectPrompt : systemPrompt.strip() + "\n\n" + projectPrompt;
        }
        DirectSession session = new DirectSession(
                subagentId, agent, parentContext, subClient,
                systemPrompt, agent.getModelOverride(), subagentCancelled);
        if (lifecycleListener != null) {
            subClient.setOutputConsumer(chunk -> emitOutput(subagentId, chunk));
        }
        sessions.put(subagentId, session);
        trimSessions();
        session.running.set(true);
        session.ownerThread = Thread.currentThread();
        // Publish the row only after its cancellation handle is registered.
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentStart(subagentId, agent.getName(), StringUtils.truncate(prompt, 60));
        }
        emitActivity(subagentId, "starting",
                renderer.renderSubagentStart(agent.getName(), StringUtils.truncate(prompt, 80)),
                parentContext);
        notifyStatus(subagentId, "starting");
        try {
            return runConversation(session, prompt, startTime);
        } catch (Exception e) {
            notifyStatus(subagentId, "failed · " + e.getClass().getSimpleName());
            emitActivity(subagentId, "failed · " + e.getClass().getSimpleName(),
                    renderer.renderSubagentError(agent.getName(), e.getMessage()), parentContext);
            throw e;
        } finally {
            finishRun(session);
        }
    }

    private String runConversation(DirectSession session, String prompt, long startTime) throws Exception {
        StringBuilder fullResponse = new StringBuilder();

        String currentMessage = prompt;
        List<DirectLlmClient.ToolCallResultInput> pendingToolResults = null;

        // Direct subagents run until they finish or their shared abort signal
        // fires. Chat does not impose an arbitrary execution-limit cutoff.
        while (true) {
            if (session.cancelled.get() || session.parentContext.isAborted()) {
                notifyStatus(session.id, "aborted");
                emitActivity(session.id, "aborted",
                        renderer.renderSubagentError(session.agent.getName(), "Aborted"),
                        session.parentContext);
                return fullResponse + "\n[Subagent aborted]";
            }

            notifyStatus(session.id, "thinking");

            // Rebuild on every iteration so an activate_tools call immediately exposes
            // its selected capability group to the subagent's next request.
            ArrayNode toolDefinitions = directToolDefinitionsFor(session.agent);
            String outboundMessage = reminderManager == null
                    ? currentMessage : reminderManager.prependTo(currentMessage);
            DirectLlmClient.StreamResult result = session.client.streamChat(
                    outboundMessage, session.systemPrompt, toolDefinitions,
                    pendingToolResults, session.modelOverride);
            if (result.cancelled || session.cancelled.get()
                    || session.parentContext.isAborted()) {
                notifyStatus(session.id, "aborted");
                emitActivity(session.id, "aborted",
                        renderer.renderSubagentError(session.agent.getName(), "Aborted"),
                        session.parentContext);
                return fullResponse + "\n[Subagent aborted]";
            }

            if (result.text != null && !result.text.isEmpty()) {
                if (fullResponse.length() > 0) fullResponse.append('\n');
                fullResponse.append(result.text);
                // Streaming chunks are already retained through onSubagentOutput.
                emitActivity(session.id, "responding",
                        "", session.parentContext);
            }

            if (result.toolCalls.isEmpty()) {
                String followUp = session.followUps.poll();
                if (followUp == null) break;
                currentMessage = followUp;
                pendingToolResults = null;
                continue;
            }

            List<DirectLlmClient.ToolCallResultInput> toolResults = new ArrayList<>();
            for (DirectLlmClient.ToolCallOutput tc : result.toolCalls) {
                if (session.cancelled.get() || session.parentContext.isAborted()) {
                    notifyStatus(session.id, "aborted");
                    break;
                }
                String rawInput = tc.arguments == null ? "" : tc.arguments.toString();
                String callSummary = TerminalRenderer.summarizeToolCall(tc.name, rawInput, 88);
                emitActivity(session.id, callSummary + " …",
                        renderer.renderToolCallStart(tc.name, rawInput), session.parentContext);
                CliTool tool = toolRegistry.get(tc.name);
                if (tool == null) {
                    ToolResult missing = ToolResult.error("Unknown tool: " + tc.name);
                    emitActivity(session.id, callSummary + " ✗ unknown tool",
                            renderer.renderSubagentToolCall(tc.name, rawInput, missing),
                            session.parentContext);
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, "Unknown tool: " + tc.name, true));
                    continue;
                }

                List<CliTool> allowed = toolRegistry.getToolsForAgent(session.agent);
                boolean hasAccess = allowed.stream().anyMatch(t -> t.id().equals(tc.name));
                if (!hasAccess) {
                    ToolResult denied = ToolResult.error(
                            "Tool not available to " + session.agent.getName() + ": " + tc.name);
                    emitActivity(session.id, callSummary + " ✗ unavailable",
                            renderer.renderSubagentToolCall(tc.name, rawInput, denied),
                            session.parentContext);
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, "Tool not available to "
                            + session.agent.getName() + ": " + tc.name, true));
                    continue;
                }

                ToolContext subContext = new ToolContext(
                        session.parentContext.getSessionId() + "-sub-" + session.agent.getName(),
                        session.agent,
                        permissionService,
                        session.parentContext.getWorkingDirectory(),
                        toolRegistry
                );
                subContext.linkAbortCheck(
                        () -> session.cancelled.get() || session.parentContext.isAborted());
                subContext.setOutputConsumer(session.parentContext.getOutputConsumer());

                try {
                    session.activeToolContext = subContext;
                    ToolResult toolResult = tool.execute(tc.arguments, subContext);
                    String outcome = TerminalRenderer.summarizeToolResult(toolResult, 72);
                    emitActivity(session.id,
                            callSummary + (toolResult.isError() ? " ✗ " : " ✓ ") + outcome,
                            renderer.renderSubagentToolCall(tc.name, rawInput, toolResult),
                            session.parentContext);

                    String output = toolResult.getOutput();
                    if (output != null && output.length() > 50_000) {
                        output = output.substring(0, 50_000) + "\n... (truncated, " + output.length() + " chars total)";
                    }

                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, output, toolResult.isError()));
                } catch (ToolExecutionException e) {
                    ToolResult failed = ToolResult.error(e.getMessage());
                    emitActivity(session.id, callSummary + " ✗ "
                                    + TerminalRenderer.truncatePreview(e.getMessage(), 72),
                            renderer.renderSubagentToolCall(tc.name, rawInput, failed),
                            session.parentContext);
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, "Error: " + e.getMessage(), true));
                } finally {
                    session.activeToolContext = null;
                }
            }

            pendingToolResults = toolResults;
            currentMessage = null;
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String finalResult = fullResponse.toString().trim();

        if (!session.terminalClaimed.compareAndSet(false, true)) {
            notifyStatus(session.id, "aborted");
            return finalResult + "\n[Subagent aborted]";
        }

        notifyStatus(session.id, "completed");
        emitActivity(session.id, "completed",
                renderer.renderSubagentComplete(session.agent.getName(), durationMs),
                session.parentContext);

        return finalResult.isEmpty() ? "(subagent returned empty response)" : finalResult;
    }

    @Override
    public boolean sendMessage(String subagentId, String message) {
        DirectSession session = sessions.get(subagentId);
        if (session == null || session.cancelled.get()
                || message == null || message.isBlank()) return false;
        session.lastTouched = System.currentTimeMillis();
        session.followUps.add(message.strip());
        emitActivity(session.id, "follow-up queued", "\n  You › " + message.strip(),
                session.parentContext);
        startQueuedRun(session);
        return true;
    }

    @Override
    public boolean canCancel(String subagentId) {
        DirectSession session = sessions.get(subagentId);
        return session != null && session.running.get()
                && !session.terminalClaimed.get() && !session.cancelled.get();
    }

    @Override
    public boolean cancel(String subagentId) {
        DirectSession session = sessions.get(subagentId);
        if (session == null || !session.running.get()
                || !session.terminalClaimed.compareAndSet(false, true)) return false;
        session.cancelled.set(true);
        session.followUps.clear();
        ToolContext activeTool = session.activeToolContext;
        if (activeTool != null) activeTool.abort();
        notifyStatus(session.id, "cancelling");
        emitActivity(session.id, "cancelling",
                renderer.renderSubagentError(session.agent.getName(), "Cancelled by user"),
                session.parentContext);
        Thread owner = session.ownerThread;
        if (owner != null && owner != Thread.currentThread()) owner.interrupt();
        return true;
    }

    private void finishRun(DirectSession session) {
        session.lastTouched = System.currentTimeMillis();
        session.ownerThread = null;
        if (session.cancelled.get()) Thread.interrupted();
        if (lifecycleListener != null) lifecycleListener.onSubagentEnd(session.id);
        session.running.set(false);
        if (session.cancelled.get()) {
            session.followUps.clear();
            sessions.remove(session.id, session);
        } else {
            startQueuedRun(session);
        }
    }

    private void startQueuedRun(DirectSession session) {
        if (session.cancelled.get() || session.followUps.isEmpty()
                || !session.running.compareAndSet(false, true)) return;
        session.terminalClaimed.set(false);
        String first = session.followUps.poll();
        Thread worker = new Thread(() -> {
            session.ownerThread = Thread.currentThread();
            if (lifecycleListener != null) {
                lifecycleListener.onSubagentStart(
                        session.id, session.agent.getName(), "Interactive follow-up");
            }
            try {
                runConversation(session, first, System.currentTimeMillis());
            } catch (Exception e) {
                notifyStatus(session.id, "failed · " + e.getClass().getSimpleName());
                emitActivity(session.id, "failed · " + e.getClass().getSimpleName(),
                        renderer.renderSubagentError(session.agent.getName(), e.getMessage()),
                        session.parentContext);
            } finally {
                finishRun(session);
            }
        }, "subagent-followup-" + session.id);
        worker.setDaemon(true);
        worker.start();
    }

    private void trimSessions() {
        if (sessions.size() <= MAX_RETAINED_SESSIONS) return;
        sessions.values().stream()
                .filter(session -> !session.running.get())
                .sorted(Comparator.comparingLong(session -> session.lastTouched))
                .limit(Math.max(0, sessions.size() - MAX_RETAINED_SESSIONS))
                .map(session -> session.id)
                .toList()
                .forEach(sessions::remove);
    }

    /** Provider-neutral schema consumed by every DirectLlmClient adapter. */
    ArrayNode directToolDefinitionsFor(AgentConfig agent) {
        // Provider adapters translate this top-level name/inputSchema shape to Responses,
        // Chat Completions, Anthropic, or Pi. The nested MCP/function shape is not accepted here.
        if (usesProgressiveToolLoading()) {
            toolRegistry.prepareProgressiveTools(agent);
            return toolRegistry.buildProgressiveDirectToolDefinitions(agent);
        }
        return toolRegistry.buildDirectToolDefinitions(agent);
    }

    private boolean usesProgressiveToolLoading() {
        String configured = System.getProperty("kompile.chat.progressiveTools");
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured);
        }
        return chatConfig != null && chatConfig.isKompileLocalServing();
    }

    private void notifyStatus(String subagentId, String status) {
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentStatus(subagentId, status);
        }
    }

    private void emitActivity(String subagentId, String summary, String detail,
                              ToolContext parentContext) {
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentActivity(subagentId, summary, detail);
        } else {
            parentContext.emitOutput(detail);
        }
    }

    private void emitOutput(String subagentId, String chunk) {
        LifecycleListener listener = lifecycleListener;
        if (listener != null) listener.onSubagentOutput(subagentId, chunk);
    }

}
