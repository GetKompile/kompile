/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Progress observations streamed from a provider. Moved verbatim from
 * {@code ProviderActivityListener}; the nested interface keeps
 * its original call contract.
 */
public interface ProviderActivityListener {
    void onToolStart(String callId, String name, String input);
    default void onToolInput(String callId, String name, String input) { }
    default void onToolOutput(String callId, String name, String output) { }
    default void onToolProgress(String callId, String name, long elapsedMillis) { }
    /** Transient provider phase, not transcript text. */
    default void onActivity(String label) { }
    /**
     * A fragment of tool-call arguments as the model streams them. Their tokens
     * are output the request's usage reports when it ends.
     */
    default void onToolInputDelta(String delta) { }
    void onToolComplete(String callId, String name, String output,
                        int exitCode, boolean error);
    /** Settled usage deltas, never cumulative SSE snapshots; input/cache categories are disjoint. */
    default void onTokenUsage(long input, long output,
                              long cacheRead, long cacheCreation) { }
    /** Latest main-thread request input, including cache; not a billing delta. */
    default void onContextUsage(long inputTokens) { }
    /** Limits the native provider actually enforces, when it reports them. */
    default void onModelLimits(int contextWindow, int maxOutputTokens) { }
    default void onNotice(String text) { }
    default void onCompactionStarted() { }
    /** A harmless abort/no-op, not a context-overflow failure. */
    default void onCompactionSkipped(String detail) { }
    /**
     * The provider compacted a session it owns. {@code trigger} is its reason
     * ({@code auto}, {@code manual}), empty when not reported;
     * {@code tokensBefore} is 0 when not reported.
     */
    default void onCompacted(String trigger, long tokensBefore) { }
    /** The provider failed to compact a session it owns; {@code detail} may be empty. */
    default void onCompactionFailed(String detail) { }
    /**
     * The provider made {@code steps} more model requests in the agent loop it runs
     * for the turn (Claude Code, OpenCode); calls add up. Routes whose tool loop
     * Kompile runs make one request per call and report none.
     */
    default void onSteps(int steps) { }
}
