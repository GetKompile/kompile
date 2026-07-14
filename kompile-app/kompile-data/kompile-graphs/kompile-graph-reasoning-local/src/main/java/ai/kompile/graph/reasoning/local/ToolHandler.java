/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.local;

import java.util.Map;

/**
 * A single MCP-style tool implementation.
 *
 * <p>Receives the active session and the parsed argument map (deserialized from the caller's
 * {@code argsJson}) and returns a JSON string result. The implementation must never throw
 * across the boundary — catch any exception and encode it as
 * {@code {"status":"ERROR","message":"..."}}. The {@link LocalToolDispatcher} provides
 * the top-level error guard, but defensive coding inside handlers is still recommended.</p>
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * Execute the tool.
     *
     * @param session the current reasoning session (never null; may be closed if the user
     *                explicitly called graph_load and then closed — caller is responsible)
     * @param args    parsed argument map from the caller's JSON; never null, may be empty
     * @return a JSON string with at minimum a {@code "status"} field
     */
    String handle(LocalReasoningSession session, Map<String, Object> args);
}
