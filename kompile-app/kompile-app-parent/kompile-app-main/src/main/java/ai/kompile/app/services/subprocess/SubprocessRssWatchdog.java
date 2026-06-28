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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.ingest.service.SubprocessEventHistoryService;
import ai.kompile.app.services.ResourceTelemetryService;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.app.subprocess.RestartableSubprocess;
import ai.kompile.app.subprocess.SubprocessRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * General parent-side watchdog that kills or restarts any managed subprocess whose
 * resident-set size (RSS) exceeds a configurable threshold.
 *
 * <p>Runs on a fixed-delay schedule (default: every 30 seconds, configurable via
 * {@code kompile.subprocess.rss-watchdog-interval-ms}).  For each alive subprocess
 * in {@link SubprocessRegistry} it reads {@code VmRSS} from
 * {@code /proc/<pid>/status} (Linux only; no-op on other platforms) and compares
 * the value against the effective limit derived from
 * {@link ResourceSchedulerConfig#getSubprocessMaxRssMb()} and
 * {@link ResourceSchedulerConfig#getSubprocessMaxRssFraction()}.</p>
 *
 * <p>When the limit is exceeded the watchdog:
 * <ol>
 *   <li>Records a crash event via {@link SubprocessEventHistoryService#recordSubprocessCrashed}.</li>
 *   <li>Delegates to {@link SubprocessRegistry#getRestartHandler} if a
 *       {@link RestartableSubprocess} is registered, so each launcher's own
 *       backoff / model-reload path is reused.</li>
 *   <li>Falls back to {@link SubprocessRegistry#forceDestroyProcess} for subprocesses
 *       with no registered restart handler.</li>
 * </ol>
 *
 * <p>If both {@code subprocessMaxRssMb} and {@code subprocessMaxRssFraction} are 0
 * (or absent in the config file) the watchdog is disabled and does nothing.
 * The fraction default (0.5 = 50% of system RAM) applies when the config file exists
 * but neither field has been customised.</p>
 */
@Component
public class SubprocessRssWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessRssWatchdog.class);

    private final SubprocessRegistry registry;
    private final ResourceSchedulerConfigService configService;
    private final ResourceTelemetryService telemetryService;
    private final SubprocessEventHistoryService eventHistoryService;

    @Autowired
    public SubprocessRssWatchdog(
            SubprocessRegistry registry,
            ResourceSchedulerConfigService configService,
            ResourceTelemetryService telemetryService,
            @Autowired(required = false) SubprocessEventHistoryService eventHistoryService) {
        this.registry = registry;
        this.configService = configService;
        this.telemetryService = telemetryService;
        this.eventHistoryService = eventHistoryService;
    }

    /**
     * Scheduled check — iterates all alive subprocesses and enforces the RSS limit.
     */
    @Scheduled(fixedDelayString = "${kompile.subprocess.rss-watchdog-interval-ms:30000}")
    public void checkAll() {
        // No-op on non-Linux (RSS via /proc is Linux-only)
        if (!isLinux()) {
            return;
        }

        ResourceSchedulerConfig cfg = configService.getConfiguration();
        long effectiveLimitMb = resolveEffectiveLimitMb(cfg);
        if (effectiveLimitMb <= 0) {
            // Both thresholds disabled — watchdog is inactive
            return;
        }

        List<SubprocessRegistry.SubprocessInfo> all = registry.listAll();
        for (SubprocessRegistry.SubprocessInfo info : all) {
            if (!info.alive()) {
                continue;
            }
            long rssMb = readRssMb(info.pid());
            if (rssMb <= 0) {
                continue; // /proc read failed (process exited or non-Linux)
            }
            if (rssMb > effectiveLimitMb) {
                handleExcess(info.id(), info.pid(), rssMb, effectiveLimitMb);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Compute the effective per-subprocess RSS limit in MB.
     *
     * <p>When both fields are set (&gt; 0), the lower of the two wins
     * (belt-and-suspenders).  Returns 0 when both are disabled.</p>
     */
    private long resolveEffectiveLimitMb(ResourceSchedulerConfig cfg) {
        long absoluteMb = cfg.getSubprocessMaxRssMb();
        double fraction = cfg.getSubprocessMaxRssFraction();

        long fractionMb = 0;
        if (fraction > 0.0) {
            long totalRamMb = telemetryService.getSystemTotalRamMb();
            if (totalRamMb > 0) {
                fractionMb = (long) (fraction * totalRamMb);
            }
        }

        if (absoluteMb > 0 && fractionMb > 0) {
            return Math.min(absoluteMb, fractionMb);
        }
        if (absoluteMb > 0) {
            return absoluteMb;
        }
        return fractionMb; // may be 0 if fraction is also 0
    }

    /**
     * Handle a subprocess that has exceeded its RSS limit.
     */
    private void handleExcess(String id, long pid, long rssMb, long limitMb) {
        String reason = String.format(
                "RSS %d MB exceeds watchdog limit %d MB for subprocess id=%s PID=%d",
                rssMb, limitMb, id, pid);
        logger.warn("SubprocessRssWatchdog: {}", reason);

        // Record durable event (pass id as modelId — acceptable per spec)
        if (eventHistoryService != null) {
            try {
                eventHistoryService.recordSubprocessCrashed(id, reason, -1, null);
            } catch (Exception e) {
                logger.debug("Could not record RSS-watchdog crash event for id={}: {}", id, e.getMessage());
            }
        }

        // Prefer restart handler; fall back to force-destroy
        Optional<RestartableSubprocess> handler = registry.getRestartHandler(id);
        if (handler.isPresent()) {
            logger.warn("SubprocessRssWatchdog: invoking restart handler for id={}", id);
            try {
                handler.get().requestRestart(reason);
            } catch (Exception e) {
                logger.error("Restart handler threw for id={}: {}", id, e.getMessage(), e);
            }
        } else {
            logger.warn("SubprocessRssWatchdog: no restart handler for id={} — force-destroying process", id);
            registry.forceDestroyProcess(id);
        }
    }

    /**
     * Read the resident-set size of the given PID from {@code /proc/<pid>/status}.
     *
     * <p>The {@code VmRSS} line is in kB; this method converts to MB.
     * Returns 0 on any read/parse failure (process already exited, non-Linux, etc.).</p>
     */
    static long readRssMb(long pid) {
        try {
            Path statusPath = Path.of("/proc", String.valueOf(pid), "status");
            if (!Files.isReadable(statusPath)) {
                return 0L;
            }
            for (String line : Files.readAllLines(statusPath)) {
                if (line.startsWith("VmRSS:")) {
                    // Format: "VmRSS:  12345678 kB"
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        long kb = Long.parseLong(parts[1]);
                        return kb / 1024L; // kB → MB
                    }
                }
            }
        } catch (Throwable ignored) {
            // process exited between listAll() and read, or non-Linux — silently skip
        }
        return 0L;
    }

    /** True when running on Linux (where /proc/<pid>/status is available). */
    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }
}
