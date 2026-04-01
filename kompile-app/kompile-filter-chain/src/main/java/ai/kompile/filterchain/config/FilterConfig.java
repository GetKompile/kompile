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

package ai.kompile.filterchain.config;

import ai.kompile.core.filter.FilterPhase;
import ai.kompile.core.filter.FilterType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for an individual filter in the chain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterConfig {

    /**
     * Unique identifier for this filter instance.
     */
    private String id;

    /**
     * Human-readable name for display.
     */
    private String name;

    /**
     * Description of what this filter does.
     */
    private String description;

    /**
     * Filter type: LOCAL, HTTP, or MCP.
     */
    @Builder.Default
    private FilterType type = FilterType.LOCAL;

    /**
     * Whether this filter is enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Execution priority (lower = earlier).
     */
    @Builder.Default
    private int priority = 100;

    /**
     * Phases where this filter applies.
     */
    @Builder.Default
    private Set<FilterPhase> phases = EnumSet.of(FilterPhase.PRE_RETRIEVAL);

    /**
     * For LOCAL type: The ID or bean name of the local filter.
     */
    private String localFilterId;

    /**
     * For HTTP/MCP type: Remote endpoint configuration.
     */
    private RemoteFilterConfig remoteConfig;

    /**
     * Filter-specific settings.
     */
    @Builder.Default
    private Map<String, Object> settings = new HashMap<>();

    /**
     * Categories this filter belongs to (for UI organization).
     */
    private String[] categories;

    /**
     * Create a local filter configuration.
     *
     * @param id The filter ID
     * @param name The filter name
     * @param localFilterId The local filter bean ID
     * @param phases The phases to apply
     * @param priority The priority
     * @return A new FilterConfig
     */
    public static FilterConfig local(String id, String name, String localFilterId,
                                      Set<FilterPhase> phases, int priority) {
        return FilterConfig.builder()
                .id(id)
                .name(name)
                .type(FilterType.LOCAL)
                .localFilterId(localFilterId)
                .phases(phases)
                .priority(priority)
                .enabled(true)
                .build();
    }

    /**
     * Create an HTTP filter configuration.
     *
     * @param id The filter ID
     * @param name The filter name
     * @param endpoint The HTTP endpoint URL
     * @param phases The phases to apply
     * @param priority The priority
     * @return A new FilterConfig
     */
    public static FilterConfig http(String id, String name, String endpoint,
                                     Set<FilterPhase> phases, int priority) {
        return FilterConfig.builder()
                .id(id)
                .name(name)
                .type(FilterType.HTTP)
                .remoteConfig(RemoteFilterConfig.builder()
                        .endpoint(endpoint)
                        .build())
                .phases(phases)
                .priority(priority)
                .enabled(true)
                .build();
    }

    /**
     * Create an MCP filter configuration.
     *
     * @param id The filter ID
     * @param name The filter name
     * @param mcpServerId The MCP server ID
     * @param toolName The MCP tool name
     * @param phases The phases to apply
     * @param priority The priority
     * @return A new FilterConfig
     */
    public static FilterConfig mcp(String id, String name, String mcpServerId, String toolName,
                                    Set<FilterPhase> phases, int priority) {
        return FilterConfig.builder()
                .id(id)
                .name(name)
                .type(FilterType.MCP)
                .remoteConfig(RemoteFilterConfig.builder()
                        .endpoint(mcpServerId)
                        .mcpToolName(toolName)
                        .build())
                .phases(phases)
                .priority(priority)
                .enabled(true)
                .build();
    }

    /**
     * Get the effective timeout for this filter.
     *
     * @param globalTimeout The global timeout to use as fallback
     * @return The timeout in milliseconds
     */
    public int getEffectiveTimeout(int globalTimeout) {
        if (remoteConfig != null && remoteConfig.getTimeoutMs() > 0) {
            return remoteConfig.getTimeoutMs();
        }
        return globalTimeout;
    }

    /**
     * Check if this filter applies to the given phase.
     */
    public boolean appliesTo(FilterPhase phase) {
        return phases != null && phases.contains(phase);
    }

    /**
     * Get a setting value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getSetting(String key, T defaultValue) {
        if (settings == null) {
            return defaultValue;
        }
        Object value = settings.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }
}
