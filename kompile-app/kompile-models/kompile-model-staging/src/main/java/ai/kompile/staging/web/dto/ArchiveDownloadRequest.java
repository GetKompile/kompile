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

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for downloading a Kompile archive from a URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveDownloadRequest {

    /**
     * URL to download the archive from.
     */
    private String url;

    /**
     * Output directory for the download.
     */
    private String destinationDir;

    /**
     * Enable resume for interrupted downloads.
     */
    @Builder.Default
    private boolean resumeEnabled = true;

    /**
     * Verify checksum after download.
     */
    @Builder.Default
    private boolean verifyChecksum = true;

    /**
     * Expected SHA256 checksum.
     */
    private String expectedChecksum;

    /**
     * Automatically import after download.
     */
    @Builder.Default
    private boolean autoImport = true;

    /**
     * Force overwrite existing models on import.
     */
    private boolean forceOverwrite;
}
