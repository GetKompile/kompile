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

import java.util.Map;

/**
 * Request to import an archive
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveImportRequest {
    @JsonProperty("archivePath")
    public String archivePath;

    @JsonProperty("verifyChecksums")
    public boolean verifyChecksums = true;

    @JsonProperty("forceOverwrite")
    public boolean forceOverwrite = false;

    @JsonProperty("skipCompatibilityCheck")
    public boolean skipCompatibilityCheck = false;

    @JsonProperty("roleAssignments")
    public Map<String, String> roleAssignments; // modelId -> role (retrieval/reranking)
}
