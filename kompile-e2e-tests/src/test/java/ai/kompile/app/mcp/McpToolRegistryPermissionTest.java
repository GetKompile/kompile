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

package ai.kompile.app.mcp;

import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.app.services.mcp.ToolDefinitionService;
import ai.kompile.app.services.mcp.ToolPermissionService;
import ai.kompile.app.services.mcp.ToolPermissionService.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for permission enforcement in {@link McpToolRegistry}.
 * Tests the errorResult method, field injection, and permission service integration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolRegistry Permission Enforcement")
class McpToolRegistryPermissionTest {

    @Mock
    private McpActionLogService actionLogService;

    @Mock
    private ToolDefinitionService toolDefinitionService;

    @Mock
    private ToolPermissionService toolPermissionService;

    @Mock
    private BuiltInToolDiscoveryService toolDiscoveryService;

    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new McpToolRegistry(new ObjectMapper(), actionLogService, toolDefinitionService);
    }

    private void injectField(String fieldName, Object value) throws Exception {
        Field field = McpToolRegistry.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private Object getField(String fieldName) throws Exception {
        Field field = McpToolRegistry.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(registry);
    }

    // =========================================================================
    // Permission service injection
    // =========================================================================

    @Nested
    @DisplayName("Permission service injection")
    class PermissionServiceInjection {

        @Test
        @DisplayName("should accept toolPermissionService via field injection")
        void acceptsPermissionService() throws Exception {
            injectField("toolPermissionService", toolPermissionService);
            assertSame(toolPermissionService, getField("toolPermissionService"));
        }

        @Test
        @DisplayName("should accept toolDiscoveryService via field injection")
        void acceptsDiscoveryService() throws Exception {
            injectField("toolDiscoveryService", toolDiscoveryService);
            assertSame(toolDiscoveryService, getField("toolDiscoveryService"));
        }

        @Test
        @DisplayName("toolPermissionService should be null by default")
        void permissionServiceNullByDefault() throws Exception {
            assertNull(getField("toolPermissionService"));
        }

        @Test
        @DisplayName("toolDiscoveryService should be null by default")
        void discoveryServiceNullByDefault() throws Exception {
            assertNull(getField("toolDiscoveryService"));
        }
    }

    // =========================================================================
    // errorResult static method
    // =========================================================================

    @Nested
    @DisplayName("errorResult")
    class ErrorResult {

        @Test
        @DisplayName("should create CallToolResult with error flag and message")
        void createsErrorResult() {
            CallToolResult result = McpToolRegistry.errorResult("Tool 'write_file' is denied by permission policy");

            assertNotNull(result);
            assertTrue(result.isError());
            assertFalse(result.content().isEmpty());

            String text = ((TextContent) result.content().get(0)).text();
            assertTrue(text.contains("denied by permission policy"));
        }

        @Test
        @DisplayName("should include tool name in error message")
        void includesToolName() {
            CallToolResult result = McpToolRegistry.errorResult("Tool 'delete_file' is denied by permission policy");

            String text = ((TextContent) result.content().get(0)).text();
            assertTrue(text.contains("delete_file"));
        }

        @Test
        @DisplayName("should handle empty error message")
        void handlesEmptyMessage() {
            CallToolResult result = McpToolRegistry.errorResult("");

            assertNotNull(result);
            assertTrue(result.isError());
        }
    }

    // =========================================================================
    // Permission logic verification (unit test of the check logic)
    // =========================================================================

    @Nested
    @DisplayName("Permission check logic")
    class PermissionCheckLogic {

        @Test
        @DisplayName("ToolPermissionService correctly resolves tool > category > default")
        void verifyResolutionLogic() {
            // Use a real ToolPermissionService to verify the resolution logic
            // that McpToolRegistry depends on
            ToolPermissionService realService = new ToolPermissionService();

            // Default ALLOW
            assertTrue(realService.isToolAllowed("any_tool", "any_category"));

            // Set category DENY
            realService.setCategoryRule("filesystem", PermissionLevel.DENY);
            assertFalse(realService.isToolAllowed("write_file", "filesystem"));

            // Tool override ALLOW
            realService.setToolRule("read_file", PermissionLevel.ALLOW);
            assertTrue(realService.isToolAllowed("read_file", "filesystem"));
            assertFalse(realService.isToolAllowed("write_file", "filesystem"));
        }

        @Test
        @DisplayName("null permission service means no enforcement (all tools pass)")
        void nullServiceMeansNoEnforcement() throws Exception {
            // When toolPermissionService is null, the createToolSpec lambda skips the check.
            // We verify this by checking the field is null.
            assertNull(getField("toolPermissionService"),
                    "Permission service should be null by default, meaning no enforcement");
        }
    }
}
