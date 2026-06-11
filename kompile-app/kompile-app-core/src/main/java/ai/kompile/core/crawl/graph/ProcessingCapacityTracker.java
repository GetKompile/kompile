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

import java.util.List;
import java.util.Optional;

/**
 * Tracks real-time processing capacity across configured backends and selects
 * the best available backend for a given task.
 *
 * <p>Integrates with GPU resource management, agent registry, and API rate
 * limiters to make dynamic routing decisions. Used by the unified crawl pipeline
 * to fall back from local models to CLI/API agents when capacity is exhausted.</p>
 */
public interface ProcessingCapacityTracker {

    /**
     * Select the best available backend for the given task type from the configured chain.
     *
     * @param taskType the type of processing task ("vlm", "llm", "embedding")
     * @param config   the routing config for the current crawl job
     * @return the selected backend, or empty if no backend has capacity
     */
    Optional<ProcessingRouteConfig.ProcessingBackend> selectBackend(
            String taskType, ProcessingRouteConfig config);

    /**
     * Check if a specific backend can accept work right now.
     *
     * @param backendId the backend identifier
     * @param taskType  the type of processing task
     * @return true if the backend has capacity
     */
    boolean canAccept(String backendId, String taskType);

    /**
     * Record that a request was dispatched to a backend (increments active count).
     *
     * @param backendId the backend that received the request
     * @param taskType  the type of processing task
     */
    void recordDispatch(String backendId, String taskType);

    /**
     * Record that a request completed on a backend (decrements active count).
     *
     * @param backendId the backend that completed the request
     * @param taskType  the type of processing task
     * @param success   whether the request succeeded
     */
    void recordCompletion(String backendId, String taskType, boolean success);

    /**
     * Get a snapshot of current capacity across all configured backends.
     *
     * @param config the routing config to evaluate
     * @return capacity snapshots for each backend
     */
    List<ProcessingRouteConfig.CapacitySnapshot> getCapacitySnapshot(ProcessingRouteConfig config);
}
