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

import ai.kompile.app.sync.config.NoteSyncConfigService;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHasher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Watches locally-mounted Obsidian vault directories for file changes and
 * triggers sync when .md files are modified. Only active when file watching
 * is enabled in the JSON config and the vault is locally accessible.
 *
 * Uses io.methvin:directory-watcher for native FS events with deduplication.
 */
@Component
public class ObsidianVaultWatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ObsidianVaultWatcher.class);

    @Autowired
    private NoteSyncConfigService configService;

    @Autowired
    private NoteSyncConnectionRepository connectionRepository;

    @Autowired
    private NoteSyncConnectionService connectionService;

    private final Map<Long, DirectoryWatcher> watchers = new ConcurrentHashMap<>();
    // Debounce: wait 2 seconds after last change before triggering sync
    private final Map<Long, ScheduledFuture<?>> pendingSyncs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        if (!configService.isObsidianFileWatchEnabled()) {
            log.info("Obsidian file watching is disabled");
            return;
        }
        startWatchingAll();
    }

    /**
     * Start watching all Obsidian connections that have a local vault path
     * (no obsidianApiUrl set = local vault).
     */
    public void startWatchingAll() {
        if (!configService.isObsidianFileWatchEnabled()) return;

        List<NoteSyncConnection> obsidianConns = connectionRepository.findByEnabledTrue().stream()
                .filter(c -> c.getProvider() == SyncProvider.OBSIDIAN
                        || c.getProvider() == SyncProvider.LOCAL_FOLDER
                        || c.getProvider() == SyncProvider.GIT_REPOSITORY)
                .filter(c -> c.getProvider() != SyncProvider.OBSIDIAN
                        || c.getObsidianApiUrl() == null || c.getObsidianApiUrl().isBlank())
                .toList();

        for (NoteSyncConnection conn : obsidianConns) {
            startWatching(conn);
        }
    }

    public void startWatching(NoteSyncConnection conn) {
        if (watchers.containsKey(conn.getId())) return;

        String scope = conn.getExternalScope();
        if (scope == null || scope.isBlank()) {
            log.warn("Cannot watch connection {} - no externalScope configured", conn.getId());
            return;
        }

        Path vaultPath = Paths.get(scope);
        if (!Files.isDirectory(vaultPath)) {
            log.warn("Cannot watch connection {} - path does not exist: {}", conn.getId(), vaultPath);
            return;
        }

        try {
            DirectoryWatcher watcher = DirectoryWatcher.builder()
                    .path(vaultPath)
                    .listener(event -> {
                        Path changed = event.path();
                        String fileName = changed.toString();
                        // Only react to .md files, ignore .obsidian/ directory
                        if (fileName.endsWith(".md") && !fileName.contains("/.obsidian/")) {
                            scheduleSync(conn.getId());
                        }
                    })
                    .fileHasher(FileHasher.LAST_MODIFIED_TIME)
                    .build();

            watcher.watchAsync();
            watchers.put(conn.getId(), watcher);
            log.info("Started watching Obsidian vault for connection {}: {}", conn.getId(), vaultPath);
        } catch (Exception e) {
            log.error("Failed to start watching vault for connection {}: {}", conn.getId(), e.getMessage());
        }
    }

    public void stopWatching(Long connectionId) {
        DirectoryWatcher watcher = watchers.remove(connectionId);
        if (watcher != null) {
            try {
                watcher.close();
                log.info("Stopped watching vault for connection {}", connectionId);
            } catch (Exception e) {
                log.warn("Error closing watcher for connection {}: {}", connectionId, e.getMessage());
            }
        }
    }

    /**
     * Debounce sync trigger: wait 2 seconds after the last file change.
     */
    private void scheduleSync(Long connectionId) {
        ScheduledFuture<?> existing = pendingSyncs.get(connectionId);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
            log.info("File change detected, triggering sync for connection {}", connectionId);
            try {
                connectionService.triggerSync(connectionId);
            } catch (Exception e) {
                log.error("File-watch triggered sync failed for connection {}: {}",
                        connectionId, e.getMessage());
            }
            pendingSyncs.remove(connectionId);
        }, 2, TimeUnit.SECONDS);

        pendingSyncs.put(connectionId, future);
    }

    @Override
    public void destroy() {
        debounceExecutor.shutdownNow();
        watchers.values().forEach(w -> {
            try { w.close(); }
            catch (Exception e) { log.warn("Failed to close vault watcher: {}", e.getMessage()); }
        });
        watchers.clear();
        log.info("ObsidianVaultWatcher shut down");
    }
}
