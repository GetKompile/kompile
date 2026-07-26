/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ai.kompile.cli.common.config.HardwareAutoConfigurator.Tier;
import ai.kompile.cli.common.config.GpuProbe.GpuInfo;
import ai.kompile.cli.common.routing.KompileService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProjectHardwareProvisioner} and {@link GpuProbe}.
 */
class ProjectHardwareProvisionerTest {

    // ── GpuProbe.parseCsv ───────────────────────────────────────────────────

    @Test
    void parseCsv_parsesStandardNvidiaSmiOutput() {
        String csv = """
                0, NVIDIA GeForce RTX 4090, 24564
                1, NVIDIA GeForce RTX 3070 Ti, 8192
                """;
        List<GpuInfo> result = GpuProbe.parseCsv(csv);
        assertEquals(2, result.size());
        assertEquals(0, result.get(0).index());
        assertEquals("NVIDIA GeForce RTX 4090", result.get(0).name());
        assertEquals(24564L, result.get(0).vramMb());
        assertEquals(1, result.get(1).index());
        assertEquals("NVIDIA GeForce RTX 3070 Ti", result.get(1).name());
        assertEquals(8192L, result.get(1).vramMb());
    }

    @Test
    void parseCsv_returnsEmptyOnBlankInput() {
        assertTrue(GpuProbe.parseCsv("").isEmpty());
        assertTrue(GpuProbe.parseCsv(null).isEmpty());
        assertTrue(GpuProbe.parseCsv("   \n  \n").isEmpty());
    }

    @Test
    void parseCsv_skipsMalformedRows() {
        String csv = """
                0, NVIDIA GeForce RTX 4090, 24564
                not-a-number, Bad Row, also-bad
                1, NVIDIA T4, 16384
                """;
        List<GpuInfo> result = GpuProbe.parseCsv(csv);
        assertEquals(2, result.size());
        assertEquals(0, result.get(0).index());
        assertEquals(1, result.get(1).index());
    }

    @Test
    void parseCsv_handlesSingleGpu() {
        List<GpuInfo> result = GpuProbe.parseCsv("0, Tesla V100, 32510");
        assertEquals(1, result.size());
        assertEquals("Tesla V100", result.get(0).name());
        assertEquals(32510L, result.get(0).vramMb());
    }

    @Test
    void probe_neverThrows() {
        // On CI/boxes without nvidia-smi this must return List.of(), not throw
        List<GpuInfo> result = assertDoesNotThrow(GpuProbe::probe);
        assertNotNull(result);
    }

    // ── Tier tables ─────────────────────────────────────────────────────────

    @Test
    void graphMatrixHeapTableCoversAllTiers() {
        assertEquals("4g",  ProjectHardwareProvisioner.graphMatrixHeapForTier(Tier.SMALL));
        assertEquals("6g",  ProjectHardwareProvisioner.graphMatrixHeapForTier(Tier.MEDIUM));
        assertEquals("12g", ProjectHardwareProvisioner.graphMatrixHeapForTier(Tier.LARGE));
        assertEquals("24g", ProjectHardwareProvisioner.graphMatrixHeapForTier(Tier.XLARGE));
        assertEquals("32g", ProjectHardwareProvisioner.graphMatrixHeapForTier(Tier.SERVER));
    }

    @Test
    void learningHeapTableCoversAllTiers() {
        assertEquals("2g", ProjectHardwareProvisioner.learningHeapForTier(Tier.SMALL));
        assertEquals("4g", ProjectHardwareProvisioner.learningHeapForTier(Tier.MEDIUM));
        assertEquals("6g", ProjectHardwareProvisioner.learningHeapForTier(Tier.LARGE));
        assertEquals("8g", ProjectHardwareProvisioner.learningHeapForTier(Tier.XLARGE));
        assertEquals("8g", ProjectHardwareProvisioner.learningHeapForTier(Tier.SERVER));
    }

    @Test
    void governorRamFloorMbIsPositiveForAllTiers() {
        for (Tier tier : Tier.values()) {
            assertTrue(ProjectHardwareProvisioner.governorRamFloorMbForTier(tier) > 0,
                    "Expected positive floor for tier " + tier);
        }
    }

    @Test
    void governorRamFloorMbValues() {
        assertEquals(2048L, ProjectHardwareProvisioner.governorRamFloorMbForTier(Tier.SMALL));
        assertEquals(3072L, ProjectHardwareProvisioner.governorRamFloorMbForTier(Tier.MEDIUM));
        assertEquals(4096L, ProjectHardwareProvisioner.governorRamFloorMbForTier(Tier.LARGE));
        assertEquals(6144L, ProjectHardwareProvisioner.governorRamFloorMbForTier(Tier.XLARGE));
        assertEquals(8192L, ProjectHardwareProvisioner.governorRamFloorMbForTier(Tier.SERVER));
    }

    @Test
    void kgeTrainingEstimateValues() {
        assertEquals(4096L,  ProjectHardwareProvisioner.kgeTrainingEstimateMbForTier(Tier.SMALL));
        assertEquals(8192L,  ProjectHardwareProvisioner.kgeTrainingEstimateMbForTier(Tier.MEDIUM));
        assertEquals(16384L, ProjectHardwareProvisioner.kgeTrainingEstimateMbForTier(Tier.LARGE));
        assertEquals(28672L, ProjectHardwareProvisioner.kgeTrainingEstimateMbForTier(Tier.XLARGE));
        assertEquals(40960L, ProjectHardwareProvisioner.kgeTrainingEstimateMbForTier(Tier.SERVER));
    }

    @Test
    void embeddingEstimateValues() {
        assertEquals(2048L,  ProjectHardwareProvisioner.embeddingEstimateMbForTier(Tier.SMALL));
        assertEquals(4096L,  ProjectHardwareProvisioner.embeddingEstimateMbForTier(Tier.MEDIUM));
        assertEquals(8192L,  ProjectHardwareProvisioner.embeddingEstimateMbForTier(Tier.LARGE));
        assertEquals(12288L, ProjectHardwareProvisioner.embeddingEstimateMbForTier(Tier.XLARGE));
        assertEquals(12288L, ProjectHardwareProvisioner.embeddingEstimateMbForTier(Tier.SERVER));
    }

    // ── provision() writes-when-missing + skips-when-present ────────────────

    @Test
    void provision_writesAllExpectedFilesOnFreshDirectory(@TempDir Path tmpDir) throws IOException {
        ProjectHardwareProvisioner.ProvisionResult result =
                ProjectHardwareProvisioner.provision(tmpDir, false);

        // At minimum these files should be written on a fresh dir
        List<String> writtenNames = result.written.stream()
                .map(p -> p.getFileName().toString())
                .toList();

        assertTrue(writtenNames.contains("subprocess-ingest-config.json"),
                "Missing subprocess-ingest-config.json; written=" + writtenNames);
        assertTrue(writtenNames.contains("resource-scheduler-config.json"),
                "Missing resource-scheduler-config.json; written=" + writtenNames);
        assertTrue(writtenNames.contains("nd4j-environment-config.json"),
                "Missing nd4j-environment-config.json; written=" + writtenNames);
        assertTrue(writtenNames.contains("pipeline-config.json"),
                "Missing pipeline-config.json; written=" + writtenNames);
        assertTrue(writtenNames.contains("app-index-config.json"),
                "Missing app-index-config.json; written=" + writtenNames);
        assertTrue(writtenNames.contains("project-runtime.json"),
                "Missing project-runtime.json; written=" + writtenNames);
        assertFalse(Files.exists(tmpDir.resolve("config/gpu-device-config.json")),
                "gpu-device-config.json is a manual override and must not be generated");

        // Skipped list must be empty on first run
        assertTrue(result.skippedExisting.isEmpty(),
                "Expected no skipped files on first provision; skipped=" + result.skippedExisting);
    }

    @Test
    void provision_skipsExistingFilesOnSecondRun(@TempDir Path tmpDir) {
        ProjectHardwareProvisioner.provision(tmpDir, false);
        ProjectHardwareProvisioner.ProvisionResult second =
                ProjectHardwareProvisioner.provision(tmpDir, false);

        // On second run nothing new should be written
        assertTrue(second.written.isEmpty(),
                "Expected nothing written on second provision; written=" + second.written);
        assertFalse(second.skippedExisting.isEmpty(),
                "Expected skipped entries on second provision");
    }

    @Test
    void provision_subprocessConfigContainsSubprocessTypesMap(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/subprocess-ingest-config.json");
        assertTrue(Files.exists(file));

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config = mapper.readValue(file.toFile(),
                new TypeReference<>() {});

        assertTrue(config.containsKey("subprocessTypes"),
                "subprocess-ingest-config.json must have 'subprocessTypes' key");

        @SuppressWarnings("unchecked")
        Map<String, Object> types = (Map<String, Object>) config.get("subprocessTypes");
        assertTrue(types.containsKey("graph-matrix"),
                "subprocessTypes must have 'graph-matrix' key");
        assertTrue(types.containsKey("learning"),
                "subprocessTypes must have 'learning' key");

        @SuppressWarnings("unchecked")
        Map<String, Object> graphMatrix = (Map<String, Object>) types.get("graph-matrix");
        assertNotNull(graphMatrix.get("heapSize"),
                "graph-matrix must have 'heapSize'");
        @SuppressWarnings("unchecked")
        Map<String, Object> learning = (Map<String, Object>) types.get("learning");
        assertNotNull(learning.get("heapSize"),
                "learning must have 'heapSize'");
    }

    @Test
    void provision_resourceSchedulerHasExpectedKeys(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/resource-scheduler-config.json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config = mapper.readValue(file.toFile(),
                new TypeReference<>() {});

        assertTrue(config.containsKey("governorRamFloorMb"));
        assertTrue(config.containsKey("heavyMemoryOpEstimatesMb"));
        assertTrue(config.containsKey("heavyMemoryBudgetSafetyFraction"));

        @SuppressWarnings("unchecked")
        Map<String, Object> estimates = (Map<String, Object>) config.get("heavyMemoryOpEstimatesMb");
        assertTrue(estimates.containsKey("embedding"));
        assertTrue(estimates.containsKey("kge-training"));
    }

    @Test
    void provision_appIndexConfigHasRelativePaths(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/app-index-config.json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config = mapper.readValue(file.toFile(),
                new TypeReference<>() {});

        String vectorPath = (String) config.get("vectorStorePath");
        String keywordPath = (String) config.get("keywordIndexPath");

        assertFalse(vectorPath.startsWith("/"),
                "vectorStorePath must be relative, got: " + vectorPath);
        assertFalse(keywordPath.startsWith("/"),
                "keywordIndexPath must be relative, got: " + keywordPath);
    }

    @Test
    void provision_projectRuntimeContainsRequiredFields(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/project-runtime.json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config = mapper.readValue(file.toFile(),
                new TypeReference<>() {});

        assertTrue(config.containsKey("tier"));
        assertTrue(config.containsKey("appHeap"));
        assertTrue(config.containsKey("stagingHeap"));
        assertTrue(config.containsKey("servingHeap"));
        assertTrue(config.containsKey("gpus"));
        assertTrue(config.containsKey("totalRamGb"));
        assertTrue(config.containsKey("cpus"));
        assertTrue(config.containsKey("provisionedAt"));
    }

    @Test
    void provision_projectRuntimeSizesEachPersonaProcess(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/project-runtime.json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config = mapper.readValue(file.toFile(),
                new TypeReference<>() {});

        // The persona launcher looks heap up as KompileService.id() + "Heap". Deriving the
        // expected key from the enum rather than hardcoding it is the point of this test: if a
        // service id ever changes, the provisioner stops emitting a key the launcher reads and
        // every persona silently drops to the machine-tier default.
        for (KompileService service : List.of(KompileService.CHAT, KompileService.CRAWL)) {
            String key = service.id() + "Heap";
            assertTrue(config.containsKey(key),
                    "project-runtime.json must size " + service.componentId() + " via " + key);
            assertTrue(String.valueOf(config.get(key)).matches("\\d+[mg]"),
                    key + " must be a -Xmx value, got: " + config.get(key));
        }
    }

    @Test
    void personaHeaps_areDefinedForEveryTier() {
        for (Tier tier : Tier.values()) {
            assertTrue(HardwareAutoConfigurator.chatHeapForTier(tier).matches("\\d+[mg]"),
                    "chatHeapForTier(" + tier + ")");
            assertTrue(HardwareAutoConfigurator.crawlHeapForTier(tier).matches("\\d+[mg]"),
                    "crawlHeapForTier(" + tier + ")");
        }
    }

    @Test
    void provision_noAbsoluteUserHomePathsInNd4jConfig(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/nd4j-environment-config.json");
        String content = Files.readString(file);

        String userHome = System.getProperty("user.home");
        // The value of user.home itself (e.g. /home/agibsonccc) must not appear
        // literally in the nd4j config
        assertFalse(content.contains(userHome),
                "nd4j-environment-config.json must not embed literal user.home path '"
                        + userHome + "'");
    }

    @Test
    void provision_summaryLinesNotEmpty(@TempDir Path tmpDir) {
        ProjectHardwareProvisioner.ProvisionResult result =
                ProjectHardwareProvisioner.provision(tmpDir, false);
        List<String> lines = result.summaryLines();
        assertFalse(lines.isEmpty());
        // Must include tier line
        assertTrue(lines.stream().anyMatch(l -> l.contains("tier") || l.contains("Tier")
                || l.contains("TIER") || l.toUpperCase().contains("HARDWARE")),
                "summaryLines must mention tier; got: " + lines);
    }

    // ── GpuProbe.assignCudaRuntimeOrder (F3 ordering function) ─────────────

    @Test
    void assignCudaRuntimeOrder_4090GetsIndex0_3070TiGetsIndex1() {
        // RTX 3070 Ti: smi 0, cap 8.6, 8192 MB VRAM
        // RTX 4090:    smi 1, cap 8.9, 24564 MB VRAM
        // Expected: 4090 wins (higher cap) → cudaRuntimeIndex 0
        List<GpuInfo> gpus = List.of(
                new GpuInfo(0, "NVIDIA GeForce RTX 3070 Ti", 8192, 8.6),
                new GpuInfo(1, "NVIDIA GeForce RTX 4090",    24564, 8.9)
        );
        var order = GpuProbe.assignCudaRuntimeOrder(gpus);
        assertEquals(1, (int) order.get(0), "3070 Ti should get cudaRuntimeIndex 1");
        assertEquals(0, (int) order.get(1), "4090 should get cudaRuntimeIndex 0");
    }

    @Test
    void assignCudaRuntimeOrder_equalCapFallsBackToVram() {
        // Both cap 8.6, but GPU 1 has more VRAM → cuda 0
        List<GpuInfo> gpus = List.of(
                new GpuInfo(0, "NVIDIA GeForce RTX 3080",    10240, 8.6),
                new GpuInfo(1, "NVIDIA GeForce RTX 3090",    24576, 8.6)
        );
        var order = GpuProbe.assignCudaRuntimeOrder(gpus);
        assertEquals(0, (int) order.get(1), "3090 (more VRAM) should get cudaRuntimeIndex 0");
        assertEquals(1, (int) order.get(0), "3080 (less VRAM) should get cudaRuntimeIndex 1");
    }

    @Test
    void assignCudaRuntimeOrder_singleGpuGetsIndex0() {
        List<GpuInfo> gpus = List.of(new GpuInfo(0, "Tesla V100", 32510, 7.0));
        var order = GpuProbe.assignCudaRuntimeOrder(gpus);
        assertEquals(0, (int) order.get(0), "Single GPU must get cudaRuntimeIndex 0");
    }

    @Test
    void assignCudaRuntimeOrder_emptyListReturnsEmpty() {
        assertTrue(GpuProbe.assignCudaRuntimeOrder(List.of()).isEmpty());
    }

    @Test
    void assignCudaRuntimeOrder_zeroComputeCapFallsBackToVram() {
        // When computeCapability = 0.0 (unknown), fall back to VRAM ordering
        List<GpuInfo> gpus = List.of(
                new GpuInfo(0, "NVIDIA GeForce RTX 3070 Ti", 8192),   // 3-arg ctor: cap=0.0
                new GpuInfo(1, "NVIDIA GeForce RTX 4090",    24564)    // 3-arg ctor: cap=0.0
        );
        var order = GpuProbe.assignCudaRuntimeOrder(gpus);
        assertEquals(0, (int) order.get(1), "4090 (more VRAM) should get cudaRuntimeIndex 0 even with unknown cap");
        assertEquals(1, (int) order.get(0), "3070 Ti should get cudaRuntimeIndex 1");
    }

    // ── device-routing-config.json has budgets but no generated device pins ──

    @Test
    void buildDeviceRoutingConfigLeavesDeviceSelectionToNd4j() {
        List<GpuInfo> gpus = List.of(
                new GpuInfo(0, "NVIDIA GeForce RTX 3070 Ti", 8192, 8.6),
                new GpuInfo(1, "NVIDIA GeForce RTX 4090",    24564, 8.9)
        );

        Map<String, Object> config = ProjectHardwareProvisioner.buildDeviceRoutingConfig(gpus);

        @SuppressWarnings("unchecked")
        Map<String, Object> routes = (Map<String, Object>) config.get("serviceRoutes");
        assertNotNull(routes, "serviceRoutes must be present");
        for (String service : List.of("vlm", "llm", "embedding")) {
            assertTrue(routes.containsKey(service), "serviceRoutes must have '" + service + "' route");
            @SuppressWarnings("unchecked")
            Map<String, Object> route = (Map<String, Object>) routes.get(service);
            assertFalse(route.containsKey("deviceType"), service + " route must not force a backend");
            assertFalse(route.containsKey("cudaDeviceId"), service + " route must not pin a CUDA device");
            assertTrue(((Number) route.get("maxDeviceMemory")).longValue() > 0,
                    service + " route must keep a positive memory budget");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> embeddingRoute = (Map<String, Object>) routes.get("embedding");
        assertEquals(9825L * 1024L * 1024L,
                ((Number) embeddingRoute.get("maxDeviceMemory")).longValue(),
                "embedding route should leave headroom for sibling local model processes");
    }

    @Test
    void autoConfigureGpuEnablesOptimizerFp16ForGeneratedProjects() {
        HardwareAutoConfigurator.AutoConfigResult result = HardwareAutoConfigurator.autoConfigure(
                128L * 1024L * 1024L * 1024L, 32, true, true);

        assertEquals(true, result.nd4jConfig.get("optimizerFp16"),
                "generated project config should use the runtime fp16 default for local model throughput");
    }

    @Test
    void provision_deviceRoutingConfigHasLlmRouteWithoutDevicePin(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        Path file = tmpDir.resolve("config/device-routing-config.json");
        if (!Files.exists(file)) {
            // No GPU on this machine — direct builder test covers route shape.
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config = mapper.readValue(file.toFile(),
                new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> routes = (Map<String, Object>) config.get("serviceRoutes");
        assertNotNull(routes, "serviceRoutes must be present");
        assertTrue(routes.containsKey("llm"),
                "serviceRoutes must have 'llm' route; got keys=" + routes.keySet());

        @SuppressWarnings("unchecked")
        Map<String, Object> llmRoute = (Map<String, Object>) routes.get("llm");
        assertFalse(llmRoute.containsKey("deviceType"), "llm route must not force a backend");
        assertFalse(llmRoute.containsKey("cudaDeviceId"), "llm route must not pin a CUDA device");
    }

    // ── Portability: no literal /home/<user> in any written file ────────────

    @Test
    void provision_noLiteralUserHomeInAnyWrittenFile(@TempDir Path tmpDir)
            throws IOException {
        ProjectHardwareProvisioner.provision(tmpDir, false);

        String userHome = System.getProperty("user.home");
        Path configDir = tmpDir.resolve("config");

        if (Files.exists(configDir)) {
            for (Path file : Files.list(configDir).toList()) {
                if (file.toString().endsWith(".json")) {
                    String content = Files.readString(file);
                    assertFalse(content.contains(userHome),
                            "File " + file.getFileName()
                                    + " must not contain literal user.home path '"
                                    + userHome + "'");
                }
            }
        }
    }
}
