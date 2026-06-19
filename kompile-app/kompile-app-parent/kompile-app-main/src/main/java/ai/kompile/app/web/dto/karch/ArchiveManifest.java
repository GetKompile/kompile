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
package ai.kompile.app.web.dto.karch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Manifest describing the contents of a Kompile Archive (.karch).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveManifest {
    @JsonProperty("formatVersion")
    public String formatVersion;

    @JsonProperty("archiveId")
    public String archiveId;

    @JsonProperty("contentVersion")
    public String contentVersion;

    @JsonProperty("description")
    public String description;

    @JsonProperty("releaseDate")
    public String releaseDate;

    @JsonProperty("models")
    public List<ArchiveModelEntry> models = new ArrayList<>();

    @JsonProperty("totalSizeBytes")
    public Long totalSizeBytes;
}
