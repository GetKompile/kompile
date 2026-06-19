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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceSnapshot.GpuSnapshot;
import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceGovernorTest {

    @Mock
    private ResourceTelemetryService telemetry;
    @Mock
    private ResourceSchedulerConfigService configService;
    @InjectMocks
    private ResourceGovernor governor;

    private final JobResourceProfile cpuJob = JobResourceProfile.cpuOnly("ingest", "Ingest", 0);
    private final JobResourceProfile gpuJob = JobResourceProfile.gpuRequired("vlm", "VLM", 0, 0);

    @BeforeEach
    void setUp() {
        // Real config (defaults: governor enabled). lenient — not every test reads it.
        lenient().when(configService.getConfiguration()).thenReturn(ResourceSchedulerConfig.defaults());
    }

    private ResourceSnapshot snap(double cpu, PressureLevel cpuP, double ram, PressureLevel ramP,
                                  double heap, boolean gpuAvail, double gpuUsed, PressureLevel gpuP) {
        List<GpuSnapshot> gpus = gpuAvail
                ? List.of(new GpuSnapshot(0, 0L, 1000L, 0L, gpuUsed, gpuP))
                : List.of();
        return new ResourceSnapshot(0L, cpu, ram, heap, 0.0,
                cpuP, ramP, PressureLevel.NOMINAL, PressureLevel.NOMINAL, gpus, gpuAvail);
    }

    @Test
    void admit_whenIdle() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.3, false, 0, PressureLevel.NOMINAL));
        assertTrue(governor.admitJob(cpuJob).admit());
    }

    @Test
    void defer_cpuBoundJob_onHighCpu() {
        when(telemetry.latest()).thenReturn(snap(0.92, PressureLevel.HIGH, 0.3, PressureLevel.NOMINAL,
                0.3, false, 0, PressureLevel.NOMINAL));
        ResourceGovernor.AdmissionResult r = governor.admitJob(cpuJob);
        assertFalse(r.admit());
        assertTrue(r.blockReason().contains("CPU"));
    }

    @Test
    void admit_gpuJob_evenOnHighCpu() {
        when(telemetry.latest()).thenReturn(snap(0.92, PressureLevel.HIGH, 0.3, PressureLevel.NOMINAL,
                0.3, true, 0.2, PressureLevel.NOMINAL));
        assertTrue(governor.admitJob(gpuJob).admit(), "GPU jobs block on GPU, not CPU");
    }

    @Test
    void defer_anyJob_onHighRam() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.9, PressureLevel.HIGH,
                0.3, true, 0.2, PressureLevel.NOMINAL));
        assertFalse(governor.admitJob(gpuJob).admit());
        assertFalse(governor.admitJob(cpuJob).admit());
    }

    @Test
    void admit_alwaysWhenGovernorDisabled() {
        ResourceSchedulerConfig disabled = ResourceSchedulerConfig.defaults();
        disabled.setGovernorEnabled(false);
        when(configService.getConfiguration()).thenReturn(disabled);
        // telemetry not consulted when disabled
        assertTrue(governor.admitJob(cpuJob).admit());
    }

    @Test
    void effectiveMemoryPressure_gpuStage_includesVram() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.50, true, 0.90, PressureLevel.HIGH));
        assertEquals(0.90, governor.effectiveMemoryPressure("EMBEDDING"), 1e-9);
    }

    @Test
    void effectiveMemoryPressure_cpuStage_ignoresVram() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.55, true, 0.90, PressureLevel.HIGH));
        assertEquals(0.55, governor.effectiveMemoryPressure("GRAPH_EXTRACTION"), 1e-9);
    }

    @Test
    void effectiveMemoryPressure_cpuBackend_usesHeapOnly() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.42, false, 0.0, PressureLevel.NOMINAL));
        assertEquals(0.42, governor.effectiveMemoryPressure("EMBEDDING"), 1e-9);
    }

    @Test
    void effectiveMemoryPressure_isAlwaysAFraction() {
        // Regression guard for the 0..100 vs 0..1 unit bug the batch sizers now avoid.
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.7, true, 0.95, PressureLevel.CRITICAL));
        double p = governor.effectiveMemoryPressure("EMBEDDING");
        assertTrue(p >= 0.0 && p <= 1.0, "must be a 0..1 fraction, was " + p);
    }

    @Test
    void shouldDeferLocalWork_onlyAtCriticalVram() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.3, true, 0.95, PressureLevel.CRITICAL));
        assertTrue(governor.shouldDeferLocalWork("EMBEDDING"));

        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.3, true, 0.86, PressureLevel.HIGH));
        assertFalse(governor.shouldDeferLocalWork("EMBEDDING"), "HIGH still proceeds (smaller batches)");
    }

    @Test
    void hasGpuHeadroom_falseOnHigh_trueOnCpuBackend() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.3, true, 0.90, PressureLevel.HIGH));
        assertFalse(governor.hasGpuHeadroom("EMBEDDING"));

        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.3, false, 0.0, PressureLevel.NOMINAL));
        assertTrue(governor.hasGpuHeadroom("EMBEDDING"), "CPU embedding always has headroom");
    }

    @Test
    void isGpuVramPressured_respectsCriticalFlag() {
        when(telemetry.latest()).thenReturn(snap(0.1, PressureLevel.NOMINAL, 0.3, PressureLevel.NOMINAL,
                0.3, true, 0.88, PressureLevel.HIGH));
        assertTrue(governor.isGpuVramPressured(false));   // HIGH >= HIGH
        assertFalse(governor.isGpuVramPressured(true));   // HIGH < CRITICAL
    }
}
