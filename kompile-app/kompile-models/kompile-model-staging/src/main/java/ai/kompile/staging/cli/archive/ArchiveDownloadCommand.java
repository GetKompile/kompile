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

import ai.kompile.staging.archive.ArchiveImporter;
import ai.kompile.staging.transfer.ArchiveDownloader;
import ai.kompile.staging.transfer.TransferProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * CLI command for downloading Kompile archives from URLs.
 */
@Component
@Command(
    name = "download",
    description = "Download a Kompile archive from a URL",
    mixinStandardHelpOptions = true
)
public class ArchiveDownloadCommand implements Callable<Integer> {

    @Autowired
    private ArchiveDownloader archiveDownloader;

    @Parameters(index = "0", arity = "0..1",
            description = "URL to download archive from")
    private String urlParam;

    @Option(names = {"-u", "--url"},
            description = "URL to download archive from")
    private String url;

    @Option(names = {"-o", "--output"},
            description = "Output directory or file path")
    private String output;

    @Option(names = {"--resume"},
            description = "Resume interrupted download if possible",
            defaultValue = "true")
    private boolean resume;

    @Option(names = {"--import"},
            description = "Automatically import after download",
            defaultValue = "true")
    private boolean autoImport;

    @Option(names = {"--verify"},
            description = "Verify checksum after download",
            defaultValue = "true")
    private boolean verify;

    @Option(names = {"--force"},
            description = "Force overwrite existing models on import")
    private boolean force;

    @Option(names = {"--checksum"},
            description = "Expected SHA256 checksum to verify")
    private String expectedChecksum;

    @Option(names = {"--token"},
            description = "Authentication bearer token")
    private String token;

    @Override
    public Integer call() {
        // Determine URL
        String downloadUrl = urlParam != null ? urlParam : url;
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            System.err.println("Please specify a URL to download");
            return 1;
        }

        // Build download options
        ArchiveDownloader.DownloadOptions.DownloadOptionsBuilder optionsBuilder =
                ArchiveDownloader.DownloadOptions.builder()
                        .allowResume(resume)
                        .verifyChecksum(verify)
                        .expectedChecksum(expectedChecksum);

        if (output != null) {
            optionsBuilder.destinationPath(Paths.get(output));
        }

        ArchiveDownloader.DownloadOptions options = optionsBuilder.build();

        System.out.println("Downloading archive from: " + downloadUrl);
        if (resume) {
            System.out.println("Resume mode: enabled");
        }

        // Download with progress
        ArchiveDownloader.DownloadResult result;
        if (autoImport) {
            ArchiveImporter.ImportOptions importOptions = ArchiveImporter.ImportOptions.builder()
                    .forceOverwrite(force)
                    .build();
            result = archiveDownloader.downloadAndImport(downloadUrl, options, importOptions, progress -> {
                printProgress(progress);
            });
        } else {
            result = archiveDownloader.download(downloadUrl, options, progress -> {
                printProgress(progress);
            });
        }

        System.out.println();

        if (result.isSuccess()) {
            System.out.println("Download completed successfully!");
            System.out.println();
            System.out.println("Archive:  " + result.getArchivePath());
            System.out.println("Size:     " + formatSize(result.getBytesDownloaded()));
            if (result.getChecksum() != null) {
                System.out.println("Checksum: " + result.getChecksum());
            }

            return 0;
        } else {
            System.err.println("Download failed: " + result.getErrorMessage());
            System.err.println("Use --resume to continue download later");
            return 1;
        }
    }

    private void printProgress(TransferProgress progress) {
        String bar = createProgressBar(progress.getProgressPercent());
        String speed = formatSpeed(progress.getBytesPerSecond());
        long etaSeconds = progress.getEstimatedSecondsRemaining();
        String eta = etaSeconds > 0 ? formatDuration(etaSeconds * 1000) : "calculating...";

        System.out.print(String.format("\r%s: %s %d%% | %s of %s | %s | ETA: %s   ",
                progress.getPhase(),
                bar,
                progress.getProgressPercent(),
                formatSize(progress.getBytesTransferred()),
                formatSize(progress.getTotalBytes()),
                speed,
                eta));
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

    private String formatDuration(long millis) {
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return String.format("%dm %ds", minutes, seconds);
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh %dm", hours, minutes);
    }
}
