/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.Event;
import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.RequestUsage;
import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.TokenCounts;
import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.TurnComplete;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeCliUsageLedgerTest {
    private static final TokenCounts ZERO = TokenCounts.ZERO;

    @Test
    void requestsCountWhileTheyRunAndTheResultAddsNothingTheyReported() {
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(false);
        List<TokenCounts> added = feed(ledger, parse(new ClaudeCliStreamParser(),
                start("msg_1", 10, 1, 0, 0),
                aggregate("msg_1", null, 10, 1, 0, 0),
                delta(42),
                start("msg_2", 12, 1, 0, 0),
                aggregate("msg_2", null, 12, 1, 0, 0),
                delta(12),
                result(22, 54, 0, 0, null)));

        // Each aggregate repeats its request's usage; the result's usage adds up the requests.
        assertEquals(List.of(counts(10, 1), ZERO, counts(0, 41), counts(12, 1), ZERO, counts(0, 11), ZERO),
                added);
        assertEquals(counts(22, 54), sum(added));
    }

    @Test
    void theTotalsSettleTheSubagentOutputNoRequestReported() {
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(false);
        List<TokenCounts> added = feed(ledger, parse(new ClaudeCliStreamParser(),
                start("msg_main", 10, 1, 1000, 0),
                aggregate("msg_sub", "toolu_task", 900, 5, 0, 0),
                aggregate("msg_sub", "toolu_task", 900, 5, 0, 0),
                // Newer APIs repeat the input counts with the final output.
                delta(10, 30, 1000, 0),
                result(10, 30, 1000, 0, "{\"claude-opus-5-5\":" + model(10, 30, 1000, 0)
                        + ",\"claude-haiku-4-5\":" + model(900, 75, 0, 0) + "}")));

        // A subagent's input counts at once; its output only the totals know.
        assertEquals(List.of(counts(10, 1, 1000, 0), counts(900, 0), ZERO, counts(0, 29), counts(0, 75)),
                added);
        assertEquals(counts(910, 105, 1000, 0), sum(added));
    }

    @Test
    void theTotalsAddWhatRanOutsideTheRequestsOnce() {
        AtomicLong sequences = new AtomicLong();
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(false);
        // Compaction and side requests report no usage of their own.
        List<TokenCounts> first = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_1", 10, 1, 0, 0), delta(20), result(10, 20, 0, 0, modelUsage(5010, 820))));
        List<TokenCounts> second = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_2", 30, 1, 0, 0), delta(5), result(30, 5, 0, 0, modelUsage(5040, 825))));

        assertEquals(List.of(counts(10, 1), counts(0, 19), counts(5000, 800)), first);
        assertEquals(List.of(counts(30, 1), counts(0, 4), ZERO), second);
    }

    @Test
    void aResumedProcessCountsFromItsFirstTotals() {
        AtomicLong sequences = new AtomicLong();
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(true);
        // The first totals include what the session used before this process started.
        List<TokenCounts> first = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_1", 10, 1, 0, 0), delta(20), result(10, 20, 0, 0, modelUsage(50_010, 20_020))));
        List<TokenCounts> second = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_2", 5, 1, 0, 0), delta(9), result(5, 9, 0, 0, modelUsage(50_015, 20_069))));

        assertEquals(List.of(counts(10, 1), counts(0, 19), ZERO), first);
        assertEquals(List.of(counts(5, 1), counts(0, 8), counts(0, 40)), second);
    }

    @Test
    void usageTheTotalsAlreadyCoveredDoesNotCountWhenItShowsLate() {
        AtomicLong sequences = new AtomicLong();
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(false);
        feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_1", 10, 1, 0, 0), delta(20), result(10, 20, 0, 0, modelUsage(10, 20))));
        // A turn Claude Code started by itself, then the user's next turn. The
        // chat adopts the first only after the second's result counted.
        List<Event> followUp = parse(new ClaudeCliStreamParser(sequences),
                start("msg_f", 100, 1, 0, 0), delta(50), result(100, 50, 0, 0, modelUsage(110, 70)));
        List<Event> next = parse(new ClaudeCliStreamParser(sequences),
                start("msg_2", 5, 1, 0, 0), delta(9), result(5, 9, 0, 0, modelUsage(115, 79)));

        assertEquals(List.of(counts(5, 1), counts(0, 8), counts(100, 50)), feed(ledger, next));
        assertEquals(List.of(ZERO, ZERO, ZERO), feed(ledger, followUp));
    }

    @Test
    void lowerTotalsStartTheCountAfresh() {
        AtomicLong sequences = new AtomicLong();
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(false);
        feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_1", 10, 1, 0, 0), delta(20), result(10, 20, 0, 0, modelUsage(10, 20))));
        List<TokenCounts> lower = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_2", 3, 1, 0, 0), delta(4), result(3, 4, 0, 0, modelUsage(3, 4))));
        List<TokenCounts> after = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_3", 2, 1, 0, 0), delta(6), result(2, 6, 0, 0, modelUsage(5, 17))));

        assertEquals(List.of(counts(3, 1), counts(0, 3), ZERO), lower);
        assertEquals(List.of(counts(2, 1), counts(0, 5), counts(0, 7)), after);
    }

    @Test
    void emptyTotalsDoNotAnchorAResumedProcess() {
        AtomicLong sequences = new AtomicLong();
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(true);
        List<TokenCounts> first = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_1", 10, 1, 0, 0), delta(20), result(10, 20, 0, 0, "{}")));
        // Had the empty totals anchored the count, the 40,010 input and 10,020
        // output the session was restored with would count as this turn's.
        List<TokenCounts> second = feed(ledger, parse(new ClaudeCliStreamParser(sequences),
                start("msg_2", 5, 1, 0, 0), delta(9), result(5, 9, 0, 0, modelUsage(40_015, 10_029))));

        assertEquals(List.of(counts(10, 1), counts(0, 19), ZERO), first);
        assertEquals(List.of(counts(5, 1), counts(0, 8), ZERO), second);
    }

    @Test
    void withoutTotalsTheResultAddsTheMainThreadUsageNoRequestReported() {
        ClaudeCliUsageLedger ledger = new ClaudeCliUsageLedger(false);
        List<TokenCounts> added = feed(ledger, parse(new ClaudeCliStreamParser(),
                start("msg_1", 10, 1, 0, 0), result(10, 7, 0, 0, null)));

        assertEquals(List.of(counts(10, 1), counts(0, 6)), added);
        assertEquals(ZERO, ledger.request(new RequestUsage(99, "", true, counts(5, 5))),
                "usage without a request id cannot be told from a repeat");
    }

    private static List<Event> parse(ClaudeCliStreamParser parser, String... lines) {
        List<Event> events = new ArrayList<>();
        for (String line : lines) events.addAll(parser.parse(line));
        return events;
    }

    /** What the ledger adds for each usage and result event, in order. */
    private static List<TokenCounts> feed(ClaudeCliUsageLedger ledger, List<Event> events) {
        List<TokenCounts> added = new ArrayList<>();
        for (Event event : events) {
            if (event instanceof RequestUsage usage) added.add(ledger.request(usage));
            else if (event instanceof TurnComplete complete) added.add(ledger.result(complete));
        }
        return added;
    }

    private static TokenCounts sum(List<TokenCounts> added) {
        TokenCounts total = ZERO;
        for (TokenCounts counts : added) total = total.plus(counts);
        return total;
    }

    private static TokenCounts counts(long input, long output) {
        return counts(input, output, 0, 0);
    }

    private static TokenCounts counts(long input, long output, long cacheRead, long cacheCreation) {
        return new TokenCounts(input, output, cacheRead, cacheCreation);
    }

    private static String start(String id, long input, long output, long cacheRead, long cacheCreation) {
        return """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"%s","usage":%s}}}"""
                .formatted(id, usage(input, output, cacheRead, cacheCreation));
    }

    private static String delta(long output) {
        return """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":%d}}}"""
                .formatted(output);
    }

    private static String delta(long input, long output, long cacheRead, long cacheCreation) {
        return """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":%s}}"""
                .formatted(usage(input, output, cacheRead, cacheCreation));
    }

    private static String aggregate(String id, String parent, long input, long output,
                                    long cacheRead, long cacheCreation) {
        return """
                {"type":"assistant","parent_tool_use_id":%s,"message":{"id":"%s","content":[],"usage":%s}}"""
                .formatted(parent == null ? "null" : "\"" + parent + "\"", id,
                        usage(input, output, cacheRead, cacheCreation));
    }

    private static String result(long input, long output, long cacheRead, long cacheCreation, String modelUsage) {
        return """
                {"type":"result","subtype":"success","is_error":false,"num_turns":1,"result":"done","usage":%s%s}"""
                .formatted(usage(input, output, cacheRead, cacheCreation),
                        modelUsage == null ? "" : ",\"modelUsage\":" + modelUsage);
    }

    private static String modelUsage(long input, long output) {
        return "{\"claude-opus-5-5\":" + model(input, output, 0, 0) + "}";
    }

    private static String model(long input, long output, long cacheRead, long cacheCreation) {
        return """
                {"inputTokens":%d,"outputTokens":%d,"cacheReadInputTokens":%d,"cacheCreationInputTokens":%d,"contextWindow":200000}"""
                .formatted(input, output, cacheRead, cacheCreation);
    }

    private static String usage(long input, long output, long cacheRead, long cacheCreation) {
        return """
                {"input_tokens":%d,"output_tokens":%d,"cache_read_input_tokens":%d,"cache_creation_input_tokens":%d}"""
                .formatted(input, output, cacheRead, cacheCreation);
    }
}
