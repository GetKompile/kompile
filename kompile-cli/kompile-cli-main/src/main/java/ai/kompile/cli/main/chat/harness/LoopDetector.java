/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * Live, per-turn loop guard, independent of optional post-hoc harness judging.
 * Detects repeated suffixes of up to eight tool executions (including A/B
 * cycles), using tool name, canonical arguments, error status and result hash.
 * New inputs/results break the cycle; there is no total-step or time limit.
 * Only hashes are retained, in a bounded buffer. Synchronized because native
 * provider callbacks may arrive on a different thread from the turn owner.
 */
public final class LoopDetector {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int WARN_AT = 3;
    public static final int STOP_AT = 5;
    private static final int MAX_CYCLE = 8;
    // Intentional status polling gets a longer runway, but cannot spin forever.
    private static final int POLL_WARN_AT = 12;
    private static final int POLL_STOP_AT = 20;
    private static final int HISTORY_LIMIT = MAX_CYCLE * POLL_STOP_AT;

    public enum Action { PROCEED, WARN, TERMINATE }

    public record Verdict(Action action, String notice) {
        public static final Verdict PROCEED = new Verdict(Action.PROCEED, null);
        public boolean shouldStopTurn() { return action == Action.TERMINATE; }
    }

    private record Observation(String fingerprint, boolean polling) { }
    private final List<Observation> history = new ArrayList<>();
    private String pendingWarning;
    private String stopNotice;

    /** Observe the actual result, before truncation or harness notices are added. */
    public synchronized Verdict afterToolResult(String toolName, JsonNode arguments, String output, boolean error) {
        String name = toolName == null ? "unknown" : toolName.replaceFirst("^mcp__[^_]+__", "");
        // Do not strip ids/timestamps or collapse whitespace inside string values:
        // those may be meaningful inputs, not disposable provider call metadata.
        // Native MCP results can already contain a per-invocation receipt. Its
        // changing path is harness bookkeeping, not new task evidence.
        String result = output == null ? "" : output.replaceAll("(?m)^\\[saved to: [^\\r\\n]*\\]$", "");
        String action = arguments == null ? "" : arguments.path("action").asText("");
        if ("process".equals(name) && "status".equals(action) && result.startsWith("Process: ")) {
            // Status elapsed time advances even when the process makes no progress.
            // Restrict normalization to the known header; preserve actual log lines.
            int boundary = result.indexOf("\nRecent Output:");
            String header = boundary < 0 ? result : result.substring(0, boundary);
            result = header.replaceAll("(?m)^  Duration:[^\\r\\n]*", "  Duration: <elapsed>")
                    + (boundary < 0 ? "" : result.substring(boundary));
        }
        String fingerprint = hash(name + "\n" + canonical(arguments) + "\n" + error + "\n" + result);
        boolean polling = ("process".equals(name) && List.of("status", "output", "stream", "list")
                .contains(action)) || ("edit_coordinator".equals(name)
                && List.of("wait_for_activity", "watch_activity").contains(action));
        return observe(new Observation(fingerprint, polling), "tool execution");
    }

    /** Native provider tools report complete arguments as JSON or plain text. */
    public synchronized Verdict afterProviderToolResult(String name, String input, String output, boolean error) {
        if (input == null || input.isBlank()) return Verdict.PROCEED; // Incomplete telemetry is not evidence.
        JsonNode arguments;
        try {
            arguments = JSON.readTree(input);
        } catch (JsonProcessingException malformed) {
            arguments = TextNode.valueOf(input);
        }
        return afterToolResult(name, arguments, output, error);
    }

    /** Only text-only responses: repeated commentary over progressing tools is legitimate. */
    public synchronized Verdict afterAssistantText(String text) {
        return observe(new Observation(hash("assistant\n" + (text == null ? "" : text.strip())), false),
                "assistant response");
    }

    /** Deliver once, on the next request; never ask a model to retry merely to warn it. */
    public synchronized String drainWarning() {
        String warning = pendingWarning;
        pendingWarning = null;
        return warning;
    }

    public synchronized boolean isTerminated() { return stopNotice != null; }
    public synchronized String stopNotice() { return stopNotice; }

    /** Genuine new user guidance starts a new detection scope, unlike harness corrections. */
    public synchronized void reset() {
        history.clear();
        pendingWarning = null;
        stopNotice = null;
    }

    private Verdict observe(Observation observation, String kind) {
        if (isTerminated()) return new Verdict(Action.TERMINATE, stopNotice);
        history.add(observation);
        if (history.size() > HISTORY_LIMIT) history.remove(0);
        int size = history.size();
        boolean warningStillRelevant = false;
        for (int cycle = 1; cycle <= MAX_CYCLE && cycle * WARN_AT <= size; cycle++) {
            int repeats = 1;
            while ((repeats + 1) * cycle <= size) {
                boolean same = true;
                for (int i = 0; i < cycle; i++) {
                    if (!history.get(size - 1 - i).equals(history.get(size - 1 - i - repeats * cycle))) {
                        same = false;
                        break;
                    }
                }
                if (!same) break;
                repeats++;
            }
            boolean polling = true;
            for (int i = size - cycle; i < size; i++) polling &= history.get(i).polling();
            int warnAt = polling ? POLL_WARN_AT : WARN_AT;
            int stopAt = polling ? POLL_STOP_AT : STOP_AT;
            warningStillRelevant |= repeats >= warnAt;
            if (repeats >= stopAt) {
                stopNotice = "Loop guard stopped the turn: a " + cycle + "-step " + kind
                        + " cycle repeated " + repeats + " times without new results. "
                        + "The harness is stopping further execution. Change approach before retrying.";
                pendingWarning = null;
                return new Verdict(Action.TERMINATE, stopNotice);
            }
            if (repeats == warnAt) {
                pendingWarning = "[Loop guard] The same " + cycle + "-step " + kind
                        + " cycle has repeated " + repeats + " times without new results. "
                        + "Do not repeat it unchanged. Change approach or report the blocker; "
                        + "the harness will stop the turn if this cycle continues.";
                return new Verdict(Action.WARN, pendingWarning);
            }
        }
        if (!warningStillRelevant) pendingWarning = null;
        return Verdict.PROCEED;
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            TreeMap<String, String> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(
                    TextNode.valueOf(entry.getKey()).toString(), canonical(entry.getValue())));
            return fields.toString();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(canonical(value)));
            return values.toString();
        }
        return node.toString();
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
