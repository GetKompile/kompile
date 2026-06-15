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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Authentication provider using HTTP Basic Authentication.
 * Suitable for private HTTP servers with username/password auth.
 */
@Component
public class BasicAuthProvider implements ArchiveAuthProvider {

    @Value("${kompile.archive.auth.basic.username:}")
    private String username;

    @Value("${kompile.archive.auth.basic.password:}")
    private String password;

    @Override
    public String getName() {
        return "basic";
    }

    @Override
    public int getPriority() {
        return 50; // Medium priority - fallback for servers without Bearer support
    }

    @Override
    public boolean supportsUrl(String url) {
        // Basic auth works with any HTTP/HTTPS URL
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    @Override
    public boolean isConfigured() {
        return username != null && !username.isEmpty() &&
               password != null && !password.isEmpty();
    }

    @Override
    public Map<String, String> getAuthHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        if (isConfigured()) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + encoded);
        }
        return headers;
    }

    /**
     * Set credentials programmatically.
     */
    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Create a provider with specific credentials.
     */
    public static BasicAuthProvider withCredentials(String username, String password) {
        BasicAuthProvider provider = new BasicAuthProvider();
        provider.setCredentials(username, password);
        return provider;
    }
}
