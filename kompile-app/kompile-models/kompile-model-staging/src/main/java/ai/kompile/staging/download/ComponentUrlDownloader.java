/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.download;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Repository-free downloader for a complete runnable text-model bundle whose
 * canonical components live at independent public HTTPS URLs.
 *
 * <p>The byte transfer is deliberately delegated to
 * {@link HuggingFaceDownloader}: that implementation already provides the
 * staging service's hardened URI/redirect checks, byte limits, atomic writes,
 * checksums, cancellation, and credential-free provenance. The distinct source
 * name prevents component mode from triggering Hugging Face repository
 * discovery or inventing a repository identifier.</p>
 */
@Service
public class ComponentUrlDownloader implements DownloadService {

    public static final String SOURCE = "https-components";

    private final HuggingFaceDownloader assetDownloader;

    public ComponentUrlDownloader(HuggingFaceDownloader assetDownloader) {
        this.assetDownloader = assetDownloader;
    }

    public static boolean isComponentSource(String source) {
        return SOURCE.equalsIgnoreCase(source);
    }

    @Override
    public String getSourceName() {
        return SOURCE;
    }

    @Override
    public boolean canHandle(String source) {
        return isComponentSource(source);
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination) {
        return assetDownloader.download(request, destination);
    }

    @Override
    public DownloadResult download(
            DownloadRequest request,
            Path destination,
            Consumer<DownloadProgress> progressCallback) {
        return assetDownloader.download(request, destination, progressCallback);
    }

    @Override
    public DownloadResult download(
            DownloadRequest request,
            Path destination,
            Consumer<DownloadProgress> progressCallback,
            StagingCancellation cancellation) {
        return assetDownloader.download(request, destination, progressCallback, cancellation);
    }

    @Override
    public boolean isAvailable(DownloadRequest request) {
        return assetDownloader.isAvailable(request);
    }
}
