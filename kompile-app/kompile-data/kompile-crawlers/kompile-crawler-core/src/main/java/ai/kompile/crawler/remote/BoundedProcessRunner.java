/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawler.remote;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs connector subprocesses with concurrent draining, hard timeout, and bounded diagnostics. */
final class BoundedProcessRunner {

    static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    static final int MAX_LISTING_OUTPUT_BYTES = 16 * 1024 * 1024;

    private BoundedProcessRunner() {
    }

    static Result run(ProcessBuilder builder, String stdin, Duration timeout, String operation)
            throws IOException {
        return run(builder, stdin, timeout, operation, DEFAULT_MAX_OUTPUT_BYTES);
    }

    static Result run(
            ProcessBuilder builder,
            String stdin,
            Duration timeout,
            String operation,
            int maxOutputBytes) throws IOException {
        if (maxOutputBytes < 1) throw new IllegalArgumentException("maxOutputBytes must be positive");
        String safeOperation = SourceCredentialRedactor.redact(operation);
        Process process = builder.redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        Thread drainer = new Thread(
                () -> drain(process.getInputStream(), captured, truncated, maxOutputBytes),
                "connector-output-drain");
        drainer.setDaemon(true);
        drainer.start();

        AtomicReference<IOException> inputFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> writeInput(process, stdin, inputFailure),
                "connector-input-write");
        writer.setDaemon(true);
        writer.start();

        try {
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                join(drainer);
                join(writer);
                throw new IOException(safeOperation + " timed out after "
                        + timeout.toSeconds() + " seconds");
            }
            join(drainer);
            join(writer);
            if (process.exitValue() == 0 && inputFailure.get() != null) {
                throw new IOException(safeOperation + " could not send connector input",
                        inputFailure.get());
            }
            String output = captured.toString(StandardCharsets.UTF_8);
            if (truncated.get()) output += "\n[output truncated]";
            return new Result(process.exitValue(), output, truncated.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(safeOperation + " interrupted", e);
        }
    }

    private static void drain(
            InputStream input,
            ByteArrayOutputStream captured,
            AtomicBoolean truncated,
            int maxOutputBytes) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = maxOutputBytes - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining));
                if (read > remaining) truncated.set(true);
            }
        } catch (IOException ignored) {
            // Process termination closes the stream; the exit/timeout remains authoritative.
        }
    }

    private static void writeInput(
            Process process, String stdin, AtomicReference<IOException> failure) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8)) {
            if (stdin != null) {
                writer.write(stdin);
                writer.flush();
            }
        } catch (IOException e) {
            failure.set(e);
        }
    }

    private static void join(Thread drainer) throws InterruptedException {
        drainer.join(5_000L);
        if (drainer.isAlive()) drainer.interrupt();
    }

    record Result(int exitCode, String output, boolean truncated) {
        java.util.List<String> lines() {
            return output.lines().toList();
        }
    }
}
