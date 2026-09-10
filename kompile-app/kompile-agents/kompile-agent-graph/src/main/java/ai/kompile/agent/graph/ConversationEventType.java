/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

/**
 * Provider-neutral durable conversation event types.
 *
 * <p>The model intentionally includes events that KClaw does not replay yet so API, CLI, and local
 * model adapters can share the same journal without changing its schema.</p>
 */
public enum ConversationEventType {
    MESSAGE,
    CONTEXT,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    CANCELLED,
    COMPACTION,
    MIGRATION
}
