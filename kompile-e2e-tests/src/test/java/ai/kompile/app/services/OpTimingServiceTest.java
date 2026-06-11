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

package ai.kompile.app.services;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpTimingServiceTest {

    @Mock
    private Nd4jEnvironmentConfigService configService;

    private OpTimingService service;

    @BeforeEach
    void setUp() {
        Nd4jEnvironmentConfig config = Nd4jEnvironmentConfig.builder()
                .profiling(false)
                .build();
        when(configService.getConfiguration()).thenReturn(config);

        service = new OpTimingService(configService, null);
        // Skip init() since it tries to call NativeOps on an ND4J backend
    }

    @Test
    void isProfilingEnabled_falseByDefault() {
        assertThat(service.isProfilingEnabled()).isFalse();
    }

    @Test
    void recordSubprocessStart_addsActiveTiming() {
        service.recordSubprocessStart("task-1", "EMBEDDING");

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        assertThat((Integer) timings.get("activeCount")).isEqualTo(1);
    }

    @Test
    void recordSubprocessStartupComplete_setsStartupDuration() {
        service.recordSubprocessStart("task-2", "INGEST");
        service.recordSubprocessStartupComplete("task-2");

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        @SuppressWarnings("unchecked")
        var active = (java.util.List<Map<String, Object>>) timings.get("active");
        assertThat(active).hasSize(1);
        double startup = (Double) active.get(0).get("startupDurationMs");
        assertThat(startup).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void recordModelLoadStart_setsModelId() {
        service.recordSubprocessStart("task-3", "MODEL_INIT");
        service.recordModelLoadStart("task-3", "bge-base-en-v1.5");

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        @SuppressWarnings("unchecked")
        var active = (java.util.List<Map<String, Object>>) timings.get("active");
        assertThat(active.get(0).get("modelId")).isEqualTo("bge-base-en-v1.5");
    }

    @Test
    void recordSubprocessComplete_removesFromActive_addsToHistory() {
        service.recordSubprocessStart("task-4", "EMBEDDING");
        service.recordSubprocessStartupComplete("task-4");
        service.recordModelLoadStart("task-4", "model-x");
        service.recordModelLoadComplete("task-4");
        service.recordSubprocessComplete("task-4", true);

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        assertThat((Integer) timings.get("activeCount")).isZero();

        Map<String, Object> history = service.getSubprocessTimingHistory(10);
        assertThat((Integer) history.get("count")).isEqualTo(1);
    }

    @Test
    void recordIpcSend_incrementsCounter() {
        service.recordSubprocessStart("task-5", "EMBEDDING");
        service.recordIpcSend("task-5", "EMBED_REQUEST", 100_000L);
        service.recordIpcSend("task-5", "EMBED_REQUEST", 200_000L);

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        @SuppressWarnings("unchecked")
        var active = (java.util.List<Map<String, Object>>) timings.get("active");
        assertThat(active.get(0).get("ipcSendCount")).isEqualTo(2L);
    }

    @Test
    void recordIpcReceive_incrementsCounter() {
        service.recordSubprocessStart("task-6", "EMBEDDING");
        service.recordIpcReceive("task-6", "EMBED_RESPONSE", 150_000L);

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        @SuppressWarnings("unchecked")
        var active = (java.util.List<Map<String, Object>>) timings.get("active");
        assertThat(active.get(0).get("ipcReceiveCount")).isEqualTo(1L);
    }

    @Test
    void getActiveSubprocessTimings_multipleActive() {
        service.recordSubprocessStart("task-a", "EMBEDDING");
        service.recordSubprocessStart("task-b", "INGEST");
        service.recordSubprocessStart("task-c", "MODEL_INIT");

        Map<String, Object> timings = service.getActiveSubprocessTimings();
        assertThat((Integer) timings.get("activeCount")).isEqualTo(3);
    }

    @Test
    void clearSubprocessTimingHistory_emptiesHistory() {
        service.recordSubprocessStart("task-7", "EMBEDDING");
        service.recordSubprocessComplete("task-7", true);

        service.clearSubprocessTimingHistory();

        Map<String, Object> history = service.getSubprocessTimingHistory(10);
        assertThat((Integer) history.get("count")).isZero();
    }

    @Test
    void getSubprocessTimingHistory_limitedByParameter() {
        for (int i = 0; i < 5; i++) {
            String id = "hist-task-" + i;
            service.recordSubprocessStart(id, "EMBEDDING");
            service.recordSubprocessComplete(id, true);
        }

        Map<String, Object> history = service.getSubprocessTimingHistory(3);
        assertThat((Integer) history.get("count")).isEqualTo(3);
    }

    @Test
    void getSubprocessTimingHistory_containsAggregates() {
        service.recordSubprocessStart("agg-task", "EMBEDDING");
        service.recordSubprocessComplete("agg-task", true);

        Map<String, Object> history = service.getSubprocessTimingHistory(10);
        assertThat(history).containsKey("aggregates");
        @SuppressWarnings("unchecked")
        Map<String, Object> aggregates = (Map<String, Object>) history.get("aggregates");
        assertThat(aggregates).containsKeys(
                "avgStartupMs", "avgModelLoadMs", "avgTotalDurationMs",
                "avgTotalOverheadMs", "successRate"
        );
    }

    @Test
    void recordSubprocessComplete_failed_recordsSuccess_false() {
        service.recordSubprocessStart("fail-task", "INGEST");
        service.recordSubprocessComplete("fail-task", false);

        Map<String, Object> history = service.getSubprocessTimingHistory(1);
        @SuppressWarnings("unchecked")
        var histList = (java.util.List<OpTimingService.SubprocessTimingStat>) history.get("history");
        assertThat(histList.get(0).isSuccess()).isFalse();
    }

    @Test
    void recordSubprocessStart_forUnknownTask_doesNotThrow() {
        // recording for a task that was never started should not fail
        service.recordSubprocessStartupComplete("never-started");
        service.recordModelLoadStart("never-started", "model");
        service.recordModelLoadComplete("never-started");
        service.recordIpcSend("never-started", "type", 100L);
        service.recordIpcReceive("never-started", "type", 100L);
    }

    @Test
    void opTimingStat_getters_returnCorrectValues() {
        OpTimingService.OpTimingStat stat = new OpTimingService.OpTimingStat();
        stat.rank = 1;
        stat.opName = "matmul";
        stat.calls = 100L;
        stat.totalMs = 50.0;
        stat.avgUs = 500.0;
        stat.stdDevUs = 10.0;
        stat.minUs = 480.0;
        stat.maxUs = 520.0;
        stat.helperPercent = 5.0;

        assertThat(stat.getRank()).isEqualTo(1);
        assertThat(stat.getOpName()).isEqualTo("matmul");
        assertThat(stat.getCalls()).isEqualTo(100L);
        assertThat(stat.getTotalMs()).isEqualTo(50.0);
        assertThat(stat.getAvgUs()).isEqualTo(500.0);
    }
}
