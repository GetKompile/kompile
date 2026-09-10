/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.core.crawl.graph;

import java.time.Duration;

/**
 * Host-owned native chat boundary for graph extraction. Implementations resolve credentials
 * outside crawl configuration and execute one deadline/output-bounded turn without tool
 * execution access. The legacy completion method remains text-only; structured schema pre-passes
 * opt in explicitly through the validated JSON contract below.
 */
@FunctionalInterface
public interface NativeChatCompletion {
    String complete(String provider, String model, String prompt, String systemPrompt,
                    Duration timeout) throws Exception;

    /** Request-scoped thinking overload; legacy implementations inherit their configured policy. */
    default String complete(String provider, String model, String thinking, String prompt,
                            String systemPrompt, Duration timeout) throws Exception {
        return complete(provider, model, prompt, systemPrompt, timeout);
    }

    /**
     * Capability proof for the request-scoped provider/model selection. The default is deliberately
     * unsupported so existing text-only lambdas cannot accidentally become a schema backend.
     */
    default boolean supportsStructuredChat(String provider, String model, String thinking) {
        return false;
    }

    /**
     * Execute one schema-constrained native text turn. The returned value is JSON text validated by
     * the provider adapter and caller-side parsing; this method never exposes tool execution or
     * provider tool calls to the untrusted model.
     */
    default String completeStructuredJson(String provider, String model, String thinking,
                                          String prompt, String systemPrompt,
                                          java.util.Map<String, Object> schema,
                                          Duration timeout) throws Exception {
        throw new UnsupportedOperationException(
                "Native chat provider does not expose a validated JSON-schema contract");
    }
}
