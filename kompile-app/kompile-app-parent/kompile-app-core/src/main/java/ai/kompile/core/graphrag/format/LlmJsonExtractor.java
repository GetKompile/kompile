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

package ai.kompile.core.graphrag.format;

/**
 * Isolates the graph-extraction JSON object from a raw LLM response.
 *
 * <p>CLI agents (opencode/claude/codex) frequently emit tool-call and log lines
 * such as {@code "[kompile] read | {"filePath":...}"} before or after the actual
 * extraction result. A naive {@code response.indexOf('{')} then slices from the
 * wrong brace, and because the extraction DTOs ignore unknown properties over list
 * fields, the mis-sliced JSON parses "successfully" into <b>zero entities / zero
 * relationships</b> — a silent, exception-free failure that masquerades as a
 * completed crawl.
 *
 * <p>This helper centralizes the robust isolation logic (strip markdown fences,
 * anchor on the {@code {"entities"} root object, otherwise fall back to the first
 * {@code '{'} on a non-tool-call line) so every graph-extraction JSON parser shares
 * one implementation and the fix cannot regress in a single caller again. Used by
 * {@code GraphExtractionOrchestrator} (crawl LLM path) and
 * {@code MatrixGraphConstructor} (direct construction path).
 */
public final class LlmJsonExtractor {

    private static final String[] TOOL_CALL_PREFIXES = {
            "[kompile]", "[llm]", "[INFO]", "[DEBUG]", "[WARN]", "[ERROR]"
    };

    private LlmJsonExtractor() {
    }

    /**
     * Extracts the JSON object substring from a raw LLM extraction response,
     * tolerating markdown code fences and CLI-agent tool-call log prefixes.
     *
     * @param response the raw text returned by the extraction LLM
     * @return the isolated JSON object string, or {@code null} if the response is
     *         null/blank. When no better anchor can be found the (fence-stripped)
     *         response is returned unchanged so the caller's parser can make the
     *         final determination.
     */
    public static String extractJsonObject(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // 1. Strip markdown code fences: ```json ... ``` or ``` ... ```
        String stripped = response;
        int fenceStart = response.indexOf("```json");
        if (fenceStart >= 0) {
            int contentStart = response.indexOf('\n', fenceStart) + 1;
            int fenceEnd = response.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                stripped = response.substring(contentStart, fenceEnd).trim();
            }
        } else {
            int fence2 = response.indexOf("```");
            if (fence2 >= 0) {
                int contentStart = response.indexOf('\n', fence2) + 1;
                int fenceEnd = response.indexOf("```", contentStart);
                if (fenceEnd > contentStart) {
                    String candidate = response.substring(contentStart, fenceEnd).trim();
                    if (candidate.startsWith("{")) {
                        stripped = candidate;
                    }
                }
            }
        }

        // 2. Anchor on the extraction result root object {"entities" to skip tool-call
        //    log prefixes emitted by CLI agents (e.g. "[kompile] read | {"filePath":...}").
        String json = stripped;
        int entitiesStart = stripped.indexOf("{\"entities\"");
        if (entitiesStart < 0) {
            // Fall back to the first '{' that is not on a tool-call / log line.
            int candidateStart = -1;
            int pos = 0;
            while (pos < stripped.length()) {
                int lineEnd = stripped.indexOf('\n', pos);
                if (lineEnd < 0) {
                    lineEnd = stripped.length();
                }
                String line = stripped.substring(pos, lineEnd).trim();
                if (!isToolCallLine(line) && line.contains("{")) {
                    int bracePos = stripped.indexOf('{', pos);
                    if (bracePos >= 0 && bracePos < lineEnd) {
                        candidateStart = bracePos;
                        break;
                    }
                }
                pos = lineEnd + 1;
            }
            if (candidateStart >= 0) {
                int jsonEnd = stripped.lastIndexOf('}');
                if (jsonEnd > candidateStart) {
                    json = stripped.substring(candidateStart, jsonEnd + 1);
                }
            }
        } else {
            int jsonEnd = stripped.lastIndexOf('}');
            if (jsonEnd > entitiesStart) {
                json = stripped.substring(entitiesStart, jsonEnd + 1);
            }
        }

        return completeMissingContainerClosures(json);
    }

    /**
     * Repairs only a structurally complete JSON value that is missing one or more final container
     * delimiters. This recovers a source-grounded proposal such as a complete nested selection object
     * whose outer brace was omitted, without inventing a missing string, field, value, or array item.
     */
    static String completeMissingContainerClosures(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        String candidate = json.strip();
        if (candidate.isEmpty() || (candidate.charAt(0) != '{' && candidate.charAt(0) != '[')) {
            return json;
        }

        java.util.ArrayDeque<Character> expectedClosures = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                expectedClosures.push('}');
            } else if (current == '[') {
                expectedClosures.push(']');
            } else if (current == '}' || current == ']') {
                if (expectedClosures.isEmpty() || expectedClosures.pop() != current) {
                    return json;
                }
            }
        }

        if (inString || expectedClosures.isEmpty()) {
            return json;
        }
        char last = candidate.charAt(candidate.length() - 1);
        if (last != '}' && last != ']') {
            return json;
        }
        StringBuilder completed = new StringBuilder(candidate);
        while (!expectedClosures.isEmpty()) {
            completed.append(expectedClosures.pop());
        }
        return completed.toString();
    }

    private static boolean isToolCallLine(String line) {
        for (String prefix : TOOL_CALL_PREFIXES) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
