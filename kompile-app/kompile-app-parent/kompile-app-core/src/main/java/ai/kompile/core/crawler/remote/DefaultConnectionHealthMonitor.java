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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation of {@link ConnectionHealthMonitor}.
 *
 * <p>Health state is tracked per provider ID in a {@link ConcurrentHashMap}.
 * A provider is marked <em>unhealthy</em> after {@value #FAILURE_THRESHOLD}
 * consecutive failures, and becomes healthy again on the very first success.
 */
@Slf4j
@Component
public class DefaultConnectionHealthMonitor implements ConnectionHealthMonitor {

    /** Number of consecutive failures required to declare a provider unhealthy. */
    private static final int FAILURE_THRESHOLD = 3;

    // -------------------------------------------------------------------------
    // Internal mutable state – one entry per provider ID
    // -------------------------------------------------------------------------

    private static final class ProviderState {
        volatile boolean healthy = true;
        volatile Instant lastCheckedAt = Instant.now();
        volatile Instant lastSuccessAt = null;
        volatile int consecutiveFailures = 0;
        volatile String lastMessage = "No activity recorded yet";
    }

    private final ConcurrentHashMap<String, ProviderState> states = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // ConnectionHealthMonitor implementation
    // -------------------------------------------------------------------------

    @Override
    public HealthStatus checkHealth(String providerId) {
        ProviderState s = stateFor(providerId);
        return toStatus(providerId, s);
    }

    @Override
    public void recordSuccess(String providerId) {
        ProviderState s = stateFor(providerId);
        synchronized (s) {
            boolean wasUnhealthy = !s.healthy;
            s.healthy = true;
            s.consecutiveFailures = 0;
            s.lastSuccessAt = Instant.now();
            s.lastCheckedAt = s.lastSuccessAt;
            s.lastMessage = "Last call succeeded";
            if (wasUnhealthy) {
                log.info("Provider '{}' recovered — marking healthy", providerId);
            } else {
                log.debug("Recorded success for provider '{}'", providerId);
            }
        }
    }

    @Override
    public void recordFailure(String providerId, String reason) {
        ProviderState s = stateFor(providerId);
        synchronized (s) {
            s.consecutiveFailures++;
            s.lastCheckedAt = Instant.now();
            s.lastMessage = reason != null ? reason : "Unknown failure";
            if (s.consecutiveFailures >= FAILURE_THRESHOLD && s.healthy) {
                s.healthy = false;
                log.warn("Provider '{}' marked UNHEALTHY after {} consecutive failures. Last reason: {}",
                        providerId, s.consecutiveFailures, s.lastMessage);
            } else {
                log.debug("Recorded failure #{} for provider '{}': {}",
                        s.consecutiveFailures, providerId, s.lastMessage);
            }
        }
    }

    @Override
    public boolean isHealthy(String providerId) {
        return stateFor(providerId).healthy;
    }

    @Override
    public List<HealthStatus> getAllStatuses() {
        if (states.isEmpty()) {
            return Collections.emptyList();
        }
        List<HealthStatus> result = new ArrayList<>(states.size());
        states.forEach((id, s) -> result.add(toStatus(id, s)));
        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProviderState stateFor(String providerId) {
        return states.computeIfAbsent(providerId, id -> new ProviderState());
    }

    private static HealthStatus toStatus(String providerId, ProviderState s) {
        return new HealthStatus(
                providerId,
                s.healthy,
                s.lastCheckedAt,
                s.lastSuccessAt,
                s.consecutiveFailures,
                s.lastMessage
        );
    }
}
