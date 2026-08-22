/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.exec;

/**
 * Ordered, transport-neutral events emitted by a headless agent run.
 *
 * <p>The sequence is assigned by the publisher, not by the producer. This is
 * important when model streaming and tool callbacks are delivered on different
 * threads: consumers can render the exact order in which Kompile observed them.</p>
 */
public record HeadlessRunEvent(
        long sequence,
        Type type,
        String sessionId,
        String callId,
        String toolName,
        String rawInput,
        String text,
        boolean ok,
        long durationMs,
        int exitCode,
        String message) {

    public enum Type {
        RUN_STARTED,
        ASSISTANT_DELTA,
        TOOL_STARTED,
        TOOL_COMPLETED,
        RUN_COMPLETED,
        RUN_FAILED,
        RUN_DETACHED
    }

    public HeadlessRunEvent {
        type = type == null ? Type.RUN_FAILED : type;
        sessionId = sessionId == null ? "" : sessionId;
        callId = callId == null ? "" : callId;
        toolName = toolName == null ? "" : toolName;
        rawInput = rawInput == null ? "" : rawInput;
        text = text == null ? "" : text;
        message = message == null ? "" : message;
    }

    public HeadlessRunEvent withSequence(long value) {
        return new HeadlessRunEvent(value, type, sessionId, callId, toolName, rawInput,
                text, ok, durationMs, exitCode, message);
    }

    public static HeadlessRunEvent started(String sessionId, String model, String cwd) {
        return new HeadlessRunEvent(0, Type.RUN_STARTED, sessionId, "", model, cwd,
                "", true, 0, 0, "");
    }

    public static HeadlessRunEvent assistantDelta(String sessionId, String text) {
        return new HeadlessRunEvent(0, Type.ASSISTANT_DELTA, sessionId, "", "", "",
                text, true, 0, 0, "");
    }

    public static HeadlessRunEvent toolStarted(String sessionId, String callId,
                                               String toolName, String rawInput) {
        return new HeadlessRunEvent(0, Type.TOOL_STARTED, sessionId, callId, toolName,
                rawInput, "", true, 0, 0, "");
    }

    public static HeadlessRunEvent toolCompleted(String sessionId, String callId,
                                                 String toolName, String rawInput,
                                                 boolean ok, long durationMs) {
        return new HeadlessRunEvent(0, Type.TOOL_COMPLETED, sessionId, callId, toolName,
                rawInput, "", ok, durationMs, 0, "");
    }

    public static HeadlessRunEvent completed(String sessionId, String text,
                                             int exitCode, int tools) {
        return new HeadlessRunEvent(0, Type.RUN_COMPLETED, sessionId, "", "", "",
                text, true, 0, exitCode, Integer.toString(tools));
    }

    public static HeadlessRunEvent failed(String sessionId, String message, int exitCode) {
        return new HeadlessRunEvent(0, Type.RUN_FAILED, sessionId, "", "", "",
                "", false, 0, exitCode, message);
    }

    public static HeadlessRunEvent detached(String sessionId, String message) {
        return new HeadlessRunEvent(0, Type.RUN_DETACHED, sessionId, "", "", "",
                "", true, 0, 0, message);
    }
}
