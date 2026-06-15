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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for OAuth provider settings configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthProviderSettings {

    private String providerId;
    private String clientId;
    private String clientSecret;
    private String tenantId;  // For Microsoft Azure AD
    private String scopes;
    private boolean configured;
    private Long lastUpdated;

    /**
     * Create a sanitized copy that doesn't expose the full secret.
     */
    public OAuthProviderSettings sanitized() {
        return OAuthProviderSettings.builder()
                .providerId(providerId)
                .clientId(clientId)
                .clientSecret(clientSecret != null && !clientSecret.isEmpty() ? "********" : null)
                .tenantId(tenantId)
                .scopes(scopes)
                .configured(configured)
                .lastUpdated(lastUpdated)
                .build();
    }

    /**
     * Check if this provider has valid credentials configured.
     */
    public boolean hasValidCredentials() {
        return clientId != null && !clientId.isEmpty() &&
               clientSecret != null && !clientSecret.isEmpty();
    }
}
