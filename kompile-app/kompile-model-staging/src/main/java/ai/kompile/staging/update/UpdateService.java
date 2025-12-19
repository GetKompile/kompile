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

package ai.kompile.staging.update;

import ai.kompile.staging.archive.ArchiveImporter;
import ai.kompile.staging.catalog.remote.RemoteCatalog;
import ai.kompile.staging.catalog.remote.RemoteCatalogEntry;
import ai.kompile.staging.catalog.remote.RemoteCatalogService;
import ai.kompile.staging.registry.ModelRegistry;
import ai.kompile.staging.registry.RegistryService;
import ai.kompile.staging.transfer.ArchiveDownloader;
import ai.kompile.staging.transfer.TransferProgress;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Service for checking and applying archive updates.
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    private final RegistryService registryService;
    private final RemoteCatalogService catalogService;
    private final ArchiveDownloader archiveDownloader;
    private final ArchiveImporter archiveImporter;

    @Autowired
    public UpdateService(RegistryService registryService,
                         RemoteCatalogService catalogService,
                         ArchiveDownloader archiveDownloader,
                         ArchiveImporter archiveImporter) {
        this.registryService = registryService;
        this.catalogService = catalogService;
        this.archiveDownloader = archiveDownloader;
        this.archiveImporter = archiveImporter;
    }

    /**
     * Check for updates for all installed archives.
     */
    public List<UpdateInfo> checkForUpdates() {
        return checkForUpdates(false);
    }

    /**
     * Check for updates, optionally refreshing the catalog.
     */
    public List<UpdateInfo> checkForUpdates(boolean refreshCatalog) {
        List<UpdateInfo> updates = new ArrayList<>();

        // Get installed archives
        ModelRegistry registry = registryService.loadRegistry();
        List<ModelRegistry.ArchiveInstallInfo> installedArchives = registry.getInstalledArchives();

        if (installedArchives.isEmpty()) {
            log.info("No installed archives to check for updates");
            return updates;
        }

        // Get remote catalog
        RemoteCatalog catalog = catalogService.getCatalog(refreshCatalog);

        // Check each installed archive
        for (ModelRegistry.ArchiveInstallInfo installed : installedArchives) {
            UpdateInfo updateInfo = checkArchiveUpdate(installed, catalog);
            updates.add(updateInfo);
        }

        return updates;
    }

    /**
     * Check for update for a specific archive.
     */
    public UpdateInfo checkForUpdate(String archiveId) {
        Optional<ModelRegistry.ArchiveInstallInfo> installed =
                registryService.loadRegistry().getInstalledArchive(archiveId);

        if (installed.isEmpty()) {
            return UpdateInfo.notInCatalog(archiveId, null);
        }

        RemoteCatalog catalog = catalogService.getCatalog();
        return checkArchiveUpdate(installed.get(), catalog);
    }

    /**
     * Apply an update for a specific archive.
     */
    public UpdateResult applyUpdate(String archiveId) {
        return applyUpdate(archiveId, progress -> {});
    }

    /**
     * Apply an update for a specific archive with progress callback.
     */
    public UpdateResult applyUpdate(String archiveId, Consumer<TransferProgress> progressCallback) {
        log.info("Applying update for archive: {}", archiveId);

        // Check if update is available
        UpdateInfo updateInfo = checkForUpdate(archiveId);
        if (!updateInfo.isUpdateAvailable()) {
            return UpdateResult.noUpdateAvailable(archiveId);
        }

        if (updateInfo.getDownloadUrl() == null) {
            return UpdateResult.failure(archiveId, "No download URL available for update");
        }

        try {
            // Download the update
            ArchiveDownloader.DownloadResult downloadResult = archiveDownloader.download(
                    updateInfo.getDownloadUrl(),
                    ArchiveDownloader.DownloadOptions.defaults(),
                    progressCallback
            );

            if (!downloadResult.isSuccess()) {
                return UpdateResult.failure(archiveId, "Download failed: " + downloadResult.getErrorMessage());
            }

            // Import the update
            ArchiveImporter.ImportResult importResult = archiveImporter.importArchive(
                    downloadResult.getArchivePath(),
                    ArchiveImporter.ImportOptions.forceOverwrite()
            );

            if (!importResult.isSuccess()) {
                return UpdateResult.failure(archiveId, "Import failed: " + importResult.getErrorMessage());
            }

            log.info("Successfully updated {} from {} to {}",
                    archiveId, updateInfo.getCurrentVersion(), updateInfo.getLatestVersion());

            return UpdateResult.success(
                    archiveId,
                    updateInfo.getCurrentVersion(),
                    updateInfo.getLatestVersion(),
                    importResult.getImportedCount()
            );

        } catch (Exception e) {
            log.error("Failed to apply update for {}", archiveId, e);
            return UpdateResult.failure(archiveId, "Update failed: " + e.getMessage());
        }
    }

    /**
     * Apply updates for all archives that have updates available.
     */
    public List<UpdateResult> applyAllUpdates() {
        return applyAllUpdates(progress -> {});
    }

    /**
     * Apply all available updates with progress callback.
     */
    public List<UpdateResult> applyAllUpdates(Consumer<TransferProgress> progressCallback) {
        List<UpdateResult> results = new ArrayList<>();

        List<UpdateInfo> updates = checkForUpdates();
        for (UpdateInfo update : updates) {
            if (update.isUpdateAvailable()) {
                UpdateResult result = applyUpdate(update.getArchiveId(), progressCallback);
                results.add(result);
            }
        }

        return results;
    }

    /**
     * Get a summary of available updates.
     */
    public UpdateSummary getUpdateSummary() {
        List<UpdateInfo> updates = checkForUpdates();

        int availableCount = (int) updates.stream().filter(UpdateInfo::isUpdateAvailable).count();
        int majorUpdates = (int) updates.stream()
                .filter(u -> u.isUpdateAvailable() && u.isMayHaveBreakingChanges())
                .count();

        return UpdateSummary.builder()
                .totalInstalled(updates.size())
                .updatesAvailable(availableCount)
                .majorUpdates(majorUpdates)
                .updates(updates)
                .build();
    }

    private UpdateInfo checkArchiveUpdate(ModelRegistry.ArchiveInstallInfo installed, RemoteCatalog catalog) {
        Optional<RemoteCatalogEntry> remoteEntry = catalog.findArchive(installed.getArchiveId());

        if (remoteEntry.isEmpty()) {
            return UpdateInfo.notInCatalog(installed.getArchiveId(), installed.getVersion());
        }

        RemoteCatalogEntry remote = remoteEntry.get();
        String downloadUrl = catalog.getDownloadUrl(remote, remote.getLatestVersion());

        return UpdateInfo.fromCatalogEntry(
                installed.getArchiveId(),
                installed.getVersion(),
                remote,
                downloadUrl
        );
    }

    /**
     * Result of an update operation.
     */
    @Data
    @Builder
    public static class UpdateResult {
        private final boolean success;
        private final String archiveId;
        private final String previousVersion;
        private final String newVersion;
        private final int modelsUpdated;
        private final String errorMessage;

        public static UpdateResult success(String archiveId, String from, String to, int models) {
            return UpdateResult.builder()
                    .success(true)
                    .archiveId(archiveId)
                    .previousVersion(from)
                    .newVersion(to)
                    .modelsUpdated(models)
                    .build();
        }

        public static UpdateResult failure(String archiveId, String error) {
            return UpdateResult.builder()
                    .success(false)
                    .archiveId(archiveId)
                    .errorMessage(error)
                    .build();
        }

        public static UpdateResult noUpdateAvailable(String archiveId) {
            return UpdateResult.builder()
                    .success(true)
                    .archiveId(archiveId)
                    .errorMessage("No update available")
                    .build();
        }
    }

    /**
     * Summary of update status.
     */
    @Data
    @Builder
    public static class UpdateSummary {
        private final int totalInstalled;
        private final int updatesAvailable;
        private final int majorUpdates;
        private final List<UpdateInfo> updates;

        public boolean hasUpdates() {
            return updatesAvailable > 0;
        }

        public boolean hasMajorUpdates() {
            return majorUpdates > 0;
        }
    }
}
