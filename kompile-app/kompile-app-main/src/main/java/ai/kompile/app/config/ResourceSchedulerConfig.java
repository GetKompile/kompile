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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the {@link ai.kompile.app.services.scheduler.ResourceAwareJobScheduler}.
 * Persisted to {@code ~/.kompile/config/resource-scheduler-config.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceSchedulerConfig {

    @JsonProperty("enabled")
    private boolean enabled = true;

    @JsonProperty("globalQueueDepth")
    private int globalQueueDepth = 50;

    @JsonProperty("maxConcurrentByType")
    private Map<String, Integer> maxConcurrentByType = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("ingest", 4),
            Map.entry("vectorPopulation", 1),
            Map.entry("crawl", 4),
            Map.entry("unifiedCrawl", 1),
            Map.entry("training", 1),
            Map.entry("vlm", 1),
            Map.entry("modelInit", 1),
            Map.entry("llm", 1),
            Map.entry("llmServing", 1),
            Map.entry("embedding", 2)
    ));

    @JsonProperty("schedulingAlgorithm")
    private String schedulingAlgorithm = "PRIORITY";

    @JsonProperty("dispatchIntervalMs")
    private long dispatchIntervalMs = 500;

    @JsonProperty("queueTimeoutMs")
    private long queueTimeoutMs = 3_600_000;

    @JsonProperty("phaseAwareYieldEnabled")
    private boolean phaseAwareYieldEnabled = true;

    @JsonProperty("batchWindowMs")
    private long batchWindowMs = 2000;

    @JsonProperty("historyRetentionDays")
    private int historyRetentionDays = 30;

    @JsonProperty("maxHistoryEntries")
    private int maxHistoryEntries = 10_000;

    /**
     * External scheduler integration mode.
     * <ul>
     *   <li>{@code none} — use built-in scheduler only (default)</li>
     *   <li>{@code kubernetes} — delegate job execution to Kubernetes Jobs/CronJobs</li>
     *   <li>{@code webhook} — POST job submissions to an external webhook URL</li>
     * </ul>
     */
    @JsonProperty("externalSchedulerMode")
    private String externalSchedulerMode = "none";

    /** Kubernetes namespace for job pods (when externalSchedulerMode=kubernetes) */
    @JsonProperty("kubernetesNamespace")
    private String kubernetesNamespace = "kompile";

    /** Kubernetes service account for job pods */
    @JsonProperty("kubernetesServiceAccount")
    private String kubernetesServiceAccount = "kompile-worker";

    /** Container image for Kubernetes job pods */
    @JsonProperty("kubernetesJobImage")
    private String kubernetesJobImage = "konduitai/kompile:latest";

    /** Webhook URL for external scheduler (when externalSchedulerMode=webhook) */
    @JsonProperty("externalWebhookUrl")
    private String externalWebhookUrl = "";

    /** External scheduler API token for authentication */
    @JsonProperty("externalAuthToken")
    private String externalAuthToken = "";

    // --- Getters and Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getGlobalQueueDepth() { return globalQueueDepth; }
    public void setGlobalQueueDepth(int depth) { this.globalQueueDepth = depth; }

    public Map<String, Integer> getMaxConcurrentByType() { return maxConcurrentByType; }
    public void setMaxConcurrentByType(Map<String, Integer> m) { this.maxConcurrentByType = m; }

    public String getSchedulingAlgorithm() { return schedulingAlgorithm; }
    public void setSchedulingAlgorithm(String alg) { this.schedulingAlgorithm = alg; }

    public long getDispatchIntervalMs() { return dispatchIntervalMs; }
    public void setDispatchIntervalMs(long ms) { this.dispatchIntervalMs = ms; }

    public long getQueueTimeoutMs() { return queueTimeoutMs; }
    public void setQueueTimeoutMs(long ms) { this.queueTimeoutMs = ms; }

    public boolean isPhaseAwareYieldEnabled() { return phaseAwareYieldEnabled; }
    public void setPhaseAwareYieldEnabled(boolean b) { this.phaseAwareYieldEnabled = b; }

    public long getBatchWindowMs() { return batchWindowMs; }
    public void setBatchWindowMs(long ms) { this.batchWindowMs = ms; }

    public int getHistoryRetentionDays() { return historyRetentionDays; }
    public void setHistoryRetentionDays(int days) { this.historyRetentionDays = days; }

    public int getMaxHistoryEntries() { return maxHistoryEntries; }
    public void setMaxHistoryEntries(int max) { this.maxHistoryEntries = max; }

    public int getMaxConcurrentForType(String type) {
        return maxConcurrentByType.getOrDefault(type, 1);
    }

    public String getExternalSchedulerMode() { return externalSchedulerMode; }
    public void setExternalSchedulerMode(String mode) { this.externalSchedulerMode = mode; }

    public String getKubernetesNamespace() { return kubernetesNamespace; }
    public void setKubernetesNamespace(String ns) { this.kubernetesNamespace = ns; }

    public String getKubernetesServiceAccount() { return kubernetesServiceAccount; }
    public void setKubernetesServiceAccount(String sa) { this.kubernetesServiceAccount = sa; }

    public String getKubernetesJobImage() { return kubernetesJobImage; }
    public void setKubernetesJobImage(String image) { this.kubernetesJobImage = image; }

    public String getExternalWebhookUrl() { return externalWebhookUrl; }
    public void setExternalWebhookUrl(String url) { this.externalWebhookUrl = url; }

    public String getExternalAuthToken() { return externalAuthToken; }
    public void setExternalAuthToken(String token) { this.externalAuthToken = token; }

    public boolean isExternalSchedulerEnabled() {
        return externalSchedulerMode != null
                && !externalSchedulerMode.isBlank()
                && !"none".equalsIgnoreCase(externalSchedulerMode);
    }

    public static ResourceSchedulerConfig defaults() {
        return new ResourceSchedulerConfig();
    }
}
