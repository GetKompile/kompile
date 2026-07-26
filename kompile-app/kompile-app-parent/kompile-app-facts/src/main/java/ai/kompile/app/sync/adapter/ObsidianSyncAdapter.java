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

package ai.kompile.app.sync.adapter;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import ai.kompile.app.sync.convert.ObsidianFrontmatterConverter;
import ai.kompile.app.sync.convert.ObsidianFrontmatterConverter.ParsedObsidianNote;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.dto.SyncConnectionTestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;

/**
 * Sync adapter for Obsidian.md vaults via the Local REST API plugin.
 * https://github.com/coddingtonbear/obsidian-local-rest-api
 *
 * The plugin runs on https://localhost:27124 with a self-signed certificate.
 * Authentication is via Bearer token.
 *
 * Enabled at runtime via NoteSyncConfigService (JSON config).
 */
@Service
public class ObsidianSyncAdapter implements SyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(ObsidianSyncAdapter.class);

    @Autowired
    private NoteSyncConfigService configService;

    @Autowired
    private ObsidianFrontmatterConverter frontmatterConverter;

    @Autowired
    private LocalMarkdownFileStore localMarkdownFileStore;

    @Autowired(required = false)
    private ai.kompile.oauth.service.TokenEncryptionService tokenEncryptionService;

    // Use a RestTemplate that trusts self-signed certs (Obsidian plugin uses one)
    private final RestTemplate restTemplate;

    public ObsidianSyncAdapter() {
        this.restTemplate = createTrustingRestTemplate();
    }

    @Override
    public String adapterId() {
        return "obsidian";
    }

    @Override
    public List<ExternalNoteSnapshot> fetchChangedSince(NoteSyncConnection conn, Instant since) {
        checkEnabled();
        if (useLocalVault(conn)) {
            return localMarkdownFileStore.fetchChangedSince(conn, since);
        }

        List<ExternalNoteSnapshot> results = new ArrayList<>();
        for (String filePath : listRemoteMarkdownFiles(conn)) {
            String content = readVaultFile(conn, filePath);
            if (content == null) {
                throw new IllegalStateException("Obsidian returned no content for " + filePath);
            }

            ParsedObsidianNote parsed = frontmatterConverter.fromObsidianFormat(content);
            // Older vault files may lack portable frontmatter timestamps. Fetching them again is
            // safe because the engine compares a canonical checksum before applying an update.
            Instant fileUpdated = parsed.updatedAt() != null ? parsed.updatedAt() : Instant.now();
            if (fileUpdated.isAfter(since)) {
                results.add(new ExternalNoteSnapshot(
                        filePath,
                        parsed.title() != null ? parsed.title() : fileNameToTitle(filePath),
                        parsed.body(),
                        parsed.tags(),
                        fileUpdated
                ));
            }
        }

        log.info("Obsidian fetch: found {} changed files since {}", results.size(), since);
        return results;
    }

    @Override
    public Optional<Set<String>> listExternalIds(NoteSyncConnection conn) {
        checkEnabled();
        if (useLocalVault(conn)) {
            return Optional.of(localMarkdownFileStore.listExternalIds(conn));
        }
        return Optional.of(listRemoteMarkdownFiles(conn));
    }

    @Override
    public Optional<ExternalNoteSnapshot> fetchById(NoteSyncConnection conn, String externalId) {
        checkEnabled();
        if (useLocalVault(conn)) {
            return localMarkdownFileStore.fetchById(conn, externalId);
        }
        try {
            String content = readVaultFile(conn, externalId);
            if (content == null) return Optional.empty();

            ParsedObsidianNote parsed = frontmatterConverter.fromObsidianFormat(content);
            return Optional.of(new ExternalNoteSnapshot(
                    externalId,
                    parsed.title() != null ? parsed.title() : fileNameToTitle(externalId),
                    parsed.body(),
                    parsed.tags(),
                    parsed.updatedAt() != null ? parsed.updatedAt() : Instant.now()
            ));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public String createExternal(NoteSyncConnection conn, Note note, String markdownContent) {
        checkEnabled();
        if (useLocalVault(conn)) {
            return localMarkdownFileStore.createExternal(conn, note, markdownContent);
        }
        String fileName = frontmatterConverter.sanitizeFileName(note.getTitle()) + ".md";
        String scope = conn.getExternalScope();
        String filePath = (scope != null && !scope.isBlank()) ? scope + "/" + fileName : fileName;

        String ofmContent = frontmatterConverter.toObsidianFormat(note, markdownContent);
        writeVaultFile(conn, filePath, ofmContent);

        log.info("Created Obsidian file '{}' for note '{}'", filePath, note.getTitle());
        return filePath;
    }

    @Override
    public void updateExternal(NoteSyncConnection conn, String externalId, Note note, String markdownContent) {
        checkEnabled();
        if (useLocalVault(conn)) {
            localMarkdownFileStore.updateExternal(conn, externalId, note, markdownContent);
            return;
        }
        String ofmContent = frontmatterConverter.toObsidianFormat(note, markdownContent);
        writeVaultFile(conn, externalId, ofmContent);
        log.info("Updated Obsidian file '{}' for note '{}'", externalId, note.getTitle());
    }

    @Override
    public void deleteExternal(NoteSyncConnection conn, String externalId) {
        checkEnabled();
        if (useLocalVault(conn)) {
            localMarkdownFileStore.deleteExternal(conn, externalId);
            return;
        }
        String url = getBaseUrl(conn) + "/vault/" + encodePath(externalId);
        HttpEntity<Void> entity = new HttpEntity<>(obsidianHeaders(conn));
        restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        log.info("Deleted Obsidian file '{}'", externalId);
    }

    @Override
    public SyncConnectionTestResponse testConnection(NoteSyncConnection conn) {
        if (useLocalVault(conn)) {
            Path root = localMarkdownFileStore.requireExistingRoot(conn);
            if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
                return SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(),
                        "Vault path is not readable and writable: " + root);
            }
            return SyncConnectionTestResponse.success(conn.getId(), conn.getAuthMode(),
                    "Local Obsidian vault path is readable and writable.");
        }
        try {
            String url = getBaseUrl(conn) + "/search/simple/?query=.md";
            HttpHeaders headers = obsidianHeaders(conn);
            headers.setContentType(MediaType.TEXT_PLAIN);
            HttpEntity<String> entity = new HttpEntity<>(conn.getExternalScope() == null ? "" : conn.getExternalScope(), headers);
            restTemplate.exchange(url, HttpMethod.POST, entity, List.class);
            return SyncConnectionTestResponse.success(conn.getId(), conn.getAuthMode(),
                    "Obsidian Local REST API token is valid.");
        } catch (Exception e) {
            return SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(),
                    "Obsidian REST API test failed: " + e.getMessage());
        }
    }

    // ── Obsidian REST API Helpers ──────────────────────────────────────

    private boolean useLocalVault(NoteSyncConnection conn) {
        return conn.getObsidianApiUrl() == null || conn.getObsidianApiUrl().isBlank();
    }

    private String readVaultFile(NoteSyncConnection conn, String filePath) {
        String url = getBaseUrl(conn) + "/vault/" + encodePath(filePath);
        HttpEntity<Void> entity = new HttpEntity<>(obsidianHeaders(conn));
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return resp.getBody();
    }

    private void writeVaultFile(NoteSyncConnection conn, String filePath, String content) {
        String url = getBaseUrl(conn) + "/vault/" + encodePath(filePath);
        HttpHeaders headers = obsidianHeaders(conn);
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> entity = new HttpEntity<>(content, headers);
        restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
    }

    @SuppressWarnings("unchecked")
    private Set<String> listRemoteMarkdownFiles(NoteSyncConnection conn) {
        String configuredScope = conn.getExternalScope();
        String rootScope = configuredScope == null ? "" : configuredScope
                .replace('\\', '/')
                .replaceAll("^/+|/+$", "");

        Set<String> markdownFiles = new LinkedHashSet<>();
        Deque<String> directories = new ArrayDeque<>();
        directories.add(rootScope);

        while (!directories.isEmpty()) {
            String directory = directories.removeFirst();
            String encodedDirectory = directory.isEmpty() ? "" : encodePath(directory) + "/";
            String url = getBaseUrl(conn) + "/vault/" + encodedDirectory;
            HttpEntity<Void> entity = new HttpEntity<>(obsidianHeaders(conn));
            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Object listed = response.getBody() == null ? null : response.getBody().get("files");
            if (!(listed instanceof List<?> entries)) {
                throw new IllegalStateException(
                        "Obsidian directory listing returned an invalid response for " + directory);
            }

            for (Object value : entries) {
                if (!(value instanceof String entry) || entry.isBlank()) {
                    continue;
                }
                String normalizedEntry = entry.replace('\\', '/');
                boolean directoryEntry = normalizedEntry.endsWith("/");
                String name = directoryEntry
                        ? normalizedEntry.substring(0, normalizedEntry.length() - 1)
                        : normalizedEntry;
                String child = directory.isEmpty() ? name : directory + "/" + name;
                if (directoryEntry) {
                    directories.addLast(child);
                } else if (child.toLowerCase(Locale.ROOT).endsWith(".md")
                        || child.toLowerCase(Locale.ROOT).endsWith(".markdown")) {
                    markdownFiles.add(child);
                }
            }
        }
        return markdownFiles;
    }

    private HttpHeaders obsidianHeaders(NoteSyncConnection conn) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(decryptToken(conn));
        headers.setAccept(List.of(MediaType.ALL));
        return headers;
    }

    private String getBaseUrl(NoteSyncConnection conn) {
        String url = conn.getObsidianApiUrl();
        if (url == null || url.isBlank()) {
            url = "https://127.0.0.1:27124";
        }
        // Strip trailing slash
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String decryptToken(NoteSyncConnection conn) {
        String encrypted = conn.getObsidianTokenEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("No Obsidian API token configured for this connection");
        }
        if (tokenEncryptionService == null) {
            throw new IllegalStateException(
                    "Obsidian credential decryption is unavailable; reconfigure credentials before syncing.");
        }
        try {
            return tokenEncryptionService.decrypt(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Obsidian credential could not be decrypted; reconfigure credentials before syncing.", e);
        }
    }

    private String encodePath(String path) {
        return org.springframework.web.util.UriUtils.encodePath(
                path, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String fileNameToTitle(String filePath) {
        String name = filePath;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        if (name.endsWith(".md")) name = name.substring(0, name.length() - 3);
        return name;
    }

    private void checkEnabled() {
        if (!configService.isObsidianEnabled()) {
            throw new IllegalStateException("Obsidian sync is not enabled. Enable it in Sync settings.");
        }
    }

    /**
     * Create a RestTemplate that trusts self-signed certificates.
     * The Obsidian Local REST API plugin uses a self-signed cert.
     */
    private static RestTemplate createTrustingRestTemplate() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(
                        java.net.HttpURLConnection connection, String httpMethod)
                        throws java.io.IOException {
                    super.prepareConnection(connection, httpMethod);
                    if (connection instanceof HttpsURLConnection https
                            && isLoopbackHost(connection.getURL().getHost())) {
                        https.setSSLSocketFactory(sc.getSocketFactory());
                        https.setHostnameVerifier((hostname, session) -> isLoopbackHost(hostname));
                    }
                }
            };
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            log.warn("Failed to create trusting RestTemplate, using default: {}", e.getMessage());
            var fallbackFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            fallbackFactory.setConnectTimeout(10_000);
            fallbackFactory.setReadTimeout(30_000);
            return new RestTemplate(fallbackFactory);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return host != null && (
                "localhost".equalsIgnoreCase(host)
                        || "127.0.0.1".equals(host)
                        || "::1".equals(host)
                        || "[::1]".equals(host));
    }

}
