/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.launcher;

import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.SubprocessSignals;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol.Message;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import com.fasterxml.jackson.core.JsonProcessingException;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A reusable, process-isolated pipeline runtime controlled entirely over stdio. */
public final class PipelineRuntimeSession implements AutoCloseable {
    private static final int MAX_STDERR_CHARS = 16_384;
    /** How long a killed child gets to exit; releasing its GPU context can take seconds. */
    private static final long KILL_EXIT_WAIT_SECONDS = 30L;
    /**
     * How long the stderr reader gets to finish an exited child's output. A process the child
     * started can hold the pipe open past the child's exit.
     */
    private static final long STDERR_DRAIN_MILLIS = 2_000L;

    private final UnifiedPipelineDefinition definition;
    private final Process process;
    /**
     * Whether the launcher started the child behind {@link SubprocessProtocolChannel#apply}: its
     * fd 1 is the stderr pipe, so a child that writes its protocol to fd 1 sends it to stderr.
     */
    private final boolean wrapped;
    private final BufferedWriter input;
    /**
     * The only writer of {@link #input}: one message at a time, in the order they were sent. A
     * child that stops reading stdin (stalled in native code, stopped, thrashing) fills the pipe,
     * and a write to a full pipe blocks until the child dies; no interrupt or close ends it.
     * Writing here keeps that from hanging callers, so a request still times out and
     * {@link #terminate} still kills the child, which fails the blocked write.
     */
    private final ExecutorService stdinWriter;
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<Message>> progressListeners =
            new ConcurrentHashMap<>();
    private final CompletableFuture<Message> ready = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean logFinished = new AtomicBoolean(false);
    private final StringBuilder stderr = new StringBuilder();
    private final SubprocessLogWriter logWriter;
    private final Thread outputReader;
    private final Thread errorReader;
    private volatile Consumer<Message> progressListener = ignored -> { };
    /** The last output line that failed to decode; a response lost that way ends in a timeout. */
    private volatile IOException protocolError;

    PipelineRuntimeSession(UnifiedPipelineDefinition definition, Process process) {
        this(definition, process, List.of(), null, false);
    }

    PipelineRuntimeSession(UnifiedPipelineDefinition definition, Process process,
                           List<String> command, String heapSize, boolean wrapped) {
        this.definition = definition;
        this.process = process;
        this.wrapped = wrapped;
        this.input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdinWriter = new ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread writer = new Thread(task, "pipeline-runtime-stdin-" + definition.getPipelineId());
                    writer.setDaemon(true);
                    return writer;
                });
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
                throw new IOException("Pipeline runtime execution timed out" + protocolErrorNote(), e);
            } catch (InterruptedException e) {
                cancel(Duration.ofSeconds(2));
                Thread.currentThread().interrupt();
                throw e;
            } finally {
                pending.remove(requestId, response);
                progressListeners.remove(requestId);
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
        return start(request, null);
    }

    public Execution start(Map<String, Object> request, Consumer<Message> progress) throws IOException {
        if (!isAlive()) throw new IOException("Pipeline runtime is not running");
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Message> response = new CompletableFuture<>();
        pending.put(requestId, response);
        if (progress != null) progressListeners.put(requestId, progress);
        try {
            send(PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.EXECUTE,
                    requestId, definition.getPipelineId(),
                    request == null ? Map.of() : Map.of("input", request)), response);
            return new Execution(requestId, response);
        } catch (IOException failure) {
            pending.remove(requestId, response);
            progressListeners.remove(requestId);
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
                    type, requestId, definition.getPipelineId(), payload), future);
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Pipeline runtime request timed out: " + type + protocolErrorNote(), e);
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * Queues {@code message} for {@link #stdinWriter}. It is encoded here, so a message that
     * cannot be encoded still fails its caller; a failed write fails {@code response}, if given.
     */
    private void send(Message message, CompletableFuture<Message> response) throws IOException {
        String line = PipelineRuntimeProtocol.encode(message);
        stdinWriter.execute(() -> {
            try {
                input.write(line);
                input.newLine();
                input.flush();
            } catch (IOException | RuntimeException failure) {
                if (response != null) response.completeExceptionally(failure);
            }
        });
    }

    private void readOutput() {
        Exception failure = null;
        boolean endOfStream = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLine(AgentLogRecord.Stream.STDOUT, line);
                // libnd4j logs with printf straight to fd 1, beneath the child's System.setOut
                // redirect, so a native message without a newline can precede a protocol line.
                int prefixAt = line.indexOf(PipelineRuntimeProtocol.PREFIX);
                if (prefixAt < 0) continue;
                Message message;
                try {
                    message = PipelineRuntimeProtocol.decode(line.substring(prefixAt));
                } catch (IOException | RuntimeException undecodable) {
                    // Keep reading: a reader that stops here leaves every later response on a
                    // runtime the pool still leases to wait out its caller's timeout. Before
                    // READY the lost line was READY or the fatal ERROR, so startup fails now.
                    String detail = undecodable instanceof JsonProcessingException json
                            ? json.getOriginalMessage() : undecodable.getMessage();
                    IOException error = new IOException(
                            "Undecodable pipeline runtime output: " + detail, undecodable);
                    protocolError = error;
                    ready.completeExceptionally(error);
                    continue;
                }
                dispatch(message);
            }
            endOfStream = true;
        } catch (Exception e) {
            failure = e;
        } finally {
            readerStopped(endOfStream, failure);
        }
    }

    private void dispatch(Message message) {
        if (PipelineRuntimeProtocol.READY.equals(message.type())) {
            ready.complete(message);
        } else if (PipelineRuntimeProtocol.ERROR.equals(message.type())
                && message.requestId() == null && !ready.isDone()) {
            ready.completeExceptionally(runtimeFailure(message));
        } else if (PipelineRuntimeProtocol.PROGRESS.equals(message.type())) {
            Consumer<Message> requestListener = message.requestId() == null
                    ? null : progressListeners.get(message.requestId());
            try {
                if (requestListener != null) requestListener.accept(message);
                progressListener.accept(message);
            } catch (RuntimeException ignored) {
                // A caller callback must not stop protocol draining for pooled runtimes.
            }
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

    /**
     * Once the reader stops nothing drains this runtime's responses, so the session is tainted
     * before a caller can lease it again, and a child that is still running is stopped: its next
     * write to the undrained stdout pipe would block it.
     */
    private void readerStopped(boolean endOfStream, Exception failure) {
        boolean terminating = !closed.compareAndSet(false, true);
        try {
            // EOF can arrive before an exiting child is reaped; wait so its exit is recorded.
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // Its last stderr lines say why it stopped, and a child that writes its protocol to fd 1
        // sent its last messages there: read them before its requests fail.
        awaitStderrOfExitedChild();
        Exception error = failure != null ? failure : new IOException((endOfStream
                ? "Pipeline runtime stdout closed" : "Pipeline runtime stdout reader failed")
                + stderrSuffix());
        ready.completeExceptionally(error);
        failPending(error);
        if (terminating) return; // terminate() owns the shutdown and the log end record
        if (process.isAlive()) {
            terminate(Duration.ofSeconds(8), "stopped reporting");
        } else {
            finishLog(endOfStream ? "EXITED" : "FAILED", error.getMessage());
        }
    }

    private void drainErrors() {
        SubprocessProtocolChannel.StderrProtocol stderrProtocol = SubprocessProtocolChannel.stderrProtocol(
                wrapped, PipelineRuntimeProtocol.PREFIX, "pipeline-runtime-" + definition.getPipelineId());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLine(AgentLogRecord.Stream.STDERR, line);
                String text = line;
                // A child that writes its protocol to fd 1 (an older build, or one that could not
                // open the channel) sends it here.
                int prefixAt = stderrProtocol.prefixIndex(line);
                Message message = null;
                if (prefixAt >= 0) {
                    try {
                        message = PipelineRuntimeProtocol.decode(line.substring(prefixAt));
                    } catch (IOException | RuntimeException undecodable) {
                        // Until the child says it uses the channel, stderr also carries its log
                        // text, which can quote the prefix: a line that doesn't decode stays log
                        // text and fails nothing.
                    }
                }
                if (message != null) {
                    text = line.substring(0, prefixAt);
                    dispatch(message);
                }
                if (!text.isEmpty()) appendStderr(text);
            }
        } catch (IOException ignored) {
        }
    }

    private void appendStderr(String text) {
        synchronized (stderr) {
            stderr.append(text).append(System.lineSeparator());
            if (stderr.length() > MAX_STDERR_CHARS) {
                stderr.delete(0, stderr.length() - MAX_STDERR_CHARS);
            }
        }
    }

    /**
     * Lets the stderr reader finish the output of a child that has exited, bounded by
     * {@link #STDERR_DRAIN_MILLIS}. An interrupt ends the wait and is kept.
     */
    private void awaitStderrOfExitedChild() {
        if (process.isAlive() || Thread.currentThread() == errorReader) return;
        try {
            errorReader.join(STDERR_DRAIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void failPending(Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
        progressListeners.clear();
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

    private String protocolErrorNote() {
        IOException error = protocolError;
        return error == null ? "" : " (" + error.getMessage() + ")";
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
                            UUID.randomUUID().toString(), definition.getPipelineId(), Map.of()), null);
                } catch (IOException ignored) {
                    // Only encoding fails here. The child may be blocked in native code and
                    // unable to read SHUTDOWN anyway; the signals below do not depend on it.
                }
                waitFor(deadline, Math.min(500L, budgetMillis));
                // Signalled through its handle: Process.destroy*() would close the pipes and lose
                // what the child writes on its way out.
                if (process.isAlive()) SubprocessSignals.terminate(process);
                waitFor(deadline, Math.min(1_000L, remainingMillis(deadline)));
                if (process.isAlive()) {
                    SubprocessSignals.kill(process);
                    awaitKilledExit();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process.isAlive()) {
                SubprocessSignals.kill(process);
                awaitKilledExit();
            }
        } finally {
            awaitStderrOfExitedChild();
            failPending("cancelled".equals(reason)
                    ? new CancellationException("Pipeline runtime execution cancelled")
                    : new IOException("Pipeline runtime session " + reason));
            finishLog("cancelled".equals(reason) ? "CANCELLED" : "CLOSED", reason);
        }
        return !process.isAlive();
    }

    /**
     * Waits for a child that was just killed. Neither the caller's budget nor an interrupt cuts
     * the wait short: the pool starts a replacement next, which can fail to allocate while this
     * child still holds its device memory. Bounded by {@link #KILL_EXIT_WAIT_SECONDS}.
     */
    private void awaitKilledExit() {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KILL_EXIT_WAIT_SECONDS);
        try {
            while (true) {
                try {
                    process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
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
            writer = new SubprocessLogWriter("pipeline-serving", runId);
            writer.putMetadata("processType", "pipeline-serving");
            writer.putMetadata("pipelineId", definition.getPipelineId());
            writer.putMetadata("pipelineKind", String.valueOf(definition.getKind()));
            writer.putMetadata("modelSetId", definition.getModelSetId());
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
