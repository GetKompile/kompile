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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubprocessIngestLauncher}.
 * Tests status tracking, cancellation, and process management logic
 * without actually spawning subprocesses or loading Spring context / ND4J.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubprocessIngestLauncher")
class SubprocessIngestLauncherTest {

    private SubprocessIngestLauncher launcher;

    @BeforeEach
    void setUp() {
        // All constructor dependencies are optional (@Autowired(required = false))
        launcher = new SubprocessIngestLauncher(
                null,   // progressTracker
                null,   // eventService
                null,   // jobHistoryService
                null,   // serverPortService
                null,   // nd4jEnvironmentConfigService
                null,   // deviceRoutingConfigService
                null,   // subprocessConfigService
                null,   // subprocessExecutableConfig
                null,   // factSheetService
                null,   // appIndexConfigService
                null    // ingestConfiguration
        );
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    // =========================================================================
    // getAllStatuses
    // =========================================================================

    @Nested
    @DisplayName("getAllStatuses")
    class GetAllStatuses {

        @Test
        @DisplayName("returns empty list when no processes are active")
        void returnsEmptyListWhenNoProcesses() {
            List<SubprocessHandle.SubprocessStatus> statuses = launcher.getAllStatuses();
            assertNotNull(statuses);
            assertTrue(statuses.isEmpty());
        }
    }

    // =========================================================================
    // getStatus
    // =========================================================================

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("returns null for unknown taskId")
        void returnsNullForUnknownTaskId() {
            SubprocessHandle.SubprocessStatus status = launcher.getStatus("nonexistent-task");
            assertNull(status);
        }

        @Test
        @DisplayName("returns null for empty taskId")
        void returnsNullForEmptyTaskId() {
            SubprocessHandle.SubprocessStatus status = launcher.getStatus("");
            assertNull(status);
        }

        @Test
        @DisplayName("returns null for null-like taskId")
        void returnsNullForNullLikeTaskId() {
            SubprocessHandle.SubprocessStatus status = launcher.getStatus("null");
            assertNull(status);
        }
    }

    // =========================================================================
    // cancelIngest
    // =========================================================================

    @Nested
    @DisplayName("cancelIngest")
    class CancelIngest {

        @Test
        @DisplayName("returns false for unknown taskId")
        void returnsFalseForUnknownTaskId() {
            boolean cancelled = launcher.cancelIngest("nonexistent-task");
            assertFalse(cancelled);
        }

        @Test
        @DisplayName("returns false for empty taskId")
        void returnsFalseForEmptyTaskId() {
            boolean cancelled = launcher.cancelIngest("");
            assertFalse(cancelled);
        }
    }

    // =========================================================================
    // Internal state initialization
    // =========================================================================

    @Nested
    @DisplayName("Internal state initialization")
    class InternalState {

        @Test
        @DisplayName("activeProcesses map is initialized and empty")
        void activeProcessesMapInitialized() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, ?> activeProcesses = (Map<String, ?>) getField(launcher, "activeProcesses");
            assertNotNull(activeProcesses);
            assertTrue(activeProcesses.isEmpty());
            assertTrue(activeProcesses instanceof ConcurrentHashMap);
        }

        @Test
        @DisplayName("taskFilePaths map is initialized and empty")
        void taskFilePathsMapInitialized() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, ?> taskFilePaths = (Map<String, ?>) getField(launcher, "taskFilePaths");
            assertNotNull(taskFilePaths);
            assertTrue(taskFilePaths.isEmpty());
        }

        @Test
        @DisplayName("checkpoint base directory is initialized")
        void checkpointBaseDirInitialized() throws Exception {
            Object checkpointBaseDir = getField(launcher, "checkpointBaseDir");
            assertNotNull(checkpointBaseDir, "checkpointBaseDir should be initialized even with null deps");
        }

        @Test
        @DisplayName("objectMapper is initialized")
        void objectMapperInitialized() throws Exception {
            Object objectMapper = getField(launcher, "objectMapper");
            assertNotNull(objectMapper);
        }

        @Test
        @DisplayName("service initializes with all null optional dependencies")
        void initializesWithAllNullDeps() {
            // Verify the launcher was created successfully in setUp()
            assertNotNull(launcher);
        }
    }

    // =========================================================================
    // Dependency null-safety
    // =========================================================================

    @Nested
    @DisplayName("Dependency null-safety")
    class DependencyNullSafety {

        @Test
        @DisplayName("progressTracker can be null without error")
        void progressTrackerCanBeNull() throws Exception {
            Object tracker = getField(launcher, "progressTracker");
            assertNull(tracker);
        }

        @Test
        @DisplayName("serverPortService can be null without error")
        void serverPortServiceCanBeNull() throws Exception {
            Object portService = getField(launcher, "serverPortService");
            assertNull(portService);
        }

        @Test
        @DisplayName("subprocessConfigService can be null without error")
        void subprocessConfigServiceCanBeNull() throws Exception {
            Object configService = getField(launcher, "subprocessConfigService");
            assertNull(configService);
        }

        @Test
        @DisplayName("factSheetService can be null without error")
        void factSheetServiceCanBeNull() throws Exception {
            Object factService = getField(launcher, "factSheetService");
            assertNull(factService);
        }
    }
}
