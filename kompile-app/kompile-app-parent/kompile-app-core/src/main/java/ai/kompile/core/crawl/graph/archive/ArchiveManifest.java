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

package ai.kompile.core.crawl.graph.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk manifest describing the archived steps of a unified-crawl job.
 *
 * <p>Lives at {@code {stateDir}/checkpoints/crawl-{jobId}/manifest.json} alongside per-step
 * sub-directories that each hold {@code chunks.jsonl} + {@code step-config.json}. The embedded
 * {@code progressSnapshot} is the verbatim {@code UnifiedCrawlJob.toProgressSnapshot()} captured at
 * archive time, so a job that has left memory can be rehydrated enough to resume its archived steps.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveManifest {

    /** Bumped if the on-disk layout changes; readers tolerate older versions where possible. */
    @Builder.Default
    private int schemaVersion = 1;

    private String jobId;
    private String name;
    private Long factSheetId;
    private String createdAt;
    private String updatedAt;

    /** Step IDs currently archived and awaiting a later run (drained as steps are resumed). */
    @Builder.Default
    private List<String> archivedSteps = new ArrayList<>();

    /** Per-step archive detail, keyed by step ID. */
    @Builder.Default
    private Map<String, StepEntry> steps = new LinkedHashMap<>();

    /** Latest {@code UnifiedCrawlJob.toProgressSnapshot()} for cross-restart rehydration. */
    private Object progressSnapshot;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepEntry {
        private String stepId;
        private int chunkCount;
        private String chunksFile;
        private String configFile;
        private String archivedAt;
    }
}
