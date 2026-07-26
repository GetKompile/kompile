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
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.http.SafeHttpTransport;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
    private final StagingAssetLimits limits;
    private final SafeHttpTransport httpTransport;

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

    public RemoteCatalogService(AuthProviderChain authProviderChain) {
        this(
                authProviderChain,
                new StagingAssetLimits(),
                new SafeHttpTransport());
    }

    @Autowired
    public RemoteCatalogService(
            AuthProviderChain authProviderChain,
            StagingAssetLimits limits,
            SafeHttpTransport httpTransport) {
        this.authProviderChain = authProviderChain;
        this.objectMapper = JsonUtils.standardMapper();
        this.limits = limits;
        this.httpTransport = httpTransport;
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
                log.warn(
                        "Failed to fetch catalog from {}",
                        safeUrlForDiagnostics(url),
                        e);
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
        String safeUrl = safeUrlForDiagnostics(url);

        // Check cache
        CachedCatalog cached = catalogCache.get(url);
        if (!forceRefresh && cached != null && !cached.isExpired(getRefreshDuration())) {
            log.debug("Using cached catalog from {}", safeUrl);
            return cached.getCatalog();
        }

        // Fetch fresh catalog
        try {
            log.info("Fetching catalog from {}", safeUrl);
            RemoteCatalog catalog = fetchCatalog(url);
            if (catalog != null) {
                catalog.setSourceUrl(safeUrl);
                catalogCache.put(url, new CachedCatalog(catalog, Instant.now()));
                return catalog;
            }
        } catch (Exception e) {
            log.error("Failed to fetch catalog from {}", safeUrl, e);
        }

        // Return cached even if expired, if available
        if (cached != null) {
            log.warn("Using expired cached catalog from {}", safeUrl);
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
                log.warn(
                        "Failed to refresh catalog from {}",
                        safeUrlForDiagnostics(url));
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
            String safeUrl = safeUrlForDiagnostics(entry.getKey());
            status.put(safeUrl, CacheStatus.builder()
                    .url(safeUrl)
                    .fetchedAt(cached.getFetchedAt().toString())
                    .expired(cached.isExpired(refreshDuration))
                    .archiveCount(cached.getCatalog().getArchiveCount())
                    .build());
        }

        return status;
    }

    private RemoteCatalog fetchCatalog(String urlStr) throws IOException {
        URI uri;
        try {
            uri = URI.create(urlStr);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid remote catalog URI");
        }

        Map<String, String> headers =
                new HashMap<>(authProviderChain.getAuthHeaders(uri.toString()));
        headers.put("User-Agent", "Kompile-Catalog-Client/1.0");
        headers.put("Accept", "application/json");

        try (SafeHttpTransport.Response response = httpTransport.execute(
                uri,
                "GET",
                headers,
                CONNECTION_TIMEOUT,
                READ_TIMEOUT,
                limits.getMaxRedirects())) {
            int responseCode = response.statusCode();
            if (responseCode != 200) {
                throw new IOException("HTTP " + responseCode + " for "
                        + SafeHttpTransport.safeUriForDiagnostics(response.uri()));
            }

            long maximumBytes = limits.maxBytesFor("remote_catalog");
            long contentLength = response.contentLength();
            if (contentLength > maximumBytes) {
                throw new IOException(
                        "Remote catalog exceeds its configured byte limit");
            }

            try (InputStream in = new BufferedInputStream(response.body());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long bytesRead = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    try {
                        bytesRead = Math.addExact(bytesRead, count);
                    } catch (ArithmeticException overflow) {
                        throw new IOException(
                                "Remote catalog byte count overflowed", overflow);
                    }
                    if (bytesRead > maximumBytes) {
                        throw new IOException(
                                "Remote catalog exceeded its configured byte limit");
                    }
                    output.write(buffer, 0, count);
                }
                return objectMapper.readValue(
                        output.toByteArray(), RemoteCatalog.class);
            }
        }
    }

    private static String safeUrlForDiagnostics(String value) {
        try {
            return SafeHttpTransport.safeUriForDiagnostics(URI.create(value));
        } catch (RuntimeException invalid) {
            return "<invalid-remote-uri>";
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
