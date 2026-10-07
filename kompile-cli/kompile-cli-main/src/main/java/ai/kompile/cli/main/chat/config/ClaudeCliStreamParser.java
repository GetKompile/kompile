/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateful decoder for the {@code claude -p --output-format stream-json} protocol
 * used by Standard Chat. Deliberately separate from the passthrough decoder:
 * provider-side tools are observed and rendered, never re-dispatched locally.
 * A subagent's own messages (those carrying {@code parent_tool_use_id}) are not
 * the answer and decode to their usage only; its task events report its progress.
 */
final class ClaudeCliStreamParser {

    interface Event { }

    record SessionInit(String sessionId) implements Event { }
    record Text(String text) implements Event { }
    record Thinking(String text) implements Event { }
    /** Transient provider phase; never assistant text or a transcript notice. */
    record Activity(String label) implements Event { }
    record ToolProgress(String callId, String name, long elapsedMillis) implements Event { }
    record ToolStart(String callId, String name, String input) implements Event { }
    record ToolInput(String callId, String name, String input) implements Event { }
    record ToolOutput(String callId, String name, String output) implements Event { }
    record ToolComplete(String callId, String name, String output, boolean error) implements Event { }
    /**
     * A fragment of the arguments the main thread's model is writing for a tool
     * call. Its tokens are output the request reports when it ends.
     */
    record ToolInputDelta(String delta) implements Event { }
    /**
     * Tokens by category. The API counts input without the cache reads and
     * writes, so the categories are disjoint. A negative count reads as 0.
     */
    record TokenCounts(long input, long output, long cacheRead, long cacheCreation) {
        static final TokenCounts ZERO = new TokenCounts(0, 0, 0, 0);

        TokenCounts {
            input = Math.max(0L, input);
            output = Math.max(0L, output);
            cacheRead = Math.max(0L, cacheRead);
            cacheCreation = Math.max(0L, cacheCreation);
        }

        /** The counts of an API {@code usage} object; a missing or null count is 0. */
        static TokenCounts of(JsonNode usage) {
            return new TokenCounts(usage.path("input_tokens").asLong(0L),
                    usage.path("output_tokens").asLong(0L),
                    usage.path("cache_read_input_tokens").asLong(0L),
                    usage.path("cache_creation_input_tokens").asLong(0L));
        }

        boolean isZero() {
            return input == 0 && output == 0 && cacheRead == 0 && cacheCreation == 0;
        }

        TokenCounts plus(TokenCounts other) {
            return new TokenCounts(input + other.input, output + other.output,
                    cacheRead + other.cacheRead, cacheCreation + other.cacheCreation);
        }

        /** Per category, how far these counts exceed {@code other}'s; 0 where they do not. */
        TokenCounts above(TokenCounts other) {
            return new TokenCounts(input - other.input, output - other.output,
                    cacheRead - other.cacheRead, cacheCreation - other.cacheCreation);
        }

        /** The higher count of each category. */
        TokenCounts max(TokenCounts other) {
            return new TokenCounts(Math.max(input, other.input), Math.max(output, other.output),
                    Math.max(cacheRead, other.cacheRead),
                    Math.max(cacheCreation, other.cacheCreation));
        }

        /** True when no category is below {@code other}'s. */
        boolean covers(TokenCounts other) {
            return input >= other.input && output >= other.output
                    && cacheRead >= other.cacheRead && cacheCreation >= other.cacheCreation;
        }

        TokenCounts withoutOutput() {
            return new TokenCounts(input, 0, cacheRead, cacheCreation);
        }
    }
    /**
     * The usage one model request reported so far. The API reports a request's
     * input and cache counts when it starts and its output when it ends, both
     * cumulatively; each message aggregate repeats what was known when its block
     * ended. A subagent's requests report only through their aggregates, so their
     * output is the count at the request's start. {@code sequence} is the event's
     * position in the process's output.
     */
    record RequestUsage(long sequence, String requestId, boolean mainThread,
                        TokenCounts usage) implements Event { }
    record Notice(String text) implements Event { }
    /**
     * Claude Code reported a failed API request ("Prompt is too long", an overload
     * it stopped retrying) as a synthetic assistant message. The text is an error,
     * not part of the answer.
     */
    record ApiError(String text) implements Event { }
    /**
     * Claude Code compacted the session it owns ({@code compact_boundary}).
     * {@code trigger} is {@code auto} or {@code manual}, empty when not reported;
     * {@code preTokens} is the context size before compaction, 0 when not
     * reported. Claude Code does not report the size after.
     */
    record Compacted(String trigger, long preTokens) implements Event { }
    /** Claude Code failed to compact its session; {@code detail} may be empty. */
    record CompactionFailed(String detail) implements Event { }
    /** Claude Code is retrying a failed API request itself; transient, not a notice. */
    record Retry(int attempt, int maxAttempts, long delayMs, String reason) implements Event { }
    /**
     * Client uuids of the user messages the current turn has consumed. Claude Code
     * stamps them on the first frames of a turn and on its result, and with
     * {@code --replay-user-messages} echoes each message it takes in, including one
     * folded into a turn already running. A turn Claude Code starts by itself, after
     * a background task finished, carries none.
     */
    record ConsumedUserMessages(List<String> uuids) implements Event { }
    /**
     * Claude Code started a task: a subagent, a background shell command, a
     * workflow or a monitor. {@code backgrounded} is false for a task its turn
     * waits for.
     */
    record TaskStarted(String taskId, String toolUseId, String description, String taskType,
                       boolean backgrounded) implements Event { }
    /** Claude Code's progress report for a running task. */
    record TaskProgress(String taskId, String description, String lastToolName, String summary,
                        long totalTokens, long toolUses, long durationMs) implements Event { }
    /**
     * A task ended. {@code status} is {@code completed} or {@code failed}, else
     * {@code killed} in a task update and {@code stopped} in a task notification,
     * which is how Claude Code ends a foreground command its interrupted turn
     * stopped; {@code summary} is Claude Code's account of it, may be empty.
     */
    record TaskEnded(String taskId, String status, String summary) implements Event { }
    /**
     * The background tasks still running, listed in full after any change: a task
     * missing from the list has stopped.
     */
    record BackgroundTasks(List<String> taskIds) implements Event { }
    /**
     * The terminal result of a turn. {@code started} is false when Claude Code
     * refused the turn before any model request ({@code num_turns: 0}), for
     * example an unknown {@code --resume} session. The token counts add up the
     * turn's main-thread requests; {@code contextTokens} is the input size of its last
     * request, 0 when no request reported usage after the last compaction.
     * {@code contextWindow} and {@code maxOutputTokens} are the limits Claude Code
     * applies to the session's model, 0 when not reported. {@code requests} counts
     * the main-thread model requests the turn made, one per message id, 0 when no
     * frame carried an id; a subagent's requests are its task's, not the turn's.
     * {@code totals} adds up the result's {@code modelUsage}: the process's running
     * totals over every model, with subagents, compaction and side requests, and
     * for a resumed session the totals it was saved with; null when the result
     * has none. {@code sequence} is the result's position in the process's output.
     */
    record TurnComplete(String result, boolean error, String errorMessage, boolean started,
                        long inputTokens, long outputTokens,
                        long cacheReadTokens, long cacheCreationTokens,
                        long contextTokens, int contextWindow, int maxOutputTokens,
                        int requests, TokenCounts totals, long sequence) implements Event {

        /** The usage of the turn's main-thread requests. */
        TokenCounts usage() {
            return new TokenCounts(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
        }
    }

    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of("completed", "failed", "killed", "stopped");

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final Map<Integer, Block> blocks = new HashMap<>();
    private final Map<Integer, StringBuilder> streamedText = new HashMap<>();
    private final Map<Integer, StringBuilder> streamedThinking = new HashMap<>();
    private final Set<String> startedToolCalls = new HashSet<>();
    private final Set<String> completedInputs = new HashSet<>();
    private final Map<String, String> toolNames = new HashMap<>();
    private String messageId = "";
    // The session's model as init reports it; the result keys modelUsage by model.
    private String mainModel = "";
    // Input size of the latest main-thread request; a compaction resets it.
    private long requestContextTokens;
    // Main-thread requests of the current turn: new message ids since the last result.
    // num_turns is not used: a process that keeps its session may report it cumulatively.
    private int turnRequests;
    // Positions usage and results in the process's output; a process's parsers share it.
    private final AtomicLong sequences;

    ClaudeCliStreamParser() {
        this(new AtomicLong());
    }

    /** A parser for one turn of a process whose parsers share {@code sequences}. */
    ClaudeCliStreamParser(AtomicLong sequences) {
        this.sequences = sequences;
    }

    /**
     * Decode one stream-json line. Well-formed protocol lines with nothing to show
     * decode to no events.
     *
     * @throws IllegalArgumentException when the line is not JSON; the transport keeps
     *         such output in its diagnostics, where it cannot pass for assistant prose
     */
    List<Event> parse(String line) {
        if (line == null || line.isBlank()) return List.of();
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a stream-json line", e);
        }
        if (node == null || !node.isObject()) return List.of();
        return switch (node.path("type").asText()) {
            case "system" -> parseSystem(node);
            case "stream_event" -> stamped(node,
                    mainThread(node) ? parseStreamEvent(node.path("event")) : List.of());
            case "assistant" -> stamped(node, parseAssistant(node));
            case "user" -> parseUser(node);
            case "tool_progress" -> mainThread(node) ? parseToolProgress(node) : List.of();
            case "result" -> stamped(node, parseResult(node));
            default -> List.of();
        };
    }

    /** A frame's events, preceded by the client uuids Claude Code stamped on it. */
    private static List<Event> stamped(JsonNode node, List<Event> events) {
        List<String> uuids = consumedUserMessages(node);
        if (uuids.isEmpty()) return events;
        List<Event> stampedEvents = new ArrayList<>(events.size() + 1);
        stampedEvents.add(new ConsumedUserMessages(uuids));
        stampedEvents.addAll(events);
        return stampedEvents;
    }

    /**
     * The client uuids on a frame, read the way Claude Code's own hosts read them:
     * the {@code user_message_uuids} list, else the single {@code user_message_uuid}.
     */
    private static List<String> consumedUserMessages(JsonNode node) {
        List<String> uuids = new ArrayList<>();
        for (JsonNode uuid : node.path("user_message_uuids")) {
            if (uuid.isTextual() && !uuid.asText().isBlank()) uuids.add(uuid.asText());
        }
        JsonNode single = node.path("user_message_uuid");
        if (uuids.isEmpty() && single.isTextual() && !single.asText().isBlank()) uuids.add(single.asText());
        return List.copyOf(uuids);
    }

    /**
     * Claude Code emits many system events for SDK hosts. Only those carrying
     * something a reader should see become events: a {@link Notice}, a
     * {@link Compacted} / {@link CompactionFailed} for session compaction, or a task
     * lifecycle event for the process panel. Request/thinking phases become
     * transient activity, never transcript text or token usage. Hook lifecycles
     * and session-state changes remain bookkeeping.
     */
    private List<Event> parseSystem(JsonNode node) {
        return switch (node.path("subtype").asText("")) {
            // Only the init handshake binds the native session id; later system
            // events may carry a session_id too.
            case "init" -> {
                mainModel = node.path("model").asText("").strip();
                String session = node.path("session_id").asText("");
                yield session.isBlank() ? List.of() : List.of(new SessionInit(session));
            }
            case "status" -> mainThread(node) ? parseStatus(node) : List.of();
            case "thinking_tokens" -> mainThread(node)
                    ? List.of(new Activity("Thinking")) : List.of();
            case "compact_boundary" -> {
                // Requests before the boundary measured the context it replaced.
                requestContextTokens = 0;
                yield List.of(parseCompactBoundary(node));
            }
            case "notification" -> notice(node.path("text"));
            case "informational", "model_fallback", "model_consent_fallback",
                 "model_refusal_fallback", "model_refusal_no_fallback" -> notice(node.path("content"));
            case "api_retry" -> List.of(parseRetry(node));
            case "task_started" -> taskStarted(node);
            case "task_progress" -> taskProgress(node);
            // A task's terminal status arrives as an update, then as its notification.
            case "task_updated" -> taskEnded(node, node.path("patch").path("status").asText(""),
                    firstText(node.path("patch"), "error"));
            case "task_notification" -> taskEnded(node, node.path("status").asText(""),
                    firstText(node, "summary", "reason"));
            case "background_tasks_changed" -> backgroundTasks(node);
            default -> List.of();
        };
    }

    private static List<Event> taskStarted(JsonNode node) {
        String taskId = node.path("task_id").asText("");
        if (taskId.isBlank()) return List.of();
        // Claude Code counts a task without the flag as backgrounded.
        JsonNode backgrounded = node.path("is_backgrounded");
        return List.of(new TaskStarted(taskId, node.path("tool_use_id").asText(""),
                node.path("description").asText("").strip(), node.path("task_type").asText(""),
                !backgrounded.isBoolean() || backgrounded.booleanValue()));
    }

    private static List<Event> taskProgress(JsonNode node) {
        String taskId = node.path("task_id").asText("");
        if (taskId.isBlank()) return List.of();
        JsonNode usage = node.path("usage");
        return List.of(new TaskProgress(taskId, node.path("description").asText("").strip(),
                node.path("last_tool_name").asText("").strip(), node.path("summary").asText("").strip(),
                Math.max(0L, usage.path("total_tokens").asLong(0L)),
                Math.max(0L, usage.path("tool_uses").asLong(0L)),
                Math.max(0L, usage.path("duration_ms").asLong(0L))));
    }

    private static List<Event> taskEnded(JsonNode node, String status, String summary) {
        String taskId = node.path("task_id").asText("");
        if (taskId.isBlank() || !TERMINAL_TASK_STATUSES.contains(status)) return List.of();
        return List.of(new TaskEnded(taskId, status, summary.strip()));
    }

    private static List<Event> backgroundTasks(JsonNode node) {
        JsonNode tasks = node.path("tasks");
        if (!tasks.isArray()) return List.of();
        List<String> taskIds = new ArrayList<>();
        for (JsonNode task : tasks) {
            String taskId = task.path("task_id").asText("");
            if (!taskId.isBlank()) taskIds.add(taskId);
        }
        return List.of(new BackgroundTasks(List.copyOf(taskIds)));
    }

    private static List<Event> parseStatus(JsonNode node) {
        if ("compacting".equals(node.path("status").asText(""))) {
            return List.of(new Activity("Compacting Claude context"),
                    new Notice("Compacting conversation"));
        }
        if ("requesting".equals(node.path("status").asText(""))) {
            return List.of(new Activity("Waiting for Claude response"));
        }
        if ("failed".equals(node.path("compact_result").asText(""))) {
            String detail = node.path("compact_error").asText("").strip();
            return harmlessCompactionFailure(detail) ? List.of() : List.of(new CompactionFailed(detail));
        }
        // A finished compaction and permission-mode changes carry no live phase.
        // A successful compaction is reported by its
        // compact_boundary event.
        return List.of();
    }

    /**
     * Claude Code also reports a compaction as failed when it was aborted, found
     * too little history to summarize, or a PreCompact hook blocked it, and shows
     * none of these as an error. None means the session no longer fits: a request
     * that does not fit fails with "Prompt is too long". Reactive compaction
     * reports the first two by code ({@code aborted}, {@code too_few_groups}).
     */
    private static boolean harmlessCompactionFailure(String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.equals("aborted") || lower.equals("too_few_groups")
                || lower.contains("request was aborted")
                || lower.contains("not enough messages to compact")
                || lower.contains("compaction blocked by precompact hook");
    }

    private static Compacted parseCompactBoundary(JsonNode node) {
        JsonNode metadata = node.path("compact_metadata");
        return new Compacted(metadata.path("trigger").asText("").strip(),
                Math.max(0L, metadata.path("pre_tokens").asLong(0L)));
    }

    private static Retry parseRetry(JsonNode node) {
        // error is Claude Code's category: overloaded, rate_limit, server_error, ...
        String category = node.path("error").asText("").strip();
        int status = node.path("error_status").asInt(0);
        String reason = category.isEmpty() || "unknown".equals(category)
                ? (status > 0 ? "HTTP " + status : "request failed")
                : category.replace('_', ' ') + (status > 0 ? ", HTTP " + status : "");
        return new Retry(node.path("attempt").asInt(0), node.path("max_retries").asInt(0),
                Math.max(0, node.path("retry_delay_ms").asLong(0)), reason);
    }

    private static List<Event> notice(JsonNode text) {
        String value = text.isTextual() ? text.asText().strip() : "";
        return value.isEmpty() ? List.of() : List.of(new Notice(value));
    }

    private List<Event> parseStreamEvent(JsonNode event) {
        if (!event.isObject()) return List.of();
        String type = event.path("type").asText();
        if ("message_start".equals(type)) {
            JsonNode message = event.path("message");
            recordRequestUsage(message.path("usage"));
            String startedMessageId = message.path("id").asText("");
            if (!startedMessageId.isBlank() && !startedMessageId.equals(messageId)) turnRequests++;
            messageId = startedMessageId;
            blocks.clear();
            streamedText.clear();
            streamedThinking.clear();
            return requestUsage(startedMessageId, true, message.path("usage"));
        }
        if ("message_delta".equals(type)) {
            // The request's final output count.
            return requestUsage(messageId, true, event.path("usage"));
        }
        if ("content_block_start".equals(type)) {
            int index = event.path("index").asInt(0);
            JsonNode contentBlock = event.path("content_block");
            if (index < 0 || !contentBlock.isObject()) return List.of();
            String blockType = contentBlock.path("type").asText();
            Block block = new Block(blockType);
            blocks.put(index, block);
            if ("tool_use".equals(blockType)) {
                block.callId = toolCallId(contentBlock, index);
                block.name = contentBlock.path("name").asText("unknown");
                block.input = jsonText(contentBlock.path("input"));
                return startTool(block);
            }
            if ("thinking".equals(blockType) || "redacted_thinking".equals(blockType)) {
                return List.of(new Activity("Thinking"));
            }
            return List.of();
        }
        if ("content_block_delta".equals(type)) {
            int index = event.path("index").asInt(0);
            JsonNode delta = event.path("delta");
            if (index < 0 || !delta.isObject()) return List.of();
            String deltaType = delta.path("type").asText();
            if ("text_delta".equals(deltaType)) {
                String text = delta.path("text").asText("");
                streamedText.computeIfAbsent(index, ignored -> new StringBuilder()).append(text);
                return text.isEmpty() ? List.of() : List.of(new Text(text));
            }
            if ("thinking_delta".equals(deltaType)) {
                String text = delta.path("thinking").asText("");
                streamedThinking.computeIfAbsent(index, ignored -> new StringBuilder()).append(text);
                return text.isEmpty() ? List.of() : List.of(new Thinking(text));
            }
            if ("input_json_delta".equals(deltaType)) {
                String partial = delta.path("partial_json").asText("");
                Block block = blocks.get(index);
                if (block != null && "tool_use".equals(block.type)) {
                    block.partialInput.append(partial);
                }
                return partial.isEmpty() ? List.of() : List.of(new ToolInputDelta(partial));
            }
            return List.of();
        }
        if ("content_block_stop".equals(type)) {
            int index = event.path("index").asInt(0);
            Block block = blocks.get(index);
            if (block != null && "tool_use".equals(block.type)) {
                String partial = block.partialInput.toString();
                if (!partial.isBlank()) block.input = normalizeJson(partial);
                return finishToolInput(block);
            }
        }
        return List.of();
    }

    private List<Event> parseAssistant(JsonNode node) {
        JsonNode message = node.path("message");
        boolean mainThread = mainThread(node);
        // Aggregates repeat their request's usage; older CLIs stream no message_start.
        if (mainThread) recordRequestUsage(message.path("usage"));
        if (apiErrorMessage(node)) {
            // A subagent's failed request does not end the turn; it is only a notice.
            String text = contentText(message.path("content")).strip();
            if (text.isEmpty()) return List.of();
            return List.of(mainThread ? new ApiError(text) : new Notice(text));
        }
        String aggregateMessageId = message.path("id").asText("");
        // A subagent's requests report their usage only through its aggregates.
        List<Event> events = new ArrayList<>(
                requestUsage(aggregateMessageId, mainThread, message.path("usage")));
        if (!mainThread) return events;
        if (!aggregateMessageId.isBlank() && !aggregateMessageId.equals(messageId)) {
            // Older CLI versions may emit aggregate messages without stream events.
            turnRequests++;
            messageId = aggregateMessageId;
            blocks.clear();
            streamedText.clear();
            streamedThinking.clear();
        }
        JsonNode content = message.path("content");
        if (!content.isArray()) return events;
        // The CLI emits one aggregate per finished block, carrying just that block
        // at content[0], so an aggregate's array index says nothing about its stream
        // index. A block is a repeat when this message already streamed its text.
        for (int index = 0; index < content.size(); index++) {
            JsonNode block = content.get(index);
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                String text = block.path("text").asText("");
                if (!text.isEmpty() && !alreadyStreamed(streamedText, text)) events.add(new Text(text));
            } else if ("thinking".equals(type)) {
                String thinking = block.path("thinking").asText("");
                if (!thinking.isEmpty() && !alreadyStreamed(streamedThinking, thinking)) {
                    events.add(new Thinking(thinking));
                }
            } else if ("tool_use".equals(type)) {
                Block tool = new Block(type);
                tool.callId = toolCallId(block, index);
                tool.name = block.path("name").asText("unknown");
                tool.input = jsonText(block.path("input"));
                events.addAll(startTool(tool));
                events.addAll(finishToolInput(tool));
            }
            // Unknown content blocks are not rendered as assistant prose. The
            // aggregate still retains all supported text/thinking/tool content.
        }
        return events;
    }

    private List<Event> parseUser(JsonNode node) {
        // --replay-user-messages echoes a client message when Claude Code consumes it.
        if (node.path("isReplay").asBoolean(false)) {
            String uuid = node.path("uuid").asText("");
            return uuid.isBlank() ? List.of() : List.of(new ConsumedUserMessages(List.of(uuid)));
        }
        // A subagent's tool results answer its own tool calls, which are not shown.
        if (!mainThread(node)) return List.of();
        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) return List.of();
        List<Event> events = new ArrayList<>();
        for (JsonNode block : content) {
            if (!"tool_result".equals(block.path("type").asText())) continue;
            String callId = block.path("tool_use_id").asText("");
            if (callId.isBlank()) continue;
            String name = toolNames.getOrDefault(callId, "unknown");
            String output = contentText(block.path("content"));
            boolean error = block.path("is_error").asBoolean(false);
            events.add(new ToolComplete(callId, name, output, error));
        }
        return events;
    }

    private List<Event> parseToolProgress(JsonNode node) {
        String callId = node.path("tool_use_id").asText(node.path("call_id").asText(""));
        if (callId.isBlank()) return List.of();
        String name = toolNames.getOrDefault(callId, node.path("tool_name").asText("unknown"));
        List<Event> events = new ArrayList<>();
        // Claude's normal heartbeat has elapsed_time_seconds, not output text.
        JsonNode elapsed = node.path("elapsed_time_seconds");
        double seconds = elapsed.asDouble(-1);
        if (elapsed.isNumber() && Double.isFinite(seconds) && seconds >= 0) {
            events.add(new ToolProgress(callId, name, (long) (seconds * 1000)));
        }
        String output = firstText(node, "content", "output", "message");
        if (!output.isBlank()) events.add(new ToolOutput(callId, name, output));
        return events;
    }

    private List<Event> parseResult(JsonNode node) {
        JsonNode usage = node.path("usage");
        // The API reports input_tokens without the cache reads and writes.
        long input = Math.max(0, usage.path("input_tokens").asLong(0));
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheCreation = usage.path("cache_creation_input_tokens").asLong(0);
        String subtype = node.path("subtype").asText("");
        boolean error = node.path("is_error").asBoolean(false) || subtype.startsWith("error");
        String errorMessage = error
                ? firstText(node, "error", "error_message", "message", "result") : "";
        if (error && errorMessage.isBlank()) errorMessage = errorsText(node.path("errors"));
        JsonNode turns = node.path("num_turns");
        boolean started = !turns.isNumber() || turns.asLong() > 0;
        String result = node.path("result").asText("");
        JsonNode modelUsage = node.path("modelUsage");
        JsonNode limits = mainModelUsage(modelUsage);
        int requests = turnRequests;
        turnRequests = 0;
        return List.of(new TurnComplete(result, error, errorMessage, started, input,
                usage.path("output_tokens").asLong(0), cacheRead, cacheCreation,
                requestContextTokens, limits.path("contextWindow").asInt(0),
                limits.path("maxOutputTokens").asInt(0), requests,
                totals(modelUsage), sequences.incrementAndGet()));
    }

    /** The {@code modelUsage} counts added up over every model; null when there are none. */
    private static TokenCounts totals(JsonNode modelUsage) {
        if (!modelUsage.isObject()) return null;
        TokenCounts totals = TokenCounts.ZERO;
        for (JsonNode model : modelUsage) {
            totals = totals.plus(new TokenCounts(model.path("inputTokens").asLong(0L),
                    model.path("outputTokens").asLong(0L),
                    model.path("cacheReadInputTokens").asLong(0L),
                    model.path("cacheCreationInputTokens").asLong(0L)));
        }
        return totals;
    }

    /** A request's usage as an event; none without counts or a request id to tell repeats by. */
    private List<Event> requestUsage(String requestId, boolean mainThread, JsonNode usage) {
        TokenCounts counts = TokenCounts.of(usage);
        if (requestId.isBlank() || counts.isZero()) return List.of();
        return List.of(new RequestUsage(sequences.incrementAndGet(), requestId, mainThread, counts));
    }

    /**
     * The {@code modelUsage} entry of the session's main model. The result also
     * lists the models Claude Code ran on the side, such as a smaller model for
     * subagents, and may key the main model without the {@code [1m]} suffix init
     * reported. When none matches, the smallest window stands in: a budget within
     * it fits every model the turn used.
     */
    private JsonNode mainModelUsage(JsonNode modelUsage) {
        if (!modelUsage.isObject()) return MissingNode.getInstance();
        JsonNode exact = mainModel.isEmpty() ? MissingNode.getInstance() : modelUsage.path(mainModel);
        if (exact.path("contextWindow").asInt(0) > 0) return exact;
        String base = baseModel(mainModel);
        JsonNode smallest = MissingNode.getInstance();
        Iterator<Map.Entry<String, JsonNode>> entries = modelUsage.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            int window = entry.getValue().path("contextWindow").asInt(0);
            if (window <= 0) continue;
            if (!base.isEmpty() && base.equals(baseModel(entry.getKey()))) return entry.getValue();
            if (smallest.isMissingNode() || window < smallest.path("contextWindow").asInt(0)) {
                smallest = entry.getValue();
            }
        }
        return smallest;
    }

    /** A model id without its context-size suffix, such as {@code [1m]}. */
    private static String baseModel(String model) {
        int suffix = model.indexOf('[');
        return (suffix < 0 ? model : model.substring(0, suffix)).strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Claude Code flags an assistant message that wraps a failed API request with
     * {@code is_api_error_message}; a CLI that omits the flag still gives it the
     * {@code <synthetic>} model and an {@code error} category.
     */
    private static boolean apiErrorMessage(JsonNode node) {
        if (node.path("is_api_error_message").asBoolean(false)) return true;
        JsonNode error = node.path("error");
        return error.isTextual() && !error.asText().isBlank()
                && "<synthetic>".equals(node.path("message").path("model").asText(""));
    }

    /** A subagent's messages carry the id of the tool call that started it. */
    private static boolean mainThread(JsonNode node) {
        JsonNode parent = node.path("parent_tool_use_id");
        return !parent.isTextual() || parent.asText().isBlank();
    }

    /**
     * Record one request's input size. The API's {@code input_tokens} leaves out
     * the cached prefix, so the request read input plus cache reads plus cache
     * writes. Usage without any input (synthetic messages) is not a measurement.
     */
    private void recordRequestUsage(JsonNode usage) {
        if (!usage.isObject()) return;
        long total = Math.max(0L, usage.path("input_tokens").asLong(0L))
                + Math.max(0L, usage.path("cache_read_input_tokens").asLong(0L))
                + Math.max(0L, usage.path("cache_creation_input_tokens").asLong(0L));
        if (total > 0) requestContextTokens = total;
    }

    /** The entries of a result's {@code errors} array, one per line. */
    private static String errorsText(JsonNode errors) {
        if (!errors.isArray()) return "";
        List<String> lines = new ArrayList<>();
        for (JsonNode entry : errors) {
            String text = entry.isTextual() ? entry.asText() : entry.path("message").asText("");
            if (!text.isBlank()) lines.add(text.strip());
        }
        return String.join("\n", lines);
    }

    private List<Event> startTool(Block block) {
        if (block.callId == null || block.callId.isBlank()) return List.of();
        toolNames.put(block.callId, block.name);
        if (!startedToolCalls.add(block.callId)) return List.of();
        return List.of(new ToolStart(block.callId, block.name, block.input));
    }

    private List<Event> finishToolInput(Block block) {
        if (block.callId == null || block.callId.isBlank()
                || !completedInputs.add(block.callId)) return List.of();
        return List.of(new ToolInput(block.callId, block.name, block.input));
    }

    private String toolCallId(JsonNode block, int index) {
        String id = block.path("id").asText("");
        if (!id.isBlank()) return id;
        return (messageId.isBlank() ? "claude" : messageId) + ":tool:" + index;
    }

    private static boolean alreadyStreamed(Map<Integer, StringBuilder> streamedBlocks, String text) {
        for (StringBuilder streamed : streamedBlocks.values()) {
            if (streamed.toString().equals(text)) return true;
        }
        return false;
    }

    private static String jsonText(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.toString();
    }

    private String normalizeJson(String json) {
        try {
            JsonNode parsed = mapper.readTree(json);
            return parsed == null ? json : parsed.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    /**
     * Tool result content as display text. Items that are not text render as a
     * short placeholder, never as their JSON.
     */
    private static String contentText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return contentItemText(content);
        List<String> parts = new ArrayList<>();
        for (JsonNode item : content) {
            String text = contentItemText(item);
            if (!text.isEmpty()) parts.add(text);
        }
        return String.join("\n", parts);
    }

    private static String contentItemText(JsonNode item) {
        if (item.isTextual()) return item.asText();
        String type = item.path("type").asText("");
        return switch (type) {
            case "text" -> item.path("text").asText("");
            case "image" -> "[image result: "
                    + item.path("source").path("media_type").asText("unknown type") + "]";
            // ToolSearch answers with references to the tools it loaded.
            case "tool_reference" -> item.path("tool_name").asText("");
            default -> type.isBlank() ? "" : "[" + type + " result]";
        };
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
            JsonNode message = value.path("message");
            if (message.isTextual() && !message.asText().isBlank()) return message.asText();
        }
        return "";
    }

    private static final class Block {
        private final String type;
        private final StringBuilder partialInput = new StringBuilder();
        private String callId;
        private String name = "unknown";
        private String input = "";

        private Block(String type) {
            this.type = type;
        }
    }
}
