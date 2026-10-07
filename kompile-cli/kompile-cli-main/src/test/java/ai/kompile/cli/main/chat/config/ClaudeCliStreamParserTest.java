/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCliStreamParserTest {

    @Test
    void streamsTextAndThinkingOnceWhenAggregateMessageFollows() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1"}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Reasoning"}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Answer"}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"assistant","message":{"id":"msg-1","content":[{"type":"thinking","thinking":"Reasoning"},{"type":"text","text":"Answer"}]}}
                """.trim()));

        assertEquals(List.of(new ClaudeCliStreamParser.Thinking("Reasoning"),
                new ClaudeCliStreamParser.Text("Answer")), events);
    }

    @Test
    void perBlockAggregatesDoNotRepeatStreamedBlocksAtOtherIndexes() {
        // The CLI's real shape: one aggregate per finished block, each at content[0].
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        for (String line : List.of(
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1"}}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Reasoning"}}}""",
                """
                {"type":"assistant","message":{"id":"msg-1","content":[{"type":"thinking","thinking":"Reasoning"}]}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Ans"}}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"wer"}}}""",
                """
                {"type":"assistant","message":{"id":"msg-1","content":[{"type":"text","text":"Answer"}]}}""",
                """
                {"type":"assistant","message":{"id":"msg-1","content":[{"type":"text","text":"Unstreamed"}]}}""")) {
            events.addAll(parser.parse(line));
        }

        assertEquals(List.of(new ClaudeCliStreamParser.Thinking("Reasoning"),
                new ClaudeCliStreamParser.Text("Ans"), new ClaudeCliStreamParser.Text("wer"),
                new ClaudeCliStreamParser.Text("Unstreamed")), events);
    }

    @Test
    void streamsDistinctToolCallsArgumentsAndToolResults() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-tools"}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Read","input":{}}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"one\\"}"}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_stop","index":0}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"assistant","message":{"id":"msg-tools","content":[{"type":"tool_use","id":"tool-1","name":"Read","input":{"path":"one"}}]}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":[{"type":"text","text":"first result"}]}]}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-tools-2"}}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"assistant","message":{"id":"msg-tools-2","content":[{"type":"tool_use","id":"tool-2","name":"Read","input":{"path":"two"}}]}}
                """.trim()));
        events.addAll(parser.parse("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-2","content":"second result","is_error":true}]}}
                """.trim()));

        List<ClaudeCliStreamParser.ToolStart> starts = events.stream()
                .filter(ClaudeCliStreamParser.ToolStart.class::isInstance)
                .map(ClaudeCliStreamParser.ToolStart.class::cast).toList();
        assertEquals(List.of("tool-1", "tool-2"), starts.stream()
                .map(ClaudeCliStreamParser.ToolStart::callId).toList());
        assertEquals(List.of("Read", "Read"), starts.stream()
                .map(ClaudeCliStreamParser.ToolStart::name).toList());

        List<ClaudeCliStreamParser.ToolInput> inputs = events.stream()
                .filter(ClaudeCliStreamParser.ToolInput.class::isInstance)
                .map(ClaudeCliStreamParser.ToolInput.class::cast).toList();
        assertEquals(2, inputs.size(), "aggregate assistant messages must not duplicate inputs");
        assertEquals("{\"path\":\"one\"}", inputs.get(0).input());
        assertEquals("{\"path\":\"two\"}", inputs.get(1).input());

        List<ClaudeCliStreamParser.ToolComplete> completed = events.stream()
                .filter(ClaudeCliStreamParser.ToolComplete.class::isInstance)
                .map(ClaudeCliStreamParser.ToolComplete.class::cast).toList();
        assertEquals(List.of("first result", "second result"), completed.stream()
                .map(ClaudeCliStreamParser.ToolComplete::output).toList());
        assertFalse(completed.get(0).error());
        assertTrue(completed.get(1).error());
    }

    @Test
    void aggregateOnlyOutputAndResultFallbackAreDecoded() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> assistant = parser.parse("""
                {"type":"assistant","message":{"id":"old-cli-msg","content":[{"type":"text","text":"aggregate answer"}]}}
                """.trim());
        assertEquals(List.of(new ClaudeCliStreamParser.Text("aggregate answer")), assistant);

        ClaudeCliStreamParser.Event event = parser.parse("""
                {"type":"result","subtype":"error_max_turns","is_error":true,"result":"Stopped at turn limit","usage":{"input_tokens":12,"output_tokens":4,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
                """.trim()).get(0);
        assertTrue(event instanceof ClaudeCliStreamParser.TurnComplete);
        ClaudeCliStreamParser.TurnComplete result = (ClaudeCliStreamParser.TurnComplete) event;
        assertTrue(result.error());
        assertEquals("Stopped at turn limit", result.errorMessage());
        // input_tokens excludes the cache reads and writes, so it is kept as reported.
        assertEquals(12, result.inputTokens());
        assertEquals(4, result.outputTokens());
    }

    @Test
    void contextSizeIsTheLastMainThreadRequestWithItsCache() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        for (String line : List.of(
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":5,"cache_read_input_tokens":40000,"cache_creation_input_tokens":2000,"output_tokens":1}}},"parent_tool_use_id":null}""",
                // A subagent's requests measure the subagent's context, not the session's.
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-sub","usage":{"input_tokens":90000,"output_tokens":1}}},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-2","usage":{"input_tokens":3,"cache_read_input_tokens":42000,"cache_creation_input_tokens":500,"output_tokens":1}}},"parent_tool_use_id":null}""",
                """
                {"type":"assistant","message":{"id":"msg-sub","content":[],"usage":{"input_tokens":95000,"output_tokens":9}},"parent_tool_use_id":"toolu_task"}""")) {
            parser.parse(line);
        }

        // result.usage adds up the turn's main-thread requests; a subagent's are in modelUsage.
        ClaudeCliStreamParser.TurnComplete turn = turnComplete(parser, """
                {"type":"result","subtype":"success","num_turns":2,"result":"done","usage":{"input_tokens":8,"output_tokens":20,"cache_read_input_tokens":82000,"cache_creation_input_tokens":2500}}""");
        assertEquals(42_503, turn.contextTokens(),
                "input plus cache reads plus cache writes of the last main-thread request");
        assertEquals(82_000, turn.cacheReadTokens(), "the turn's usage still adds up its requests");
        assertEquals(20, turn.outputTokens());
    }

    @Test
    void aCompactionLeavesTheContextSizeUnknownUntilTheNextRequest() {
        String request = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":10,"cache_read_input_tokens":150000}}},"parent_tool_use_id":null}""";
        String boundary = """
                {"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":150010},"session_id":"s","uuid":"u"}""";
        String result = """
                {"type":"result","subtype":"success","num_turns":2,"result":"done","usage":{"input_tokens":22,"output_tokens":5,"cache_read_input_tokens":150000,"cache_creation_input_tokens":8000}}""";

        ClaudeCliStreamParser compactedLast = new ClaudeCliStreamParser();
        compactedLast.parse(request);
        assertEquals(List.of(new ClaudeCliStreamParser.Compacted("auto", 150_010)),
                compactedLast.parse(boundary));
        assertEquals(0, turnComplete(compactedLast, result).contextTokens(),
                "the request before the boundary measured the context the compaction replaced");

        ClaudeCliStreamParser continued = new ClaudeCliStreamParser();
        continued.parse(request);
        continued.parse(boundary);
        continued.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-2","usage":{"input_tokens":12,"cache_creation_input_tokens":8000}}},"parent_tool_use_id":null}""");
        assertEquals(8_012, turnComplete(continued, result).contextTokens(),
                "the first request after the boundary measures the compacted context");
    }

    @Test
    void aggregatesMeasureTheRequestWhenNoStreamEventsArrive() {
        String result = """
                {"type":"result","subtype":"success","num_turns":1,"result":"answer"}""";
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.RequestUsage(1, "old-cli-msg", true,
                        new ClaudeCliStreamParser.TokenCounts(7, 3, 1000, 0)),
                new ClaudeCliStreamParser.Text("answer")), parser.parse("""
                {"type":"assistant","message":{"id":"old-cli-msg","content":[{"type":"text","text":"answer"}],"usage":{"input_tokens":7,"cache_read_input_tokens":1000,"output_tokens":3}},"parent_tool_use_id":null}"""));
        assertEquals(1_007, turnComplete(parser, result).contextTokens());

        assertEquals(0, turnComplete(new ClaudeCliStreamParser(), result).contextTokens(),
                "a turn whose requests reported no usage has no measurement");
    }

    @Test
    void eachFrameCarryingUsageReportsItsRequest() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        for (String line : List.of(
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":10,"cache_read_input_tokens":1000,"output_tokens":1}}},"parent_tool_use_id":null}""",
                // An aggregate repeats the usage known when its block ended.
                """
                {"type":"assistant","message":{"id":"msg-1","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":1000,"output_tokens":1}},"parent_tool_use_id":null}""",
                """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}},"parent_tool_use_id":null}""",
                // A subagent's stream events are not the main thread's; its aggregates carry its usage.
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-sub","usage":{"input_tokens":900,"output_tokens":1}}},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"assistant","message":{"id":"msg-sub","content":[],"usage":{"input_tokens":900,"output_tokens":5}},"parent_tool_use_id":"toolu_task"}""")) {
            events.addAll(parser.parse(line));
        }

        assertEquals(List.of(
                new ClaudeCliStreamParser.RequestUsage(1, "msg-1", true,
                        new ClaudeCliStreamParser.TokenCounts(10, 1, 1000, 0)),
                new ClaudeCliStreamParser.RequestUsage(2, "msg-1", true,
                        new ClaudeCliStreamParser.TokenCounts(10, 1, 1000, 0)),
                new ClaudeCliStreamParser.RequestUsage(3, "msg-1", true,
                        new ClaudeCliStreamParser.TokenCounts(0, 30, 0, 0)),
                new ClaudeCliStreamParser.RequestUsage(4, "msg-sub", false,
                        new ClaudeCliStreamParser.TokenCounts(900, 5, 0, 0))), events);
    }

    @Test
    void framesWithoutCountsOrARequestIdReportNoUsage() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(), parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1"}}}"""));
        assertEquals(List.of(), parser.parse("""
                {"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":0}}}"""));
        // Without a request id a repeat cannot be told from new usage.
        assertEquals(List.of(), parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}}"""));
        assertEquals(List.of(), parser.parse("""
                {"type":"assistant","message":{"content":[],"usage":{"input_tokens":10,"output_tokens":1}},"parent_tool_use_id":"toolu_task"}"""));
    }

    @Test
    void theParsersOfOneProcessNumberUsageAndResultsInOutputOrder() {
        AtomicLong sequences = new AtomicLong();
        String start = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-%d","usage":{"input_tokens":5,"output_tokens":1}}}}""";
        String result = """
                {"type":"result","subtype":"success","num_turns":1,"result":"done","usage":{"input_tokens":5,"output_tokens":1}}""";
        ClaudeCliStreamParser first = new ClaudeCliStreamParser(sequences);
        ClaudeCliStreamParser second = new ClaudeCliStreamParser(sequences);

        assertEquals(1, ((ClaudeCliStreamParser.RequestUsage) first.parse(start.formatted(1)).get(0)).sequence());
        assertEquals(2, turnComplete(first, result).sequence());
        assertEquals(3, ((ClaudeCliStreamParser.RequestUsage) second.parse(start.formatted(2)).get(0)).sequence());
        assertEquals(4, turnComplete(second, result).sequence());
    }

    @Test
    void theArgumentsTheModelWritesForAToolStreamAsToolInput() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        for (String line : List.of(
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1"}}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":""}}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"file_path\\": "}}}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"/tmp/a\\"}"}}}""")) {
            events.addAll(parser.parse(line));
        }

        assertEquals(List.of(new ClaudeCliStreamParser.ToolInputDelta("{\"file_path\": "),
                        new ClaudeCliStreamParser.ToolInputDelta("\"/tmp/a\"}")),
                events.stream().filter(ClaudeCliStreamParser.ToolInputDelta.class::isInstance).toList());
        assertEquals(List.of(), parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}},"parent_tool_use_id":"toolu_task"}"""),
                "a subagent's stream events are not the main thread's");
    }

    @Test
    void theTotalsAddUpEveryModelTheProcessUsed() {
        ClaudeCliStreamParser.TurnComplete turn = turnComplete(new ClaudeCliStreamParser(), """
                {"type":"result","subtype":"success","num_turns":1,"result":"done","usage":{"input_tokens":10,"output_tokens":30},"modelUsage":{"claude-opus-5-5":{"inputTokens":10,"outputTokens":30,"cacheReadInputTokens":1000,"cacheCreationInputTokens":200,"contextWindow":200000},"claude-haiku-4-5-20251001":{"inputTokens":900,"outputTokens":75,"contextWindow":200000}}}""");
        assertEquals(new ClaudeCliStreamParser.TokenCounts(910, 105, 1000, 200), turn.totals());
        assertEquals(new ClaudeCliStreamParser.TokenCounts(10, 30, 0, 0), turn.usage());

        assertEquals(ClaudeCliStreamParser.TokenCounts.ZERO, turnComplete(new ClaudeCliStreamParser(), """
                {"type":"result","subtype":"success","num_turns":1,"result":"done","modelUsage":{}}""").totals());
        assertNull(turnComplete(new ClaudeCliStreamParser(), """
                {"type":"result","subtype":"success","num_turns":1,"result":"done"}""").totals(),
                "a result without modelUsage has no totals");
    }

    @Test
    void aTurnRefusedBeforeAnyModelRequestIsNotStarted() {
        // Claude Code 2.1.282's stream-json result for `--resume <unknown id>`.
        ClaudeCliStreamParser.TurnComplete refused = (ClaudeCliStreamParser.TurnComplete)
                new ClaudeCliStreamParser().parse("""
                        {"type":"result","subtype":"error_during_execution","duration_api_ms":0,"is_error":true,"num_turns":0,"session_id":"gone","errors":["No conversation found with session ID: gone"]}
                        """.trim()).get(0);
        assertTrue(refused.error());
        assertFalse(refused.started());
        assertEquals("No conversation found with session ID: gone", refused.errorMessage());

        ClaudeCliStreamParser.TurnComplete ran = (ClaudeCliStreamParser.TurnComplete)
                new ClaudeCliStreamParser().parse("""
                        {"type":"result","subtype":"success","num_turns":2,"result":"done"}
                        """.trim()).get(0);
        assertTrue(ran.started());
    }

    @Test
    void theTurnCountsItsOwnMainThreadRequests() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        for (String line : List.of(
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1"}},"parent_tool_use_id":null}""",
                // A streamed request's aggregates are the same request.
                """
                {"type":"assistant","message":{"id":"msg-1","content":[{"type":"tool_use","id":"tool-1","name":"Bash","input":{"command":"ls"}}]},"parent_tool_use_id":null}""",
                // A subagent's requests are its task's, not the turn's.
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-sub"}},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"assistant","message":{"id":"msg-sub","content":[{"type":"text","text":"sub"}]},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-2"}},"parent_tool_use_id":null}""",
                // An older CLI streams no events: a new aggregate id is a new request.
                """
                {"type":"assistant","message":{"id":"msg-3","content":[{"type":"text","text":"done"}]},"parent_tool_use_id":null}""")) {
            parser.parse(line);
        }
        assertEquals(3, turnComplete(parser, """
                {"type":"result","subtype":"success","num_turns":7,"result":"done"}""").requests(),
                "num_turns is not the count: a process that keeps its session may report it cumulatively");

        parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-4"}},"parent_tool_use_id":null}""");
        assertEquals(1, turnComplete(parser, """
                {"type":"result","subtype":"success","num_turns":8,"result":"again"}""").requests(),
                "the next turn counts only its own requests");
        assertEquals(0, turnComplete(parser, """
                {"type":"result","subtype":"success","num_turns":1,"result":""}""").requests(),
                "a turn whose frames carried no message id reports none");
    }

    @Test
    void toolProgressIsStreamedAsToolOutput() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        parser.parse("""
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg"}}}
                """.trim());
        parser.parse("""
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Bash","input":{}}}}
                """.trim());
        assertEquals(List.of(new ClaudeCliStreamParser.ToolOutput("tool-1", "Bash", "working")),
                parser.parse("""
                        {"type":"tool_progress","tool_use_id":"tool-1","content":"working"}
                        """.trim()));
    }

    @Test
    void elapsedOnlyHeartbeatsAreActivityNotToolOutput() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.ToolProgress("tool-1", "Bash", 12_500)),
                parser.parse("""
                        {"type":"tool_progress","tool_use_id":"tool-1","tool_name":"Bash","elapsed_time_seconds":12.5}
                        """));
        assertEquals(List.of(), parser.parse("""
                {"type":"tool_progress","tool_use_id":"tool-1","elapsed_time_seconds":-1}
                """));
        assertEquals(List.of(), parser.parse("""
                {"type":"tool_progress","tool_use_id":"tool-1","elapsed_time_seconds":15,"parent_tool_use_id":"agent-1"}
                """));
    }

    @Test
    void quietProviderPhasesAreTransientActivity() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.Activity("Waiting for Claude response")),
                parser.parse("""
                        {"type":"system","subtype":"status","status":"requesting"}
                        """));
        for (String line : List.of(
                """
                {"type":"system","subtype":"thinking_tokens","estimated_tokens":120}
                """,
                """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}}
                """,
                """
                {"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"private"}}}
                """)) {
            assertEquals(List.of(new ClaudeCliStreamParser.Activity("Thinking")), parser.parse(line));
        }
        assertEquals(List.of(), parser.parse("""
                {"type":"system","subtype":"thinking_tokens","estimated_tokens":120,"parent_tool_use_id":"agent-1"}
                """));
    }

    @Test
    void initSystemEventBindsSessionButLaterSystemEventsDoNot() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.SessionInit("native-1")),
                parser.parse("""
                        {"type":"system","subtype":"init","session_id":"native-1"}
                        """.trim()));
        // A mid-stream system event carrying a session_id (e.g. compaction) must
        // surface as its own event, not be swallowed as a second session start.
        assertEquals(List.of(new ClaudeCliStreamParser.Compacted("manual", 100)),
                parser.parse("""
                        {"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":100},"session_id":"native-1","uuid":"u"}
                        """.trim()));
    }

    @Test
    void hookAndSessionBookkeepingProducesNoEvents() {
        // Claude Code 2.1.282 shapes. Each used to render as "[Claude] <status>",
        // "[Claude] <subtype>" or the event's JSON.
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        for (String line : List.of(
                """
                {"type":"system","subtype":"status","status":null,"permissionMode":"plan","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"status","status":null,"compact_result":"success","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"hook_started","hook_id":"h","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"hook_response","hook_id":"h","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","output":"ok","stdout":"ok","stderr":"","exit_code":0,"session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"task_updated","task_id":"t","patch":{"description":"Explore the repo"},"session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"session_state_changed","state":"running","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"notification","key":"k","priority":"high","session_id":"s","uuid":"u"}""")) {
            assertEquals(List.of(), parser.parse(line), line);
        }
    }

    @Test
    void taskLifecycleEventsAreTyped() {
        // Claude Code 2.1.282 shapes for a background subagent.
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        for (String line : List.of(
                """
                {"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_1","description":"Explore the repo","subagent_type":"Explore","is_backgrounded":true,"task_type":"local_agent","prompt":"look","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"task_progress","task_id":"a1","tool_use_id":"toolu_1","description":"Explore the repo","subagent_type":"Explore","usage":{"total_tokens":10,"tool_uses":1,"duration_ms":5},"last_tool_name":"Grep","summary":"Searching","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"a1","task_type":"local_agent","description":"Explore the repo"}],"session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"task_updated","task_id":"a1","patch":{"status":"completed","end_time":9},"session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"toolu_1","status":"completed","output_file":"/tmp/a1.output","summary":"Found 3 callers","usage":{"total_tokens":10,"tool_uses":1,"duration_ms":9},"session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"background_tasks_changed","tasks":[],"session_id":"s","uuid":"u"}""",
                // Without the flag Claude Code counts a task as backgrounded.
                """
                {"type":"system","subtype":"task_started","task_id":"b2","description":"mvn install","task_type":"local_bash","session_id":"s","uuid":"u"}""",
                // A paused task has not ended.
                """
                {"type":"system","subtype":"task_updated","task_id":"b2","patch":{"status":"paused"},"session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"task_updated","task_id":"b2","patch":{"status":"killed","error":"stopped by the host"},"session_id":"s","uuid":"u"}""",
                // An interrupted turn's foreground command, ended by its notification alone.
                """
                {"type":"system","subtype":"task_started","task_id":"c3","tool_use_id":"toolu_3","description":"Ping localhost 20 times","is_backgrounded":false,"task_type":"local_bash","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"task_notification","task_id":"c3","tool_use_id":"toolu_3","status":"stopped","output_file":"","summary":"Ping localhost 20 times","session_id":"s","uuid":"u"}""")) {
            events.addAll(parser.parse(line));
        }
        assertEquals(List.of(
                new ClaudeCliStreamParser.TaskStarted("a1", "toolu_1", "Explore the repo", "local_agent", true),
                new ClaudeCliStreamParser.TaskProgress("a1", "Explore the repo", "Grep", "Searching", 10, 1, 5),
                new ClaudeCliStreamParser.BackgroundTasks(List.of("a1")),
                new ClaudeCliStreamParser.TaskEnded("a1", "completed", ""),
                new ClaudeCliStreamParser.TaskEnded("a1", "completed", "Found 3 callers"),
                new ClaudeCliStreamParser.BackgroundTasks(List.of()),
                new ClaudeCliStreamParser.TaskStarted("b2", "", "mvn install", "local_bash", true),
                new ClaudeCliStreamParser.TaskEnded("b2", "killed", "stopped by the host"),
                new ClaudeCliStreamParser.TaskStarted("c3", "toolu_3", "Ping localhost 20 times", "local_bash", false),
                new ClaudeCliStreamParser.TaskEnded("c3", "stopped", "Ping localhost 20 times")), events);
    }

    @Test
    void aSubagentsFramesAreNotTheAnswer() {
        // A background subagent's frames interleave with the main thread's.
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        for (String line : List.of(
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-main"}},"parent_tool_use_id":null}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Main answer"}},"parent_tool_use_id":null}""",
                """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-sub"}},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Subagent notes"}},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"assistant","message":{"id":"msg-sub","content":[{"type":"text","text":"Subagent notes"},{"type":"tool_use","id":"toolu_sub","name":"Grep","input":{"pattern":"x"}}]},"parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"tool_progress","tool_use_id":"toolu_sub","content":"searching","parent_tool_use_id":"toolu_task"}""",
                """
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_sub","content":"3 matches"}]},"parent_tool_use_id":"toolu_task"}""",
                // The main thread's aggregate repeats what it streamed before the subagent's frames.
                """
                {"type":"assistant","message":{"id":"msg-main","content":[{"type":"text","text":"Main answer"}]},"parent_tool_use_id":null}""")) {
            events.addAll(parser.parse(line));
        }
        assertEquals(List.of(new ClaudeCliStreamParser.Text("Main answer")), events);
    }

    @Test
    void framesCarryTheClientMessagesTheirTurnConsumed() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        // --replay-user-messages echoes a client message as the turn takes it in.
        assertEquals(List.of(new ClaudeCliStreamParser.ConsumedUserMessages(List.of("c-1"))),
                parser.parse("""
                        {"type":"user","message":{"role":"user","content":"hello"},"session_id":"s","parent_tool_use_id":null,"uuid":"c-1","timestamp":"t","isReplay":true}"""));
        // The turn's first stream event and first assistant message are stamped.
        assertEquals(List.of(new ClaudeCliStreamParser.ConsumedUserMessages(List.of("c-1")),
                        new ClaudeCliStreamParser.Text("Hi")),
                parser.parse("""
                        {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}},"parent_tool_use_id":null,"user_message_uuid":"c-1","user_message_uuids":["c-1"]}"""));
        assertEquals(List.of(new ClaudeCliStreamParser.ConsumedUserMessages(List.of("c-1"))),
                parser.parse("""
                        {"type":"assistant","message":{"id":"m","content":[]},"parent_tool_use_id":null,"user_message_uuid":"c-1"}"""));
        // The result lists every message the turn consumed, a folded-in one included.
        List<ClaudeCliStreamParser.Event> result = parser.parse("""
                {"type":"result","subtype":"success","num_turns":1,"result":"Hi","user_message_uuid":"c-2","user_message_uuids":["c-1","c-2"]}""");
        assertEquals(new ClaudeCliStreamParser.ConsumedUserMessages(List.of("c-1", "c-2")), result.get(0));
        assertTrue(result.get(1) instanceof ClaudeCliStreamParser.TurnComplete);

        // A turn Claude Code starts by itself carries no client uuid, and a tool
        // result's own uuid is not one.
        assertEquals(1, parser.parse("""
                {"type":"result","subtype":"success","num_turns":1,"result":"The agent finished"}""").size());
        assertEquals(List.of(), parser.parse("""
                {"type":"user","message":{"content":"<task-notification>"},"parent_tool_use_id":null,"uuid":"internal"}"""));
    }

    @Test
    void systemEventsCarryingReadableTextBecomeNotices() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        List<ClaudeCliStreamParser.Event> events = new ArrayList<>();
        for (String line : List.of(
                """
                {"type":"system","subtype":"status","status":"compacting","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"notification","key":"k","text":"Fast mode is unavailable","priority":"high","color":"warning","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"informational","content":"Context is 90% full","level":"notice","session_id":"s","uuid":"u"}""",
                """
                {"type":"system","subtype":"model_fallback","content":"Switched to claude-sonnet-5","level":"warning","trigger":"overloaded","session_id":"s","uuid":"u"}""")) {
            events.addAll(parser.parse(line));
        }
        assertEquals(List.of(
                new ClaudeCliStreamParser.Activity("Compacting Claude context"),
                new ClaudeCliStreamParser.Notice("Compacting conversation"),
                new ClaudeCliStreamParser.Notice("Fast mode is unavailable"),
                new ClaudeCliStreamParser.Notice("Context is 90% full"),
                new ClaudeCliStreamParser.Notice("Switched to claude-sonnet-5")), events);
    }

    @Test
    void compactionOutcomesAreTypedEvents() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.Compacted("auto", 0)),
                parser.parse("""
                        {"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto"},"session_id":"s","uuid":"u"}
                        """.trim()));
        assertEquals(List.of(new ClaudeCliStreamParser.Compacted("", 0)),
                parser.parse("""
                        {"type":"system","subtype":"compact_boundary","session_id":"s","uuid":"u"}
                        """.trim()));
        assertEquals(List.of(new ClaudeCliStreamParser.CompactionFailed("prompt too long")),
                parser.parse("""
                        {"type":"system","subtype":"status","status":null,"compact_result":"failed","compact_error":"prompt too long","session_id":"s","uuid":"u"}
                        """.trim()));
        assertEquals(List.of(new ClaudeCliStreamParser.CompactionFailed("")),
                parser.parse("""
                        {"type":"system","subtype":"status","status":null,"compact_result":"failed","compact_error":null,"session_id":"s","uuid":"u"}
                        """.trim()));
    }

    @Test
    void compactionFailuresClaudeCodeDoesNotShowAsErrorsProduceNoEvents() {
        // Claude Code 2.1.282 reports an aborted compaction, one with too little to
        // summarize and one a hook blocked as failed; the session still fits.
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        for (String detail : List.of("aborted", "too_few_groups", "Request was aborted.",
                "Not enough messages to compact.", "Compaction blocked by PreCompact hook: policy")) {
            assertEquals(List.of(), parser.parse(compactionFailure(detail)), detail);
        }
        for (String detail : List.of("exhausted",
                "Compaction failed · conversation could not be reduced below the context limit")) {
            assertEquals(List.of(new ClaudeCliStreamParser.CompactionFailed(detail)),
                    parser.parse(compactionFailure(detail)), detail);
        }
    }

    @Test
    void theTurnReportsTheLimitsClaudeCodeAppliesToTheSessionModel() {
        // modelUsage holds every model the turn used, side models included.
        String result = """
                {"type":"result","subtype":"success","num_turns":1,"result":"done","modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":10,"contextWindow":200000,"maxOutputTokens":64000},"claude-fable-5-1":{"inputTokens":90,"contextWindow":1000000,"maxOutputTokens":128000}}}""";
        for (String model : List.of("claude-fable-5-1", "claude-fable-5-1[1m]")) {
            ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
            parser.parse("""
                    {"type":"system","subtype":"init","session_id":"s","model":"%s"}""".formatted(model));
            ClaudeCliStreamParser.TurnComplete turn = turnComplete(parser, result);
            assertEquals(1_000_000, turn.contextWindow(), model);
            assertEquals(128_000, turn.maxOutputTokens(), model);
        }

        ClaudeCliStreamParser.TurnComplete withoutInit = turnComplete(new ClaudeCliStreamParser(), result);
        assertEquals(200_000, withoutInit.contextWindow(),
                "without the session's model, the smallest window is the one no request exceeds");
        assertEquals(64_000, withoutInit.maxOutputTokens());

        ClaudeCliStreamParser.TurnComplete unreported = turnComplete(new ClaudeCliStreamParser(), """
                {"type":"result","subtype":"success","num_turns":1,"result":"done"}""");
        assertEquals(0, unreported.contextWindow());
        assertEquals(0, unreported.maxOutputTokens());
    }

    @Test
    void apiRetryIsATransientRetryNotANotice() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.Retry(2, 10, 1500, "overloaded, HTTP 529")),
                parser.parse("""
                        {"type":"system","subtype":"api_retry","attempt":2,"max_retries":10,"retry_delay_ms":1500,"error_status":529,"error":"overloaded","session_id":"s","uuid":"u"}
                        """.trim()));
        assertEquals(List.of(new ClaudeCliStreamParser.Retry(1, 10, 500, "request failed")),
                parser.parse("""
                        {"type":"system","subtype":"api_retry","attempt":1,"max_retries":10,"retry_delay_ms":500,"error_status":null,"error":"unknown","no_response":{"waited_ms":1,"retry_wait_ms":500},"session_id":"s","uuid":"u"}
                        """.trim()));
    }

    @Test
    void aFailedApiRequestIsAnErrorNotAnswerText() {
        // Claude Code 2.1.282 wraps a rejected request in a synthetic assistant message.
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertEquals(List.of(new ClaudeCliStreamParser.ApiError("Prompt is too long")),
                parser.parse("""
                        {"type":"assistant","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Prompt is too long"}]},"parent_tool_use_id":null,"error":"invalid_request","is_api_error_message":true,"session_id":"s","uuid":"u"}
                        """.trim()));
        // A CLI without the flag still gives it the synthetic model and an error.
        assertEquals(List.of(new ClaudeCliStreamParser.ApiError("API Error: Repeated 529 Overloaded errors")),
                parser.parse("""
                        {"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"API Error: Repeated 529 Overloaded errors"}]},"parent_tool_use_id":null,"error":"server_error"}
                        """.trim()));
        // A subagent's failed request does not end the turn.
        assertEquals(List.of(new ClaudeCliStreamParser.Notice("Prompt is too long")),
                parser.parse("""
                        {"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"Prompt is too long"}]},"parent_tool_use_id":"toolu_task","error":"invalid_request","is_api_error_message":true}
                        """.trim()));
        assertEquals(List.of(new ClaudeCliStreamParser.Text("the error is handled")),
                parser.parse("""
                        {"type":"assistant","message":{"id":"msg-1","model":"claude-opus-5-5","content":[{"type":"text","text":"the error is handled"}]},"parent_tool_use_id":null}
                        """.trim()));
    }

    @Test
    void toolResultItemsRenderAsTextNeverAsJson() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        parser.parse("""
                {"type":"assistant","message":{"id":"msg-search","content":[{"type":"tool_use","id":"tool-s","name":"ToolSearch","input":{"query":"grep"}}]}}
                """.trim());
        List<ClaudeCliStreamParser.Event> events = parser.parse("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-s","content":[{"type":"tool_reference","tool_name":"mcp__kompile__grep"},{"type":"tool_reference","tool_name":"WebFetch"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}},{"type":"document","source":{"type":"text","data":"secret"}},{"note":"untyped"}]}]}}
                """.trim());
        assertEquals(List.of(new ClaudeCliStreamParser.ToolComplete("tool-s", "ToolSearch",
                "mcp__kompile__grep\nWebFetch\n[image result: image/png]\n[document result]", false)), events);
    }

    @Test
    void aLineThatIsNotJsonIsRejectedForTheTransportDiagnostics() {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"type\":\"system\",\"subtype\""));
        assertEquals(List.of(), parser.parse("[1,2]"));
    }

    private static ClaudeCliStreamParser.TurnComplete turnComplete(
            ClaudeCliStreamParser parser, String resultLine) {
        return (ClaudeCliStreamParser.TurnComplete) parser.parse(resultLine).get(0);
    }

    private static String compactionFailure(String detail) {
        return """
                {"type":"system","subtype":"status","status":null,"compact_result":"failed","compact_error":"%s","session_id":"s","uuid":"u"}"""
                .formatted(detail);
    }
}
