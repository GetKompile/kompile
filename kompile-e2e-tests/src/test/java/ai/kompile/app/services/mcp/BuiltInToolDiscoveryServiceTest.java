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

package ai.kompile.app.services.mcp;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.DiscoveredTool;
import ai.kompile.app.services.mcp.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BuiltInToolDiscoveryService}.
 * Tests tool discovery, category inference, and metadata extraction
 * without loading Spring context or ND4J.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BuiltInToolDiscoveryService")
class BuiltInToolDiscoveryServiceTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ServerPortService serverPortService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private BuiltInToolDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new BuiltInToolDiscoveryService(applicationContext, objectMapper, serverPortService);
    }

    /**
     * Directly injects discovered tools into the service via reflection
     * for testing methods that operate on the tool list.
     */
    private void injectDiscoveredTools(List<DiscoveredTool> tools) throws Exception {
        Field field = BuiltInToolDiscoveryService.class.getDeclaredField("discoveredTools");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<DiscoveredTool> internalList = (List<DiscoveredTool>) field.get(service);
        internalList.clear();
        internalList.addAll(tools);
    }

    private DiscoveredTool createTool(String name, String description, String beanName,
                                       String methodName, String beanClass) {
        DiscoveredTool tool = new DiscoveredTool();
        tool.setName(name);
        tool.setDescription(description);
        tool.setBeanName(beanName);
        tool.setMethodName(methodName);
        tool.setBeanClass(beanClass);
        tool.setReturnType("Map");
        tool.setParameters(new ArrayList<>());
        return tool;
    }

    // =========================================================================
    // getDiscoveredTools
    // =========================================================================

    @Nested
    @DisplayName("getDiscoveredTools")
    class GetDiscoveredTools {

        @Test
        @DisplayName("returns empty list before discovery")
        void returnsEmptyListBeforeDiscovery() {
            List<DiscoveredTool> tools = service.getDiscoveredTools();
            assertNotNull(tools);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("returns discovered tools after injection")
        void returnsToolsAfterInjection() throws Exception {
            DiscoveredTool tool = createTool("rag_query", "Query RAG", "ragTool", "ragQuery", "ai.kompile.RagTool");
            injectDiscoveredTools(List.of(tool));

            List<DiscoveredTool> tools = service.getDiscoveredTools();
            assertEquals(1, tools.size());
            assertEquals("rag_query", tools.get(0).getName());
        }

        @Test
        @DisplayName("returns defensive copy - modifications do not affect internal state")
        void returnsDefensiveCopy() throws Exception {
            DiscoveredTool tool = createTool("test_tool", "Test", "bean", "method", "Class");
            injectDiscoveredTools(List.of(tool));

            List<DiscoveredTool> copy = service.getDiscoveredTools();
            copy.clear();

            // Internal list should still have the tool
            assertEquals(1, service.getDiscoveredTools().size());
        }
    }

    // =========================================================================
    // Category inference
    // =========================================================================

    @Nested
    @DisplayName("Category inference")
    class CategoryInference {

        /**
         * Invokes the private inferCategory method via reflection.
         */
        private String invokeInferCategory(String toolName, String description) throws Exception {
            Method m = BuiltInToolDiscoveryService.class.getDeclaredMethod("inferCategory", String.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(service, toolName, description);
        }

        @Test
        @DisplayName("infers 'rag' category from tool name containing 'query'")
        void infersRagFromQuery() throws Exception {
            assertEquals("rag", invokeInferCategory("rag_query", "Performs a search"));
        }

        @Test
        @DisplayName("infers 'filesystem' category from tool name containing 'file'")
        void infersFilesystemFromFile() throws Exception {
            assertEquals("filesystem", invokeInferCategory("read_file", "Reads a file from disk"));
        }

        @Test
        @DisplayName("infers 'chat' category from tool name containing 'chat'")
        void infersChatFromChat() throws Exception {
            assertEquals("chat", invokeInferCategory("chat_session", "Manages chat sessions"));
        }

        @Test
        @DisplayName("infers 'system' as default when no keywords match")
        void infersSystemAsDefault() throws Exception {
            assertEquals("system", invokeInferCategory("unknown_operation", "Does something obscure"));
        }

        @Test
        @DisplayName("infers category from description when name has no match")
        void infersCategoryFromDescription() throws Exception {
            assertEquals("rag", invokeInferCategory("my_tool", "Searches documents in the index"));
        }

        @Test
        @DisplayName("infers 'indexing' category from tool name containing 'index'")
        void infersIndexingFromIndex() throws Exception {
            assertEquals("indexing", invokeInferCategory("rebuild_index", "Rebuilds the data index"));
        }
    }

    // =========================================================================
    // Tool discovery with mocked context
    // =========================================================================

    @Nested
    @DisplayName("discoverBuiltInTools")
    class DiscoverBuiltInTools {

        @Test
        @DisplayName("discovers tools from beans with @Tool methods")
        void discoversToolsFromBeans() {
            // Create a test bean class that has @Tool annotation
            TestToolBean testBean = new TestToolBean();

            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"testToolBean"});
            when(applicationContext.getBean("testToolBean")).thenReturn(testBean);

            service.discoverBuiltInTools();

            List<DiscoveredTool> tools = service.getDiscoveredTools();
            assertEquals(1, tools.size());
            assertEquals("test_search", tools.get(0).getName());
            assertEquals("Searches for test data", tools.get(0).getDescription());
        }

        @Test
        @DisplayName("handles beans with no @Tool methods")
        void handlesBeanWithNoToolMethods() {
            Object plainBean = new Object();

            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"plainBean"});
            when(applicationContext.getBean("plainBean")).thenReturn(plainBean);

            service.discoverBuiltInTools();

            assertTrue(service.getDiscoveredTools().isEmpty());
        }

        @Test
        @DisplayName("handles exception when introspecting beans")
        void handlesExceptionDuringIntrospection() {
            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"failingBean"});
            when(applicationContext.getBean("failingBean")).thenThrow(new RuntimeException("Bean creation failed"));

            assertDoesNotThrow(() -> service.discoverBuiltInTools());
            assertTrue(service.getDiscoveredTools().isEmpty());
        }

        @Test
        @DisplayName("clears previous discoveries on re-run")
        void clearsPreviousDiscoveries() throws Exception {
            // Inject a tool first
            DiscoveredTool existing = createTool("old_tool", "Old", "bean", "method", "Class");
            injectDiscoveredTools(List.of(existing));
            assertEquals(1, service.getDiscoveredTools().size());

            // Re-discover with no beans
            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});

            service.discoverBuiltInTools();

            assertTrue(service.getDiscoveredTools().isEmpty());
        }
    }

    // =========================================================================
    // getToolsByCategory
    // =========================================================================

    @Nested
    @DisplayName("getToolsByCategory")
    class GetToolsByCategory {

        @Test
        @DisplayName("groups tools by inferred category")
        void groupsToolsByCategory() throws Exception {
            DiscoveredTool ragTool = createTool("rag_query", "Query documents", "ragBean", "query", "RagClass");
            DiscoveredTool fileTool = createTool("read_file", "Read a file", "fileBean", "read", "FileClass");
            injectDiscoveredTools(List.of(ragTool, fileTool));

            Map<String, List<DiscoveredTool>> byCategory = service.getToolsByCategory();

            assertTrue(byCategory.containsKey("rag"));
            assertTrue(byCategory.containsKey("filesystem"));
            assertEquals(1, byCategory.get("rag").size());
            assertEquals(1, byCategory.get("filesystem").size());
        }

        @Test
        @DisplayName("returns empty map when no tools discovered")
        void returnsEmptyMapWhenNoTools() {
            Map<String, List<DiscoveredTool>> byCategory = service.getToolsByCategory();
            assertTrue(byCategory.isEmpty());
        }
    }

    // =========================================================================
    // ToolParameter metadata
    // =========================================================================

    @Nested
    @DisplayName("ToolParameter metadata")
    class ToolParameterMetadata {

        @Test
        @DisplayName("tool parameters retain name, type, and required flag")
        void toolParametersRetainMetadata() {
            ToolParameter param = new ToolParameter();
            param.setName("query");
            param.setType("string");
            param.setDescription("The search query");
            param.setRequired(true);

            assertEquals("query", param.getName());
            assertEquals("string", param.getType());
            assertEquals("The search query", param.getDescription());
            assertTrue(param.isRequired());
        }

        @Test
        @DisplayName("tool parameter defaults are sensible")
        void toolParameterDefaults() {
            ToolParameter param = new ToolParameter();
            assertNull(param.getName());
            assertNull(param.getType());
            assertNull(param.getDescription());
            assertFalse(param.isRequired());
        }
    }

    // =========================================================================
    // Test helper bean with @Tool annotation
    // =========================================================================

    /**
     * A test bean with a @Tool annotated method, used for discovery tests.
     */
    static class TestToolBean {
        @Tool(name = "test_search", description = "Searches for test data")
        public Map<String, Object> testSearch(String query) {
            return Map.of("result", query);
        }
    }
}
