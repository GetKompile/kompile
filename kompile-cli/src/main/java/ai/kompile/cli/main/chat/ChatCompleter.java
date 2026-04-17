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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.mcp.McpSseClient;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * JLine3 tab completer for the chat REPL.
 * Completes slash commands, tool names, and skill names.
 */
public class ChatCompleter implements Completer {

    private static final String[] SLASH_COMMANDS = {
            "/tools", "/tool", "/help", "/status", "/history", "/clear",
            "/rag", "/agents", "/agent", "/config", "/sessions", "/ask",
            "/conversations", "/transcript", "/memory", "/recall",
            "/quit", "/exit", "/queue", "/queues", "/queue-send",
            "/queue-send-all", "/queue-remove", "/queue-clear", "/queue-status",
            "/jobs", "/jobs-remove", "/jobs-clear", "/auto-dequeue", "/stats",
            "/skills", "/model", "/plan", "/todos"
    };

    private final Supplier<List<McpSseClient.ToolInfo>> toolsSupplier;
    private final Supplier<Set<String>> skillNamesSupplier;

    /**
     * Backward-compatible constructor (no skill completion).
     */
    public ChatCompleter(Supplier<List<McpSseClient.ToolInfo>> toolsSupplier) {
        this(toolsSupplier, Set::of);
    }

    public ChatCompleter(Supplier<List<McpSseClient.ToolInfo>> toolsSupplier,
                         Supplier<Set<String>> skillNamesSupplier) {
        this.toolsSupplier = toolsSupplier;
        this.skillNamesSupplier = skillNamesSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        int cursor = line.cursor();
        String upToCursor = buffer.substring(0, cursor);

        if (upToCursor.startsWith("/")) {
            // Check if we're completing a tool name after "/tool "
            if (upToCursor.startsWith("/tool ") && !upToCursor.startsWith("/tools")) {
                String toolPrefix = upToCursor.substring(6).trim().toLowerCase();
                List<McpSseClient.ToolInfo> tools = toolsSupplier.get();
                if (tools != null) {
                    for (McpSseClient.ToolInfo tool : tools) {
                        if (tool.getName().toLowerCase().startsWith(toolPrefix)) {
                            candidates.add(new Candidate(tool.getName(), tool.getName(),
                                    null, tool.getDescription(), null, null, true));
                        }
                    }
                }
            } else {
                // Complete slash commands
                String prefix = upToCursor.toLowerCase();
                for (String cmd : SLASH_COMMANDS) {
                    if (cmd.startsWith(prefix)) {
                        candidates.add(new Candidate(cmd));
                    }
                }

                // Complete skill names (e.g. /commit, /review)
                Set<String> skillNames = skillNamesSupplier.get();
                if (skillNames != null) {
                    for (String skill : skillNames) {
                        String skillCmd = "/" + skill;
                        if (skillCmd.startsWith(prefix)) {
                            candidates.add(new Candidate(skillCmd, skillCmd,
                                    "skill", null, null, null, true));
                        }
                    }
                }
            }
        }
    }
}
