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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chains multiple authentication providers and selects the best one for each URL.
 * Providers are selected based on URL pattern matching and priority.
 */
@Component
public class AuthProviderChain {

    private static final Logger log = LoggerFactory.getLogger(AuthProviderChain.class);

    private final List<ArchiveAuthProvider> providers;

    @Autowired
    public AuthProviderChain(List<ArchiveAuthProvider> providers) {
        // Sort by priority (descending)
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(ArchiveAuthProvider::getPriority).reversed())
                .toList();

        log.info("Initialized auth provider chain with {} providers: {}",
                providers.size(),
                providers.stream().map(ArchiveAuthProvider::getName).toList());
    }

    /**
     * Find the best provider for a URL.
     */
    public Optional<ArchiveAuthProvider> findProvider(String url) {
        return providers.stream()
                .filter(p -> p.supportsUrl(url) && p.isConfigured())
                .findFirst();
    }

    /**
     * Get authentication headers for a URL.
     * Returns headers from the first matching configured provider.
     */
    public Map<String, String> getAuthHeaders(String url) {
        return findProvider(url)
                .map(p -> {
                    log.debug("Using auth provider '{}' for URL: {}", p.getName(), url);
                    return p.getAuthHeaders(url);
                })
                .orElseGet(() -> {
                    log.debug("No auth provider configured for URL: {}", url);
                    return new HashMap<>();
                });
    }

    /**
     * Get authentication headers for a URL with HTTP method context.
     */
    public Map<String, String> getAuthHeaders(String url, String method, Map<String, String> headers) {
        return findProvider(url)
                .map(p -> p.getAuthHeaders(url, method, headers))
                .orElse(new HashMap<>());
    }

    /**
     * Check if any provider is configured for the URL.
     */
    public boolean hasAuthForUrl(String url) {
        return findProvider(url).isPresent();
    }

    /**
     * Get all configured providers.
     */
    public List<ArchiveAuthProvider> getConfiguredProviders() {
        return providers.stream()
                .filter(ArchiveAuthProvider::isConfigured)
                .toList();
    }

    /**
     * Get provider by name.
     */
    public Optional<ArchiveAuthProvider> getProvider(String name) {
        return providers.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst();
    }
}
