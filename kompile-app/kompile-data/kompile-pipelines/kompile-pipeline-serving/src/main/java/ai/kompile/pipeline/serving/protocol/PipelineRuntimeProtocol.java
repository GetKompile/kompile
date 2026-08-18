/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.protocol;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned bidirectional stdio protocol used by every reusable pipeline runtime. */
public final class PipelineRuntimeProtocol {
    public static final int VERSION = 1;
    public static final String PREFIX = "PIPELINE_RUNTIME:";

    public static final String HELLO = "HELLO";
    public static final String READY = "READY";
    public static final String LOAD_PIPELINE = "LOAD_PIPELINE";
    public static final String LOADED = "LOADED";
    public static final String EXECUTE = "EXECUTE";
    public static final String PROGRESS = "PROGRESS";
    public static final String RESULT = "RESULT";
    public static final String ERROR = "ERROR";
    public static final String CANCEL = "CANCEL";
    public static final String CANCELLED = "CANCELLED";
    public static final String HEALTH = "HEALTH";
    public static final String HEALTHY = "HEALTHY";
    public static final String RESET = "RESET";
    public static final String RESET_DONE = "RESET_DONE";
    public static final String UNLOAD = "UNLOAD";
    public static final String UNLOADED = "UNLOADED";
    public static final String SHUTDOWN = "SHUTDOWN";
    public static final String SHUTDOWN_COMPLETE = "SHUTDOWN_COMPLETE";

    private static final ObjectMapper MAPPER = ObjectMappers.getJsonMapper();

    private PipelineRuntimeProtocol() {
    }

    public static Message message(String type, String requestId, String pipelineId,
                                  Map<String, Object> payload) {
        return new Message(VERSION, type, requestId, pipelineId,
                payload == null ? Map.of() : payload, null, Instant.now().toString());
    }

    public static Message error(String requestId, String pipelineId, Throwable error) {
        return error(requestId, pipelineId, "RUNTIME", error);
    }

    public static Message error(String requestId, String pipelineId,
                                String failureStage, Throwable error) {
        Map<String, Object> diagnostic = diagnostic(failureStage, error);
        String detail = String.valueOf(diagnostic.get("summary"));
        return new Message(VERSION, ERROR, requestId, pipelineId, diagnostic, detail,
                Instant.now().toString());
    }

    /** Build a bounded, transport-safe diagnostic without losing the causal exception chain. */
    public static Map<String, Object> diagnostic(String failureStage, Throwable error) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("failureStage", failureStage == null || failureStage.isBlank()
                ? "UNKNOWN" : failureStage);
        if (error == null) {
            diagnostic.put("summary", "Unknown pipeline runtime error");
            diagnostic.put("exceptionClass", "unknown");
            diagnostic.put("exceptionChain", List.of());
            diagnostic.put("stackTrace", List.of());
            return Map.copyOf(diagnostic);
        }

        Throwable root = error;
        for (int depth = 0; root.getCause() != null
                && root.getCause() != root && depth < 15; depth++) {
            root = root.getCause();
        }
        String summary = root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getName()
                : root.getMessage();
        diagnostic.put("summary", summary);
        diagnostic.put("exceptionClass", error.getClass().getName());
        if (error.getMessage() != null) diagnostic.put("exceptionMessage", error.getMessage());
        diagnostic.put("rootCauseClass", root.getClass().getName());
        if (root.getMessage() != null) diagnostic.put("rootCauseMessage", root.getMessage());

        List<Map<String, String>> chain = new ArrayList<>();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("exceptionClass", current.getClass().getName());
            if (current.getMessage() != null) item.put("exceptionMessage", current.getMessage());
            chain.add(Map.copyOf(item));
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        diagnostic.put("exceptionChain", List.copyOf(chain));

        List<String> stack = new ArrayList<>();
        for (StackTraceElement frame : error.getStackTrace()) {
            if (stack.size() >= 80) break;
            stack.add(frame.toString());
        }
        diagnostic.put("stackTrace", List.copyOf(stack));
        return Map.copyOf(diagnostic);
    }

    public static String encode(Message message) throws IOException {
        return PREFIX + MAPPER.writeValueAsString(message);
    }

    public static Message decode(String line) throws IOException {
        if (line == null || !line.startsWith(PREFIX)) {
            throw new IOException("Not a pipeline runtime protocol message");
        }
        Message message = MAPPER.readValue(line.substring(PREFIX.length()), Message.class);
        if (message.version() != VERSION) {
            throw new IOException("Unsupported pipeline runtime protocol version " + message.version());
        }
        return message;
    }

    public static void write(PrintStream output, Message message) throws IOException {
        synchronized (output) {
            output.println(encode(message));
            output.flush();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(int version,
                          String type,
                          String requestId,
                          String pipelineId,
                          Map<String, Object> payload,
                          String error,
                          String timestamp) {
        public Message {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
