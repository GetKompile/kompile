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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * CLI-side response compression for tool results in local mode (when not connected to a
 * kompile-app server that performs server-side compression).
 *
 * <p>Mirrors the behavior of the server-side {@code ToolResponseCompressorRegistry}:
 * large tool outputs are truncated using tool-specific strategies (head+tail for file/bash
 * tools, simple head truncation for others) so that the LLM context window is not flooded
 * with irrelevant bytes.
 *
 * <p>This class is stateless and must not be instantiated.
 */
public final class ToolResponseOptimizer {

    /** Default character threshold above which compression is applied. */
    public static final int DEFAULT_THRESHOLD_CHARS = 4000;

    /** Maximum number of array elements kept during JSON-aware compression. */
    private static final int MAX_JSON_ARRAY_ITEMS = 20;

    /** Number of tail characters preserved for head+tail tool strategies. */
    private static final int TAIL_CHARS = 500;

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    private ToolResponseOptimizer() {
        throw new UnsupportedOperationException("ToolResponseOptimizer is a utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Compress a raw (plain-text) tool output.
     *
     * <p>Compression strategy depends on the tool:
     * <ul>
     *   <li>{@code read} and {@code bash}: head+tail — keeps the first
     *       {@code thresholdChars/2} characters followed by the last {@value #TAIL_CHARS}
     *       characters, with a truncation notice in between.</li>
     *   <li>All other tools: simple head truncation at {@code thresholdChars} characters.</li>
     * </ul>
     *
     * @param toolName       the name of the tool that produced the output
     * @param rawOutput      the raw tool output string
     * @param thresholdChars character count above which compression is triggered
     * @return a {@link CompressedResult} describing the (possibly compressed) output
     */
    public static CompressedResult compress(String toolName, String rawOutput, int thresholdChars) {
        if (rawOutput == null) {
            rawOutput = "";
        }
        int originalChars = rawOutput.length();

        if (originalChars < thresholdChars) {
            return new CompressedResult(rawOutput, originalChars, originalChars, false);
        }

        String compressed;
        if (isHeadTailTool(toolName)) {
            compressed = applyHeadTail(rawOutput, originalChars, thresholdChars);
        } else {
            compressed = applyHeadTruncation(rawOutput, originalChars, thresholdChars);
        }

        return new CompressedResult(compressed, originalChars, compressed.length(), true);
    }

    /**
     * Compress a JSON tool output with JSON-aware strategies.
     *
     * <p>Applies the following transformations in order:
     * <ol>
     *   <li>If the root is a JSON array with more than {@value #MAX_JSON_ARRAY_ITEMS} elements,
     *       it is truncated to the first {@value #MAX_JSON_ARRAY_ITEMS} elements and a
     *       {@code {"_truncated": true, "_total": N, "_returned": 20}} sentinel object is
     *       appended.</li>
     *   <li>Any string values within the JSON that exceed {@code thresholdChars} are
     *       individually truncated with a notice.</li>
     *   <li>If the resulting JSON still exceeds {@code thresholdChars} as a serialised string,
     *       plain-text {@link #compress} is applied as a final safety net.</li>
     *   <li>If the input is not valid JSON, falls back to plain-text
     *       {@link #compress}.</li>
     * </ol>
     *
     * @param toolName       the name of the tool that produced the output
     * @param jsonOutput     the raw JSON output string
     * @param thresholdChars character count above which compression is triggered
     * @return a {@link CompressedResult} describing the (possibly compressed) output
     */
    public static CompressedResult compressJson(String toolName, String jsonOutput, int thresholdChars) {
        if (jsonOutput == null) {
            jsonOutput = "";
        }
        int originalChars = jsonOutput.length();

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonOutput);
        } catch (Exception e) {
            // Not valid JSON — fall back to plain-text compression
            return compress(toolName, jsonOutput, thresholdChars);
        }

        boolean modified = false;

        // 1. Truncate oversized arrays
        if (root.isArray()) {
            ArrayNode array = (ArrayNode) root;
            int total = array.size();
            if (total > MAX_JSON_ARRAY_ITEMS) {
                ArrayNode truncated = OBJECT_MAPPER.createArrayNode();
                for (int i = 0; i < MAX_JSON_ARRAY_ITEMS; i++) {
                    truncated.add(array.get(i));
                }
                ObjectNode sentinel = OBJECT_MAPPER.createObjectNode();
                sentinel.put("_truncated", true);
                sentinel.put("_total", total);
                sentinel.put("_returned", MAX_JSON_ARRAY_ITEMS);
                truncated.add(sentinel);
                root = truncated;
                modified = true;
            }
        }

        // 2. Truncate oversized string values within the node tree
        root = truncateStringValues(root, thresholdChars);

        // 3. Serialise and apply plain-text compression if still too large
        String serialised;
        try {
            serialised = OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Serialisation failure — fall back to plain-text compression
            return compress(toolName, jsonOutput, thresholdChars);
        }

        if (!modified && serialised.length() < thresholdChars) {
            // Nothing changed and within budget
            return new CompressedResult(serialised, originalChars, serialised.length(), false);
        }

        if (serialised.length() >= thresholdChars) {
            // Still too large after JSON-level reduction — apply plain-text safety net
            CompressedResult plainResult = compress(toolName, serialised, thresholdChars);
            return new CompressedResult(
                    plainResult.output(),
                    originalChars,
                    plainResult.compressedChars(),
                    true);
        }

        return new CompressedResult(serialised, originalChars, serialised.length(), modified);
    }

    // -------------------------------------------------------------------------
    // Code-aware compression
    // -------------------------------------------------------------------------

    /**
     * Apply language-aware compression to file content, combining structural
     * analysis with boilerplate stripping.
     *
     * <p>This method applies {@link CodeTokenCompressor#compress} to strip
     * license headers, collapse import blocks, reduce blank lines, and
     * summarize long comments — then falls back to head+tail truncation
     * if still over threshold.
     *
     * @param rawOutput      the raw file content (with line numbers from ReadTool)
     * @param fileName       the source file name for language detection
     * @param thresholdChars character count above which additional truncation is applied
     * @return a {@link CompressedResult} with the compressed output
     */
    public static CompressedResult compressCode(String rawOutput, String fileName, int thresholdChars) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return new CompressedResult(rawOutput == null ? "" : rawOutput, 0, 0, false);
        }
        int originalChars = rawOutput.length();

        // Strip line-number prefixes to get raw source lines
        String[] numberedLines = rawOutput.split("\n");
        List<String> sourceLines = new java.util.ArrayList<>(numberedLines.length);
        for (String nl : numberedLines) {
            // Format: "  1234\tcode..." — strip the prefix
            int tabIdx = nl.indexOf('\t');
            if (tabIdx >= 0 && tabIdx < 10) {
                sourceLines.add(nl.substring(tabIdx + 1));
            } else {
                sourceLines.add(nl);
            }
        }

        // Apply language-aware compression
        CodeTokenCompressor.CompressResult cr =
                CodeTokenCompressor.compress(sourceLines, fileName);
        String compressed = cr.format();

        // If still over threshold, apply head+tail truncation
        if (compressed.length() >= thresholdChars) {
            compressed = applyHeadTail(compressed, compressed.length(), thresholdChars);
        }

        return new CompressedResult(compressed, originalChars, compressed.length(),
                compressed.length() < originalChars);
    }

    /**
     * Apply compression to grep/search results: deduplicate identical matches,
     * collapse similar results, then truncate if still over threshold.
     *
     * @param rawOutput      the raw grep output
     * @param thresholdChars character count above which additional truncation is applied
     * @return a {@link CompressedResult} with the compressed output
     */
    public static CompressedResult compressSearchResults(String rawOutput, int thresholdChars) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return new CompressedResult(rawOutput == null ? "" : rawOutput, 0, 0, false);
        }
        int originalChars = rawOutput.length();

        String compressed = CodeTokenCompressor.compressGrepResults(rawOutput);

        // If still over threshold, apply head truncation
        if (compressed.length() >= thresholdChars) {
            compressed = applyHeadTruncation(compressed, compressed.length(), thresholdChars);
        }

        return new CompressedResult(compressed, originalChars, compressed.length(),
                compressed.length() < originalChars);
    }

    // -------------------------------------------------------------------------
    // Observation summarization (non-code content)
    // -------------------------------------------------------------------------

    /**
     * Compress CLI/build/test output by applying heuristic patterns:
     * strip progress bars, download lines, and repeated patterns;
     * keep only error/warning lines from build logs; collapse
     * repeated similar lines.
     *
     * <p>Based on RTK (Rust Token Killer) research showing 60-91% savings
     * on CLI output and the JetBrains "Complexity Trap" observation masking
     * approach.
     *
     * @param rawOutput      the CLI/bash output
     * @param thresholdChars maximum output size before additional truncation
     * @return a {@link CompressedResult} with the compressed output
     */
    public static CompressedResult compressObservation(String rawOutput, int thresholdChars) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return new CompressedResult(rawOutput == null ? "" : rawOutput, 0, 0, false);
        }
        int originalChars = rawOutput.length();
        if (originalChars < thresholdChars / 2) {
            // Not worth compressing small outputs
            return new CompressedResult(rawOutput, originalChars, originalChars, false);
        }

        String[] lines = rawOutput.split("\n");
        java.util.ArrayList<String> result = new java.util.ArrayList<>(lines.length);

        int strippedCount = 0;
        int consecutiveSimilar = 0;
        String lastPatternGroup = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Strip pure progress indicators
            if (isProgressLine(trimmed)) {
                strippedCount++;
                continue;
            }

            // Strip download/fetch progress lines
            if (isDownloadLine(trimmed)) {
                strippedCount++;
                continue;
            }

            // Strip Maven/Gradle download lines
            if (isBuildDownloadLine(trimmed)) {
                strippedCount++;
                continue;
            }

            // Strip blank lines beyond 1 consecutive
            if (trimmed.isEmpty()) {
                if (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
                    strippedCount++;
                    continue;
                }
            }

            // Collapse runs of similar lines (e.g., repeated test PASS lines)
            String group = getPatternGroup(trimmed);
            if (group != null && group.equals(lastPatternGroup)) {
                consecutiveSimilar++;
                continue;
            } else {
                if (consecutiveSimilar > 0) {
                    result.add("  [+" + consecutiveSimilar + " similar]");
                    strippedCount += consecutiveSimilar;
                    consecutiveSimilar = 0;
                }
                lastPatternGroup = group;
            }

            result.add(line);
        }

        // Flush trailing similar
        if (consecutiveSimilar > 0) {
            result.add("  [+" + consecutiveSimilar + " similar]");
            strippedCount += consecutiveSimilar;
        }

        StringBuilder sb = new StringBuilder();
        for (String r : result) {
            sb.append(r).append("\n");
        }
        if (strippedCount > 0) {
            sb.append("[").append(strippedCount).append(" low-value line")
                    .append(strippedCount > 1 ? "s" : "").append(" stripped]\n");
        }

        String compressed = sb.toString();

        // If still over threshold, apply head+tail
        if (compressed.length() >= thresholdChars) {
            compressed = applyHeadTail(compressed, compressed.length(), thresholdChars);
        }

        return new CompressedResult(compressed, originalChars, compressed.length(),
                compressed.length() < originalChars);
    }

    /** Detect progress bar patterns: [####    ] 45%, ████▒▒▒ 60/100, etc. */
    private static boolean isProgressLine(String trimmed) {
        // Spinner/animation characters
        if (trimmed.matches("^[\\\\|/\\-⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]\\s.*")) return true;
        // Progress bars with blocks or hashes
        if (trimmed.matches(".*[█▓▒░▏▎▍▌▋▊▉]{3,}.*")) return true;
        if (trimmed.matches(".*[#=]{5,}[> \\]].*\\d+%.*")) return true;
        // Percentage-only lines
        if (trimmed.matches("^\\s*\\d{1,3}%\\s*$")) return true;
        // Carriage-return overwrite lines (often progress)
        if (trimmed.startsWith("\r")) return true;
        return false;
    }

    /** Detect download/fetch progress: "Downloading ... 45%", "Fetching ...", etc. */
    private static boolean isDownloadLine(String trimmed) {
        String lower = trimmed.toLowerCase();
        if (lower.matches(".*downloading\\s+.*\\d+[kmg]?b.*")) return true;
        if (lower.matches(".*fetching\\s+.*\\d+[kmg]?b.*")) return true;
        if (lower.matches(".*\\d+\\.\\d+\\s*[kmg]b/s.*")) return true;
        if (lower.matches(".*eta\\s+\\d+[smh].*\\d+%.*")) return true;
        return false;
    }

    /** Detect Maven/Gradle dependency download lines. */
    private static boolean isBuildDownloadLine(String trimmed) {
        if (trimmed.startsWith("Downloading from ")) return true;
        if (trimmed.startsWith("Downloaded from ")) return true;
        if (trimmed.matches("^Download(ing|ed)\\s+https?://.*")) return true;
        if (trimmed.matches("^\\s*> Downloading .* \\d+%.*")) return true;
        return false;
    }

    /**
     * Return a "pattern group" key for collapsing runs of similar lines.
     * Lines with the same group key are collapsed to a single line + count.
     * Returns null if the line should not be grouped.
     */
    private static String getPatternGroup(String trimmed) {
        // Test output: "PASS test_xxx", "ok test_xxx", "✓ test_xxx"
        if (trimmed.matches("^(PASS|PASSED|ok|✓|✅)\\s+.*")) return "PASS";
        // "Tests run: N, Failures: 0" (Maven Surefire summary — keep, don't group)
        if (trimmed.startsWith("Tests run:")) return null;
        // Repeated "INFO" log lines with same prefix
        if (trimmed.matches("^\\[?INFO\\]?\\s+---.*")) return "INFO_SEPARATOR";
        // npm install lines
        if (trimmed.matches("^added \\d+ packages.*")) return "NPM_INSTALL";
        // pip install lines
        if (trimmed.matches("^(Installing|Collecting|Requirement already).*")) return "PIP_INSTALL";
        // Repeated compilation lines
        if (trimmed.matches("^\\[INFO\\] Compiling \\d+ source file.*")) return "COMPILE";
        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean isHeadTailTool(String toolName) {
        if (toolName == null) return false;
        String lower = toolName.toLowerCase();
        return lower.equals("file_read") || lower.equals("shell");
    }

    /**
     * Head+tail strategy: keeps the first {@code thresholdChars/2} characters,
     * a truncation notice, and then the last {@value #TAIL_CHARS} characters.
     */
    private static String applyHeadTail(String raw, int originalChars, int thresholdChars) {
        int headChars = thresholdChars / 2;
        String head = raw.substring(0, Math.min(headChars, raw.length()));

        int tailStart = Math.max(headChars, raw.length() - TAIL_CHARS);
        String tail = tailStart < raw.length() ? raw.substring(tailStart) : "";

        return head
                + "\n...[truncated, " + originalChars + " chars total]..."
                + tail;
    }

    /**
     * Simple head truncation: keeps the first {@code thresholdChars} characters.
     */
    private static String applyHeadTruncation(String raw, int originalChars, int thresholdChars) {
        return raw.substring(0, Math.min(thresholdChars, raw.length()))
                + "\n...[truncated, " + originalChars + " chars total]";
    }

    /**
     * Recursively walk a {@link JsonNode} tree and truncate any string values that
     * exceed {@code thresholdChars}.  Returns a (possibly new) node with truncated strings.
     */
    private static JsonNode truncateStringValues(JsonNode node, int thresholdChars) {
        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() > thresholdChars) {
                String notice = "...[truncated, " + text.length() + " chars total]";
                return OBJECT_MAPPER.getNodeFactory()
                        .textNode(text.substring(0, thresholdChars) + notice);
            }
            return node;
        }

        if (node.isArray()) {
            ArrayNode result = OBJECT_MAPPER.createArrayNode();
            for (JsonNode element : node) {
                result.add(truncateStringValues(element, thresholdChars));
            }
            return result;
        }

        if (node.isObject()) {
            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                result.set(entry.getKey(), truncateStringValues(entry.getValue(), thresholdChars));
            }
            return result;
        }

        return node;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Describes the outcome of a compression operation.
     *
     * @param output           the compressed (or original) output string
     * @param originalChars    the character count of the input before compression
     * @param compressedChars  the character count of the output after compression
     * @param wasCompressed    {@code true} if compression was actually applied
     */
    public record CompressedResult(
            String output,
            int originalChars,
            int compressedChars,
            boolean wasCompressed) {
    }
}
