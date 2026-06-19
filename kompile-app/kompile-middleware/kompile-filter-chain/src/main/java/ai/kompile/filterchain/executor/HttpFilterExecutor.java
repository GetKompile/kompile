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

package ai.kompile.filterchain.executor;

import ai.kompile.core.filter.*;
import ai.kompile.filterchain.config.FilterChainConfig;
import ai.kompile.filterchain.config.FilterConfig;
import ai.kompile.filterchain.config.RemoteFilterConfig;
import ai.kompile.filterchain.service.FilterChainConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Executor for HTTP remote filters.
 * Calls external HTTP endpoints with the filter context and interprets responses.
 */
@Component
public class HttpFilterExecutor implements FilterExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpFilterExecutor.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FilterChainConfigService configService;

    @Autowired
    public HttpFilterExecutor(FilterChainConfigService configService) {
        this.configService = configService;
        this.objectMapper = JsonUtils.standardMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public FilterResult execute(FilterConfig config, FilterContext context, FilterPhase phase) {
        RemoteFilterConfig remoteConfig = config.getRemoteConfig();
        if (remoteConfig == null || remoteConfig.getEndpoint() == null) {
            return FilterResult.terminateFatalError("HTTP filter missing endpoint configuration");
        }

        String endpoint = remoteConfig.getResolvedEndpoint();
        int timeout = config.getEffectiveTimeout(configService.getConfiguration().getGlobalTimeoutMs());
        int retries = remoteConfig.getRetries();
        int retryDelay = remoteConfig.getRetryDelayMs();

        log.debug("Executing HTTP filter '{}' to {}", config.getId(), endpoint);

        Exception lastException = null;

        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                if (attempt > 0) {
                    log.debug("Retry attempt {} for filter '{}'", attempt, config.getId());
                    Thread.sleep(retryDelay);
                }

                HttpRequest request = buildRequest(config, context, phase, endpoint, timeout);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                return handleResponse(config.getId(), response, context);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FilterResult.terminateFatalError("Filter execution interrupted");
            } catch (Exception e) {
                lastException = e;
                log.warn("HTTP filter '{}' attempt {} failed: {}", config.getId(), attempt + 1, e.getMessage());
            }
        }

        // All retries failed
        log.error("HTTP filter '{}' failed after {} attempts", config.getId(), retries + 1);
        return FilterResult.terminateFatalError(
                "HTTP filter failed: " + (lastException != null ? lastException.getMessage() : "Unknown error"));
    }

    @Override
    public boolean supports(FilterConfig config) {
        return config != null && config.getType() == FilterType.HTTP;
    }

    /**
     * Build the HTTP request.
     */
    private HttpRequest buildRequest(FilterConfig config, FilterContext context,
                                      FilterPhase phase, String endpoint, int timeout) throws Exception {

        RemoteFilterConfig remoteConfig = config.getRemoteConfig();

        // Build request body
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("requestId", context.getRequestId());
        requestBody.put("phase", phase.name());
        requestBody.put("filterId", config.getId());

        // Add context data
        ObjectNode contextNode = objectMapper.createObjectNode();
        contextNode.put("conversationId", context.getConversationId());
        contextNode.put("userMessage", context.getUserMessage());
        contextNode.put("originalQuery", context.getOriginalQuery());
        contextNode.put("rewrittenQuery", context.getRewrittenQuery());
        contextNode.put("formattedContext", context.getFormattedContext());
        contextNode.put("llmResponse", context.getLlmResponse());

        // Add metadata
        if (context.getRequestMetadata() != null && !context.getRequestMetadata().isEmpty()) {
            contextNode.set("metadata", objectMapper.valueToTree(context.getRequestMetadata()));
        }

        requestBody.set("context", contextNode);

        // Add filter settings
        if (config.getSettings() != null && !config.getSettings().isEmpty()) {
            requestBody.set("settings", objectMapper.valueToTree(config.getSettings()));
        }

        String body = objectMapper.writeValueAsString(requestBody);

        // Build request
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeout))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Filter-Id", config.getId())
                .header("X-Filter-Phase", phase.name());

        // Add custom headers
        Map<String, String> headers = remoteConfig.getResolvedHeaders();
        if (headers != null) {
            headers.forEach(builder::header);
        }

        // Add authentication
        addAuthentication(builder, remoteConfig);

        // Set method and body
        String method = remoteConfig.getHttpMethod();
        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else if ("POST".equalsIgnoreCase(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else if ("PUT".equalsIgnoreCase(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }

        return builder.build();
    }

    /**
     * Add authentication to the request.
     */
    private void addAuthentication(HttpRequest.Builder builder, RemoteFilterConfig config) {
        RemoteFilterConfig.AuthConfig auth = config.getAuthConfig();
        if (auth == null || auth.getType() == RemoteFilterConfig.AuthConfig.AuthType.NONE) {
            return;
        }

        switch (auth.getType()) {
            case API_KEY -> {
                String apiKey = RemoteFilterConfig.resolveEnvVar(auth.getApiKey());
                if (apiKey != null) {
                    builder.header(auth.getApiKeyHeader(), apiKey);
                }
            }
            case BEARER -> {
                String token = RemoteFilterConfig.resolveEnvVar(auth.getBearerToken());
                if (token != null) {
                    builder.header("Authorization", "Bearer " + token);
                }
            }
            case BASIC -> {
                String username = auth.getUsername();
                String password = RemoteFilterConfig.resolveEnvVar(auth.getPassword());
                if (username != null && password != null) {
                    String credentials = Base64.getEncoder().encodeToString(
                            (username + ":" + password).getBytes());
                    builder.header("Authorization", "Basic " + credentials);
                }
            }
            case OAUTH2 -> {
                // OAuth2 would require token fetching, not implemented here
                log.warn("OAuth2 authentication not yet implemented for HTTP filters");
            }
        }
    }

    /**
     * Handle the HTTP response.
     */
    private FilterResult handleResponse(String filterId, HttpResponse<String> response, FilterContext context) {
        int statusCode = response.statusCode();
        String body = response.body();

        log.debug("HTTP filter '{}' responded with status {}", filterId, statusCode);

        // Parse response body
        JsonNode responseJson = null;
        if (body != null && !body.isBlank()) {
            try {
                responseJson = objectMapper.readTree(body);
            } catch (Exception e) {
                log.warn("Failed to parse response JSON from filter '{}': {}", filterId, e.getMessage());
            }
        }

        // Handle by status code family
        int statusFamily = statusCode / 100;

        return switch (statusFamily) {
            case 2 -> handleSuccessResponse(filterId, responseJson, context);
            case 4 -> handleUserError(filterId, statusCode, responseJson, body);
            case 5 -> handleFatalError(filterId, statusCode, responseJson, body);
            default -> FilterResult.terminateFatalError(
                    "Unexpected HTTP status " + statusCode + " from filter '" + filterId + "'");
        };
    }

    /**
     * Handle 2xx success response.
     */
    private FilterResult handleSuccessResponse(String filterId, JsonNode response, FilterContext context) {
        if (response == null) {
            return FilterResult.continueWith(context);
        }

        List<FilterTraceEntry> traces = new ArrayList<>();

        // Check for action field
        String action = getStringField(response, "action", "CONTINUE");
        FilterAction filterAction = parseAction(action);

        // Handle termination actions
        if (filterAction == FilterAction.TERMINATE_SUCCESS) {
            String message = getStringField(response, "message", null);
            return FilterResult.terminateSuccess(message, null);
        }

        // Apply mutations from response
        JsonNode mutations = response.get("mutations");
        if (mutations != null && mutations.isArray()) {
            for (JsonNode mutation : mutations) {
                String field = getStringField(mutation, "field", null);
                JsonNode value = mutation.get("value");

                if (field != null && value != null) {
                    applyMutation(context, filterId, field, value);
                    traces.add(FilterTraceEntry.info(filterId,
                            "Applied mutation: " + field));
                }
            }
        }

        // Collect traces from response
        JsonNode responseTraces = response.get("traces");
        if (responseTraces != null && responseTraces.isArray()) {
            for (JsonNode trace : responseTraces) {
                String level = getStringField(trace, "level", "INFO");
                String message = getStringField(trace, "message", "");
                traces.add(new FilterTraceEntry(
                        filterId, null,
                        FilterTraceEntry.Level.valueOf(level.toUpperCase()),
                        message, java.time.Instant.now(), null, null));
            }
        }

        return FilterResult.continueWith(context).withTraces(traces);
    }

    /**
     * Handle 4xx user error response.
     */
    private FilterResult handleUserError(String filterId, int statusCode, JsonNode response, String body) {
        String message = "Request blocked by filter '" + filterId + "'";

        if (response != null) {
            message = getStringField(response, "message", message);
        } else if (body != null && !body.isBlank()) {
            message = body;
        }

        return FilterResult.terminateUserError(message, statusCode);
    }

    /**
     * Handle 5xx fatal error response.
     */
    private FilterResult handleFatalError(String filterId, int statusCode, JsonNode response, String body) {
        String message = "Filter '" + filterId + "' returned error " + statusCode;

        if (response != null) {
            message = getStringField(response, "message", message);
        } else if (body != null && !body.isBlank()) {
            message = body;
        }

        return FilterResult.terminateFatalError(message, statusCode);
    }

    /**
     * Apply a mutation to the context.
     */
    private void applyMutation(FilterContext context, String filterId, String field, JsonNode value) {
        try {
            switch (field) {
                case "rewrittenQuery" -> {
                    String oldValue = context.getRewrittenQuery();
                    String newValue = value.asText();
                    context.setRewrittenQuery(newValue);
                    context.recordMutation(filterId, field, oldValue, newValue);
                }
                case "formattedContext" -> {
                    String oldValue = context.getFormattedContext();
                    String newValue = value.asText();
                    context.setFormattedContext(newValue);
                    context.recordMutation(filterId, field, oldValue, newValue);
                }
                case "llmResponse" -> {
                    String oldValue = context.getLlmResponse();
                    String newValue = value.asText();
                    context.setLlmResponse(newValue);
                    context.recordMutation(filterId, field, oldValue, newValue);
                }
                case "systemPrompt" -> {
                    String oldValue = context.getSystemPrompt();
                    String newValue = value.asText();
                    context.setSystemPrompt(newValue);
                    context.recordMutation(filterId, field, oldValue, newValue);
                }
                default -> log.debug("Unknown mutation field: {}", field);
            }
        } catch (Exception e) {
            log.warn("Failed to apply mutation for field '{}': {}", field, e.getMessage());
        }
    }

    /**
     * Parse action string to FilterAction.
     */
    private FilterAction parseAction(String action) {
        if (action == null) {
            return FilterAction.CONTINUE;
        }
        try {
            return FilterAction.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            return FilterAction.CONTINUE;
        }
    }

    /**
     * Get a string field from JSON.
     */
    private String getStringField(JsonNode node, String field, String defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode fieldNode = node.get(field);
        if (fieldNode == null || fieldNode.isNull()) {
            return defaultValue;
        }
        return fieldNode.asText(defaultValue);
    }
}
