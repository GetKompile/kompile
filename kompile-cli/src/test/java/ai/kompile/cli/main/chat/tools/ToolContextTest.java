/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolContext — especially path traversal protection (security-critical).
 */
@DisplayName("ToolContext")
class ToolContextTest {

    @TempDir
    Path workDir;

    private PermissionService permissionService;
    private AgentConfig agent;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService();
        // Allow all so permission checks don't block tests
        permissionService.allowAll();
        agent = AgentConfig.builder("test").build();
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        context = new ToolContext("test-session", agent, permissionService, workDir, registry);
    }

    @Nested
    @DisplayName("Path resolution")
    class PathResolution {

        @Test
        void resolvesRelativePathWithinWorkDir() throws ToolExecutionException {
            Path resolved = context.resolvePath("src/main/java/Foo.java");
            assertTrue(resolved.startsWith(workDir));
            assertEquals(workDir.resolve("src/main/java/Foo.java"), resolved);
        }

        @Test
        void resolvesNestedRelativePath() throws ToolExecutionException {
            Path resolved = context.resolvePath("subdir/file.txt");
            assertEquals(workDir.resolve("subdir/file.txt"), resolved);
        }

        @Test
        void traversalWithDotDotBlockedWithPermissionCheck() {
            // Create a permission service that denies external directory access
            PermissionService denyingService = new PermissionService();
            denyingService.setUserOverride("external_directory", PermissionService.PermissionLevel.DENY);
            ToolContext restrictedContext = new ToolContext("test", agent, denyingService, workDir,
                    new ToolRegistry(new ObjectMapper()));

            assertThrows(ToolExecutionException.class, () -> {
                restrictedContext.resolvePath("../../etc/passwd");
            });
        }

        @Test
        void absolutePathOutsideWorkDirBlockedWithPermissionCheck() {
            PermissionService denyingService = new PermissionService();
            denyingService.setUserOverride("external_directory", PermissionService.PermissionLevel.DENY);
            ToolContext restrictedContext = new ToolContext("test", agent, denyingService, workDir,
                    new ToolRegistry(new ObjectMapper()));

            assertThrows(ToolExecutionException.class, () -> {
                restrictedContext.resolvePath("/etc/passwd");
            });
        }

        @Test
        void pathWithDotDotStayingInsideWorkDirIsAllowed() throws ToolExecutionException {
            // subdir/../file.txt normalizes to file.txt which is still in workDir
            Path resolved = context.resolvePath("subdir/../file.txt");
            assertTrue(resolved.startsWith(workDir));
            assertEquals(workDir.resolve("file.txt"), resolved);
        }

        @Test
        void currentDirPathIsAllowed() throws ToolExecutionException {
            Path resolved = context.resolvePath(".");
            assertEquals(workDir, resolved);
        }
    }

    @Nested
    @DisplayName("Abort signal")
    class AbortSignal {

        @Test
        void initiallyNotAborted() {
            assertFalse(context.isAborted());
        }

        @Test
        void abortSetsSignal() {
            context.abort();
            assertTrue(context.isAborted());
        }

        @Test
        void abortSignalIsSharedReference() {
            var signal = context.getAbortSignal();
            assertFalse(signal.get());
            context.abort();
            assertTrue(signal.get());
        }
    }

    @Nested
    @DisplayName("Permission checks")
    class PermissionChecks {

        @Test
        void allowedPermissionDoesNotThrow() {
            // "read" is ALLOW by default
            assertDoesNotThrow(() -> context.checkPermission("read", "Read a file"));
        }

        @Test
        void deniedPermissionThrowsToolExecutionException() {
            PermissionService denyingService = new PermissionService();
            denyingService.setUserOverride("write", PermissionService.PermissionLevel.DENY);
            ToolContext restrictedContext = new ToolContext("test", agent, denyingService, workDir,
                    new ToolRegistry(new ObjectMapper()));

            ToolExecutionException ex = assertThrows(ToolExecutionException.class, () -> {
                restrictedContext.checkPermission("write", "Write a file");
            });
            assertTrue(ex.isPermissionDenied());
        }
    }

    @Test
    void gettersReturnConstructorValues() {
        assertEquals("test-session", context.getSessionId());
        assertEquals(agent, context.getAgent());
        assertEquals(permissionService, context.getPermissionService());
        assertEquals(workDir, context.getWorkingDirectory());
        assertNotNull(context.getToolRegistry());
    }
}
