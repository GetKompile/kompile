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

package ai.kompile.core.crawl.graph;

/**
 * Implemented by a long-lived service (e.g. the embedding model) whose work a crawl wants
 * attributed to a specific job, independent of {@link AgentCallContext}'s thread-locals.
 *
 * <p><b>Why this exists.</b> {@link AgentCallContext} carries the {@code jobId} on a plain
 * {@link ThreadLocal}, which does not propagate to the separate executor threads that actually
 * run embedding (edge/entity embedding pools, the shared crawl executor). As a result a
 * subprocess's events were emitted with a {@code null} jobId and never reached the owning crawl's
 * live UI. A crawl calls {@link #setActiveCrawlJobId} once at the start of a run (and clears it at
 * the end) so every event the service emits for the duration is attributed to that job — no
 * thread-local plumbing required.</p>
 */
public interface CrawlJobScoped {

    /**
     * Bind subsequent work to the given crawl job, or clear the binding.
     *
     * @param jobId the owning crawl job id; {@code null} or blank clears the binding
     */
    void setActiveCrawlJobId(String jobId);
}
