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

package ai.kompile.embedding.anserini.subprocess;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmbeddingSubprocessLauncher#resolveSystemPhysicalCeilingMb}.
 *
 * <p>The method was widened from {@code private} to package-private
 * ({@code // visible for testing}) — a minimal, native-safe change recorded
 * in the commit with the OOM-fix (commit a2b3d5de).  No ND4J, no subprocess
 * is started, and no disk access occurs.
 *
 * <p>A minimal launcher is constructed via {@link EmbeddingSubprocessLauncher#builder()}
 * with a dummy classpath entry (avoids {@code buildSubprocessClasspath()}) and
 * explicit {@code JVM_CLASSPATH} mode (avoids native-executable path resolution).
 *
 * <p>Framework: JUnit 5 (same as the sibling config test in this module).</p>
 *
 * <h3>What is NOT tested here</h3>
 * <ul>
 *   <li>OMP env-var block ({@code OMP_NUM_THREADS} / {@code OPENBLAS_NUM_THREADS} /
 *       {@code GOTO_NUM_THREADS}) — these are set inside {@code startEmbeddingSubprocess}
 *       on a live {@link ProcessBuilder}, which requires launching a real subprocess.
 *       Cannot be tested without a real process; skipped. The correctness is verified
 *       by the committed OOM-fix integration run.</li>
 *   <li>warmup batch-size set ({@code {1, 16, 64}}) — buried in
 *       {@code EmbeddingSubprocessMain.warmupDspBuckets()} as a local array in a private
 *       static method.  No reachable constant or package-private accessor exists; skipped.
 *       The comment in the source ("Reduced from {1,2,4,8,16,32,64} to {1,16,64}") is the
 *       durable record.</li>
 * </ul>
 */
@DisplayName("EmbeddingSubprocessLauncher.resolveSystemPhysicalCeilingMb")
class EmbeddingSubprocessPhysicalCeilingTest {

    /** System property key for the physical-fraction override. */
    private static final String FRACTION_PROP = "kompile.subprocess.maxphysical-fraction";

    /** Restore this to whatever value (or absence) it had before each test. */
    private String savedFractionProp;

    /** Launcher instance built with the minimal viable configuration for this test. */
    private EmbeddingSubprocessLauncher launcher;

    @BeforeEach
    void setUp() {
        savedFractionProp = System.getProperty(FRACTION_PROP);
        // Build a minimal launcher: dummy classpath avoids buildSubprocessClasspath(),
        // JVM_CLASSPATH mode avoids native-executable path resolution in the constructor.
        launcher = EmbeddingSubprocessLauncher.builder()
                .javaHome(System.getProperty("java.home"))
                .classpath(List.of("dummy-for-test.jar"))
                .launchMode(EmbeddingSubprocessLauncher.LaunchMode.JVM_CLASSPATH)
                .maxHeapMb(4096)
                .build();
    }

    @AfterEach
    void tearDown() {
        // Restore the system property so tests don't bleed into each other.
        if (savedFractionProp == null) {
            System.clearProperty(FRACTION_PROP);
        } else {
            System.setProperty(FRACTION_PROP, savedFractionProp);
        }
    }

    // ── resolveSystemPhysicalCeilingMb: basic contract ──────────────────────

    @Test
    @DisplayName("Returns a positive value >= the offHeapFloorMb argument")
    void returnsValueAtLeastFloor() {
        long floorMb = 2048L;
        long result = launcher.resolveSystemPhysicalCeilingMb(floorMb);
        assertTrue(result >= floorMb,
                "Ceiling must always be >= the offHeapFloor; got " + result + " for floor=" + floorMb);
        assertTrue(result > 0, "Result must be a positive MB count");
    }

    @Test
    @DisplayName("Default fraction (0.95) yields ceiling at most equal to total physical RAM in MB")
    void defaultFraction_ceilingAtMostPhysicalRam() {
        // Sanity upper bound: no sane machine has > 100 TB RAM.
        long maxSaneMb = 100L * 1024L * 1024L; // 100 TiB in MB
        long floorMb = 1024L;
        long result = launcher.resolveSystemPhysicalCeilingMb(floorMb);
        assertTrue(result <= maxSaneMb,
                "Ceiling must not exceed total physical RAM; got an insane value: " + result + " MB");
    }

    @Test
    @DisplayName("Floor wins when it exceeds the fraction-derived ceiling")
    void floorWins_whenFloorExceedsFractionCeiling() {
        // Use an absurdly large floor (1 PiB in MB) — guaranteed > any real machine's RAM.
        // Math.max(floor, ceiling) must return floor in this case.
        long enormousFloorMb = 1024L * 1024L * 1024L; // 1 PiB in MB
        long result = launcher.resolveSystemPhysicalCeilingMb(enormousFloorMb);
        assertEquals(enormousFloorMb, result,
                "When floor > ceiling, Math.max must return the floor");
    }

    // ── resolveSystemPhysicalCeilingMb: fraction override via system property ─

    @Test
    @DisplayName("-Dkompile.subprocess.maxphysical-fraction overrides the 0.95 default")
    void fractionPropertyOverride_appliedCorrectly() {
        // Use a tiny fraction (0.10) and a very small floor so ceiling wins.
        // Expected ceiling ≈ totalPhysicalRam * 0.10.
        System.setProperty(FRACTION_PROP, "0.10");
        long floorMb = 1L; // tiny floor so ceiling wins

        long result = launcher.resolveSystemPhysicalCeilingMb(floorMb);

        // Derive expected from the same MXBean the impl uses.
        long totalBytes = ((OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        long expectedCeilingMb = (long) (totalBytes / (1024.0 * 1024.0) * 0.10);
        long expectedResult = Math.max(floorMb, expectedCeilingMb);

        assertEquals(expectedResult, result,
                "Fraction 0.10 override must produce ceiling = totalRam * 0.10 (floor wins if larger)");
    }

    @Test
    @DisplayName("Fraction override 1.0 returns approximately total physical RAM in MB")
    void fractionPropertyOverride_1dot0_equalsTotalRam() {
        System.setProperty(FRACTION_PROP, "1.0");
        long floorMb = 1L;
        long result = launcher.resolveSystemPhysicalCeilingMb(floorMb);

        long totalBytes = ((OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        long expectedMb = (long) (totalBytes / (1024.0 * 1024.0));

        // Allow 1 MB rounding tolerance.
        assertTrue(Math.abs(result - expectedMb) <= 1L,
                "Fraction 1.0 must yield ≈ total physical RAM in MB (±1 MB rounding); "
                        + "expected≈" + expectedMb + " got=" + result);
    }
}
