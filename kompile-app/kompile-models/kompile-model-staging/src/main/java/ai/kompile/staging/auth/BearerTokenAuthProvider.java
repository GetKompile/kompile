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

package ai.kompile.staging.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication provider using Bearer tokens.
 * Suitable for GitHub, GitLab, and other APIs that accept Bearer tokens.
 */
@Component
public class BearerTokenAuthProvider implements ArchiveAuthProvider {

    @Value("${kompile.archive.auth.bearer-token:}")
    private String bearerToken;

    @Override
    public String getName() {
        return "bearer";
    }

    @Override
    public int getPriority() {
        return 100; // High priority - preferred for GitHub etc.
    }

    @Override
    public boolean supportsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // Bearer tokens work well with these hosts
        return lower.contains("github.com") ||
               lower.contains("gitlab.com") ||
               lower.contains("huggingface.co") ||
               lower.contains("kompile.ai");
    }

    @Override
    public boolean isConfigured() {
        return bearerToken != null && !bearerToken.isEmpty();
    }

    @Override
    public Map<String, String> getAuthHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        if (isConfigured()) {
            headers.put("Authorization", "Bearer " + bearerToken);
        }
        return headers;
    }

    /**
     * Set the bearer token programmatically.
     */
    public void setToken(String token) {
        this.bearerToken = token;
    }

    /**
     * Create a provider with a specific token.
     */
    public static BearerTokenAuthProvider withToken(String token) {
        BearerTokenAuthProvider provider = new BearerTokenAuthProvider();
        provider.setToken(token);
        return provider;
    }
}
