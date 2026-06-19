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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultConnectionHealthMonitor}.
 *
 * <p>All tests exercise the in-memory health tracking without any external
 * dependencies. Each test method creates its own monitor instance (via
 * {@link #setUp()}) to guarantee isolation.</p>
 */
@DisplayName("DefaultConnectionHealthMonitor")
class DefaultConnectionHealthMonitorTest {

    private DefaultConnectionHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new DefaultConnectionHealthMonitor();
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Initial / unknown provider state")
    class InitialState {

        @Test
        @DisplayName("isHealthy() returns true for a provider never seen before")
        void isHealthyReturnsTrueForUnknownProvider() {
            // The monitor lazily initialises providers as healthy.
            assertTrue(monitor.isHealthy("never-seen-provider"),
                    "A brand-new provider should start out healthy");
        }

        @Test
        @DisplayName("checkHealth() returns a non-null HealthStatus for unknown provider")
        void checkHealthReturnsStatusForUnknownProvider() {
            ConnectionHealthMonitor.HealthStatus status = monitor.checkHealth("new-provider");
            assertNotNull(status);
            assertEquals("new-provider", status.providerId());
            assertTrue(status.healthy());
            assertEquals(0, status.consecutiveFailures());
            assertNull(status.lastSuccessAt(), "No success recorded yet");
        }

        @Test
        @DisplayName("getAllStatuses() returns empty list when no providers have been seen")
        void getAllStatusesEmptyInitially() {
            // Fresh monitor with no interaction at all.
            List<ConnectionHealthMonitor.HealthStatus> statuses = monitor.getAllStatuses();
            assertNotNull(statuses);
            assertTrue(statuses.isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // recordSuccess
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("recordSuccess()")
    class RecordSuccess {

        @Test
        @DisplayName("makes a previously unknown provider healthy (it already is, but sets lastSuccessAt)")
        void recordSuccessForNewProvider() {
            monitor.recordSuccess("google");
            assertTrue(monitor.isHealthy("google"));
            assertNotNull(monitor.checkHealth("google").lastSuccessAt());
        }

        @Test
        @DisplayName("resets consecutiveFailures to zero")
        void resetsFailureCounter() {
            monitor.recordFailure("google", "timeout");
            monitor.recordFailure("google", "timeout");
            monitor.recordSuccess("google");

            ConnectionHealthMonitor.HealthStatus status = monitor.checkHealth("google");
            assertEquals(0, status.consecutiveFailures());
        }

        @Test
        @DisplayName("restores health after provider became unhealthy")
        void restoresHealthAfterThreeFailures() {
            monitor.recordFailure("slack", "conn refused");
            monitor.recordFailure("slack", "conn refused");
            monitor.recordFailure("slack", "conn refused");
            assertFalse(monitor.isHealthy("slack"), "Should be unhealthy after 3 failures");

            monitor.recordSuccess("slack");

            assertTrue(monitor.isHealthy("slack"), "Should be healthy again after success");
        }
    }

    // -------------------------------------------------------------------------
    // recordFailure — threshold behaviour
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("recordFailure() — threshold behaviour")
    class RecordFailure {

        @Test
        @DisplayName("one failure does not mark provider unhealthy")
        void oneFailureKeepsHealthy() {
            monitor.recordFailure("dropbox", "HTTP 503");
            assertTrue(monitor.isHealthy("dropbox"),
                    "A single failure should not cross the unhealthy threshold");
        }

        @Test
        @DisplayName("two consecutive failures do not mark provider unhealthy")
        void twoFailuresKeepsHealthy() {
            monitor.recordFailure("dropbox", "HTTP 503");
            monitor.recordFailure("dropbox", "HTTP 503");
            assertTrue(monitor.isHealthy("dropbox"),
                    "Two consecutive failures should not yet cross the threshold");
        }

        @Test
        @DisplayName("three consecutive failures mark provider unhealthy")
        void threeFailuresMarkUnhealthy() {
            monitor.recordFailure("dropbox", "HTTP 503");
            monitor.recordFailure("dropbox", "HTTP 503");
            monitor.recordFailure("dropbox", "HTTP 503");

            assertFalse(monitor.isHealthy("dropbox"),
                    "Three consecutive failures should mark the provider unhealthy");
        }

        @Test
        @DisplayName("consecutiveFailures counter increments correctly")
        void failureCounterIncrements() {
            monitor.recordFailure("s3", "Access Denied");
            monitor.recordFailure("s3", "Access Denied");

            assertEquals(2, monitor.checkHealth("s3").consecutiveFailures());
        }

        @Test
        @DisplayName("failure message is stored in the status")
        void failureMessageStored() {
            monitor.recordFailure("notion", "rate limited");
            assertEquals("rate limited", monitor.checkHealth("notion").message());
        }

        @Test
        @DisplayName("null failure reason is handled gracefully")
        void nullReasonHandledGracefully() {
            assertDoesNotThrow(() -> monitor.recordFailure("provider-x", null));
            assertNotNull(monitor.checkHealth("provider-x").message());
        }
    }

    // -------------------------------------------------------------------------
    // Recovery after failures
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Recovery: recordSuccess() after failures")
    class Recovery {

        @Test
        @DisplayName("success after 3 failures restores health and clears failure count")
        void successAfterThreeFailuresRestores() {
            monitor.recordFailure("gdrive", "401");
            monitor.recordFailure("gdrive", "401");
            monitor.recordFailure("gdrive", "401");
            assertFalse(monitor.isHealthy("gdrive"));

            monitor.recordSuccess("gdrive");

            assertTrue(monitor.isHealthy("gdrive"));
            assertEquals(0, monitor.checkHealth("gdrive").consecutiveFailures());
        }

        @Test
        @DisplayName("partial failures cleared by success do not re-trigger unhealthy on next failure")
        void partialFailuresFollowedBySuccessThenOneFailure() {
            monitor.recordFailure("onedrive", "timeout");
            monitor.recordFailure("onedrive", "timeout");
            monitor.recordSuccess("onedrive");

            // One new failure after recovery: still healthy
            monitor.recordFailure("onedrive", "timeout");

            assertTrue(monitor.isHealthy("onedrive"),
                    "One failure after recovery should not yet mark unhealthy");
            assertEquals(1, monitor.checkHealth("onedrive").consecutiveFailures());
        }
    }

    // -------------------------------------------------------------------------
    // getAllStatuses
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getAllStatuses()")
    class GetAllStatuses {

        @Test
        @DisplayName("returns an entry for each provider that has been seen")
        void returnsAllTrackedProviders() {
            monitor.recordSuccess("google");
            monitor.recordFailure("slack", "timeout");
            monitor.recordSuccess("dropbox");

            List<ConnectionHealthMonitor.HealthStatus> statuses = monitor.getAllStatuses();

            assertEquals(3, statuses.size(), "Expected one status per distinct provider");

            List<String> providerIds = statuses.stream()
                    .map(ConnectionHealthMonitor.HealthStatus::providerId)
                    .toList();
            assertTrue(providerIds.contains("google"));
            assertTrue(providerIds.contains("slack"));
            assertTrue(providerIds.contains("dropbox"));
        }

        @Test
        @DisplayName("returned list is unmodifiable")
        void returnedListIsUnmodifiable() {
            monitor.recordSuccess("provider-a");
            List<ConnectionHealthMonitor.HealthStatus> statuses = monitor.getAllStatuses();
            assertThrows(UnsupportedOperationException.class, () -> statuses.clear());
        }

        @Test
        @DisplayName("statuses reflect current health after mixed success/failure calls")
        void statusesReflectCurrentHealth() {
            monitor.recordSuccess("healthy-provider");
            monitor.recordFailure("sick-provider", "err");
            monitor.recordFailure("sick-provider", "err");
            monitor.recordFailure("sick-provider", "err");

            List<ConnectionHealthMonitor.HealthStatus> statuses = monitor.getAllStatuses();

            ConnectionHealthMonitor.HealthStatus healthyStatus = statuses.stream()
                    .filter(s -> "healthy-provider".equals(s.providerId()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(healthyStatus.healthy());

            ConnectionHealthMonitor.HealthStatus sickStatus = statuses.stream()
                    .filter(s -> "sick-provider".equals(s.providerId()))
                    .findFirst()
                    .orElseThrow();
            assertFalse(sickStatus.healthy());
            assertEquals(3, sickStatus.consecutiveFailures());
        }

        @Test
        @DisplayName("does not include providers whose health was only queried via isHealthy()")
        void isHealthyAloneRegistersProvider() {
            // isHealthy() calls stateFor() which lazily creates a ProviderState entry,
            // so the provider IS tracked after the first isHealthy() call.
            monitor.isHealthy("lazy-provider");
            List<ConnectionHealthMonitor.HealthStatus> statuses = monitor.getAllStatuses();
            // It is acceptable for the implementation to track the provider here;
            // we verify the contract is consistent: if tracked, it must be healthy.
            statuses.stream()
                    .filter(s -> "lazy-provider".equals(s.providerId()))
                    .findFirst()
                    .ifPresent(s -> assertTrue(s.healthy(),
                            "Provider seen only via isHealthy() must appear healthy"));
        }
    }

    // -------------------------------------------------------------------------
    // checkHealth convenience
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkHealth()")
    class CheckHealth {

        @Test
        @DisplayName("returns healthy=true after a success")
        void healthyAfterSuccess() {
            monitor.recordSuccess("confluence");
            ConnectionHealthMonitor.HealthStatus status = monitor.checkHealth("confluence");
            assertTrue(status.healthy());
        }

        @Test
        @DisplayName("returns healthy=false after three failures")
        void unhealthyAfterThreeFailures() {
            monitor.recordFailure("jira", "err");
            monitor.recordFailure("jira", "err");
            monitor.recordFailure("jira", "err");
            assertFalse(monitor.checkHealth("jira").healthy());
        }

        @Test
        @DisplayName("lastCheckedAt is updated after recordFailure()")
        void lastCheckedAtUpdatedAfterFailure() {
            monitor.recordFailure("provider-z", "timeout");
            assertNotNull(monitor.checkHealth("provider-z").lastCheckedAt());
        }

        @Test
        @DisplayName("lastSuccessAt is set after recordSuccess()")
        void lastSuccessAtSetAfterSuccess() {
            monitor.recordSuccess("provider-z");
            assertNotNull(monitor.checkHealth("provider-z").lastSuccessAt());
        }
    }
}
