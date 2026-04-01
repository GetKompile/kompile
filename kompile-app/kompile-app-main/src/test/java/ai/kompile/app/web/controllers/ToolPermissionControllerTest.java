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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.ToolPermissionService;
import ai.kompile.app.services.mcp.ToolPermissionService.PermissionLevel;
import ai.kompile.app.services.mcp.ToolPermissionService.ToolPermissionConfig;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link ToolPermissionController}.
 * Uses standalone MockMvc to avoid loading Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolPermissionController Tests")
class ToolPermissionControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ToolPermissionService permissionService;

    @Mock
    private BuiltInToolDiscoveryService toolDiscoveryService;

    @InjectMocks
    private ToolPermissionController controller;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Inject the field-injected toolDiscoveryService via reflection
        // since @InjectMocks doesn't handle @Autowired(required=false) fields
        java.lang.reflect.Field field = ToolPermissionController.class.getDeclaredField("toolDiscoveryService");
        field.setAccessible(true);
        field.set(controller, toolDiscoveryService);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    private ToolPermissionConfig createDefaultConfig() {
        ToolPermissionConfig config = new ToolPermissionConfig();
        config.setDefaultPermission(PermissionLevel.ALLOW);
        return config;
    }

    private ToolPermissionConfig createConfigWithRules() {
        ToolPermissionConfig config = new ToolPermissionConfig();
        config.setDefaultPermission(PermissionLevel.ALLOW);
        config.getCategoryRules().put("filesystem", PermissionLevel.DENY);
        config.getToolRules().put("read_file", PermissionLevel.ALLOW);
        return config;
    }

    // =========================================================================
    // GET /api/tool-permissions
    // =========================================================================

    @Nested
    @DisplayName("GET /api/tool-permissions")
    class GetConfig {

        @Test
        @DisplayName("should return 200 with config")
        void returnsConfig() throws Exception {
            ToolPermissionConfig config = createConfigWithRules();
            when(permissionService.getConfig()).thenReturn(config);

            mockMvc.perform(get("/api/tool-permissions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultPermission", is("ALLOW")))
                    .andExpect(jsonPath("$.categoryRules.filesystem", is("DENY")))
                    .andExpect(jsonPath("$.toolRules.read_file", is("ALLOW")));
        }

        @Test
        @DisplayName("should return empty rules when no config")
        void returnsEmptyRules() throws Exception {
            when(permissionService.getConfig()).thenReturn(createDefaultConfig());

            mockMvc.perform(get("/api/tool-permissions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultPermission", is("ALLOW")))
                    .andExpect(jsonPath("$.categoryRules", anEmptyMap()))
                    .andExpect(jsonPath("$.toolRules", anEmptyMap()));
        }
    }

    // =========================================================================
    // GET /api/tool-permissions/tools-with-status
    // =========================================================================

    @Nested
    @DisplayName("GET /api/tool-permissions/tools-with-status")
    class GetToolsWithStatus {

        @Test
        @DisplayName("should return tools grouped by category with resolved permissions")
        void returnsToolsWithStatus() throws Exception {
            ToolPermissionConfig config = new ToolPermissionConfig();
            config.getCategoryRules().put("filesystem", PermissionLevel.DENY);
            config.getToolRules().put("read_file", PermissionLevel.ALLOW);
            when(permissionService.getConfig()).thenReturn(config);

            EnhancedToolDefinition readFile = EnhancedToolDefinition.builder()
                    .name("read_file")
                    .description("Read a file")
                    .category("filesystem")
                    .build();
            EnhancedToolDefinition writeFile = EnhancedToolDefinition.builder()
                    .name("write_file")
                    .description("Write a file")
                    .category("filesystem")
                    .build();
            when(toolDiscoveryService.getEnhancedToolDefinitions())
                    .thenReturn(List.of(readFile, writeFile));
            when(permissionService.isToolAllowed("read_file", "filesystem")).thenReturn(true);
            when(permissionService.isToolAllowed("write_file", "filesystem")).thenReturn(false);

            mockMvc.perform(get("/api/tool-permissions/tools-with-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultPermission", is("ALLOW")))
                    .andExpect(jsonPath("$.categories.filesystem.displayName", is("File System Operations")))
                    .andExpect(jsonPath("$.categories.filesystem.toolCount", is(2)))
                    .andExpect(jsonPath("$.tools", hasSize(2)))
                    .andExpect(jsonPath("$.tools[0].name", is("read_file")))
                    .andExpect(jsonPath("$.tools[0].resolvedPermission", is("ALLOW")))
                    .andExpect(jsonPath("$.tools[0].hasOverride", is(true)))
                    .andExpect(jsonPath("$.tools[1].name", is("write_file")))
                    .andExpect(jsonPath("$.tools[1].resolvedPermission", is("DENY")))
                    .andExpect(jsonPath("$.tools[1].hasOverride", is(false)));
        }

        @Test
        @DisplayName("should return empty when no tools discovered")
        void returnsEmptyWhenNoTools() throws Exception {
            when(permissionService.getConfig()).thenReturn(createDefaultConfig());
            when(toolDiscoveryService.getEnhancedToolDefinitions()).thenReturn(List.of());

            mockMvc.perform(get("/api/tool-permissions/tools-with-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tools", hasSize(0)))
                    .andExpect(jsonPath("$.categories", anEmptyMap()));
        }
    }

    // =========================================================================
    // PUT /api/tool-permissions/default
    // =========================================================================

    @Nested
    @DisplayName("PUT /api/tool-permissions/default")
    class SetDefault {

        @Test
        @DisplayName("should set default permission to DENY")
        void setDefaultDeny() throws Exception {
            mockMvc.perform(put("/api/tool-permissions/default")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"DENY\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultPermission", is("DENY")));

            verify(permissionService).setDefaultPermission(PermissionLevel.DENY);
        }

        @Test
        @DisplayName("should set default permission to ALLOW")
        void setDefaultAllow() throws Exception {
            mockMvc.perform(put("/api/tool-permissions/default")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"ALLOW\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultPermission", is("ALLOW")));

            verify(permissionService).setDefaultPermission(PermissionLevel.ALLOW);
        }
    }

    // =========================================================================
    // PUT /api/tool-permissions/category/{name}
    // =========================================================================

    @Nested
    @DisplayName("PUT /api/tool-permissions/category/{name}")
    class SetCategoryRule {

        @Test
        @DisplayName("should set category rule")
        void setCategoryRule() throws Exception {
            mockMvc.perform(put("/api/tool-permissions/category/filesystem")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"DENY\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category", is("filesystem")))
                    .andExpect(jsonPath("$.permission", is("DENY")));

            verify(permissionService).setCategoryRule("filesystem", PermissionLevel.DENY);
        }
    }

    // =========================================================================
    // DELETE /api/tool-permissions/category/{name}
    // =========================================================================

    @Nested
    @DisplayName("DELETE /api/tool-permissions/category/{name}")
    class RemoveCategoryRule {

        @Test
        @DisplayName("should remove category rule")
        void removeCategoryRule() throws Exception {
            mockMvc.perform(delete("/api/tool-permissions/category/filesystem"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category", is("filesystem")))
                    .andExpect(jsonPath("$.removed", is(true)));

            verify(permissionService).removeCategoryRule("filesystem");
        }
    }

    // =========================================================================
    // PUT /api/tool-permissions/tool/{name}
    // =========================================================================

    @Nested
    @DisplayName("PUT /api/tool-permissions/tool/{name}")
    class SetToolRule {

        @Test
        @DisplayName("should set tool rule")
        void setToolRule() throws Exception {
            mockMvc.perform(put("/api/tool-permissions/tool/read_file")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"permission\":\"ALLOW\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tool", is("read_file")))
                    .andExpect(jsonPath("$.permission", is("ALLOW")));

            verify(permissionService).setToolRule("read_file", PermissionLevel.ALLOW);
        }
    }

    // =========================================================================
    // DELETE /api/tool-permissions/tool/{name}
    // =========================================================================

    @Nested
    @DisplayName("DELETE /api/tool-permissions/tool/{name}")
    class RemoveToolRule {

        @Test
        @DisplayName("should remove tool rule")
        void removeToolRule() throws Exception {
            mockMvc.perform(delete("/api/tool-permissions/tool/read_file"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tool", is("read_file")))
                    .andExpect(jsonPath("$.removed", is(true)));

            verify(permissionService).removeToolRule("read_file");
        }
    }

    // =========================================================================
    // POST /api/tool-permissions/bulk
    // =========================================================================

    @Nested
    @DisplayName("POST /api/tool-permissions/bulk")
    class BulkUpdate {

        @Test
        @DisplayName("should apply bulk update with category and tool rules")
        void bulkUpdateAll() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "defaultPermission", "DENY",
                    "categoryRules", Map.of("filesystem", "DENY", "rag", "ALLOW"),
                    "toolRules", Map.of("read_file", "ALLOW")
            ));

            mockMvc.perform(post("/api/tool-permissions/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated", is(true)));

            verify(permissionService).setDefaultPermission(PermissionLevel.DENY);
            verify(permissionService).bulkUpdate(
                    argThat(m -> m.get("filesystem") == PermissionLevel.DENY && m.get("rag") == PermissionLevel.ALLOW),
                    argThat(m -> m.get("read_file") == PermissionLevel.ALLOW)
            );
        }

        @Test
        @DisplayName("should handle bulk update with only default permission")
        void bulkUpdateDefaultOnly() throws Exception {
            String body = "{\"defaultPermission\":\"DENY\"}";

            mockMvc.perform(post("/api/tool-permissions/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated", is(true)));

            verify(permissionService).setDefaultPermission(PermissionLevel.DENY);
            verify(permissionService).bulkUpdate(null, null);
        }
    }
}
