/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

/**
 * Canonical code-navigation guidance shared by the built-in agent and role prompts and
 * the MCP {@code initialize} instructions ({@code CodeIndexSessionBrief}). The bundled
 * templates (templates/AGENTS.md, templates/system-prompt.md) carry the same
 * {@link #RULE} wording.
 */
public final class CodeNavigationGuidance {

    /** Rule for agents that hold the full code-index tool set. */
    public static final String RULE = "Code navigation: for definitions, symbols, callers/implementors "
            + "and change impact, query the kompile code index first - local_code_index (find, "
            + "blended_search, callers, implementors, impact), code_search, code_graph, and file_context "
            + "(what a file declares and what depends on it). project_id auto-resolves from the working "
            + "directory. Use grep for literal text, strings, config and non-code files, and to confirm "
            + "index results. If the index reports missing or stale, run local_code_index action=index "
            + "(background) or action=repair from the repository root - never create a separate index "
            + "for a subdirectory.";

    /**
     * Rule for the read-only agents (planner, explore, review, architect subagent). Of the
     * code-index tools they hold only code_search and file_context, with code_search's
     * index action denied, so they report a missing or stale index instead of rebuilding it.
     */
    public static final String READ_ONLY_RULE = "Index first: for definitions, symbols and what depends "
            + "on a file, query the kompile code index before grep - code_search (blended_search, search, "
            + "entities, signatures) and file_context (what a file declares and what depends on it). "
            + "project_id auto-resolves from the working directory. Use grep for literal text, strings, "
            + "config and non-code files, and to confirm index results. If the index reports missing or "
            + "stale, say so in your findings and continue with grep - do not index it yourself; the "
            + "caller runs local_code_index action=index or action=repair from the repository root.";

    private CodeNavigationGuidance() {
    }
}
