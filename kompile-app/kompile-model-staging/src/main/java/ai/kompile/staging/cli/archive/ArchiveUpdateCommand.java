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

package ai.kompile.staging.cli.archive;

import ai.kompile.staging.transfer.TransferProgress;
import ai.kompile.staging.update.UpdateInfo;
import ai.kompile.staging.update.UpdateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for checking and applying archive updates.
 */
@Component
@Command(
    name = "update",
    description = "Check for and apply archive updates",
    mixinStandardHelpOptions = true
)
public class ArchiveUpdateCommand implements Callable<Integer> {

    @Autowired
    private UpdateService updateService;

    @Parameters(index = "0", arity = "0..1",
            description = "Archive ID to update (or 'check' to just check)")
    private String archiveIdOrAction;

    @Option(names = {"--check"},
            description = "Only check for updates, don't apply")
    private boolean checkOnly;

    @Option(names = {"--all"},
            description = "Apply all available updates")
    private boolean updateAll;

    @Option(names = {"--refresh"},
            description = "Refresh the remote catalog before checking")
    private boolean refresh;

    @Option(names = {"--json"},
            description = "Output in JSON format")
    private boolean json;

    @Option(names = {"--yes", "-y"},
            description = "Skip confirmation prompts")
    private boolean skipConfirmation;

    @Override
    public Integer call() {
        // Determine action
        boolean isCheck = checkOnly || "check".equalsIgnoreCase(archiveIdOrAction);

        if (isCheck) {
            return checkForUpdates();
        } else if (updateAll) {
            return applyAllUpdates();
        } else if (archiveIdOrAction != null && !"check".equalsIgnoreCase(archiveIdOrAction)) {
            return applyUpdate(archiveIdOrAction);
        } else {
            // Default: check for updates
            return checkForUpdates();
        }
    }

    private int checkForUpdates() {
        if (!json) {
            System.out.println("Checking for updates...");
            if (refresh) {
                System.out.println("Refreshing remote catalog...");
            }
        }

        List<UpdateInfo> updates = updateService.checkForUpdates(refresh);

        if (updates.isEmpty()) {
            if (json) {
                System.out.println("[]");
            } else {
                System.out.println("No installed archives to check.");
            }
            return 0;
        }

        if (json) {
            printUpdatesJson(updates);
        } else {
            printUpdatesTable(updates);
        }

        return 0;
    }

    private void printUpdatesTable(List<UpdateInfo> updates) {
        long availableCount = updates.stream().filter(UpdateInfo::isUpdateAvailable).count();

        System.out.println();
        System.out.println("Update Status:");
        System.out.println();

        String format = "%-30s %-12s %-12s %-15s%n";
        System.out.printf(format, "ARCHIVE", "CURRENT", "LATEST", "STATUS");
        System.out.println("-".repeat(75));

        for (UpdateInfo update : updates) {
            String status;
            if (update.isUpdateAvailable()) {
                String diff = update.getVersionDiff() != null ?
                        update.getVersionDiff().name().toLowerCase().replace("_", " ") : "";
                status = "UPDATE (" + diff + ")";
                if (update.isMayHaveBreakingChanges()) {
                    status += " !";
                }
            } else {
                status = "up to date";
            }

            System.out.printf(format,
                    truncate(update.getArchiveId(), 30),
                    truncate(update.getCurrentVersion(), 12),
                    truncate(update.getLatestVersion(), 12),
                    status);
        }

        System.out.println();
        System.out.println("Total: " + updates.size() + " archive(s), " + availableCount + " update(s) available");

        if (availableCount > 0) {
            System.out.println();
            System.out.println("To apply updates:");
            System.out.println("  archive update --all              # Apply all updates");
            System.out.println("  archive update <archive-id>       # Apply specific update");
        }
    }

    private void printUpdatesJson(List<UpdateInfo> updates) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < updates.size(); i++) {
            UpdateInfo u = updates.get(i);
            sb.append("  {\n");
            sb.append("    \"archiveId\": \"").append(escapeJson(u.getArchiveId())).append("\",\n");
            sb.append("    \"archiveName\": ").append(u.getArchiveName() != null ?
                    "\"" + escapeJson(u.getArchiveName()) + "\"" : "null").append(",\n");
            sb.append("    \"currentVersion\": \"").append(escapeJson(u.getCurrentVersion())).append("\",\n");
            sb.append("    \"latestVersion\": ").append(u.getLatestVersion() != null ?
                    "\"" + escapeJson(u.getLatestVersion()) + "\"" : "null").append(",\n");
            sb.append("    \"updateAvailable\": ").append(u.isUpdateAvailable()).append(",\n");
            sb.append("    \"versionDiff\": ").append(u.getVersionDiff() != null ?
                    "\"" + u.getVersionDiff().name() + "\"" : "null").append(",\n");
            sb.append("    \"mayHaveBreakingChanges\": ").append(u.isMayHaveBreakingChanges()).append(",\n");
            sb.append("    \"sizeBytes\": ").append(u.getSizeBytes()).append(",\n");
            sb.append("    \"downloadUrl\": ").append(u.getDownloadUrl() != null ?
                    "\"" + escapeJson(u.getDownloadUrl()) + "\"" : "null").append("\n");
            sb.append("  }");
            if (i < updates.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]");
        System.out.println(sb.toString());
    }

    private int applyUpdate(String archiveId) {
        System.out.println("Checking for update: " + archiveId);

        UpdateInfo updateInfo = updateService.checkForUpdate(archiveId);

        if (!updateInfo.isUpdateAvailable()) {
            System.out.println("Archive '" + archiveId + "' is already up to date (" +
                    updateInfo.getCurrentVersion() + ")");
            return 0;
        }

        System.out.println("Update available: " + updateInfo.getCurrentVersion() +
                " -> " + updateInfo.getLatestVersion());
        if (updateInfo.isMayHaveBreakingChanges()) {
            System.out.println("WARNING: This is a major version update and may have breaking changes!");
        }
        System.out.println("Size: " + updateInfo.getFormattedSize());

        if (!skipConfirmation) {
            System.out.println();
            System.out.println("Applying update...");
        }

        UpdateService.UpdateResult result = updateService.applyUpdate(archiveId, progress -> {
            printProgress(progress);
        });

        System.out.println();

        if (result.isSuccess()) {
            System.out.println("Update applied successfully!");
            System.out.println("  Archive:  " + result.getArchiveId());
            System.out.println("  Previous: " + result.getPreviousVersion());
            System.out.println("  New:      " + result.getNewVersion());
            System.out.println("  Models:   " + result.getModelsUpdated());
            return 0;
        } else {
            System.err.println("Update failed: " + result.getErrorMessage());
            return 1;
        }
    }

    private int applyAllUpdates() {
        System.out.println("Checking for updates...");
        if (refresh) {
            System.out.println("Refreshing remote catalog...");
        }

        UpdateService.UpdateSummary summary = updateService.getUpdateSummary();

        if (!summary.hasUpdates()) {
            System.out.println("All archives are up to date.");
            return 0;
        }

        System.out.println("Found " + summary.getUpdatesAvailable() + " update(s) available");
        if (summary.hasMajorUpdates()) {
            System.out.println("WARNING: " + summary.getMajorUpdates() +
                    " update(s) may have breaking changes");
        }

        if (!skipConfirmation) {
            System.out.println();
            System.out.println("Applying all updates...");
        }

        List<UpdateService.UpdateResult> results = updateService.applyAllUpdates(progress -> {
            printProgress(progress);
        });

        System.out.println();
        System.out.println("Update Summary:");
        System.out.println();

        int successCount = 0;
        int failCount = 0;

        for (UpdateService.UpdateResult result : results) {
            if (result.isSuccess()) {
                successCount++;
                System.out.println("  [OK] " + result.getArchiveId() + ": " +
                        result.getPreviousVersion() + " -> " + result.getNewVersion());
            } else {
                failCount++;
                System.out.println("  [FAIL] " + result.getArchiveId() + ": " + result.getErrorMessage());
            }
        }

        System.out.println();
        System.out.println("Updated: " + successCount + ", Failed: " + failCount);

        return failCount > 0 ? 1 : 0;
    }

    private void printProgress(TransferProgress progress) {
        String bar = createProgressBar(progress.getProgressPercent());
        String speed = formatSpeed(progress.getBytesPerSecond());

        System.out.print(String.format("\r%s: %s %d%% | %s | %s   ",
                progress.getPhase(),
                bar,
                progress.getProgressPercent(),
                formatSize(progress.getBytesTransferred()),
                speed));
    }

    private String createProgressBar(int percent) {
        int width = 25;
        int filled = percent * width / 100;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                sb.append("=");
            } else if (i == filled) {
                sb.append(">");
            } else {
                sb.append(" ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format("%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
    }
}
