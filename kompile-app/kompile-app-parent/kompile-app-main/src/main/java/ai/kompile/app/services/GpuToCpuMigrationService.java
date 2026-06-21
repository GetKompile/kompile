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
import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sustained-load GPU&rarr;CPU failover for crawl pipeline stages.
 *
 * <p>The platform runs a multi-backend ND4J ({@code JCublasBackend} + {@code CpuBackend}); each crawl
 * subprocess resolves its device at launch via
 * {@link DeviceRoutingConfigService#resolveNd4jConfigForService(String)}. This service is the policy that
 * decides <em>when</em> to point a stage at the CPU side of that backend: when the worst GPU device has been
 * under {@code HIGH}+ VRAM pressure <em>continuously</em> for a configured window <strong>and</strong> the
 * host CPU/RAM still has headroom, it pins the configured crawl stages to {@code deviceType="cpu"} so their
 * next subprocess migrates off the GPU. When GPU pressure stays relieved for the restore window, it puts the
 * prior routes back.</p>
 *
 * <p>Migration is driven entirely by the kompile JSON config ({@link ResourceSchedulerConfig}, persisted to
 * {@code ~/.kompile/config/resource-scheduler-config.json}) — there are no Spring properties involved. It is
 * hysteretic in both directions (separate migrate/restore windows) so transient spikes cannot make it
 * thrash, and it never migrates <em>into</em> a CPU that lacks headroom (that would just move the
 * bottleneck — the user's "leftover CPU resources" rule).</p>
 */
@Service
public class GpuToCpuMigrationService {

    private static final Logger log = LoggerFactory.getLogger(GpuToCpuMigrationService.class);

    /** Poll cadence; the sustained windows (tens of seconds) make sub-second granularity unnecessary. */
    private static final long TICK_MS = 5_000L;

    /** Route overlay that forces a service onto the CPU backend ({@code cudaCurrentDevice=-1}). */
    private static final ServiceDeviceConfig CPU_ROUTE = new ServiceDeviceConfig("cpu", null, null, null);

    /** Sentinel stored when a migrated service had no prior route (so restore removes it cleanly). */
    private static final ServiceDeviceConfig NONE = new ServiceDeviceConfig(null, null, null, null);

    @Autowired
    private ResourceTelemetryService telemetry;

    @Autowired
    private ResourceSchedulerConfigService configService;

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRouting;

    /** Optional: lets the saturation path note when cluster peers are available for remote offload. */
    @Autowired(required = false)
    private CrawlWorkerRegistry workerRegistry;

    /** Sentinel for "this timer is not currently running" (nanoTime origin is arbitrary, so 0 is a valid reading). */
    private static final long UNSET = Long.MIN_VALUE;

    // --- poller-thread-only sustained-load state ---
    private long gpuPressuredSinceNanos = UNSET;
    private long gpuRelievedSinceNanos = UNSET;

    // --- migration state (written by poller, read by status()) ---
    private volatile boolean migrated = false;
    private volatile boolean weEnabledRouting = false;
    private volatile String lastReason = "";
    /** service -> the route it had before we migrated it ({@link #NONE} = none existed). */
    private final Map<String, ServiceDeviceConfig> priorRoutes = new ConcurrentHashMap<>();

    private ScheduledExecutorService poller;

    @PostConstruct
    public void start() {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gpu-cpu-migration");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::tickSafe, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("GpuToCpuMigrationService started (tick={}ms)", TICK_MS);
    }

    @PreDestroy
    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
        // Don't leave the config CPU-pinned across a restart we initiated.
        if (migrated) {
            restore("service shutdown");
        }
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable t) {
            log.debug("GPU->CPU migration tick failed: {}", t.toString());
        }
    }

    private void tick() {
        if (deviceRouting == null) {
            return; // no routing mechanism available — nothing we can migrate
        }
        evaluate(telemetry.latest(), configService.getConfiguration(), System.nanoTime());
    }

    /**
     * Decision core, separated from its clock/telemetry/config sources so the sustained-window logic is
     * unit-testable without a real scheduler or wall-clock. Mutates the migration state in place.
     */
    void evaluate(ResourceSnapshot s, ResourceSchedulerConfig cfg, long now) {
        if (!cfg.isGpuToCpuMigrationEnabled()) {
            if (migrated) {
                restore("migration disabled by config");
            }
            return;
        }
        if (!s.gpuBackendAvailable()) {
            if (migrated) {
                restore("no GPU backend present");
            }
            return; // CPU-only host: nothing to fail over from
        }

        boolean gpuHigh = s.worstGpuPressure().atLeast(PressureLevel.HIGH);
        boolean cpuHeadroom = !s.cpuPressure().atLeast(PressureLevel.HIGH)
                && !s.ramPressure().atLeast(PressureLevel.HIGH);

        // Track how long the current condition (pressured / relieved) has held continuously.
        if (gpuHigh) {
            if (gpuPressuredSinceNanos == UNSET) {
                gpuPressuredSinceNanos = now;
            }
            gpuRelievedSinceNanos = UNSET;
        } else {
            if (gpuRelievedSinceNanos == UNSET) {
                gpuRelievedSinceNanos = now;
            }
            gpuPressuredSinceNanos = UNSET;
        }

        long migrateAfter = TimeUnit.SECONDS.toNanos(Math.max(1, cfg.getGpuToCpuMigrateAfterSeconds()));
        long restoreAfter = TimeUnit.SECONDS.toNanos(Math.max(1, cfg.getGpuToCpuRestoreAfterSeconds()));

        if (!migrated) {
            boolean sustainedHigh = gpuPressuredSinceNanos != UNSET
                    && (now - gpuPressuredSinceNanos) >= migrateAfter;
            if (sustainedHigh && cpuHeadroom) {
                migrate(cfg.getGpuToCpuMigrationServices(), s);
            } else if (sustainedHigh) {
                // Sustained GPU pressure with no leftover CPU — local migration can't help. If a cluster peer
                // has capacity, the RemotePeerJobSchedulerDelegate (failover mode) offloads scheduler work to
                // it (the 3rd tier); otherwise we hold on the GPU.
                int peers = workerRegistry != null ? workerRegistry.size(System.currentTimeMillis()) : 0;
                if (peers > 0) {
                    log.info("Local host saturated under sustained GPU pressure (cpu={}, ram={}) — {} cluster "
                            + "peer(s) available for remote offload", s.cpuPressure(), s.ramPressure(), peers);
                } else {
                    log.debug("Sustained GPU pressure but no CPU headroom and no cluster peers (cpu={}, ram={}) "
                            + "— holding on GPU", s.cpuPressure(), s.ramPressure());
                }
            }
        } else {
            boolean sustainedLow = gpuRelievedSinceNanos != UNSET
                    && (now - gpuRelievedSinceNanos) >= restoreAfter;
            if (sustainedLow) {
                restore("GPU pressure relieved (" + s.worstGpuPressure() + ")");
            }
        }
    }

    private void migrate(List<String> services, ResourceSnapshot s) {
        if (services == null || services.isEmpty()) {
            return;
        }
        try {
            DeviceRoutingConfig cur = deviceRouting.getConfiguration();
            boolean wasEnabled = Boolean.TRUE.equals(cur.enabled());
            Map<String, ServiceDeviceConfig> routes =
                    new LinkedHashMap<>(cur.serviceRoutes() != null ? cur.serviceRoutes() : Map.of());

            priorRoutes.clear();
            List<String> moved = new ArrayList<>();
            for (String svc : services) {
                if (svc == null || svc.isBlank()) {
                    continue;
                }
                ServiceDeviceConfig prior = routes.get(svc);
                if (prior != null && "cpu".equalsIgnoreCase(prior.deviceType())) {
                    continue; // already CPU (e.g. user-pinned) — leave it, don't claim ownership
                }
                priorRoutes.put(svc, prior != null ? prior : NONE);
                routes.put(svc, CPU_ROUTE);
                moved.add(svc);
            }
            if (moved.isEmpty()) {
                return;
            }

            weEnabledRouting = !wasEnabled;
            deviceRouting.saveConfiguration(new DeviceRoutingConfig(routes, true));
            migrated = true;
            lastReason = "sustained GPU " + s.worstGpuPressure() + " + CPU headroom (cpu="
                    + s.cpuPressure() + ", ram=" + s.ramPressure() + ")";
            log.info("MIGRATED crawl GPU stages {} to CPU — {} (next subprocess of each lands on the CPU backend)",
                    moved, lastReason);
        } catch (Exception e) {
            log.warn("GPU->CPU migration failed: {}", e.toString());
        }
    }

    private void restore(String why) {
        if (priorRoutes.isEmpty()) {
            migrated = false;
            return;
        }
        try {
            DeviceRoutingConfig cur = deviceRouting.getConfiguration();
            Map<String, ServiceDeviceConfig> routes =
                    new LinkedHashMap<>(cur.serviceRoutes() != null ? cur.serviceRoutes() : Map.of());
            List<String> restored = new ArrayList<>();
            for (Map.Entry<String, ServiceDeviceConfig> e : priorRoutes.entrySet()) {
                ServiceDeviceConfig prior = e.getValue();
                if (prior == NONE) {
                    routes.remove(e.getKey());
                } else {
                    routes.put(e.getKey(), prior);
                }
                restored.add(e.getKey());
            }
            // If we were the ones who turned routing on, turn it back off once our routes are gone.
            boolean enabled = weEnabledRouting ? false : Boolean.TRUE.equals(cur.enabled());
            deviceRouting.saveConfiguration(new DeviceRoutingConfig(routes, enabled));

            priorRoutes.clear();
            migrated = false;
            weEnabledRouting = false;
            gpuPressuredSinceNanos = UNSET;
            gpuRelievedSinceNanos = UNSET;
            lastReason = "";
            log.info("RESTORED crawl GPU stages {} to prior device — {}", restored, why);
        } catch (Exception e) {
            log.warn("GPU->CPU restore failed: {}", e.toString());
        }
    }

    /** Observability for the status endpoint / UI. Never throws. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("migrated", migrated);
        out.put("migratedServices", new ArrayList<>(priorRoutes.keySet()));
        out.put("reason", lastReason);
        return out;
    }

    /** True when crawl GPU stages are currently failed over to CPU. */
    public boolean isMigrated() {
        return migrated;
    }
}
