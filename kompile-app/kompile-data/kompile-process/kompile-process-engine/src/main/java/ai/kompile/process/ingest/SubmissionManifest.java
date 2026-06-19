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

package ai.kompile.process.ingest;

import ai.kompile.process.hitl.Assumption;
import ai.kompile.process.hitl.CellExclusion;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tracks which file version is authoritative, what exclusions apply,
 * and what pre-processing normalizations are needed.
 * Addresses the "v3_FINAL_v2" problem revealed by email analysis:
 * multiple versions of a file arrive and a human must assert which is canonical.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubmissionManifest {

    private String id;
    private String workflowRunId;
    /** Submitting region identifier, e.g., "AMER", "EMEA", "APAC". */
    private String sourceRegion;
    /** File ID of the single authoritative version for this submission cycle. */
    private String authoritativeFileId;
    private String authoritativeFileName;
    /** SHA-256 hash of the authoritative file contents. */
    private String fileContentHash;
    /** Person who declared which file is authoritative (by name or user ID). */
    private String versionAssertedBy;
    /** Channel through which the assertion was made: "email", "slack", "system". */
    private String versionAssertionSource;
    private Instant receivedAt;
    /** Cell/sheet ranges excluded from processing per pre-read instructions. */
    private List<CellExclusion> exclusions;
    /** Normalizations to apply before triage begins. */
    private List<NormalizationRule> normalizations;
    /** Risk assumptions embedded in the submission (extracted from pre-read notes). */
    private List<Assumption> embeddedAssumptions;
    private Map<String, Object> metadata;
}
