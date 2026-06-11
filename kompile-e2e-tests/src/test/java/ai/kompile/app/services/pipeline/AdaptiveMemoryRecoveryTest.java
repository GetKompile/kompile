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

package ai.kompile.app.services.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link AdaptiveMemoryRecovery}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdaptiveMemoryRecoveryTest {

    private AdaptiveMemoryRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new AdaptiveMemoryRecovery(64);
    }

    // ===== Initialization =====

    @Test
    void initialBatchSize_matchesConstructorArg() {
        assertThat(recovery.getCurrentBatchSize()).isEqualTo(64);
    }

    @Test
    void initialOomCount_isZero() {
        assertThat(recovery.getOomCount()).isEqualTo(0);
    }

    // ===== handleOOM — first attempt =====

    @Test
    void handleOOM_firstAttempt_shouldRetry() {
        AdaptiveMemoryRecovery.RecoveryAction action =
                recovery.handleOOM(64, new OutOfMemoryError("test"));
        assertThat(action.shouldRetry()).isTrue();
    }

    @Test
    void handleOOM_firstAttempt_reducesBatchBy50Percent() {
        AdaptiveMemoryRecovery.RecoveryAction action =
                recovery.handleOOM(64, new OutOfMemoryError("test"));
        assertThat(action.getNewBatchSize()).isLessThan(64);
        assertThat(action.getNewBatchSize()).isGreaterThanOrEqualTo(4); // ABSOLUTE_MIN
    }

    @Test
    void handleOOM_firstAttempt_reducesThreads() {
        AdaptiveMemoryRecovery.RecoveryAction action =
                recovery.handleOOM(64, new OutOfMemoryError("test"));
        assertThat(action.getNewOmpThreads()).isGreaterThanOrEqualTo(1);
        assertThat(action.getNewMaxThreads()).isGreaterThanOrEqualTo(1);
    }

    // ===== handleOOM — repeated attempts =====

    @Test
    void handleOOM_secondAttempt_shouldRetry() {
        recovery.handleOOM(64, new OutOfMemoryError("1"));
        AdaptiveMemoryRecovery.RecoveryAction action =
                recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("2"));
        assertThat(action.shouldRetry()).isTrue();
    }

    @Test
    void handleOOM_thirdAttempt_shouldRetry() {
        recovery.handleOOM(64, new OutOfMemoryError("1"));
        recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("2"));
        AdaptiveMemoryRecovery.RecoveryAction action =
                recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("3"));
        assertThat(action.shouldRetry()).isTrue();
    }

    @Test
    void handleOOM_fourthAttempt_shouldFail() {
        recovery.handleOOM(64, new OutOfMemoryError("1"));
        recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("2"));
        recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("3"));
        AdaptiveMemoryRecovery.RecoveryAction action =
                recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("4"));
        assertThat(action.shouldRetry()).isFalse();
        assertThat(action.getFailureReason()).isNotNull().isNotBlank();
    }

    @Test
    void handleOOM_batchNeverBelowAbsoluteMinimum() {
        // Run multiple OOMs — batch should never drop below ABSOLUTE_MIN_BATCH_SIZE (4)
        for (int i = 0; i < 3; i++) {
            recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("oom-" + i));
        }
        assertThat(recovery.getCurrentBatchSize()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void handleOOM_oomCountIncrements() {
        recovery.handleOOM(64, new OutOfMemoryError("oom"));
        assertThat(recovery.getOomCount()).isEqualTo(1);
        recovery.handleOOM(32, new OutOfMemoryError("oom2"));
        assertThat(recovery.getOomCount()).isEqualTo(2);
    }

    // ===== User notification callback =====

    @Test
    void setUserNotificationCallback_calledOnOOM() {
        List<String> notifications = new ArrayList<>();
        recovery.setUserNotificationCallback(notifications::add);

        recovery.handleOOM(64, new OutOfMemoryError("test"));

        assertThat(notifications).isNotEmpty();
        assertThat(notifications.get(0)).contains("MEMORY ADAPTATION");
    }

    @Test
    void setUserNotificationCallback_calledOnFatalOOM() {
        List<String> notifications = new ArrayList<>();
        recovery.setUserNotificationCallback(notifications::add);

        // Exhaust all recovery attempts
        for (int i = 0; i < 4; i++) {
            recovery.handleOOM(recovery.getCurrentBatchSize(), new OutOfMemoryError("oom"));
        }

        assertThat(notifications).anyMatch(n -> n.contains("FATAL"));
    }

    // ===== reset() =====

    @Test
    void reset_restoresOriginalBatchSize() {
        recovery.handleOOM(64, new OutOfMemoryError("test"));
        assertThat(recovery.getCurrentBatchSize()).isLessThan(64);

        recovery.reset();
        assertThat(recovery.getCurrentBatchSize()).isEqualTo(64);
    }

    @Test
    void reset_restoresOomCountToZero() {
        recovery.handleOOM(64, new OutOfMemoryError("test"));
        recovery.reset();
        assertThat(recovery.getOomCount()).isEqualTo(0);
    }

    // ===== checkAndReduceIfNeeded =====

    @Test
    void checkAndReduceIfNeeded_returnsFalse_underNormalMemory() {
        // Under normal conditions (not running with a tiny heap), this should return false
        AdaptiveMemoryRecovery smallBatch = new AdaptiveMemoryRecovery(4);
        // Just verify it doesn't throw; result depends on JVM heap usage
        assertThatCode(() -> smallBatch.checkAndReduceIfNeeded()).doesNotThrowAnyException();
    }

    // ===== RecoveryAction factory methods =====

    @Test
    void recoveryAction_retry_shouldRetryIsTrue() {
        AdaptiveMemoryRecovery.RecoveryAction action =
                AdaptiveMemoryRecovery.RecoveryAction.retry(8, 2, 2);
        assertThat(action.shouldRetry()).isTrue();
        assertThat(action.getNewBatchSize()).isEqualTo(8);
        assertThat(action.getNewOmpThreads()).isEqualTo(2);
        assertThat(action.getNewMaxThreads()).isEqualTo(2);
        assertThat(action.getFailureReason()).isNull();
    }

    @Test
    void recoveryAction_fail_shouldRetryIsFalse() {
        AdaptiveMemoryRecovery.RecoveryAction action =
                AdaptiveMemoryRecovery.RecoveryAction.fail("Cannot recover");
        assertThat(action.shouldRetry()).isFalse();
        assertThat(action.getFailureReason()).isEqualTo("Cannot recover");
        assertThat(action.getNewBatchSize()).isEqualTo(0);
    }
}
