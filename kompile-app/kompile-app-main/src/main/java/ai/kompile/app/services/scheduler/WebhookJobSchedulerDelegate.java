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

package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Delegates job execution by POSTing to an external webhook URL.
 *
 * <p>This delegate sends job submission requests to an arbitrary HTTP endpoint,
 * allowing integration with custom external schedulers, orchestrators, or
 * cloud-native job management systems.</p>
 *
 * <h3>Webhook Contract</h3>
 * <ul>
 *   <li><b>Submit</b>: {@code POST <webhookUrl>/submit} with JSON body containing
 *       jobId, jobType, description, resource requirements, and metadata</li>
 *   <li><b>Cancel</b>: {@code POST <webhookUrl>/cancel} with JSON body containing
 *       jobId and externalRef</li>
 *   <li><b>Status</b>: {@code GET <webhookUrl>/status/<externalRef>}</li>
 *   <li><b>Callback</b>: External system calls back to
 *       {@code POST /api/scheduler/callback} with {jobId, success, message}</li>
 * </ul>
 *
 * <p>Authentication is via a Bearer token in the {@code Authorization} header,
 * configured via {@link ResourceSchedulerConfig#getExternalAuthToken()}.</p>
 *
 * <p>Requires {@code externalSchedulerMode=webhook} and a non-empty
 * {@code externalWebhookUrl} in the scheduler config.</p>
 */
@Component
public class WebhookJobSchedulerDelegate implements ExternalJobSchedulerDelegate {

    private static final Logger log = LoggerFactory.getLogger(WebhookJobSchedulerDelegate.class);

    private final ResourceSchedulerConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookJobSchedulerDelegate(ResourceSchedulerConfigService configService,
                                        ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getMode() {
        return "webhook";
    }

    @Override
    public CompletableFuture<ExternalJobRef> submitJob(
            String jobId, String jobType, String description,
            JobResourceProfile resourceProfile,
            Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceSchedulerConfig config = configService.getConfiguration();
            String webhookUrl = config.getExternalWebhookUrl();

            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("jobId", jobId);
                payload.put("jobType", jobType);
                payload.put("description", description);

                Map<String, Object> resources = new LinkedHashMap<>();
                resources.put("serviceType", resourceProfile.serviceType());
                resources.put("requiresGpu", resourceProfile.requiresGpu());
                resources.put("peakGpuMemoryBytes", resourceProfile.peakGpuMemoryBytes());
                resources.put("estimatedHeapBytes", resourceProfile.estimatedHeapBytes());
                resources.put("maxConcurrent", resourceProfile.maxConcurrent());
                payload.put("resources", resources);

                if (metadata != null && !metadata.isEmpty()) {
                    payload.put("metadata", metadata);
                }

                String body = objectMapper.writeValueAsString(payload);
                String submitUrl = normalizeUrl(webhookUrl) + "/submit";

                log.info("Submitting job '{}' ({}) to webhook: {}", jobId, jobType, submitUrl);

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(submitUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body));

                addAuthHeader(requestBuilder, config);

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    // Parse external ID from response if available
                    String externalId = jobId; // default to our jobId
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseBody = objectMapper.readValue(
                                response.body(), Map.class);
                        if (responseBody.containsKey("externalId")) {
                            externalId = String.valueOf(responseBody.get("externalId"));
                        }
                    } catch (Exception e) {
                        log.debug("Could not parse webhook response body: {}", e.getMessage());
                    }

                    log.info("Webhook accepted job '{}': externalId='{}', httpStatus={}",
                            jobId, externalId, response.statusCode());
                    return new ExternalJobRef(externalId, "SUBMITTED", response.body());
                } else {
                    log.error("Webhook rejected job '{}': httpStatus={}, body={}",
                            jobId, response.statusCode(), response.body());
                    return new ExternalJobRef(jobId, "FAILED",
                            "HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                log.error("Error submitting job '{}' to webhook: {}", jobId, e.getMessage(), e);
                return new ExternalJobRef(jobId, "FAILED", e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> cancelJob(String jobId, String externalRef) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceSchedulerConfig config = configService.getConfiguration();
            String webhookUrl = config.getExternalWebhookUrl();

            try {
                Map<String, Object> payload = Map.of(
                        "jobId", jobId,
                        "externalRef", externalRef
                );

                String body = objectMapper.writeValueAsString(payload);
                String cancelUrl = normalizeUrl(webhookUrl) + "/cancel";

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(cancelUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body));

                addAuthHeader(requestBuilder, config);

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
                log.info("Cancel webhook for job '{}' (externalRef='{}'): httpStatus={}, success={}",
                        jobId, externalRef, response.statusCode(), success);
                return success;
            } catch (Exception e) {
                log.error("Error cancelling job '{}' via webhook: {}", jobId, e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceSchedulerConfig config = configService.getConfiguration();
            String webhookUrl = config.getExternalWebhookUrl();

            try {
                String statusUrl = normalizeUrl(webhookUrl) + "/status/" + externalRef;

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(statusUrl))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET();

                addAuthHeader(requestBuilder, config);

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseBody = objectMapper.readValue(
                                response.body(), Map.class);
                        String status = String.valueOf(
                                responseBody.getOrDefault("status", "UNKNOWN"));
                        String message = String.valueOf(
                                responseBody.getOrDefault("message", ""));
                        return new ExternalJobStatus(externalRef, status, message, responseBody);
                    } catch (Exception e) {
                        return new ExternalJobStatus(externalRef, "UNKNOWN",
                                "Could not parse response", Map.of());
                    }
                } else {
                    return new ExternalJobStatus(externalRef, "UNKNOWN",
                            "HTTP " + response.statusCode(), Map.of());
                }
            } catch (Exception e) {
                log.error("Error checking job status '{}' via webhook: {}",
                        externalRef, e.getMessage());
                return new ExternalJobStatus(externalRef, "UNKNOWN", e.getMessage(), Map.of());
            }
        });
    }

    @Override
    public boolean isAvailable() {
        ResourceSchedulerConfig config = configService.getConfiguration();
        if (!"webhook".equalsIgnoreCase(config.getExternalSchedulerMode())) {
            return false;
        }
        String webhookUrl = config.getExternalWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Webhook delegate not available: no webhook URL configured");
            return false;
        }

        // Quick health check
        try {
            String healthUrl = normalizeUrl(webhookUrl) + "/health";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            addAuthHeader(requestBuilder, config);

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.debug("Webhook health check failed for '{}': {}",
                    webhookUrl, e.getMessage());
            return false;
        }
    }

    private void addAuthHeader(HttpRequest.Builder builder, ResourceSchedulerConfig config) {
        String token = config.getExternalAuthToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    private String normalizeUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
