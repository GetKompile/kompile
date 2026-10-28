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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ProviderConnectivityPolicy;
import ai.kompile.cli.main.chat.render.TerminalRenderer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * /btw — a one-off side question answered outside the conversation.
 * <p>
 * Modeled on Claude Code's /btw: ask a quick question ("why does X fail?") while the
 * main task keeps running. The answer is printed as a dismissible block and is never
 * added to the conversation, so it cannot pollute the model's context or disturb a
 * live agent turn. Semantics:
 * <ul>
 *   <li><b>No context pollution</b> — the question and answer never enter the chat
 *       history; they are not replayed, compacted, or summarized into later turns.</li>
 *   <li><b>Session-aware</b> — a bounded excerpt of the recent transcript is supplied
 *       as read-only context, so the answer can be relevant to the current project.</li>
 *   <li><b>No tools, single turn</b> — a tool-free one-shot request on its own throwaway
 *       client (the same isolated utility lane as session-title generation); it cannot
 *       run commands, touch the filesystem, or follow up.</li>
 *   <li><b>Bounded</b> — hard deadline, small output cap, and provider connectivity
 *       policy so a slow or broken provider can never stall the REPL.</li>
 * </ul>
 */
public final class BtwCommand {

    static final String SYSTEM_PROMPT = "You answer one-off side questions asked with /btw while a main "
            + "task keeps running. Answer concisely (a few sentences unless asked otherwise). Use the "
            + "supplied recent session context when relevant; say what is unknown instead of inventing "
            + "detail. You have no tools and this exchange is never shown to the main task, so do not "
            + "announce actions — just answer.";
    private static final long TIMEOUT_MS = 90_000;
    private static final int MAX_CONTEXT_TURNS = 12;
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final int MAX_TURN_CHARS = 1_200;
    private static final int MAX_ANSWER_TOKENS = 2048;

    private BtwCommand() { }

    /** Entry used by the router: renders to stdout, never throws. */
    static void run(TerminalRenderer renderer, ChatConfig baseConfig, ChatHistory chatHistory,
                    Path workingDirectory, String question) {
        Path directory = workingDirectory == null ? Path.of(System.getProperty("user.dir")) : workingDirectory;
        run(renderer, baseConfig, chatHistory, directory, question, snapshot ->
                DirectLlmClient.withConnectivityPolicy(snapshot, JsonUtils.standardMapper(),
                        new ProviderConnectivityPolicy(Duration.ofSeconds(5), Duration.ofSeconds(20),
                                Duration.ofSeconds(10), Duration.ofSeconds(10), 1,
                                Duration.ofMillis(100), Duration.ofMillis(100)),
                        directory));
    }

    /** Test-visible entry with an injectable client factory. */
    static void run(TerminalRenderer renderer, ChatConfig baseConfig, ChatHistory chatHistory,
                    Path workingDirectory, String question,
                    Function<ChatConfig, DirectLlmClient> factory) {
        String trimmed = question == null ? "" : ReminderManager.stripReminderBlock(question).strip();
        if (trimmed.isEmpty()) {
            System.out.println(renderer.yellow("Usage: /btw <question> — asks without touching the conversation."));
            return;
        }
        if (baseConfig == null) {
            System.out.println(renderer.yellow("/btw requires a configured chat provider. Use /setup."));
            return;
        }
        // This native transport has no enforceable tool-free mode yet. A system
        // prompt is not a sandbox: do not even create its session for a side question.
        if (baseConfig.isOpenCodeNative()) {
            System.out.println(renderer.yellow("/btw is unavailable on native OpenCode: tool-free requests are not supported. "
                    + "The conversation was not touched."));
            return;
        }
        List<ChatHistory.Turn> turns = readTurns(chatHistory);
        ChatConfig snapshot = sanitize(baseConfig);
        DirectLlmClient.StreamResult result;
        try (DirectLlmClient client = factory.apply(snapshot)) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
            AtomicBoolean cancelled = new AtomicBoolean();
            // Utility work must not stream into the view or inherit the main turn's reasoning budget.
            client.setOutputConsumer(ignored -> { });
            client.setCancelSignal(cancelled);
            client.setCancellationCheck(() -> cancelled.get() || System.nanoTime() >= deadline);
            client.runToolFree(true, "low");
            client.setWireMaxOutputTokens(MAX_ANSWER_TOKENS);
            result = client.streamOneShot(buildUserMessage(buildContext(turns), trimmed), SYSTEM_PROMPT,
                    null, MAX_ANSWER_TOKENS);
        } catch (Exception error) {
            System.out.println(renderer.yellow("/btw failed: "
                    + TerminalRenderer.printableToolOutput(error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage())));
            return;
        }
        if (result == null || result.cancelled) {
            System.out.println(renderer.dim("/btw timed out before an answer arrived; the conversation was not touched."));
            return;
        }
        if (result.failed || !result.toolCalls.isEmpty()) {
            String reason = result.failureMessage == null || result.failureMessage.isBlank()
                    ? "provider error" : result.failureMessage.strip();
            System.out.println(renderer.yellow("/btw could not get an answer: " + TerminalRenderer.printableToolOutput(reason)));
            return;
        }
        String answer = cleanAnswer(result.text);
        if (answer.isBlank()) {
            System.out.println(renderer.yellow("/btw received an empty answer; the conversation was not touched."));
            return;
        }
        System.out.println();
        System.out.println(renderer.bold(renderer.cyan("── /btw "))
                + renderer.dim("(side question — not added to the conversation)"));
        System.out.println(TerminalRenderer.printableToolOutput(answer));
        System.out.println(renderer.dim("──────────────────────────────────────────────────"));
    }

    /** Utility work must not inherit premium modes or the main turn's reasoning budget. */
    private static ChatConfig sanitize(ChatConfig baseConfig) {
        ChatConfig snapshot = baseConfig.copy();
        snapshot.setThinking(null);
        snapshot.setFastMode(false);
        snapshot.setUltracode(false);
        return snapshot;
    }

    private static List<ChatHistory.Turn> readTurns(ChatHistory chatHistory) {
        if (chatHistory == null) return List.of();
        try {
            List<ChatHistory.Turn> turns = chatHistory.readTurns();
            return turns == null ? List.of() : turns;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** Bounded recent-transcript excerpt, chronological, reminders stripped. */
    static String buildContext(List<ChatHistory.Turn> turns) {
        if (turns == null || turns.isEmpty()) return "";
        StringBuilder context = new StringBuilder();
        int budget = MAX_CONTEXT_CHARS;
        int counted = 0;
        for (int i = turns.size() - 1; i >= 0 && counted < MAX_CONTEXT_TURNS && budget > 0; i--) {
            ChatHistory.Turn turn = turns.get(i);
            String role = turn.role() == null ? "assistant" : turn.role();
            String content = ReminderManager.stripReminderBlock(
                    turn.content() == null ? "" : turn.content()).strip();
            if (content.isEmpty()) continue;
            if (content.length() > MAX_TURN_CHARS) content = content.substring(0, MAX_TURN_CHARS) + " …";
            String line = role + ": " + content + "\n";
            if (line.length() > budget) break;
            context.insert(0, line);
            counted++;
            budget -= line.length();
        }
        return context.toString().strip();
    }

    static String buildUserMessage(String context, String question) {
        StringBuilder message = new StringBuilder();
        if (!context.isBlank()) {
            message.append("Recent session context (excerpt, may be partial):\n")
                    .append(context)
                    .append("\n\n");
        }
        message.append("Side question:\n").append(question);
        return message.toString();
    }

    /** Strip reasoning blocks; unterminated ones void the whole answer. */
    static String cleanAnswer(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("(?is)<think>.*?</think>", "").strip();
        if (java.util.regex.Pattern.compile("(?i)<think>").matcher(cleaned).find()) return "";
        return cleaned;
    }
}
