/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cooperative controller for long-running agent runs.
 *
 * <p>The controller is deliberately transport independent: the REPL, a future
 * headless runner, and tests can all issue the same pause/resume/step/approve
 * controls. The agent loop calls the two gate methods at safe points, so a
 * pause never interrupts an in-flight HTTP request or tool invocation.</p>
 */
public final class AgentRunController {
    public enum Mode { AUTO, SUPERVISED, SINGLE_STEP }
    public enum State { RUNNING, PAUSED, STOPPED }

    public record Decision(boolean allowed, String reason) {}
    public record Snapshot(Mode mode, State state, int completedSteps, int toolCalls,
                           int maxSteps, int maxToolCalls, boolean approvalPending) {}

    private static final Set<String> READ_ONLY_CRAWL_OPERATIONS = Set.of(
            "status", "list", "source_types", "preflight", "transcript",
            "graph_stats", "runtime_config");

    private final Mode mode;
    private final int maxSteps;
    private final int maxToolCalls;
    private final AtomicInteger completedSteps = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicBoolean approvalPending = new AtomicBoolean();
    private volatile State state;

    public AgentRunController(Mode mode, int maxSteps, int maxToolCalls) {
        this.mode = mode == null ? Mode.SUPERVISED : mode;
        this.maxSteps = maxSteps <= 0 ? 50 : maxSteps;
        this.maxToolCalls = maxToolCalls <= 0 ? 200 : maxToolCalls;
        this.state = this.mode == Mode.SINGLE_STEP ? State.PAUSED : State.RUNNING;
    }

    public synchronized boolean beforeStep(int nextStep) {
        if (state == State.STOPPED || state == State.PAUSED) {
            return false;
        }
        if (nextStep > maxSteps) {
            state = State.STOPPED;
            return false;
        }
        return true;
    }

    public synchronized void afterStep() {
        if (state == State.STOPPED) return;
        int completed = completedSteps.incrementAndGet();
        if (completed >= maxSteps) {
            state = State.STOPPED;
        } else if (mode == Mode.SINGLE_STEP) {
            state = State.PAUSED;
        }
    }

    public synchronized Decision beforeTool(String toolName, JsonNode arguments) {
        if (state == State.STOPPED) {
            return new Decision(false, "agent run is stopped");
        }
        if (state == State.PAUSED) {
            return new Decision(false, "agent run is paused; use /crawl resume or /crawl step");
        }
        int calls = toolCalls.incrementAndGet();
        if (calls > maxToolCalls) {
            state = State.STOPPED;
            return new Decision(false, "tool-call budget exhausted (" + maxToolCalls + ")");
        }
        if (isMutation(toolName, arguments) && mode == Mode.SUPERVISED
                && !approvalPending.getAndSet(false)) {
            return new Decision(false, "mutation requires approval; use /crawl approve");
        }
        return new Decision(true, "");
    }

    public synchronized void pause() {
        if (state != State.STOPPED) state = State.PAUSED;
    }

    public synchronized void resume() {
        if (state != State.STOPPED) state = State.RUNNING;
    }

    public synchronized void stepOnce() {
        if (state != State.STOPPED) {
            state = State.RUNNING;
        }
    }

    public synchronized void stop() {
        state = State.STOPPED;
    }

    public void approveOnce() {
        approvalPending.set(true);
    }

    public Snapshot snapshot() {
        return new Snapshot(mode, state, completedSteps.get(), toolCalls.get(),
                maxSteps, maxToolCalls, approvalPending.get());
    }

    /** Restore counters and cooperative state from a persisted checkpoint. */
    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null) return;
        completedSteps.set(Math.max(0, Math.min(snapshot.completedSteps(), maxSteps)));
        toolCalls.set(Math.max(0, Math.min(snapshot.toolCalls(), maxToolCalls)));
        approvalPending.set(snapshot.approvalPending());
        state = snapshot.state() == null ? State.RUNNING : snapshot.state();
    }

    public Mode mode() { return mode; }
    public State state() { return state; }

    public static boolean isMutation(String toolName, JsonNode arguments) {
        if (toolName == null) return true;
        String normalized = toolName.toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0) normalized = normalized.substring(colon + 1);
        int namespace = normalized.lastIndexOf("__");
        if (namespace >= 0) normalized = normalized.substring(namespace + 2);
        if (normalized.startsWith("mcp_")) normalized = normalized.substring(4);
        if (normalized.equals("crawl_control")) {
            String op = arguments == null ? "" : arguments.path("operation").asText("").toLowerCase(Locale.ROOT);
            return !READ_ONLY_CRAWL_OPERATIONS.contains(op);
        }
        return switch (normalized) {
            case "crawl_documents", "crawl_source", "write", "edit", "edit_batch", "patch", "edit_patch",
                    "bash", "task", "graph_assert", "ask_graph_assert", "graph_retract",
                    "ask_graph_retract", "graph_import", "graph_export", "graph_simulate" -> true;
            default -> false;
        };
    }
}
