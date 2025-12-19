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

import java.util.List;

/**
 * Request DTO for exporting models to a Kompile archive (.karch).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveExportRequest {

    /**
     * Model IDs to export.
     */
    private List<String> modelIds;

    /**
     * Output path for the archive file.
     */
    private String outputPath;

    /**
     * Archive identifier.
     */
    private String archiveId;

    /**
     * Archive version (e.g., "1.0.0").
     */
    private String version;

    /**
     * Archive description.
     */
    private String description;

    /**
     * Export all models in the registry.
     */
    private boolean exportAll;

    /**
     * Publisher name.
     */
    private String publisherName;

    /**
     * Publisher URL.
     */
    private String publisherUrl;

    /**
     * Minimum Kompile version required.
     */
    private String minKompileVersion;

    /**
     * Include README file in archive.
     */
    private boolean includeReadme;

    /**
     * Include changelog file in archive.
     */
    private boolean includeChangelog;
}
