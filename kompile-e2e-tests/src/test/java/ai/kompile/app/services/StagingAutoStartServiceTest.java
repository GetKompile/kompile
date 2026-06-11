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

import ai.kompile.app.config.ModelStagingWiringConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StagingAutoStartService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StagingAutoStartServiceTest {

    @Mock
    private StagingServerLifecycleService lifecycleService;

    @Mock
    private ModelStagingWiringConfiguration wiringConfiguration;

    private StagingAutoStartService service;

    @BeforeEach
    void setUp() {
        service = new StagingAutoStartService(lifecycleService, wiringConfiguration);
        when(lifecycleService.getConfiguredPort()).thenReturn(8080);
    }

    // ===== Null lifecycleService =====

    @Test
    void onApplicationReady_doesNothing_whenLifecycleServiceNull() {
        StagingAutoStartService noService = new StagingAutoStartService(null, wiringConfiguration);
        assertThatCode(() -> noService.onApplicationReady()).doesNotThrowAnyException();
        verify(wiringConfiguration, never()).configureStagingService(anyString(), any());
    }

    // ===== Auto-start disabled =====

    @Test
    void onApplicationReady_doesNothing_whenAutoStartDisabled() {
        when(lifecycleService.isAutoStartEnabled()).thenReturn(false);
        service.onApplicationReady();
        verify(lifecycleService, never()).startServer(anyInt());
    }

    // ===== Already running =====

    @Test
    void onApplicationReady_configuresWiring_whenAlreadyRunning() {
        when(lifecycleService.isAutoStartEnabled()).thenReturn(true);
        when(lifecycleService.isRunning(8080)).thenReturn(true);

        service.onApplicationReady();

        verify(lifecycleService, never()).startServer(anyInt());
        verify(wiringConfiguration).configureStagingService(eq("http://localhost:8080"), isNull());
    }

    // ===== No executable found =====

    @Test
    void onApplicationReady_doesNotStart_whenNoExecutableFound() {
        when(lifecycleService.isAutoStartEnabled()).thenReturn(true);
        when(lifecycleService.isRunning(8080)).thenReturn(false);
        when(lifecycleService.findStagingExecutable()).thenReturn(null);

        service.onApplicationReady();

        verify(lifecycleService, never()).startServer(anyInt());
        verify(wiringConfiguration, never()).configureStagingService(anyString(), any());
    }

    // ===== Successful start =====

    @Test
    void onApplicationReady_startsServer_whenExecutableFound() {
        // StagingExecutable is a Lombok @Data class — build via builder
        StagingServerLifecycleService.StagingExecutable exe =
                StagingServerLifecycleService.StagingExecutable.builder()
                        .type(StagingServerLifecycleService.ExecutableType.NATIVE_IMAGE)
                        .projectLocal(true)
                        .build();

        StagingServerLifecycleService.StartResult successResult =
                StagingServerLifecycleService.StartResult.builder()
                        .success(true)
                        .message("Started OK")
                        .build();

        when(lifecycleService.isAutoStartEnabled()).thenReturn(true);
        when(lifecycleService.isRunning(8080)).thenReturn(false);
        when(lifecycleService.findStagingExecutable()).thenReturn(exe);
        when(lifecycleService.startServer(8080)).thenReturn(successResult);

        service.onApplicationReady();

        verify(lifecycleService).startServer(8080);
        verify(wiringConfiguration).configureStagingService(eq("http://localhost:8080"), isNull());
    }

    // ===== Failed start =====

    @Test
    void onApplicationReady_doesNotConfigureWiring_whenStartFails() {
        StagingServerLifecycleService.StagingExecutable exe =
                StagingServerLifecycleService.StagingExecutable.builder()
                        .type(StagingServerLifecycleService.ExecutableType.JAR)
                        .projectLocal(false)
                        .build();

        StagingServerLifecycleService.StartResult failResult =
                StagingServerLifecycleService.StartResult.builder()
                        .success(false)
                        .message("Port in use")
                        .build();

        when(lifecycleService.isAutoStartEnabled()).thenReturn(true);
        when(lifecycleService.isRunning(8080)).thenReturn(false);
        when(lifecycleService.findStagingExecutable()).thenReturn(exe);
        when(lifecycleService.startServer(8080)).thenReturn(failResult);

        service.onApplicationReady();

        verify(wiringConfiguration, never()).configureStagingService(anyString(), any());
    }

    // ===== Null wiringConfiguration =====

    @Test
    void onApplicationReady_doesNotCrash_whenWiringConfigNull() {
        StagingAutoStartService noWiring = new StagingAutoStartService(lifecycleService, null);
        when(lifecycleService.isAutoStartEnabled()).thenReturn(true);
        when(lifecycleService.isRunning(8080)).thenReturn(true);

        assertThatCode(() -> noWiring.onApplicationReady()).doesNotThrowAnyException();
    }

    // ===== Port variations =====

    @Test
    void onApplicationReady_usesConfiguredPort() {
        when(lifecycleService.getConfiguredPort()).thenReturn(9090);
        when(lifecycleService.isAutoStartEnabled()).thenReturn(true);
        when(lifecycleService.isRunning(9090)).thenReturn(true);

        service = new StagingAutoStartService(lifecycleService, wiringConfiguration);
        service.onApplicationReady();

        verify(wiringConfiguration).configureStagingService(eq("http://localhost:9090"), isNull());
    }
}
