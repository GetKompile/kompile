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

package ai.kompile.app.services;

import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.web.dto.PipelineConfigDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HardwareAutoConfigFirstBootService}.
 *
 * <p>All three service dependencies are mocked so no Spring context, ND4J, or disk
 * config is needed. The {@code @TempDir} is used as the dataDir root so tests never
 * touch the real {@code ~/.kompile}.</p>
 *
 * <h3>Covered scenarios</h3>
 * <ol>
 *   <li>Marker present → service exits immediately; no {@code updateConfiguration} calls made.</li>
 *   <li>Marker absent + all config files already on disk → no overwrites; marker written.</li>
 *   <li>Marker absent + no config files on disk → all three configs written; marker written.</li>
 * </ol>
 */
class HardwareAutoConfigFirstBootServiceTest {

    @TempDir
    Path tempDir;

    private SubprocessConfigService subprocessService;
    private Nd4jEnvironmentConfigService nd4jService;
    private PipelineConfigService pipelineService;

    private Path configDir;

    @BeforeEach
    void setUp() {
        subprocessService = mock(SubprocessConfigService.class);
        nd4jService       = mock(Nd4jEnvironmentConfigService.class);
        pipelineService   = mock(PipelineConfigService.class);

        configDir = tempDir.resolve("config");
    }

    private HardwareAutoConfigFirstBootService service() {
        return new HardwareAutoConfigFirstBootService(
                subprocessService, nd4jService, pipelineService,
                tempDir.toString());
    }

    // ── Test 1: marker already present ───────────────────────────────────────

    @Test
    void markerPresent_doesNothing() throws IOException {
        // Arrange — create the marker
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve(HardwareAutoConfigFirstBootService.MARKER_FILENAME),
                "2025-01-01T00:00:00Z\nalready done\n");

        // Act
        service().runFirstBootAutoConfig();

        // Assert — no service methods called
        verifyNoInteractions(subprocessService, nd4jService, pipelineService);
    }

    // ── Test 2: marker absent + configs already on disk ──────────────────────

    @Test
    void markerAbsent_configsAlreadyExist_noOverwrites_markerWritten() throws IOException {
        // All three services report their config file already exists
        when(subprocessService.isConfigFilePersisted()).thenReturn(true);
        when(nd4jService.isConfigFilePersisted()).thenReturn(true);
        when(pipelineService.isConfigFilePersisted()).thenReturn(true);

        // Act
        service().runFirstBootAutoConfig();

        // Assert — update methods never called (write-only-when-missing)
        verify(subprocessService, never()).updateConfiguration(any(SubprocessConfigUpdate.class));
        verify(nd4jService,       never()).updateConfiguration(any());
        verify(pipelineService,   never()).updateConfig(any(PipelineConfigDto.class));

        // Assert — marker was written
        Path marker = configDir.resolve(HardwareAutoConfigFirstBootService.MARKER_FILENAME);
        assertTrue(Files.exists(marker), "marker file should have been created");
        String markerContent = Files.readString(marker);
        assertTrue(markerContent.contains("skipped=3"),
                "marker should report 3 skipped files, got: " + markerContent);
    }

    // ── Test 3: marker absent + configs missing from disk ────────────────────

    @Test
    void markerAbsent_configsMissing_allFilesWritten_markerWritten() throws IOException {
        // All three services report their config file does NOT exist
        when(subprocessService.isConfigFilePersisted()).thenReturn(false);
        when(nd4jService.isConfigFilePersisted()).thenReturn(false);
        when(pipelineService.isConfigFilePersisted()).thenReturn(false);

        // updateConfig returns a non-null DTO (pipeline service contract)
        when(pipelineService.updateConfig(any())).thenReturn(PipelineConfigDto.builder().build());

        // Act
        service().runFirstBootAutoConfig();

        // Assert — all three update methods were called exactly once
        verify(subprocessService, times(1)).updateConfiguration(any(SubprocessConfigUpdate.class));
        verify(nd4jService,       times(1)).updateConfiguration(any());
        verify(pipelineService,   times(1)).updateConfig(any(PipelineConfigDto.class));

        // Assert — marker was written
        Path marker = configDir.resolve(HardwareAutoConfigFirstBootService.MARKER_FILENAME);
        assertTrue(Files.exists(marker), "marker file should have been created");
        String markerContent = Files.readString(marker);
        assertTrue(markerContent.contains("written=3"),
                "marker should report 3 written files, got: " + markerContent);
    }

    // ── Test 4: service failure does not propagate ────────────────────────────

    @Test
    void serviceFailure_doesNotThrow_markerStillWritten() throws IOException {
        // Services report missing but throw on update
        when(subprocessService.isConfigFilePersisted()).thenReturn(false);
        when(nd4jService.isConfigFilePersisted()).thenReturn(false);
        when(pipelineService.isConfigFilePersisted()).thenReturn(false);

        doThrow(new RuntimeException("simulated subprocess failure"))
                .when(subprocessService).updateConfiguration(any());
        doThrow(new RuntimeException("simulated nd4j failure"))
                .when(nd4jService).updateConfiguration(any());
        doThrow(new RuntimeException("simulated pipeline failure"))
                .when(pipelineService).updateConfig(any());

        // Act — must not throw
        assertDoesNotThrow(() -> service().runFirstBootAutoConfig());

        // Marker is still written (errors do not block it)
        Path marker = configDir.resolve(HardwareAutoConfigFirstBootService.MARKER_FILENAME);
        assertTrue(Files.exists(marker), "marker should be written even if individual applies fail");
    }
}
