/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A headless supervisory chat REPL that lives in the same JVM as the main
 * {@link ChatRepl}. Judge and enforcer turns use independent model clients and
 * retain their own live transcript, while a direct control link lets either
 * REPL feed a correction back to the main turn owner.
 */
public final class AuxiliaryChatRepl implements JudgeBackend {

    public enum Kind {
        JUDGE("judge", "Judge chat"),
        ENFORCER("enforcer", "Enforcer chat"),
        DIRECTION("direction", "Direction monitor");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    @FunctionalInterface
    interface TurnRunner {
        String run(String userPrompt, String systemPrompt, Consumer<String> stream) throws Exception;
    }

    @FunctionalInterface
    interface StructuredTurnRunner {
        String run(String userPrompt, String systemPrompt, String schemaName,
                   JsonNode schema, boolean strict, Consumer<String> stream) throws Exception;
    }

    @FunctionalInterface
    public interface MainChatControl {
        boolean submitFeedback(String source, String feedback, boolean interrupt);
    }

    private static final int MAX_TRANSCRIPT_CHARS = 128_000;
    private static final int MAX_MODEL_HISTORY_MESSAGES = 80;

    private final Kind kind;
    private final String backendDescription;
    private final TurnRunner turnRunner;
    private final TurnRunner verdictRunner;
    private final StructuredTurnRunner structuredVerdictRunner;
    private final AutoCloseable ownedResource;
    private final Instant startedAt = Instant.now();
    private final Object turnLock = new Object();
    private final Object transcriptLock = new Object();
    private final StringBuilder transcript = new StringBuilder();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String status;
    private volatile String failureReason = "";
    private volatile MainChatControl mainChatControl;

    private AuxiliaryChatRepl(
            Kind kind,
            String backendDescription,
            TurnRunner turnRunner,
            TurnRunner verdictRunner,
            StructuredTurnRunner structuredVerdictRunner,
            AutoCloseable ownedResource,
            String initialStatus) {
        this.kind = kind;
        this.backendDescription = backendDescription == null || backendDescription.isBlank()
                ? "in-process" : backendDescription;
        this.turnRunner = turnRunner;
        this.verdictRunner = verdictRunner;
        this.structuredVerdictRunner = structuredVerdictRunner;
        this.ownedResource = ownedResource;
        this.status = initialStatus;
        append("[" + kind.id() + "] " + initialStatus + " · " + this.backendDescription + "\n");
    }

    /** Create a model-backed auxiliary REPL with its own independent chat history. */
    public static AuxiliaryChatRepl modelBacked(
            Kind kind, DirectLlmClient client, String modelOverride) {
        if (client == null) {
            return observer(kind, "model client unavailable");
        }
        String provider = client.getConfiguredProvider();
        String model = modelOverride == null || modelOverride.isBlank()
                ? client.getConfiguredModel() : modelOverride;
        String thinking = client.getChatConfig().getThinking();
        String description = (provider == null || provider.isBlank() ? "provider" : provider)
                + "/" + (model == null || model.isBlank() ? "default" : model)
                + " · thinking/effort: "
                + (thinking == null || thinking.isBlank() ? "provider default" : thinking.trim());
        // ResilientJudgeBackend enforces a hard deadline by interrupting its worker.
        // Make that interruption visible to every direct-provider stream parser.
        client.setCancellationCheck(() -> Thread.currentThread().isInterrupted());
        AtomicReference<String> activeRoute = new AtomicReference<>(String.valueOf(provider) + "/" + String.valueOf(model));
        TurnRunner conversationalRunner = (userPrompt, systemPrompt, stream) -> {
            String providerNow = client.getConfiguredProvider();
            String modelNow = modelOverride == null || modelOverride.isBlank()
                    ? client.getConfiguredModel() : modelOverride;
            String routeNow = String.valueOf(providerNow) + "/" + String.valueOf(modelNow);
            String priorRoute = activeRoute.getAndSet(routeNow);
            if (!routeNow.equals(priorRoute)) {
                client.clearHistory();
            }
            // Judge requests are self-contained. Retain recent conversational
            // continuity without letting a long tool-heavy parent chat overflow
            // the supervisory model's independent context window.
            if (client.getHistorySize() >= MAX_MODEL_HISTORY_MESSAGES) {
                client.clearHistory();
            }
            Consumer<String> previous = client.getOutputConsumer();
            client.setOutputConsumer(stream);
            try {
                DirectLlmClient.StreamResult result = client.streamChat(
                        userPrompt, systemPrompt, null, null, modelOverride);
                return completedText(result);
            } finally {
                client.setOutputConsumer(previous);
            }
        };
        TurnRunner statelessVerdictRunner = (userPrompt, systemPrompt, stream) -> {
            Consumer<String> previous = client.getOutputConsumer();
            client.setOutputConsumer(stream);
            try {
                DirectLlmClient.StreamResult result = client.streamOneShot(
                        userPrompt, systemPrompt, modelOverride);
                return completedText(result);
            } finally {
                client.setOutputConsumer(previous);
            }
        };
        StructuredTurnRunner structuredRunner =
                (userPrompt, systemPrompt, schemaName, schema, strict, stream) -> {
                    Consumer<String> previous = client.getOutputConsumer();
                    client.setOutputConsumer(stream);
                    try {
                        DirectLlmClient.StreamResult result = client.streamOneShotJson(
                                userPrompt, systemPrompt, modelOverride,
                                schemaName, schema, strict);
                        return completedText(result);
                    } finally {
                        client.setOutputConsumer(previous);
                    }
                };
        return new AuxiliaryChatRepl(
                kind, description, conversationalRunner, statelessVerdictRunner,
                structuredRunner, client, "ready");
    }

    /** Create an in-process transcript/control REPL without an LLM backend. */
    public static AuxiliaryChatRepl observer(Kind kind, String status) {
        String initial = status == null || status.isBlank() ? "idle" : status;
        return new AuxiliaryChatRepl(kind, "in-process", null, null, null, null, initial);
    }

    /** Test seam for a streaming in-process turn runner. */
    static AuxiliaryChatRepl withRunner(Kind kind, String description, TurnRunner runner) {
        StructuredTurnRunner structured =
                (user, system, schemaName, schema, strict, stream) ->
                        runner.run(user, system, stream);
        return new AuxiliaryChatRepl(
                kind, description, runner, runner, structured, null, "ready");
    }

    /**
     * A stateless machine-verdict view of this REPL. It shares the visible activity transcript,
     * but each generation uses {@link DirectLlmClient#streamOneShot} so prior verdicts and
     * conversational /judge chat cannot contaminate the JSON contract.
     */
    JudgeBackend verdictBackend() {
        return new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) throws Exception {
                return runTurn(verdictRunner, userPrompt, systemPrompt);
            }

            @Override
            public String generateJson(
                    String userPrompt, String systemPrompt, JsonSchema outputSchema) throws Exception {
                if (structuredVerdictRunner == null) {
                    return generate(userPrompt, systemPrompt);
                }
                return runTurn(
                        (user, system, stream) -> structuredVerdictRunner.run(
                                user, system, outputSchema.name(), outputSchema.schema(),
                                outputSchema.strict(), stream),
                        userPrompt, systemPrompt);
            }

            @Override
            public boolean isAvailable() {
                return verdictRunner != null && AuxiliaryChatRepl.this.isAvailable();
            }

            @Override
            public void restart() {
                if (!closed.get()) {
                    failureReason = "";
                    status = "ready";
                    fireChange();
                }
            }

            @Override
            public String failureReason() {
                return AuxiliaryChatRepl.this.failureReason();
            }

            @Override
            public String describe() {
                return AuxiliaryChatRepl.this.describe();
            }

            @Override
            public void close() {
                // The owning ChatRepl controls the visible auxiliary REPL lifecycle.
            }
        };
    }

    public String id() {
        return kind.id();
    }

    public String displayName() {
        return kind.displayName();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String status() {
        return status;
    }

    public boolean isRunning() {
        return "running".equals(status);
    }

    public void setStatus(String status) {
        if (closed.get()) return;
        this.status = status == null || status.isBlank() ? "idle" : status;
        append("\n[status] " + this.status + "\n");
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    public void setMainChatControl(MainChatControl control) {
        this.mainChatControl = control;
    }

    /** Record a deterministic decision or lifecycle event in this REPL's log. */
    public void observe(String event) {
        if (event == null || event.isBlank()) return;
        append("\n" + event.stripTrailing() + "\n");
    }

    /** Send actionable feedback through the main REPL's owned dispatch/cancel path. */
    public boolean sendFeedback(String feedback, boolean interrupt) {
        if (feedback == null || feedback.isBlank()) return false;
        observe("[feedback -> main" + (interrupt ? " · interrupt" : "") + "]\n" + feedback);
        MainChatControl control = mainChatControl;
        return control != null && control.submitFeedback(kind.id(), feedback, interrupt);
    }

    public String transcript() {
        synchronized (transcriptLock) {
            return transcript.toString();
        }
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        return runTurn(turnRunner, userPrompt, systemPrompt);
    }

    private String runTurn(
            TurnRunner selectedRunner, String userPrompt, String systemPrompt) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException(displayName() + " is closed");
        }
        if (selectedRunner == null) {
            throw new IllegalStateException(displayName() + " has no model backend");
        }

        synchronized (turnLock) {
            status = "running";
            failureReason = "";
            append("\n> " + compactPrompt(userPrompt) + "\n\n");
            StringBuilder streamed = new StringBuilder();
            try {
                String response = selectedRunner.run(userPrompt, systemPrompt, chunk -> {
                    if (chunk == null || chunk.isEmpty()) return;
                    streamed.append(chunk);
                    append(chunk);
                });
                if (streamed.length() == 0 && response != null && !response.isEmpty()) {
                    append(response);
                }
                append("\n");
                status = "ready";
                fireChange();
                return response == null ? "" : response;
            } catch (Exception failure) {
                failureReason = failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                status = "failed";
                append("\n[error] " + failureReason + "\n");
                throw failure;
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return turnRunner != null && !closed.get();
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String describe() {
        return "chat-repl(" + kind.id() + ", " + backendDescription + ")";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        status = "closed";
        append("\n[status] closed\n");
        if (ownedResource != null) {
            try {
                ownedResource.close();
            } catch (Exception failure) {
                failureReason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
            }
        }
    }

    private void append(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (transcriptLock) {
            transcript.append(text);
            if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
                int remove = transcript.length() - MAX_TRANSCRIPT_CHARS;
                transcript.delete(0, remove);
                transcript.insert(0, "… earlier auxiliary chat output trimmed …\n");
            }
        }
        fireChange();
    }

    private void fireChange() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A terminal repaint failure must not break supervision.
            }
        }
    }

    private static String completedText(DirectLlmClient.StreamResult result) throws Exception {
        if (result == null) {
            return "";
        }
        if (result.cancelled) {
            throw new InterruptedException("judge provider request was cancelled");
        }
        if (result.failed) {
            String failure = result.failureMessage;
            if (failure == null || failure.isBlank()) {
                failure = result.text;
            }
            throw new IllegalStateException(failure == null || failure.isBlank()
                    ? "judge provider request failed" : failure.strip());
        }
        return result.text == null ? "" : result.text;
    }

    private static String compactPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return "(empty request)";
        String stripped = prompt.strip();
        int max = 8_000;
        return stripped.length() <= max
                ? stripped : stripped.substring(0, max) + "\n… [request truncated]";
    }
}
