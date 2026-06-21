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

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.DeviceRoutingConfig.ServiceDeviceConfig;
import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceSnapshot.GpuSnapshot;
import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GpuToCpuMigrationService}'s decision core. The poller-driven clock is bypassed by
 * calling {@code evaluate(snapshot, config, nowNanos)} directly with controlled timestamps, so the
 * sustained-window + CPU-headroom policy is verified deterministically.
 */
@ExtendWith(MockitoExtension.class)
class GpuToCpuMigrationServiceTest {

    @Mock
    private ResourceTelemetryService telemetry;
    @Mock
    private ResourceSchedulerConfigService configService;
    @Mock
    private DeviceRoutingConfigService deviceRouting;
    @InjectMocks
    private GpuToCpuMigrationService service;

    /** Non-zero clock origin so the test is independent of nanoTime semantics. */
    private static final long BASE = TimeUnit.SECONDS.toNanos(1_000);
    private static final List<String> STAGES = List.of("vectorPopulation", "embedding", "ingest");

    private ResourceSchedulerConfig cfg;

    @BeforeEach
    void setUp() {
        // Defaults: migration enabled, migrate@30s, restore@20s, stages [vectorPopulation, embedding, ingest].
        cfg = ResourceSchedulerConfig.defaults();
        lenient().when(deviceRouting.getConfiguration()).thenReturn(DeviceRoutingConfig.defaults());
    }

    private ResourceSnapshot snap(PressureLevel gpu, PressureLevel cpu, PressureLevel ram, boolean gpuAvail) {
        List<GpuSnapshot> gpus = gpuAvail
                ? List.of(new GpuSnapshot(0, 0L, 1000L, 0L, 0.9, gpu))
                : List.of();
        return new ResourceSnapshot(0L, 0.5, 0.5, 0.5, 0.0,
                cpu, ram, PressureLevel.NOMINAL, PressureLevel.NOMINAL, gpus, gpuAvail);
    }

    /** Worst-GPU under HIGH VRAM pressure with the host CPU/RAM healthy. */
    private ResourceSnapshot gpuHighCpuFree() {
        return snap(PressureLevel.HIGH, PressureLevel.NOMINAL, PressureLevel.NOMINAL, true);
    }

    @Test
    void migratesAfterSustainedHighWithCpuHeadroom() throws Exception {
        ResourceSnapshot s = gpuHighCpuFree();

        // A single sample only starts the sustained timer — it must not migrate yet.
        service.evaluate(s, cfg, BASE);
        assertFalse(service.isMigrated(), "single high sample must not migrate");
        verify(deviceRouting, never()).saveConfiguration(any());

        // 31s of continuous HIGH GPU + CPU headroom crosses the 30s migrate window.
        service.evaluate(s, cfg, BASE + TimeUnit.SECONDS.toNanos(31));
        assertTrue(service.isMigrated(), "sustained high + CPU headroom must migrate");

        ArgumentCaptor<DeviceRoutingConfig> cap = ArgumentCaptor.forClass(DeviceRoutingConfig.class);
        verify(deviceRouting).saveConfiguration(cap.capture());
        DeviceRoutingConfig saved = cap.getValue();
        assertTrue(Boolean.TRUE.equals(saved.enabled()), "routing must be enabled so the CPU routes take effect");
        Map<String, ServiceDeviceConfig> routes = saved.serviceRoutes();
        for (String svc : STAGES) {
            assertEquals("cpu", routes.get(svc).deviceType(), svc + " must be pinned to CPU");
        }
    }

    @Test
    void doesNotMigrateWhenCpuPressured() throws Exception {
        // GPU sustained HIGH but no leftover CPU — migrating would just relocate the bottleneck.
        ResourceSnapshot s = snap(PressureLevel.HIGH, PressureLevel.HIGH, PressureLevel.NOMINAL, true);
        service.evaluate(s, cfg, BASE);
        service.evaluate(s, cfg, BASE + TimeUnit.SECONDS.toNanos(120));
        assertFalse(service.isMigrated(), "no CPU headroom — must not migrate");
        verify(deviceRouting, never()).saveConfiguration(any());
    }

    @Test
    void restoresAfterSustainedRelief() throws Exception {
        ResourceSnapshot high = gpuHighCpuFree();
        service.evaluate(high, cfg, BASE);
        service.evaluate(high, cfg, BASE + TimeUnit.SECONDS.toNanos(31));
        assertTrue(service.isMigrated());

        // GPU relief must be sustained past the 20s restore window before routes are put back.
        ResourceSnapshot low = snap(PressureLevel.NOMINAL, PressureLevel.NOMINAL, PressureLevel.NOMINAL, true);
        long t = BASE + TimeUnit.SECONDS.toNanos(40);
        service.evaluate(low, cfg, t);
        assertTrue(service.isMigrated(), "single relief sample must not restore yet");
        service.evaluate(low, cfg, t + TimeUnit.SECONDS.toNanos(21));
        assertFalse(service.isMigrated(), "sustained relief must restore");

        // Second save clears our routes and (since we turned routing on) disables it again.
        ArgumentCaptor<DeviceRoutingConfig> cap = ArgumentCaptor.forClass(DeviceRoutingConfig.class);
        verify(deviceRouting, times(2)).saveConfiguration(cap.capture());
        DeviceRoutingConfig restored = cap.getAllValues().get(1);
        assertFalse(Boolean.TRUE.equals(restored.enabled()), "routing disabled again after restore");
        for (String svc : STAGES) {
            assertFalse(restored.serviceRoutes().containsKey(svc), svc + " route removed on restore");
        }
    }

    @Test
    void noMigrationWhenDisabledByConfig() throws Exception {
        cfg.setGpuToCpuMigrationEnabled(false);
        ResourceSnapshot s = gpuHighCpuFree();
        service.evaluate(s, cfg, BASE);
        service.evaluate(s, cfg, BASE + TimeUnit.SECONDS.toNanos(120));
        assertFalse(service.isMigrated());
        verify(deviceRouting, never()).saveConfiguration(any());
    }

    @Test
    void noMigrationWithoutGpuBackend() throws Exception {
        // CPU-only host: nothing to fail over from, regardless of "pressure".
        ResourceSnapshot s = snap(PressureLevel.NOMINAL, PressureLevel.NOMINAL, PressureLevel.NOMINAL, false);
        service.evaluate(s, cfg, BASE);
        service.evaluate(s, cfg, BASE + TimeUnit.SECONDS.toNanos(120));
        assertFalse(service.isMigrated());
        verify(deviceRouting, never()).saveConfiguration(any());
    }
}
