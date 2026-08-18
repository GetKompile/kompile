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
        String detail = error == null ? "Unknown pipeline runtime error"
                : error.getMessage() == null ? error.getClass().getName() : error.getMessage();
        return new Message(VERSION, ERROR, requestId, pipelineId, Map.of(), detail,
                Instant.now().toString());
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
