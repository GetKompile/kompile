/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProvisionedAgentRuntime;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime.AppendEventsRequest;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime.PrepareRequest;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime.ToolExecutionRequest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Authenticated app-main HTTP boundary for chat-persona provisioned-agent runtime calls. */
@RestController
@RequestMapping("/api/kclaw/runtime")
public final class ProvisionedAgentRuntimeController {

    static final int MAX_REQUEST_BYTES = ProvisionedAgentRuntime.MAX_HTTP_BODY_BYTES;

    private final ProvisionedAgentRuntime runtime;
    private final ObjectMapper strictMapper;

    public ProvisionedAgentRuntimeController(
            ProvisionedAgentRuntime runtime,
            ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostMapping("/context")
    public ResponseEntity<?> prepare(HttpServletRequest request) {
        try {
            return encoded(runtime.prepare(decode(request, PrepareRequest.class)));
        } catch (BadRuntimeRequest invalid) {
            return error(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (ProvisionedAgentRuntime.RuntimeException failure) {
            return runtimeError(failure);
        }
    }

    @PostMapping("/events")
    public ResponseEntity<?> append(HttpServletRequest request) {
        try {
            return encoded(runtime.append(decode(request, AppendEventsRequest.class)));
        } catch (BadRuntimeRequest invalid) {
            return error(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (ProvisionedAgentRuntime.RuntimeException failure) {
            return runtimeError(failure);
        }
    }

    @PostMapping("/tool")
    public ResponseEntity<?> executeTool(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(runtime.executeTool(
                    decode(request, ToolExecutionRequest.class)));
        } catch (BadRuntimeRequest invalid) {
            return error(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (ProvisionedAgentRuntime.RuntimeException failure) {
            return runtimeError(failure);
        }
    }

    private <T> T decode(HttpServletRequest request, Class<T> type) {
        try {
            if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
                throw new BadRuntimeRequest("Runtime request body exceeds the bounded limit");
            }
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length == 0 || body.length > MAX_REQUEST_BYTES) {
                throw new BadRuntimeRequest(
                        "Runtime request body is empty or exceeds the bounded limit");
            }
            return strictMapper.readValue(body, type);
        } catch (BadRuntimeRequest invalid) {
            throw invalid;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BadRuntimeRequest("Invalid provisioned-agent runtime request");
        }
    }

    private ResponseEntity<?> encoded(Object response) {
        try {
            byte[] body = strictMapper.writeValueAsBytes(response);
            if (body.length == 0 || body.length > ProvisionedAgentRuntime.MAX_HTTP_BODY_BYTES) {
                throw new ProvisionedAgentRuntime.RuntimeException(
                        500, "Provisioned-agent runtime response exceeded the bounded limit");
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (ProvisionedAgentRuntime.RuntimeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ProvisionedAgentRuntime.RuntimeException(
                    500, "Could not encode provisioned-agent runtime response", failure);
        }
    }

    private static ResponseEntity<ErrorResponse> runtimeError(
            ProvisionedAgentRuntime.RuntimeException failure) {
        HttpStatus status = HttpStatus.resolve(failure.statusCode());
        return error(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                failure.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }

    public record ErrorResponse(String error) {
    }

    private static final class BadRuntimeRequest extends java.lang.RuntimeException {
        private BadRuntimeRequest(String message) {
            super(message);
        }
    }
}
