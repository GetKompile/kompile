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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * Durable persistence for archived unified-crawl pipeline steps.
 *
 * <p>This is the storage half of the modular-crawl feature: it writes a step's inputs (chunked
 * documents + config) and a manifest to disk, and records resumable pointers in the job-history
 * store. Step <em>execution</em> (re-running an archived step) stays in the crawl-graph service where
 * the embedding / graph helpers live; this service only loads the archived inputs back.</p>
 *
 * <p>The interface lives in {@code kompile-app-core} so the crawl-graph service can depend on it; the
 * implementation lives in {@code kompile-app-main} (the composition root) where the job-history JPA
 * layer is available. It is injected as an optional bean — when absent, archiving is a no-op and the
 * pipeline behaves exactly as before.</p>
 */
public interface CrawlStepArchiveService {

    /**
     * Persist a step's inputs (chunks + config) plus a manifest to disk and mark the owning job
     * resumable in the history store.
     *
     * @param job    the live job being archived
     * @param stepId canonical step ID (e.g. {@code "VECTOR_INDEXING"}, {@code "GRAPH_EXTRACTION"})
     * @param chunks the documents the step would have consumed (may be empty for graph-state steps)
     * @param config the step's config object (e.g. {@code VectorIndexConfig}) — serialized as-is
     * @return absolute path of the archive directory, or {@code null} on failure
     */
    String archive(UnifiedCrawlJob job, String stepId, List<Document> chunks, Object config);

    /**
     * Load a previously archived step's chunks and raw config JSON.
     *
     * @return the archived data, or {@code null} if no archive exists for this job/step
     */
    ArchivedStepData load(String jobId, String stepId);

    /**
     * Mark an archived step as completed: drop it from the manifest's archived list and, when nothing
     * remains archived, clear the job's resumable flag in the history store.
     */
    void markStepCompleted(String jobId, String stepId);

    /**
     * Re-flush the job's latest progress snapshot into its manifest. Invoked by the periodic history
     * sync so a still-running job that has archived steps stays durably resumable after a crash.
     */
    void refreshManifest(UnifiedCrawlJob job);

    /** List crawl jobs that have archived steps on disk and can be resumed. */
    List<ResumableCrawlJob> listResumableCrawlJobs();

    /**
     * Read the persisted snapshot for a job that may no longer be in memory, so it can be rehydrated
     * enough to resume its archived steps. Returns {@code null} if no manifest/history snapshot exists.
     */
    ArchivedJobSnapshot loadSnapshot(String jobId);

    /** Chunks plus raw config JSON for an archived step. */
    record ArchivedStepData(List<Document> chunks, String configJson) {}

    /** Summary of a resumable crawl job for listing in the UI / CLI. */
    record ResumableCrawlJob(
            String jobId,
            String name,
            Long factSheetId,
            List<String> archivedSteps,
            String archivedAt,
            String archiveDir) {}

    /** Minimal persisted state needed to rehydrate a job that has left memory. */
    record ArchivedJobSnapshot(
            String jobId,
            String name,
            Long factSheetId,
            List<String> archivedSteps,
            Map<String, Object> rawSnapshot) {}
}
