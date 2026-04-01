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

package ai.kompile.core.structured;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for generating summaries of tables.
 *
 * <p>Supports two summary generation methods:</p>
 * <ul>
 *   <li><b>heuristic</b> - Fast, rule-based summary from table structure (default)</li>
 *   <li><b>llm</b> - Uses configured LLM to generate natural language summary</li>
 * </ul>
 *
 * <p>Configuration is per-request via UI. Methods:</p>
 * <ul>
 *   <li>{@link #setSummaryMethod(String)} - Set "heuristic" or "llm"</li>
 *   <li>{@link #setMaxTableCharsForLlm(int)} - Set max characters for LLM input</li>
 * </ul>
 */
@Service
public class TableSummaryService {

    private static final Logger logger = LoggerFactory.getLogger(TableSummaryService.class);

    private static final Pattern MARKDOWN_ROW_PATTERN = Pattern.compile("^\\|(.+)\\|$", Pattern.MULTILINE);
    private static final int DEFAULT_MAX_TABLE_CHARS = 5000;
    private static final String DEFAULT_SUMMARY_METHOD = "heuristic";

    // Runtime configurable (set via UI/API)
    private String summaryMethod = DEFAULT_SUMMARY_METHOD;
    private int maxTableCharsForLlm = DEFAULT_MAX_TABLE_CHARS;

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    /**
     * Generates a summary for the given table document.
     *
     * @param table The table to summarize
     * @return A summary string describing the table
     */
    public String generateSummary(TableDocument table) {
        if ("llm".equalsIgnoreCase(summaryMethod)) {
            return generateLlmSummary(table);
        }
        return generateHeuristicSummary(table);
    }

    /**
     * Generates a summary using heuristics based on table structure.
     * Fast and requires no API calls.
     */
    public String generateHeuristicSummary(TableDocument table) {
        TableMetadata meta = table.getTableMetadata();
        StringBuilder summary = new StringBuilder();

        // Basic dimensions
        summary.append("Table with ").append(meta.rowCount()).append(" rows and ")
               .append(meta.columnCount()).append(" columns.");

        // Add column headers if available
        List<String> headers = meta.columnHeaders();
        if (headers != null && !headers.isEmpty()) {
            summary.append(" Columns: ").append(String.join(", ", headers)).append(".");
        }

        // Add first row sample if available
        String firstDataRow = extractFirstDataRow(table.getMarkdownContent());
        if (firstDataRow != null && !firstDataRow.isEmpty()) {
            // Truncate if too long
            if (firstDataRow.length() > 150) {
                firstDataRow = firstDataRow.substring(0, 147) + "...";
            }
            summary.append(" Sample data: ").append(firstDataRow);
        }

        // Add extraction method for context
        if (meta.extractionMethod() != null && !meta.extractionMethod().equals("unknown")) {
            summary.append(" (extracted via ").append(meta.extractionMethod()).append(")");
        }

        return summary.toString();
    }

    /**
     * Generates a summary using the configured LLM.
     * Falls back to heuristic if LLM is not available.
     */
    public String generateLlmSummary(TableDocument table) {
        if (chatClientBuilder == null) {
            logger.warn("LLM summary requested but ChatClient not available. Falling back to heuristic.");
            return generateHeuristicSummary(table);
        }

        try {
            // Lazy initialization of chat client
            if (chatClient == null) {
                chatClient = chatClientBuilder.build();
            }

            String tableContent = table.getMarkdownContent();

            // Truncate large tables
            if (tableContent.length() > maxTableCharsForLlm) {
                tableContent = truncateTable(tableContent, maxTableCharsForLlm);
            }

            String prompt = buildSummaryPrompt(tableContent, table.getTableMetadata());

            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            if (response != null && !response.isEmpty()) {
                return response.trim();
            } else {
                logger.warn("LLM returned empty response. Falling back to heuristic.");
                return generateHeuristicSummary(table);
            }

        } catch (Exception e) {
            logger.warn("Failed to generate LLM summary: {}. Falling back to heuristic.", e.getMessage());
            return generateHeuristicSummary(table);
        }
    }

    private String buildSummaryPrompt(String tableContent, TableMetadata metadata) {
        return String.format("""
            Summarize this table in 1-2 sentences. Focus on:
            - What type of data it contains
            - What questions it could answer
            - Key patterns or notable values if apparent

            Table has %d rows and %d columns.
            Columns: %s

            Table content:
            %s

            Provide only the summary, no additional text.
            """,
            metadata.rowCount(),
            metadata.columnCount(),
            metadata.getHeadersAsString(),
            tableContent
        );
    }

    /**
     * Extracts the first data row from a markdown table.
     */
    String extractFirstDataRow(String markdownTable) {
        if (markdownTable == null || markdownTable.isEmpty()) {
            return null;
        }

        String[] lines = markdownTable.split("\n");
        int dataRowIndex = -1;

        // Find the first data row (skip header and separator)
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("|") && line.endsWith("|")) {
                // Skip separator row (contains only dashes and pipes)
                if (line.matches("^\\|[-:|\\s]+\\|$")) {
                    continue;
                }
                // Skip header row (first pipe-delimited row)
                if (dataRowIndex == -1 && i == 0) {
                    dataRowIndex = i;
                    continue;
                }
                // This is a data row
                if (i > dataRowIndex) {
                    return extractCellValues(line);
                }
            }
        }

        return null;
    }

    private String extractCellValues(String row) {
        Matcher matcher = MARKDOWN_ROW_PATTERN.matcher(row);
        if (matcher.matches()) {
            String content = matcher.group(1);
            // Split by | and trim each cell
            String[] cells = content.split("\\|");
            StringBuilder result = new StringBuilder();
            for (String cell : cells) {
                String trimmed = cell.trim();
                if (!trimmed.isEmpty()) {
                    if (result.length() > 0) {
                        result.append(", ");
                    }
                    result.append(trimmed);
                }
            }
            return result.toString();
        }
        return row;
    }

    /**
     * Truncates a markdown table to approximately the target character count
     * while keeping complete rows.
     */
    String truncateTable(String tableContent, int maxChars) {
        if (tableContent.length() <= maxChars) {
            return tableContent;
        }

        String[] lines = tableContent.split("\n");
        StringBuilder result = new StringBuilder();
        int rowsIncluded = 0;

        // Always include header and separator
        for (int i = 0; i < Math.min(2, lines.length); i++) {
            result.append(lines[i]).append("\n");
        }

        // Add data rows until we hit the limit
        for (int i = 2; i < lines.length && result.length() < maxChars; i++) {
            result.append(lines[i]).append("\n");
            rowsIncluded++;
        }

        // Add truncation note
        int remainingRows = lines.length - 2 - rowsIncluded;
        if (remainingRows > 0) {
            result.append("\n[... ").append(remainingRows).append(" more rows truncated ...]");
        }

        return result.toString();
    }

    /**
     * Sets the summary method. Can be called from UI/API.
     * @param method "heuristic" or "llm"
     */
    public void setSummaryMethod(String method) {
        this.summaryMethod = method;
    }

    /**
     * Gets the current summary method.
     */
    public String getSummaryMethod() {
        return summaryMethod;
    }

    /**
     * Sets the max characters for LLM table input. Can be called from UI/API.
     * @param maxChars Maximum characters to send to LLM (tables are truncated if larger)
     */
    public void setMaxTableCharsForLlm(int maxChars) {
        this.maxTableCharsForLlm = maxChars;
    }

    /**
     * Gets the current max characters for LLM table input.
     */
    public int getMaxTableCharsForLlm() {
        return maxTableCharsForLlm;
    }

    /**
     * Checks if LLM-based summarization is available.
     */
    public boolean isLlmAvailable() {
        return chatClientBuilder != null;
    }
}
