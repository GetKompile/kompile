/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.exec;

import java.util.LinkedHashMap;
import java.util.Map;

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
        String message,
        Map<String, String> metadata,
        com.fasterxml.jackson.databind.JsonNode data) {

    public enum Type {
        RUN_STARTED,
        BACKEND_STARTED,
        SOURCES,
        STATS,
        ASSISTANT_DELTA,
        TOOL_STARTED,
        TOOL_COMPLETED,
        TOKEN_USAGE,
        COMMAND_OUTCOME,
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
        metadata = metadata == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    /** Compatibility constructor for callers compiled against the original event shape. */
    public HeadlessRunEvent(long sequence, Type type, String sessionId, String callId,
                            String toolName, String rawInput, String text, boolean ok,
                            long durationMs, int exitCode, String message) {
        this(sequence, type, sessionId, callId, toolName, rawInput, text, ok,
                durationMs, exitCode, message, Map.of());
    }

    public HeadlessRunEvent(long sequence, Type type, String sessionId, String callId,
                            String toolName, String rawInput, String text, boolean ok,
                            long durationMs, int exitCode, String message,
                            Map<String, String> metadata) {
        this(sequence, type, sessionId, callId, toolName, rawInput, text, ok,
                durationMs, exitCode, message, metadata, null);
    }

    public HeadlessRunEvent withSequence(long value) {
        return new HeadlessRunEvent(value, type, sessionId, callId, toolName, rawInput,
                text, ok, durationMs, exitCode, message, metadata, data);
    }

    /** Structured payload accessor for command outcomes (e.g. /model menu or state). */
    public com.fasterxml.jackson.databind.JsonNode data() {
        return data;
    }

    public static HeadlessRunEvent started(String sessionId, String model, String cwd) {
        return started(sessionId, model, cwd, Map.of());
    }

    public static HeadlessRunEvent started(String sessionId, String model, String cwd,
                                           Map<String, String> configuration) {
        return new HeadlessRunEvent(0, Type.RUN_STARTED, sessionId, "", model, cwd,
                "", true, 0, 0, "", configuration);
    }

    public static HeadlessRunEvent assistantDelta(String sessionId, String text) {
        return new HeadlessRunEvent(0, Type.ASSISTANT_DELTA, sessionId, "", "", "",
                text, true, 0, 0, "", Map.of());
    }

    public static HeadlessRunEvent backendStarted(
            String sessionId, String agent, String processId) {
        return new HeadlessRunEvent(0, Type.BACKEND_STARTED, sessionId, processId,
                agent, "", "", true, 0, 0, "", Map.of());
    }

    public static HeadlessRunEvent sources(String sessionId, String json) {
        return new HeadlessRunEvent(0, Type.SOURCES, sessionId, "", "", "",
                json, true, 0, 0, "", Map.of());
    }

    public static HeadlessRunEvent stats(String sessionId, String json) {
        return new HeadlessRunEvent(0, Type.STATS, sessionId, "", "", "",
                json, true, 0, 0, "", Map.of());
    }

    public static HeadlessRunEvent toolStarted(String sessionId, String callId,
                                               String toolName, String rawInput) {
        return new HeadlessRunEvent(0, Type.TOOL_STARTED, sessionId, callId, toolName,
                rawInput, "", true, 0, 0, "", Map.of());
    }

    public static HeadlessRunEvent toolCompleted(String sessionId, String callId,
                                                 String toolName, String rawInput,
                                                 boolean ok, long durationMs) {
        return new HeadlessRunEvent(0, Type.TOOL_COMPLETED, sessionId, callId, toolName,
                rawInput, "", ok, durationMs, 0, "", Map.of());
    }

    public static HeadlessRunEvent tokenUsage(String sessionId, long input, long output,
                                              long cacheRead, long cacheCreation) {
        return new HeadlessRunEvent(0, Type.TOKEN_USAGE, sessionId, "", "", "",
                "", true, 0, 0, "", Map.of(
                "input_tokens", Long.toString(Math.max(0, input)),
                "output_tokens", Long.toString(Math.max(0, output)),
                "cache_read_tokens", Long.toString(Math.max(0, cacheRead)),
                "cache_creation_tokens", Long.toString(Math.max(0, cacheCreation))));
    }

    public static HeadlessRunEvent commandOutcome(String sessionId, WebCommandResolver.Resolution result) {
        return new HeadlessRunEvent(0, Type.COMMAND_OUTCOME, sessionId, "", "", "",
                result.text(), result.exitCode() == 0, 0, result.exitCode(), "", Map.of(
                "command", result.command(), "status", result.status().name(),
                "protocol_version", Integer.toString(WebChatInput.VERSION)), result.data());
    }

    public static HeadlessRunEvent completed(String sessionId, String text,
                                             int exitCode, int tools) {
        return new HeadlessRunEvent(0, Type.RUN_COMPLETED, sessionId, "", "", "",
                text, exitCode == 0, 0, exitCode, Integer.toString(tools), Map.of());
    }

    public static HeadlessRunEvent failed(String sessionId, String message, int exitCode) {
        return new HeadlessRunEvent(0, Type.RUN_FAILED, sessionId, "", "", "",
                "", false, 0, exitCode, message, Map.of());
    }

    public static HeadlessRunEvent detached(String sessionId, String message) {
        return new HeadlessRunEvent(0, Type.RUN_DETACHED, sessionId, "", "", "",
                "", true, 0, 0, message, Map.of());
    }
}
