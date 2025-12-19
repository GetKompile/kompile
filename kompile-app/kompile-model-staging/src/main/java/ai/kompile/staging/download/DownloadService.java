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

package ai.kompile.staging.download;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Interface for downloading models from external sources.
 */
public interface DownloadService {

    /**
     * Get the source name this downloader handles (e.g., "huggingface", "github").
     */
    String getSourceName();

    /**
     * Check if this downloader can handle the given source.
     */
    boolean canHandle(String source);

    /**
     * Download a model to the specified destination.
     *
     * @param request The download request containing source info
     * @param destination The directory to download to
     * @return The result of the download operation
     */
    DownloadResult download(DownloadRequest request, Path destination);

    /**
     * Download a model with progress callback.
     *
     * @param request The download request
     * @param destination The directory to download to
     * @param progressCallback Callback for progress updates
     * @return The result of the download operation
     */
    DownloadResult download(DownloadRequest request, Path destination,
                           Consumer<DownloadProgress> progressCallback);

    /**
     * Check if a model is available from the source.
     */
    boolean isAvailable(DownloadRequest request);
}
