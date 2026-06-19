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

package ai.kompile.core.crawler.remote;

import java.time.Instant;
import java.util.List;

/**
 * Tracks the health of remote crawl provider connections.
 *
 * <p>Implementations record successive successes and failures per provider ID
 * (e.g. a connector instance name or OAuth client ID) and derive a simple
 * healthy/unhealthy verdict that higher-level code can consult before
 * attempting expensive remote operations.
 */
public interface ConnectionHealthMonitor {

    /**
     * Immutable snapshot of the health state for a single provider.
     *
     * @param providerId          the identifier of the remote crawl provider
     * @param healthy             {@code true} when the provider is considered healthy
     * @param lastCheckedAt       when the status was last evaluated
     * @param lastSuccessAt       when the most recent successful call was recorded,
     *                            or {@code null} if there has never been one
     * @param consecutiveFailures number of consecutive failures since the last success
     * @param message             optional human-readable explanation of the current state
     */
    record HealthStatus(
            String providerId,
            boolean healthy,
            Instant lastCheckedAt,
            Instant lastSuccessAt,
            int consecutiveFailures,
            String message
    ) {}

    /**
     * Returns the current health status for the given provider, computing it
     * from the recorded success/failure history if necessary.
     *
     * @param providerId the provider to inspect
     * @return a fresh {@link HealthStatus} snapshot; never {@code null}
     */
    HealthStatus checkHealth(String providerId);

    /**
     * Records a successful call to the named provider.
     * Resets the consecutive-failure counter and marks the provider healthy.
     *
     * @param providerId the provider that just succeeded
     */
    void recordSuccess(String providerId);

    /**
     * Records a failed call to the named provider.
     * Increments the consecutive-failure counter; may mark the provider
     * unhealthy if the configured threshold is crossed.
     *
     * @param providerId the provider that failed
     * @param reason     a short description of the failure; may be {@code null}
     */
    void recordFailure(String providerId, String reason);

    /**
     * Convenience predicate that returns {@code true} only when the provider
     * is currently considered healthy.
     *
     * @param providerId the provider to test
     * @return {@code true} if healthy
     */
    boolean isHealthy(String providerId);

    /**
     * Returns the current health status for every tracked provider.
     *
     * @return an unmodifiable snapshot list; empty when no providers have been seen yet
     */
    List<HealthStatus> getAllStatuses();
}
