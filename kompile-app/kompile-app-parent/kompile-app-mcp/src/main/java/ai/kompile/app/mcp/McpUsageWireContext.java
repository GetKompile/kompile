/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.mcp;

import ai.kompile.cli.common.metrics.ToolCallUsage;

/**
 * Per-thread handoff of finalized usage from a tool handler to the transport layer.
 *
 * <p>SDK 0.10.0 exposes NO session or request id on McpSyncServerExchange, and
 * CallToolResult has no _meta field (verified in Task 0). The deterministic seam is
 * therefore: the tool handler finalizes usage, stashes it HERE, and the kompile-owned
 * SessionTransport.sendMessage — which serializes the JSONRPCResponse on the same
 * handling thread for sync specifications — substitutes the wire result with an
 * equivalent node carrying _meta["ai.kompile/usage"] (compile+run verified in Task 0's
 * smoke test). The holder is consumed (cleared) on read so a missed send never leaks
 * usage into an unrelated response.</p>
 *
 * <p>Pure serialization never creates or mutates usage; this is projection only.</p>
 */
public final class McpUsageWireContext {

    private static final ThreadLocal<ToolCallUsage> CURRENT = new ThreadLocal<>();

    private McpUsageWireContext() {
    }

    /** Handler side: publish the usage for the response being produced on this thread. */
    public static void publish(ToolCallUsage usage) {
        if (usage == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(usage);
        }
    }

    /**
     * Transport side: take (consume) the usage for this thread, or null when the
     * response carries no usage (non-tool responses, missed correlation, async hops).
     */
    public static ToolCallUsage take() {
        ToolCallUsage usage = CURRENT.get();
        CURRENT.remove();
        return usage;
    }

    /** Test/diagnostic support: true when a usage value is pending on this thread. */
    public static boolean pending() {
        return CURRENT.get() != null;
    }

    /** Test support: clear without consuming. */
    public static void clear() {
        CURRENT.remove();
    }
}
