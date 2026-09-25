/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * Logical session identity for app-side MCP invocations.
 *
 * <p>Contract (Task 4): a client-supplied session value is CORRELATION, not
 * authorization. A generated fallback carries explicit provenance so identified
 * conversations are never silently merged under a service-instance catalog session
 * id (the previous "mcp-app-&lt;uuid&gt;" behavior is retained only as a labeled
 * fallback, never as a merge key).</p>
 */
public final class McpSessionContext {

    public static final String PROVENANCE_EXCHANGE_CLIENT = "exchange-client-info";
    public static final String PROVENANCE_CLIENT_DECLARED = "client-declared";
    public static final String PROVENANCE_SERVICE_GENERATED = "service-generated";

    private McpSessionContext() {
    }

    /**
     * Resolve the logical session id for an exchange. The SDK 0.10.0 exchange exposes
     * only clientInfo (Implementation: name/version) and capabilities — no session id —
     * so identity is: clientInfo.name when present (provenance-labeled), else the
     * per-process generated fallback (explicitly service-generated, never merged with
     * host conversation ids).
     */
    public static String logicalSessionId(McpSyncServerExchange exchange) {
        try {
            if (exchange != null && exchange.getClientInfo() != null
                    && exchange.getClientInfo().name() != null
                    && !exchange.getClientInfo().name().isBlank()) {
                return "mcp-client-" + exchange.getClientInfo().name();
            }
        } catch (Exception unavailable) {
            // fall through to generated fallback
        }
        return "mcp-app-" + java.util.UUID.randomUUID();
    }

    /** Provenance for the id returned by {@link #logicalSessionId}. */
    public static String provenanceOf(String sessionId) {
        if (sessionId != null && sessionId.startsWith("mcp-client-")) {
            return PROVENANCE_EXCHANGE_CLIENT;
        }
        if (sessionId != null && sessionId.startsWith("mcp-app-")) {
            return PROVENANCE_SERVICE_GENERATED;
        }
        return PROVENANCE_CLIENT_DECLARED;
    }
}
