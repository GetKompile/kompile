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

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formats a list of {@link ChatHistory.Turn} into various output formats.
 * <p>
 * Supported formats:
 * <ul>
 *   <li>{@code kompile} — plain-text transcript ({@code > } user prefix, bare assistant text)</li>
 *   <li>{@code openai} — JSON array of {@code {"role":"...","content":"..."}} objects</li>
 *   <li>{@code anthropic} — same JSON structure as openai (Messages API uses identical schema)</li>
 *   <li>{@code markdown} — {@code ## User} / {@code ## Assistant} headers with {@code ---} separators</li>
 *   <li>{@code jsonl} — one JSON object per line</li>
 * </ul>
 */
public class ConversationFormatter {

    public static final List<String> SUPPORTED_FORMATS = List.of(
            "kompile", "openai", "anthropic", "markdown", "jsonl"
    );

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /**
     * Formats turns into the specified format.
     *
     * @param turns  the conversation turns
     * @param format one of {@link #SUPPORTED_FORMATS}
     * @return the formatted string
     * @throws IllegalArgumentException if the format is not recognized
     */
    public static String format(List<ChatHistory.Turn> turns, String format) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }

        switch (format.toLowerCase()) {
            case "kompile":
                return formatKompile(turns);
            case "openai":
            case "anthropic":
                return formatJson(turns);
            case "markdown":
                return formatMarkdown(turns);
            case "jsonl":
                return formatJsonl(turns);
            default:
                throw new IllegalArgumentException("Unknown format: " + format +
                        ". Supported formats: " + String.join(", ", SUPPORTED_FORMATS));
        }
    }

    /**
     * Checks whether a format name is supported.
     */
    public static boolean isSupported(String format) {
        return SUPPORTED_FORMATS.contains(format.toLowerCase());
    }

    // ─── Format implementations ────────────────────────────────────────

    private static String formatKompile(List<ChatHistory.Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ChatHistory.Turn turn : turns) {
            if ("user".equals(turn.role())) {
                sb.append("> ").append(turn.content()).append("\n\n");
            } else {
                sb.append(turn.content()).append("\n\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String formatJson(List<ChatHistory.Turn> turns) {
        try {
            ArrayNode array = MAPPER.createArrayNode();
            for (ChatHistory.Turn turn : turns) {
                ObjectNode obj = MAPPER.createObjectNode();
                obj.put("role", turn.role());
                obj.put("content", turn.content());
                array.add(obj);
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON: " + e.getMessage(), e);
        }
    }

    private static String formatMarkdown(List<ChatHistory.Turn> turns) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ChatHistory.Turn turn : turns) {
            if (!first) {
                sb.append("\n---\n\n");
            }
            first = false;

            String header = "user".equals(turn.role()) ? "## User" : "## Assistant";
            sb.append(header).append("\n\n");
            sb.append(turn.content()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String formatJsonl(List<ChatHistory.Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ChatHistory.Turn turn : turns) {
            try {
                ObjectNode obj = MAPPER.createObjectNode();
                obj.put("role", turn.role());
                obj.put("content", turn.content());
                sb.append(MAPPER.writeValueAsString(obj)).append("\n");
            } catch (Exception e) {
                // Skip malformed entries
            }
        }
        return sb.toString().stripTrailing();
    }
}
