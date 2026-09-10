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

package ai.kompile.app.sync.service;

import ai.kompile.app.sync.adapter.SyncAdapter;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.NoteSyncRun;
import ai.kompile.app.sync.domain.SyncAuthMode;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.dto.SyncConnectionRequest;
import ai.kompile.app.sync.dto.SyncConnectionResponse;
import ai.kompile.app.sync.dto.SyncConnectionTestResponse;
import ai.kompile.app.sync.dto.SyncRunResponse;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import ai.kompile.app.sync.repository.NoteSyncRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class NoteSyncConnectionService {



    private static final Duration RUN_LEASE_DURATION = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(NoteSyncConnectionService.class);

    @Autowired
    private NoteSyncConnectionRepository connectionRepository;

    @Autowired
    private NoteSyncRecordRepository syncRecordRepository;

    @Autowired
    private NoteSyncRunRepository syncRunRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private ai.kompile.oauth.service.TokenEncryptionService tokenEncryptionService;

    @Autowired(required = false)
    private List<SyncAdapter> adapters;

    public SyncConnectionResponse createConnection(SyncConnectionRequest req) {
        NoteSyncConnection conn = NoteSyncConnection.builder()
                .factSheetId(req.getFactSheetId())
                .provider(req.getProvider())
                .externalScope(req.getExternalScope())
                .direction(req.getDirection())
                .pollCron(req.getPollCron())
                .repositoryUrl(sanitizeRepositoryUrl(req.getRepositoryUrl()))
                .gitBranch(trimToNull(req.getGitBranch()))
                .gitUsername(trimToNull(req.getGitUsername()))
                .authMode(resolveAuthMode(req))
                .authStatus(initialAuthStatus(req))
                .authStatusMessage(initialAuthMessage(req))
                .autoCommit(req.getAutoCommit() != null ? req.getAutoCommit() : true)
                .remoteSyncEnabled(req.getRemoteSyncEnabled() != null ? req.getRemoteSyncEnabled() : true)
                .enabled(false)
                .build();

        if (req.getProvider() == SyncProvider.OBSIDIAN) {
            conn.setObsidianApiUrl(trimToNull(req.getObsidianApiUrl()));
            if (req.getObsidianToken() != null && !req.getObsidianToken().isBlank()) {
                conn.setObsidianTokenEncrypted(encryptToken(req.getObsidianToken()));
            }
        }
        if (conn.getAuthMode() == SyncAuthMode.HTTPS_TOKEN && req.getGitToken() != null && !req.getGitToken().isBlank()) {
            conn.setGitTokenEncrypted(encryptToken(req.getGitToken()));
        }
        reconcileAuthSecretStatus(conn);

        conn = connectionRepository.save(conn);
        log.info("Created sync connection {} for factSheet={} provider={} scope={}",
                conn.getId(), conn.getFactSheetId(), conn.getProvider(), conn.getExternalScope());
        return SyncConnectionResponse.from(conn);
    }

    public SyncConnectionResponse updateConnection(Long id, SyncConnectionRequest req) {
        NoteSyncConnection conn = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));

        conn.setExternalScope(req.getExternalScope());
        conn.setDirection(req.getDirection());
        conn.setPollCron(req.getPollCron());
        conn.setRepositoryUrl(sanitizeRepositoryUrl(req.getRepositoryUrl()));
        conn.setGitBranch(trimToNull(req.getGitBranch()));
        conn.setGitUsername(trimToNull(req.getGitUsername()));
        conn.setAuthMode(resolveAuthMode(req));
        conn.setAuthStatus(initialAuthStatus(req));
        conn.setAuthStatusMessage(initialAuthMessage(req));
        conn.setAuthLastCheckedAt(null);
        conn.setAutoCommit(req.getAutoCommit() != null ? req.getAutoCommit() : true);
        conn.setRemoteSyncEnabled(req.getRemoteSyncEnabled() != null ? req.getRemoteSyncEnabled() : true);

        if (req.getProvider() == SyncProvider.OBSIDIAN) {
            conn.setObsidianApiUrl(trimToNull(req.getObsidianApiUrl()));
            if (req.getObsidianToken() != null && !req.getObsidianToken().isBlank()) {
                conn.setObsidianTokenEncrypted(encryptToken(req.getObsidianToken()));
            } else if (conn.getAuthMode() != SyncAuthMode.OBSIDIAN_REST_TOKEN) {
                conn.setObsidianTokenEncrypted(null);
            }
        }
        if (conn.getAuthMode() == SyncAuthMode.HTTPS_TOKEN) {
            if (req.getGitToken() != null && !req.getGitToken().isBlank()) {
                conn.setGitTokenEncrypted(encryptToken(req.getGitToken()));
            }
        } else {
            conn.setGitTokenEncrypted(null);
        }
        reconcileAuthSecretStatus(conn);

        conn = connectionRepository.save(conn);
        return SyncConnectionResponse.from(conn);
    }

    @Transactional
    public void deleteConnection(Long id) {
        syncRecordRepository.deleteByConnectionId(id);
        connectionRepository.deleteById(id);
        log.info("Deleted sync connection {}", id);
    }

    public List<SyncConnectionResponse> listConnectionsForFactSheet(Long factSheetId) {
        return connectionRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId)
                .stream()
                .map(SyncConnectionResponse::from)
                .collect(Collectors.toList());
    }

    public SyncConnectionResponse getConnection(Long id) {
        return connectionRepository.findById(id)
                .map(SyncConnectionResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
    }

    public SyncConnectionResponse enableConnection(Long id) {
        NoteSyncConnection conn = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        requireAuthReady(conn);
        conn.setEnabled(true);
        conn = connectionRepository.save(conn);
        return SyncConnectionResponse.from(conn);
    }

    public SyncConnectionResponse disableConnection(Long id) {
        NoteSyncConnection conn = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        conn.setEnabled(false);
        conn = connectionRepository.save(conn);
        return SyncConnectionResponse.from(conn);
    }

    public SyncConnectionTestResponse testConnection(Long id) {
        NoteSyncConnection conn = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        if (conn.getAuthMode() == null) {
            conn.setAuthMode(resolveAuthMode(conn));
        }
        SyncAdapter adapter = resolveAdapter(conn.getProvider());
        SyncConnectionTestResponse result;
        try {
            result = adapter.testConnection(conn);
        } catch (Exception e) {
            result = SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(), e.getMessage());
        }
        conn.setAuthStatus(result.getAuthStatus());
        conn.setAuthStatusMessage(result.getMessage());
        conn.setAuthLastCheckedAt(result.getCheckedAt());
        connectionRepository.save(conn);
        return result;
    }

    /**
     * Atomically leases a connection, records the run, and dispatches it only after this
     * transaction commits. The same session id is used by the API, worker, WebSocket, and graph crawl.
     */
    public SyncRunResponse startSync(Long connectionId, boolean pullOnly) {
        NoteSyncConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));
        validateCanRun(conn);

        Instant now = Instant.now();
        String displacedRunId = conn.getActiveSyncRunId();
        Instant displacedLease = conn.getSyncLeaseExpiresAt();
        String sessionId = (pullOnly ? "pull-" : "sync-") + UUID.randomUUID();
        int acquired = connectionRepository.acquireRunLease(
                connectionId, sessionId, now, now.plus(RUN_LEASE_DURATION));
        if (acquired != 1) {
            NoteSyncConnection active = connectionRepository.findById(connectionId).orElse(conn);
            throw new SyncAlreadyRunningException(
                    active.getActiveSyncRunId(), active.getSyncLeaseExpiresAt());
        }

        if (displacedRunId != null
                && (displacedLease == null || !displacedLease.isAfter(now))) {
            markDisplacedRunInterrupted(displacedRunId, now);
        }

        NoteSyncRun run = NoteSyncRun.builder()
                .id(sessionId)
                .connectionId(connectionId)
                .factSheetId(conn.getFactSheetId())
                .provider(conn.getProvider())
                .mode(pullOnly ? "PULL" : "FULL")
                .status("QUEUED")
                .stage("QUEUED")
                .message("Source sync queued")
                .graphStatus("NOT_REQUIRED")
                .checkpointBefore(conn.getLastSyncAt())
                .queuedAt(now)
                .updatedAt(now)
                .build();
        run = syncRunRepository.save(run);
        eventPublisher.publishEvent(new NoteSyncRunQueuedEvent(sessionId, connectionId, pullOnly));
        return SyncRunResponse.from(run);
    }

    public SyncRunResponse triggerSync(Long connectionId) {
        return startSync(connectionId, false);
    }

    public SyncRunResponse triggerPull(Long connectionId) {
        return startSync(connectionId, true);
    }

    @Transactional(readOnly = true)
    public SyncRunResponse getRun(String sessionId) {
        return syncRunRepository.findById(sessionId)
                .map(SyncRunResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Sync run not found: " + sessionId));
    }

    @Transactional(readOnly = true)
    public List<SyncRunResponse> listRuns(Long connectionId) {
        return listRuns(connectionId, null);
    }

    @Transactional(readOnly = true)
    public List<SyncRunResponse> listRuns(Long connectionId, Long factSheetId) {
        List<NoteSyncRun> runs;
        if (connectionId != null) {
            runs = syncRunRepository.findTop50ByConnectionIdOrderByQueuedAtDesc(connectionId);
        } else if (factSheetId != null) {
            runs = syncRunRepository.findTop50ByFactSheetIdOrderByQueuedAtDesc(factSheetId);
        } else {
            runs = syncRunRepository.findTop50ByOrderByQueuedAtDesc();
        }
        return runs.stream().map(SyncRunResponse::from).toList();
    }

    public boolean renewRunLease(Long connectionId, String sessionId) {
        Instant now = Instant.now();
        return connectionRepository.renewRunLease(
                connectionId, sessionId, now, now.plus(RUN_LEASE_DURATION)) == 1;
    }

    public void renewRunLeaseOrThrow(Long connectionId, String sessionId) {
        if (!renewRunLease(connectionId, sessionId)) {
            throw new SyncLeaseLostException(connectionId, sessionId);
        }
    }

    public void releaseRunLease(Long connectionId, String sessionId) {
        connectionRepository.releaseRunLease(connectionId, sessionId, Instant.now());
    }

    public SyncConnectionResponse updateAutoSync(Long id, boolean enabled, String pollCron) {
        NoteSyncConnection conn = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        conn.setPollCron(enabled ? requireCron(pollCron) : null);
        if (enabled) {
            requireAuthReady(conn);
            conn.setEnabled(true);
        }
        return SyncConnectionResponse.from(connectionRepository.save(conn));
    }

    /**
     * Synchronous guard used by controllers before an asynchronous run is accepted.
     */
    public void validateCanRun(Long id) {
        NoteSyncConnection conn = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        validateCanRun(conn);
    }

    private void validateCanRun(NoteSyncConnection conn) {
        if (!Boolean.TRUE.equals(conn.getEnabled())) {
            throw new IllegalArgumentException(
                    "Source connection is disabled. Test authentication and enable it before syncing.");
        }
        requireAuthReady(conn);
    }

    private void markDisplacedRunInterrupted(String runId, Instant now) {
        syncRunRepository.findById(runId).ifPresent(run -> {
            if (!isTerminal(run.getStatus())) {
                run.setStatus("INTERRUPTED");
                run.setStage("INTERRUPTED");
                run.setMessage("Run lease expired before completion");
                run.setErrorMessage("The worker stopped reporting progress and its lease expired.");
                run.setFinishedAt(now);
                syncRunRepository.save(run);
            }
        });
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status)
                || "PARTIAL".equals(status)
                || "ERROR".equals(status)
                || "INTERRUPTED".equals(status);
    }

    public static final class SyncAlreadyRunningException extends IllegalStateException {
        private final String activeRunId;
        private final Instant leaseExpiresAt;

        public SyncAlreadyRunningException(String activeRunId, Instant leaseExpiresAt) {
            super("A source sync is already active"
                    + (activeRunId == null ? "" : ": " + activeRunId));
            this.activeRunId = activeRunId;
            this.leaseExpiresAt = leaseExpiresAt;
        }

        public String getActiveRunId() {
            return activeRunId;
        }

        public Instant getLeaseExpiresAt() {
            return leaseExpiresAt;
        }
    }

    /** Fencing failure raised when a stale worker no longer owns its connection lease. */
    public static final class SyncLeaseLostException extends IllegalStateException {
        public SyncLeaseLostException(Long connectionId, String sessionId) {
            super("Source sync lease is no longer owned by run " + sessionId
                    + " for connection " + connectionId);
        }
    }

    private void requireAuthReady(NoteSyncConnection conn) {
        SyncAuthMode mode = conn.getAuthMode() == null ? resolveAuthMode(conn) : conn.getAuthMode();
        boolean credentialsRequired = conn.getProvider() == SyncProvider.NOTION
                || mode != SyncAuthMode.NONE;
        if (credentialsRequired && !"VALID".equalsIgnoreCase(conn.getAuthStatus())) {
            throw new IllegalArgumentException(
                    "Source authentication must pass Test Auth before this connection can be enabled or synced.");
        }
    }

    private String requireCron(String pollCron) {
        String cron = trimToNull(pollCron);
        if (cron == null) {
            throw new IllegalArgumentException("pollCron is required when auto sync is enabled");
        }
        try {
            org.springframework.scheduling.support.CronExpression.parse(cron);
            return cron;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid pollCron: " + e.getMessage(), e);
        }
    }

    private SyncAdapter resolveAdapter(SyncProvider provider) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("No sync adapters are configured");
        }
        return adapters.stream()
                .filter(adapter -> adapter.adapterId().equalsIgnoreCase(provider.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No adapter for " + provider));
    }

    private SyncAuthMode resolveAuthMode(SyncConnectionRequest req) {
        if (req.getAuthMode() != null) {
            return req.getAuthMode();
        }
        if (req.getProvider() == SyncProvider.OBSIDIAN) {
            return trimToNull(req.getObsidianApiUrl()) == null ? SyncAuthMode.NONE : SyncAuthMode.OBSIDIAN_REST_TOKEN;
        }
        if (req.getProvider() == SyncProvider.GIT_REPOSITORY) {
            if (trimToNull(req.getRepositoryUrl()) == null || Boolean.FALSE.equals(req.getRemoteSyncEnabled())) {
                return SyncAuthMode.NONE;
            }
            return trimToNull(req.getGitToken()) == null ? SyncAuthMode.SYSTEM_GIT : SyncAuthMode.HTTPS_TOKEN;
        }
        return SyncAuthMode.NONE;
    }

    private SyncAuthMode resolveAuthMode(NoteSyncConnection conn) {
        if (conn.getProvider() == SyncProvider.OBSIDIAN) {
            return trimToNull(conn.getObsidianApiUrl()) == null ? SyncAuthMode.NONE : SyncAuthMode.OBSIDIAN_REST_TOKEN;
        }
        if (conn.getProvider() == SyncProvider.GIT_REPOSITORY) {
            if (trimToNull(conn.getRepositoryUrl()) == null || Boolean.FALSE.equals(conn.getRemoteSyncEnabled())) {
                return SyncAuthMode.NONE;
            }
            return conn.getGitTokenEncrypted() == null || conn.getGitTokenEncrypted().isBlank()
                    ? SyncAuthMode.SYSTEM_GIT
                    : SyncAuthMode.HTTPS_TOKEN;
        }
        return SyncAuthMode.NONE;
    }

    private String initialAuthStatus(SyncConnectionRequest req) {
        SyncAuthMode mode = resolveAuthMode(req);
        if (mode == SyncAuthMode.NONE) {
            return "NOT_REQUIRED";
        }
        if (mode == SyncAuthMode.OBSIDIAN_REST_TOKEN && trimToNull(req.getObsidianToken()) == null) {
            return "MISSING";
        }
        if (mode == SyncAuthMode.HTTPS_TOKEN && trimToNull(req.getGitToken()) == null) {
            return "MISSING";
        }
        return "CONFIGURED";
    }

    private String initialAuthMessage(SyncConnectionRequest req) {
        SyncAuthMode mode = resolveAuthMode(req);
        return switch (mode) {
            case NONE -> "No credentials required for this connection.";
            case OBSIDIAN_REST_TOKEN -> "Obsidian Local REST API token is required and can be validated with Test Auth.";
            case SYSTEM_GIT -> "Remote Git auth uses the server's git credentials, SSH agent, or credential helper.";
            case HTTPS_TOKEN -> "Git HTTPS token is stored by Kompile and used for pull/push operations.";
        };
    }

    private void reconcileAuthSecretStatus(NoteSyncConnection conn) {
        if (conn.getAuthMode() == SyncAuthMode.NONE) {
            conn.setAuthStatus("NOT_REQUIRED");
            return;
        }
        if (conn.getAuthMode() == SyncAuthMode.SYSTEM_GIT) {
            conn.setAuthStatus("CONFIGURED");
            return;
        }
        if (conn.getAuthMode() == SyncAuthMode.OBSIDIAN_REST_TOKEN) {
            boolean configured = conn.getObsidianTokenEncrypted() != null && !conn.getObsidianTokenEncrypted().isBlank();
            conn.setAuthStatus(configured ? "CONFIGURED" : "MISSING");
            return;
        }
        if (conn.getAuthMode() == SyncAuthMode.HTTPS_TOKEN) {
            boolean configured = conn.getGitTokenEncrypted() != null && !conn.getGitTokenEncrypted().isBlank();
            conn.setAuthStatus(configured ? "CONFIGURED" : "MISSING");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String sanitizeRepositoryUrl(String value) {
        String candidate = trimToNull(value);
        if (candidate == null) return null;
        int scheme = candidate.indexOf("://");
        int at = candidate.indexOf('@', Math.max(0, scheme + 3));
        if (scheme >= 0 && at > scheme) {
            candidate = candidate.substring(0, scheme + 3) + candidate.substring(at + 1);
        }
        int query = candidate.indexOf('?');
        if (query >= 0) candidate = candidate.substring(0, query);
        int fragment = candidate.indexOf('#');
        if (fragment >= 0) candidate = candidate.substring(0, fragment);
        return candidate;
    }

    private String encryptToken(String plainToken) {
        if (tokenEncryptionService != null) {
            return tokenEncryptionService.encrypt(plainToken);
        }
        throw new IllegalStateException(
                "Credential encryption is unavailable; the token was not stored");
    }
}
