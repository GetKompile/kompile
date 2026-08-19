/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.launcher;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol.Message;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A reusable, process-isolated pipeline runtime controlled entirely over stdio. */
public final class PipelineRuntimeSession implements AutoCloseable {
    private static final int MAX_STDERR_CHARS = 16_384;

    private final UnifiedPipelineDefinition definition;
    private final Process process;
    private final BufferedWriter input;
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pending =
            new ConcurrentHashMap<>();
    private final CompletableFuture<Message> ready = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean logFinished = new AtomicBoolean(false);
    private final StringBuilder stderr = new StringBuilder();
    private final SubprocessLogWriter logWriter;
    private final Thread outputReader;
    private final Thread errorReader;
    private volatile Consumer<Message> progressListener = ignored -> { };

    PipelineRuntimeSession(UnifiedPipelineDefinition definition, Process process) {
        this(definition, process, List.of(), null);
    }

    PipelineRuntimeSession(UnifiedPipelineDefinition definition, Process process,
                           List<String> command, String heapSize) {
        this.definition = definition;
        this.process = process;
        this.input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        this.logWriter = createLogWriter(command, heapSize);
        this.outputReader = new Thread(this::readOutput,
                "pipeline-runtime-stdout-" + definition.getPipelineId());
        this.outputReader.setDaemon(true);
        this.errorReader = new Thread(this::drainErrors,
                "pipeline-runtime-stderr-" + definition.getPipelineId());
        this.errorReader.setDaemon(true);
        this.outputReader.start();
        this.errorReader.start();
    }

    void awaitReady(Duration timeout) throws Exception {
        try {
            ready.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            close();
            throw new IOException("Pipeline runtime did not become ready for "
                    + definition.getPipelineId(), e);
        }
    }

    /** Structured runtime failure propagated without discarding the protocol diagnostic. */
    public static final class RuntimeFailure extends IOException {
        private final Map<String, Object> diagnostic;

        private RuntimeFailure(String message, Map<String, Object> diagnostic) {
            super(message);
            this.diagnostic = diagnostic == null ? Map.of() : Map.copyOf(diagnostic);
        }

        public Map<String, Object> diagnostic() {
            return diagnostic;
        }
    }

    /** One cancellable execution submitted to this reusable runtime. */
    public final class Execution {
        private final String requestId;
        private final CompletableFuture<Message> response;

        private Execution(String requestId, CompletableFuture<Message> response) {
            this.requestId = requestId;
            this.response = response;
        }

        public String requestId() {
            return requestId;
        }

        public Map<String, Object> await(Duration timeout) throws Exception {
            try {
                return output(response.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                cancel(Duration.ofSeconds(2));
                throw new IOException("Pipeline runtime execution timed out", e);
            } finally {
                pending.remove(requestId, response);
            }
        }

        public boolean cancel(Duration timeout) {
            boolean cancelled = PipelineRuntimeSession.this.cancel(requestId, timeout);
            // Native calls may ignore Java interruption. Complete the caller now that the
            // session has been tainted/termination has been requested; the pool observes
            // isAlive()==false and will not reuse this runtime.
            response.completeExceptionally(
                    new CancellationException("Pipeline runtime execution cancelled"));
            return cancelled;
        }
    }

    public Execution start(Map<String, Object> request) throws IOException {
        if (!isAlive()) throw new IOException("Pipeline runtime is not running");
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Message> response = new CompletableFuture<>();
        pending.put(requestId, response);
        try {
            send(PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.EXECUTE,
                    requestId, definition.getPipelineId(),
                    request == null ? Map.of() : Map.of("input", request)));
            return new Execution(requestId, response);
        } catch (IOException failure) {
            pending.remove(requestId, response);
            throw failure;
        }
    }

    public Map<String, Object> execute(Map<String, Object> request, Duration timeout) throws Exception {
        return start(request).await(timeout);
    }

    private Map<String, Object> output(Message response) {
        Object output = response.payload().get("output");
        if (output instanceof Map<?, ?> values) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) values;
            return typed;
        }
        return response.payload();
    }

    public boolean health(Duration timeout) {
        if (!isAlive()) return false;
        try {
            Message response = request(PipelineRuntimeProtocol.HEALTH, Map.of(), timeout);
            return PipelineRuntimeProtocol.HEALTHY.equals(response.type());
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean cancel(String requestId, Duration timeout) {
        if (requestId == null || requestId.isBlank()) return false;
        try {
            request(PipelineRuntimeProtocol.CANCEL,
                    Map.of("targetRequestId", requestId), timeout);
        } catch (Exception ignored) {
            // A non-cooperative native call can prevent the child from answering CANCEL.
            // Process termination below is therefore the source of truth.
        }
        return terminate(timeout, "cancelled");
    }

    public void onProgress(Consumer<Message> listener) {
        this.progressListener = listener == null ? ignored -> { } : listener;
    }

    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public UnifiedPipelineDefinition definition() {
        return definition;
    }

    private Message request(String type, Map<String, Object> payload, Duration timeout) throws Exception {
        if (!isAlive()) throw new IOException("Pipeline runtime is not running");
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            send(PipelineRuntimeProtocol.message(
                    type, requestId, definition.getPipelineId(), payload));
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Pipeline runtime request timed out: " + type, e);
        } finally {
            pending.remove(requestId);
        }
    }

    private void send(Message message) throws IOException {
        synchronized (input) {
            input.write(PipelineRuntimeProtocol.encode(message));
            input.newLine();
            input.flush();
        }
    }

    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLine(AgentLogRecord.Stream.STDOUT, line);
                if (!line.startsWith(PipelineRuntimeProtocol.PREFIX)) continue;
                Message message = PipelineRuntimeProtocol.decode(line);
                if (PipelineRuntimeProtocol.READY.equals(message.type())) {
                    ready.complete(message);
                } else if (PipelineRuntimeProtocol.ERROR.equals(message.type())
                        && message.requestId() == null && !ready.isDone()) {
                    ready.completeExceptionally(runtimeFailure(message));
                } else if (PipelineRuntimeProtocol.PROGRESS.equals(message.type())) {
                    progressListener.accept(message);
                } else if (message.requestId() != null) {
                    CompletableFuture<Message> future = pending.get(message.requestId());
                    if (future != null) {
                        if (PipelineRuntimeProtocol.ERROR.equals(message.type())) {
                            future.completeExceptionally(runtimeFailure(message));
                        } else {
                            future.complete(message);
                        }
                    }
                }
            }
            IOException closed = new IOException(
                    "Pipeline runtime stdout closed" + stderrSuffix());
            ready.completeExceptionally(closed);
            failPending(closed);
            if (!process.isAlive()) finishLog("EXITED", closed.getMessage());
        } catch (Exception e) {
            ready.completeExceptionally(e);
            failPending(e);
            if (!process.isAlive()) finishLog("FAILED", e.getMessage());
        }
    }

    private void drainErrors() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLine(AgentLogRecord.Stream.STDERR, line);
                synchronized (stderr) {
                    stderr.append(line).append(System.lineSeparator());
                    if (stderr.length() > MAX_STDERR_CHARS) {
                        stderr.delete(0, stderr.length() - MAX_STDERR_CHARS);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void failPending(Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    private RuntimeFailure runtimeFailure(Message message) {
        Map<String, Object> diagnostic = new LinkedHashMap<>(message.payload());
        diagnostic.putIfAbsent("summary", message.error() == null
                ? "Pipeline runtime failed" : message.error());
        if (message.requestId() != null) diagnostic.put("requestId", message.requestId());
        if (message.pipelineId() != null) diagnostic.put("pipelineId", message.pipelineId());
        diagnostic.put("runtimePid", process.pid());
        String captured = stderrText();
        if (!captured.isBlank()) diagnostic.put("stderr", captured);
        if (logWriter != null) diagnostic.put("logFile", logWriter.getLogFile().getAbsolutePath());
        return new RuntimeFailure(String.valueOf(diagnostic.get("summary")), diagnostic);
    }

    private String stderrText() {
        synchronized (stderr) {
            return stderr.toString();
        }
    }

    private String stderrSuffix() {
        String captured = stderrText();
        return captured.isEmpty() ? "" : ":\n" + captured;
    }

    @Override
    public void close() {
        terminate(Duration.ofSeconds(8), "closed");
    }

    /**
     * Taints the session before attempting shutdown. This is deliberately process based:
     * Future.cancel(true) only interrupts the Java worker and cannot interrupt a native CUDA
     * dynamic-shape plan. A tainted session must never be returned to the reusable pool.
     */
    private synchronized boolean terminate(Duration timeout, String reason) {
        closed.set(true);
        long budgetMillis = Math.max(1_000L, timeout == null ? 8_000L : timeout.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        try {
            if (process.isAlive()) {
                try {
                    send(PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.SHUTDOWN,
                            UUID.randomUUID().toString(), definition.getPipelineId(), Map.of()));
                } catch (Exception ignored) {
                    // The child may be blocked in native code and unable to read SHUTDOWN.
                }
                waitFor(deadline, Math.min(500L, budgetMillis));
                if (process.isAlive()) process.destroy();
                waitFor(deadline, Math.min(1_000L, remainingMillis(deadline)));
                if (process.isAlive()) process.destroyForcibly();
                waitFor(deadline, remainingMillis(deadline));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process.isAlive()) process.destroyForcibly();
        } finally {
            failPending("cancelled".equals(reason)
                    ? new CancellationException("Pipeline runtime execution cancelled")
                    : new IOException("Pipeline runtime session " + reason));
            finishLog("cancelled".equals(reason) ? "CANCELLED" : "CLOSED", reason);
        }
        return !process.isAlive();
    }

    private void waitFor(long deadline, long maximumMillis) throws InterruptedException {
        long remaining = Math.min(remainingMillis(deadline), maximumMillis);
        if (remaining > 0 && process.isAlive()) process.waitFor(remaining, TimeUnit.MILLISECONDS);
    }

    private long remainingMillis(long deadline) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private SubprocessLogWriter createLogWriter(List<String> command, String heapSize) {
        SubprocessLogWriter writer = null;
        try {
            String runId = UUID.randomUUID().toString();
            writer = new SubprocessLogWriter("serving", runId);
            writer.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    definition.getPipelineId(),
                    command == null ? List.of() : List.copyOf(command),
                    null,
                    process.pid(),
                    heapSize));
            return writer;
        } catch (Exception ignored) {
            if (writer != null) writer.close();
            return null;
        }
    }

    private void logLine(AgentLogRecord.Stream stream, String line) {
        if (logWriter == null) return;
        try {
            logWriter.writeLine(stream, line);
        } catch (IOException ignored) {
            // Diagnostics must never prevent protocol draining or cancellation.
        }
    }

    private void finishLog(String state, String errorMessage) {
        if (logWriter == null || !logFinished.compareAndSet(false, true)) return;
        try {
            Integer exitCode = null;
            if (!process.isAlive()) {
                try {
                    exitCode = process.exitValue();
                } catch (IllegalThreadStateException ignored) {
                    // The process exited between isAlive and exitValue.
                }
            }
            logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    state, exitCode, errorMessage, false, false));
        } catch (IOException ignored) {
            // Best-effort log finalization.
        } finally {
            logWriter.close();
        }
    }
}
