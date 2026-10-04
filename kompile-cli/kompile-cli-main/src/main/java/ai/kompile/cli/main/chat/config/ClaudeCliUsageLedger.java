/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.RequestUsage;
import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.TokenCounts;
import ai.kompile.cli.main.chat.config.ClaudeCliStreamParser.TurnComplete;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tokens one {@code claude -p} process used, as deltas that count each token
 * once. Requests report their usage as they run, so a turn's tokens count while
 * it works. A result's {@code modelUsage} totals then settle what no request
 * reported: a subagent's output, compaction and side requests. A result without
 * totals settles the main thread's usage its requests did not report.
 *
 * <p>A turn Claude Code started by itself shows when the chat adopts it, which
 * can be after a later result. Totals cover every request before them, so usage
 * from before the last totals counted is skipped. A resumed process's totals
 * include what the session used before it started, so its first totals are the
 * starting point, not usage.</p>
 */
final class ClaudeCliUsageLedger {
    /** How many requests' usage is remembered to tell repeated reports apart. */
    static final int MAX_REQUESTS = 256;

    // The usage each request reported, least recently reported first.
    private final Map<String, TokenCounts> requests = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TokenCounts> eldest) {
            return size() > MAX_REQUESTS;
        }
    };
    // What the counted usage adds up to on the totals' scale; null until a
    // resumed process's first totals say where that scale starts.
    private TokenCounts counted;
    // The latest totals; lower ones mean Claude Code counts afresh.
    private TokenCounts lastTotals = TokenCounts.ZERO;
    // Main-thread usage counted since the last result, which that result's usage covers.
    private TokenCounts mainSinceResult = TokenCounts.ZERO;
    // The output position of the last totals counted.
    private long settledThrough;

    ClaudeCliUsageLedger(boolean resumed) {
        counted = resumed ? null : TokenCounts.ZERO;
    }

    /** The tokens a request's usage adds; none for a repeat or for usage totals already covered. */
    synchronized TokenCounts request(RequestUsage usage) {
        if (usage.sequence() <= settledThrough || usage.requestId().isBlank()) return TokenCounts.ZERO;
        // A subagent's output is its count when the request started; the totals settle it.
        TokenCounts reported = usage.mainThread() ? usage.usage() : usage.usage().withoutOutput();
        TokenCounts seen = requests.getOrDefault(usage.requestId(), TokenCounts.ZERO);
        TokenCounts merged = seen.max(reported);
        TokenCounts added = merged.above(seen);
        requests.put(usage.requestId(), merged);
        if (counted != null) counted = counted.plus(added);
        if (usage.mainThread()) mainSinceResult = mainSinceResult.plus(added);
        return added;
    }

    /** The tokens a result adds to what the requests before it reported. */
    synchronized TokenCounts result(TurnComplete complete) {
        if (complete.sequence() <= settledThrough) return TokenCounts.ZERO;
        TokenCounts totals = complete.totals();
        // Empty totals tell nothing: no request ran yet, or the result left them out.
        if (totals != null && totals.isZero()) totals = null;
        TokenCounts added;
        if (totals != null && counted != null && totals.covers(lastTotals)) {
            added = totals.above(counted);
            counted = counted.max(totals);
            settledThrough = complete.sequence();
        } else {
            added = complete.usage().above(mainSinceResult);
            if (totals != null) {
                // A resumed process's first totals, or Claude Code counting afresh.
                counted = totals;
                settledThrough = complete.sequence();
            } else if (counted != null) {
                counted = counted.plus(added);
            }
        }
        if (totals != null) lastTotals = totals;
        mainSinceResult = TokenCounts.ZERO;
        return added;
    }
}
