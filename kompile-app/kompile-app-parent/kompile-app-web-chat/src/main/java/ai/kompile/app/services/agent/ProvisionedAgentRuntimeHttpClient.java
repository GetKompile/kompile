/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services.agent;

import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Narrow authenticated chat-persona client for the app-main provisioned-agent runtime. */
@Service
@RegisterReflectionForBinding({
        ProvisionedAgentRuntime.PrepareRequest.class,
        ProvisionedAgentRuntime.RuntimeContext.class,
        ProvisionedAgentRuntime.CanonicalEvent.class,
        ProvisionedAgentRuntime.EventDraft.class,
        ProvisionedAgentRuntime.AppendEventsRequest.class,
        ProvisionedAgentRuntime.ToolDescriptor.class,
        ProvisionedAgentRuntime.ToolExecutionRequest.class,
        ProvisionedAgentRuntime.ToolExecutionResult.class
})
public final class ProvisionedAgentRuntimeHttpClient implements ProvisionedAgentRuntime {

    private static final String BASE_PATH = "/api/kclaw/runtime";
    static final int MAX_REQUEST_BYTES = ProvisionedAgentRuntime.MAX_HTTP_BODY_BYTES;
    static final int MAX_RESPONSE_BYTES = ProvisionedAgentRuntime.MAX_HTTP_BODY_BYTES;
    private static final Duration CONTEXT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper;
    private final IntegrationControlCredentials credentials;
    private final Supplier<String> adminBaseUrl;
    private final HttpClient client;

    @Autowired
    public ProvisionedAgentRuntimeHttpClient(
            ObjectMapper mapper,
            IntegrationControlCredentials credentials) {
        this(mapper, credentials,
                () -> KompileServiceEndpoints.resolve(KompileService.ADMIN).baseUrl(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    /** Test seam for a bounded in-process HTTP server. */
    ProvisionedAgentRuntimeHttpClient(
            ObjectMapper mapper,
            IntegrationControlCredentials credentials,
            Supplier<String> adminBaseUrl,
            HttpClient client) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.adminBaseUrl = Objects.requireNonNull(adminBaseUrl, "adminBaseUrl");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public RuntimeContext prepare(PrepareRequest request) {
        return post("/context", request, RuntimeContext.class, CONTEXT_TIMEOUT);
    }

    @Override
    public CanonicalEvent append(AppendEventsRequest request) {
        return post("/events", request, CanonicalEvent.class, EVENT_TIMEOUT);
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        return post("/tool", request, ToolExecutionResult.class, TOOL_TIMEOUT);
    }

    private <T> T post(String path, Object body, Class<T> responseType, Duration timeout) {
        String credential = credentials.credential();
        if (credential.isBlank()) {
            throw new ProvisionedAgentRuntime.RuntimeException(
                    503, "Provisioned-agent control credential is unavailable");
        }
        try {
            byte[] payload = mapper.writeValueAsBytes(body);
            if (payload.length == 0 || payload.length > MAX_REQUEST_BYTES) {
                throw new ProvisionedAgentRuntime.RuntimeException(
                        413, "Provisioned-agent runtime request exceeded the bounded limit");
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(ChannelControlHeaders.TOKEN_HEADER, credential)
                    .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) {
                throw new ProvisionedAgentRuntime.RuntimeException(
                        502, "Provisioned-agent runtime response exceeded the bounded limit");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProvisionedAgentRuntime.RuntimeException(
                        response.statusCode(), "Provisioned-agent runtime request failed with HTTP "
                        + response.statusCode());
            }
            if (responseType == null) {
                return null;
            }
            return mapper.readValue(responseBody, responseType);
        } catch (ProvisionedAgentRuntime.RuntimeException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProvisionedAgentRuntime.RuntimeException(
                    503, "Provisioned-agent runtime request was interrupted", interrupted);
        } catch (IOException | IllegalArgumentException failure) {
            throw new ProvisionedAgentRuntime.RuntimeException(
                    503, "Provisioned-agent runtime is unavailable", failure);
        }
    }

    private URI endpoint(String path) {
        String base = adminBaseUrl.get();
        if (base == null || base.isBlank()) {
            throw new ProvisionedAgentRuntime.RuntimeException(
                    503, "Provisioned-agent admin endpoint is unavailable");
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        URI endpoint = URI.create(normalized + BASE_PATH + path);
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ProvisionedAgentRuntime.RuntimeException(
                    503, "Provisioned-agent admin endpoint must use HTTP or HTTPS");
        }
        if (endpoint.getUserInfo() != null
                || endpoint.getHost() == null
                || ("http".equalsIgnoreCase(scheme) && !isLoopback(endpoint.getHost()))) {
            throw new ProvisionedAgentRuntime.RuntimeException(
                    503, "Remote provisioned-agent admin endpoints must use HTTPS");
        }
        return endpoint;
    }

    private static boolean isLoopback(String host) {
        String normalized = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }
}
