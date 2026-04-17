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

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.config.DirectLlmClient;

import java.util.List;

/**
 * LLM-driven conversation summarizer. Produces a structured 9-section summary
 * of chat history, inspired by Claude Code's progressive tiered compaction
 * strategy. Combined with the heuristic CompactionService (which prunes tool
 * outputs), this gives a two-tier compression pipeline:
 *
 *   Tier 1 (automatic): CompactionService prunes old tool results.
 *   Tier 2 (on demand): ConversationSummarizer calls the LLM to produce a
 *                        structured summary that replaces the full history.
 *
 * The summary prompt captures: primary intent, technical concepts, files
 * touched, problem solving, pending tasks, current work, next step, user
 * preferences, and a high-level narrative. This yields high recall while
 * dramatically reducing token count.
 */
public class ConversationSummarizer {

    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are a precise conversation summarizer. Produce a faithful, "
                    + "structured summary of the chat transcript provided by the user. "
                    + "Preserve every concrete technical detail the assistant will need to "
                    + "continue the work without loss of context: file paths, identifiers, "
                    + "commands run, errors encountered, decisions made, and unresolved tasks. "
                    + "Do not add information that is not in the transcript.";

    private final DirectLlmClient directLlmClient;

    public ConversationSummarizer(DirectLlmClient directLlmClient) {
        this.directLlmClient = directLlmClient;
    }

    /**
     * Generate a structured summary of the given conversation history.
     * Streams the summary to stdout as it is produced.
     *
     * @param history          conversation entries to summarize
     * @param focusInstruction optional user-provided focus hint (e.g. "focus
     *                         on the API changes"). May be null or empty.
     * @param modelOverride    optional model override (null uses configured default)
     * @return the generated summary text and token usage
     */
    public SummaryResult summarize(List<CompactionService.ConversationEntry> history,
                                    String focusInstruction,
                                    String modelOverride) {
        if (history == null || history.isEmpty()) {
            return new SummaryResult("", 0, 0);
        }

        String transcript = serializeHistory(history);
        String prompt = buildPrompt(transcript, focusInstruction);

        DirectLlmClient.StreamResult result = directLlmClient.streamOneShot(
                prompt, SUMMARY_SYSTEM_PROMPT, modelOverride);

        return new SummaryResult(result.text, result.inputTokens, result.outputTokens);
    }

    private String buildPrompt(String transcript, String focusInstruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following conversation transcript into a structured ");
        sb.append("summary that preserves the context needed to continue the work.\n\n");

        if (focusInstruction != null && !focusInstruction.isBlank()) {
            sb.append("Additional focus from the user: ").append(focusInstruction.trim()).append("\n\n");
        }

        sb.append("Organize the summary into the following sections, using these exact headings. ");
        sb.append("Omit a section only if it genuinely has no content; never fabricate.\n\n");

        sb.append("## 1. Primary Request and Intent\n");
        sb.append("What the user is ultimately trying to accomplish.\n\n");

        sb.append("## 2. Key Technical Concepts\n");
        sb.append("Technologies, frameworks, libraries, protocols, and patterns referenced.\n\n");

        sb.append("## 3. Files and Code Sections\n");
        sb.append("Every file path examined or modified, with a one-line note on what was ");
        sb.append("done or learned about each. Include line ranges where discussed.\n\n");

        sb.append("## 4. Errors and Fixes\n");
        sb.append("Each error, failure, or surprise encountered, and how it was resolved ");
        sb.append("(or why it was left unresolved).\n\n");

        sb.append("## 5. Problem Solving\n");
        sb.append("Non-trivial reasoning, tradeoffs weighed, and decisions made.\n\n");

        sb.append("## 6. All User Messages\n");
        sb.append("A terse list of the user's messages in order (short paraphrase of each). ");
        sb.append("This preserves intent evolution across the session.\n\n");

        sb.append("## 7. Pending Tasks\n");
        sb.append("Explicit TODOs or follow-ups the user has asked for that are not done.\n\n");

        sb.append("## 8. Current Work\n");
        sb.append("What was being worked on immediately before the summary request, with ");
        sb.append("enough detail (specific files, functions, commands) to resume without re-reading.\n\n");

        sb.append("## 9. Optional Next Step\n");
        sb.append("The single most likely next action, if it is obviously implied by the ");
        sb.append("most recent work. If not clear, say \"Ask the user what to do next.\"\n\n");

        sb.append("---\n");
        sb.append("Transcript to summarize:\n");
        sb.append("---\n");
        sb.append(transcript);
        return sb.toString();
    }

    private String serializeHistory(List<CompactionService.ConversationEntry> history) {
        StringBuilder sb = new StringBuilder();
        for (CompactionService.ConversationEntry entry : history) {
            if (entry.content == null) continue;
            switch (entry.type) {
                case SYSTEM:
                    sb.append("[SYSTEM]\n").append(entry.content).append("\n\n");
                    break;
                case USER:
                    sb.append("[USER]\n").append(entry.content).append("\n\n");
                    break;
                case ASSISTANT:
                    sb.append("[ASSISTANT]\n").append(entry.content).append("\n\n");
                    break;
                case TOOL_CALL:
                    sb.append("[TOOL_CALL ").append(entry.toolName != null ? entry.toolName : "?")
                            .append("]\n").append(entry.content).append("\n\n");
                    break;
                case TOOL_RESULT:
                    sb.append("[TOOL_RESULT ").append(entry.toolName != null ? entry.toolName : "?")
                            .append("]\n").append(truncateForSummary(entry.content)).append("\n\n");
                    break;
            }
        }
        return sb.toString();
    }

    private String truncateForSummary(String content) {
        // Tool results can be huge; cap each at 4KB in the transcript so the
        // summary request itself does not blow the context window.
        if (content.length() <= 4096) return content;
        return content.substring(0, 4096) + "\n... (tool result truncated for summarization)";
    }

    public static class SummaryResult {
        private final String summary;
        private final long inputTokens;
        private final long outputTokens;

        public SummaryResult(String summary, long inputTokens, long outputTokens) {
            this.summary = summary;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public String getSummary() { return summary; }
        public long getInputTokens() { return inputTokens; }
        public long getOutputTokens() { return outputTokens; }
        public boolean isEmpty() { return summary == null || summary.isBlank(); }
    }
}
