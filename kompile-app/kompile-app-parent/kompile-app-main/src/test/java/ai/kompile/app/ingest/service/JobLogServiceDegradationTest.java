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

package ai.kompile.app.ingest.service;

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.repository.JobLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.PessimisticLockingFailureException;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies a job-log DB failure (e.g. H2 lock timeout / index corruption) NEVER propagates to the
 * caller (crawl) — it degrades to the application log instead. This is the "proper degrading"
 * robustness requirement: a logging hiccup must not fail an end-to-end crawl.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobLogServiceDegradationTest {

    @Mock private JobLogRepository repository;
    private JobLogService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new JobLogService(repository);
        // @Value-injected in production; force-enable for the unit test.
        Field enabled = JobLogService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.set(service, true);
    }

    @Test
    void writeFailureDegradesInsteadOfThrowing() {
        when(repository.findMaxSequenceNumber(anyString())).thenReturn(0L);
        when(repository.save(any()))
                .thenThrow(new PessimisticLockingFailureException("Timeout trying to lock table / corrupt index"));

        // None of these may throw — a job-log DB failure must not fail the crawl.
        assertDoesNotThrow(() -> service.logStdout("task-1", "progress line"));
        assertDoesNotThrow(() -> service.logError("task-1", "boom", new RuntimeException("x"), "L", "T"));
        assertDoesNotThrow(() -> service.logSystem("task-1", JobLogEntry.LogLevel.INFO, "sys"));

        // It DID attempt to persist (then degraded to the app log).
        verify(repository, atLeastOnce()).save(any());
    }

    @Test
    void sequenceReadFailureDoesNotThrow() {
        // Even the sequence-number read can hit the corrupted index — must fall back, not throw.
        when(repository.findMaxSequenceNumber(anyString()))
                .thenThrow(new PessimisticLockingFailureException("corrupt index on read"));
        when(repository.save(any())).thenReturn(null);

        assertDoesNotThrow(() -> service.logStdout("task-2", "line after read failure"));
    }

    @Test
    void circuitBreakerStopsHittingDbAfterFailure() {
        // The pool-protection behavior: once the DB fails, the circuit opens and subsequent log
        // calls must NOT touch the DB again (otherwise each would hold a connection through the
        // ~30s lock-timeout and drain the Hikari pool — the observed app-death cascade).
        when(repository.findMaxSequenceNumber(anyString())).thenReturn(0L);
        when(repository.save(any())).thenThrow(new PessimisticLockingFailureException("lock timeout"));

        assertDoesNotThrow(() -> service.logStdout("t", "1")); // fails → opens circuit
        assertDoesNotThrow(() -> service.logStdout("t", "2")); // circuit open → skip DB
        assertDoesNotThrow(() -> service.logStdout("t", "3")); // circuit open → skip DB

        // save() was attempted exactly ONCE; the open circuit skipped the rest.
        verify(repository, times(1)).save(any());
    }

    @Test
    void batchWriteFailureDegradesInsteadOfThrowing() {
        when(repository.findMaxSequenceNumber(anyString())).thenReturn(0L);
        when(repository.saveAll(any()))
                .thenThrow(new PessimisticLockingFailureException("batch insert failed"));

        assertDoesNotThrow(() -> service.logBatch("task-3",
                List.of(JobLogEntry.stdout("task-3", "a", 1), JobLogEntry.stdout("task-3", "b", 2))));
    }
}
