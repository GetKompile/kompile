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

package ai.kompile.staging.catalog.remote;

import ai.kompile.staging.auth.AuthProviderChain;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for fetching and caching remote archive catalogs.
 * Supports multiple catalog URLs with fallback.
 */
@Service
public class RemoteCatalogService {

    private static final Logger log = LoggerFactory.getLogger(RemoteCatalogService.class);
    private static final int CONNECTION_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    private final AuthProviderChain authProviderChain;
    private final ObjectMapper objectMapper;

    // Cache of catalogs by URL
    private final Map<String, CachedCatalog> catalogCache = new ConcurrentHashMap<>();

    @Value("${kompile.archive.catalog.urls:}")
    private List<String> catalogUrls;

    @Value("${kompile.archive.catalog.refresh-interval:24h}")
    private String refreshInterval;

    // Default catalog URLs
    private static final List<String> DEFAULT_CATALOG_URLS = List.of(
            "https://github.com/GetKompile/kompile/releases/latest/download/catalog.json",
            "https://kompile.ai/archives/catalog.json"
    );

    @Autowired
    public RemoteCatalogService(AuthProviderChain authProviderChain) {
        this.authProviderChain = authProviderChain;
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Get the combined catalog from all configured sources.
     */
    public RemoteCatalog getCatalog() {
        return getCatalog(false);
    }

    /**
     * Get the combined catalog, optionally forcing a refresh.
     */
    public RemoteCatalog getCatalog(boolean forceRefresh) {
        List<String> urls = getEffectiveCatalogUrls();

        // Try each URL until one works
        for (String url : urls) {
            try {
                RemoteCatalog catalog = getCatalogFromUrl(url, forceRefresh);
                if (catalog != null && catalog.getArchiveCount() > 0) {
                    return catalog;
                }
            } catch (Exception e) {
                log.warn("Failed to fetch catalog from {}", url, e);
            }
        }

        // Return empty catalog if all fail
        log.warn("Failed to fetch catalog from all sources");
        return RemoteCatalog.empty();
    }

    /**
     * Get catalog from a specific URL.
     */
    public RemoteCatalog getCatalogFromUrl(String url, boolean forceRefresh) {
        // Check cache
        CachedCatalog cached = catalogCache.get(url);
        if (!forceRefresh && cached != null && !cached.isExpired(getRefreshDuration())) {
            log.debug("Using cached catalog from {}", url);
            return cached.getCatalog();
        }

        // Fetch fresh catalog
        try {
            log.info("Fetching catalog from {}", url);
            RemoteCatalog catalog = fetchCatalog(url);
            if (catalog != null) {
                catalog.setSourceUrl(url);
                catalogCache.put(url, new CachedCatalog(catalog, Instant.now()));
                return catalog;
            }
        } catch (Exception e) {
            log.error("Failed to fetch catalog from {}", url, e);
        }

        // Return cached even if expired, if available
        if (cached != null) {
            log.warn("Using expired cached catalog from {}", url);
            return cached.getCatalog();
        }

        return null;
    }

    /**
     * Find an archive across all catalogs.
     */
    public Optional<RemoteCatalogEntry> findArchive(String archiveId) {
        return getCatalog().findArchive(archiveId);
    }

    /**
     * Refresh all catalogs.
     */
    public void refreshAll() {
        for (String url : getEffectiveCatalogUrls()) {
            try {
                getCatalogFromUrl(url, true);
            } catch (Exception e) {
                log.warn("Failed to refresh catalog from {}", url);
            }
        }
    }

    /**
     * Clear the catalog cache.
     */
    public void clearCache() {
        catalogCache.clear();
    }

    /**
     * Get cache status.
     */
    public Map<String, CacheStatus> getCacheStatus() {
        Map<String, CacheStatus> status = new HashMap<>();
        Duration refreshDuration = getRefreshDuration();

        for (Map.Entry<String, CachedCatalog> entry : catalogCache.entrySet()) {
            CachedCatalog cached = entry.getValue();
            status.put(entry.getKey(), CacheStatus.builder()
                    .url(entry.getKey())
                    .fetchedAt(cached.getFetchedAt().toString())
                    .expired(cached.isExpired(refreshDuration))
                    .archiveCount(cached.getCatalog().getArchiveCount())
                    .build());
        }

        return status;
    }

    private RemoteCatalog fetchCatalog(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECTION_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Kompile-Catalog-Client/1.0");
        conn.setRequestProperty("Accept", "application/json");

        // Add auth headers
        Map<String, String> authHeaders = authProviderChain.getAuthHeaders(urlStr);
        for (Map.Entry<String, String> header : authHeaders.entrySet()) {
            conn.setRequestProperty(header.getKey(), header.getValue());
        }

        conn.setInstanceFollowRedirects(true);
        int responseCode = conn.getResponseCode();

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + responseCode + " for " + urlStr);
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            return objectMapper.readValue(in, RemoteCatalog.class);
        } finally {
            conn.disconnect();
        }
    }

    private List<String> getEffectiveCatalogUrls() {
        if (catalogUrls != null && !catalogUrls.isEmpty()) {
            // Filter out empty strings
            return catalogUrls.stream()
                    .filter(s -> s != null && !s.isEmpty())
                    .toList();
        }
        return DEFAULT_CATALOG_URLS;
    }

    private Duration getRefreshDuration() {
        try {
            // Parse duration like "24h", "1d", "30m"
            String interval = refreshInterval.trim().toLowerCase();
            if (interval.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(interval.substring(0, interval.length() - 1)));
            } else if (interval.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(interval.substring(0, interval.length() - 1)));
            } else if (interval.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(interval.substring(0, interval.length() - 1)));
            }
            return Duration.ofHours(24); // Default
        } catch (Exception e) {
            return Duration.ofHours(24);
        }
    }

    /**
     * Cached catalog with timestamp.
     */
    private static class CachedCatalog {
        private final RemoteCatalog catalog;
        private final Instant fetchedAt;

        CachedCatalog(RemoteCatalog catalog, Instant fetchedAt) {
            this.catalog = catalog;
            this.fetchedAt = fetchedAt;
        }

        RemoteCatalog getCatalog() {
            return catalog;
        }

        Instant getFetchedAt() {
            return fetchedAt;
        }

        boolean isExpired(Duration maxAge) {
            return Instant.now().isAfter(fetchedAt.plus(maxAge));
        }
    }

    /**
     * Cache status information.
     */
    @lombok.Data
    @lombok.Builder
    public static class CacheStatus {
        private String url;
        private String fetchedAt;
        private boolean expired;
        private int archiveCount;
    }
}
