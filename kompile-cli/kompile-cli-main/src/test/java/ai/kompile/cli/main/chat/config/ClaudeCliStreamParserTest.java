/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(7, result.inputTokens());
        assertEquals(4, result.outputTokens());
    }

    @Test
    void toolProgressAndSystemNoticesArePreserved() {
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
        assertEquals(List.of(new ClaudeCliStreamParser.Notice("Compacting context")),
                parser.parse("""
                        {"type":"system","subtype":"status","message":"Compacting context"}
                        """.trim()));
    }
}
