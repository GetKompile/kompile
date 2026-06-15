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

package ai.kompile.cli.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manifest embedded in every config archive zip.
 * Describes what's inside so consumers can preview before importing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigArchiveManifest {

    @JsonProperty
    private String version = "1.0";

    @JsonProperty
    private String createdAt;

    @JsonProperty
    private String hostname;

    @JsonProperty
    private String description;

    /** Kompile config files (from ~/.kompile/config/ and ~/.kompile/*.json) */
    @JsonProperty
    private List<String> kompileConfigs = new ArrayList<>();

    /** Chat provider config files included, keyed by provider name */
    @JsonProperty
    private Map<String, List<String>> chatProviderConfigs = new LinkedHashMap<>();

    /** System prompt files included */
    @JsonProperty
    private List<String> systemPrompts = new ArrayList<>();

    public ConfigArchiveManifest() {
        this.createdAt = Instant.now().toString();
        try {
            this.hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            this.hostname = "unknown";
        }
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getKompileConfigs() { return kompileConfigs; }
    public void setKompileConfigs(List<String> kompileConfigs) { this.kompileConfigs = kompileConfigs; }

    public Map<String, List<String>> getChatProviderConfigs() { return chatProviderConfigs; }
    public void setChatProviderConfigs(Map<String, List<String>> chatProviderConfigs) { this.chatProviderConfigs = chatProviderConfigs; }

    public List<String> getSystemPrompts() { return systemPrompts; }
    public void setSystemPrompts(List<String> systemPrompts) { this.systemPrompts = systemPrompts; }
}
