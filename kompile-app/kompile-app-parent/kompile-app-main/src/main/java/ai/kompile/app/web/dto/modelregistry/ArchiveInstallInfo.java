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
package ai.kompile.app.web.dto.modelregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Information about an installed archive for provenance tracking.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveInstallInfo {
    @JsonProperty("archive_id")
    public String archiveId;

    @JsonProperty("archive_name")
    public String archiveName;

    @JsonProperty("version")
    public String version;

    @JsonProperty("installed_at")
    public String installedAt;

    @JsonProperty("source_url")
    public String sourceUrl;

    @JsonProperty("checksum")
    public String checksum;

    @JsonProperty("model_ids")
    public List<String> modelIds = new ArrayList<>();
}
