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

package ai.kompile.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Information about an OAuth provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthProviderInfo {

    /**
     * Provider identifier.
     */
    private String providerId;

    /**
     * Display name for the provider.
     */
    private String displayName;

    /**
     * Description of what this provider connects to.
     */
    private String description;

    /**
     * Material icon name.
     */
    private String icon;

    /**
     * Brand color for the provider.
     */
    private String color;

    /**
     * Whether this provider is configured (has client ID/secret).
     */
    private boolean configured;

    /**
     * Message if not configured.
     */
    private String notConfiguredMessage;

    /**
     * Required OAuth scopes.
     */
    private List<String> requiredScopes;

    /**
     * Related source providers that use this OAuth connection.
     */
    private List<String> relatedSources;
}
