/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

/** Provider-neutral actor associated with a durable conversation event. */
public enum ConversationRole {
    USER,
    ASSISTANT,
    SYSTEM,
    CONTEXT,
    TOOL
}
