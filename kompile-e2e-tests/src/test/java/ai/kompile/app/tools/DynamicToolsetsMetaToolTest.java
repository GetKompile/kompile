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

package ai.kompile.app.tools;

import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService.DiscoveredTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DynamicToolsetsMetaTool")
class DynamicToolsetsMetaToolTest {

    static class Sample {
        public record EchoInput(String message) {}

        @Tool(name = "echo_tool", description = "Echoes message back")
        public Map<String, Object> echo(EchoInput input) {
            return Map.of("echoed", input.message());
        }
    }

    private static BuiltInToolDiscoveryService mockDiscovery(DiscoveredTool... tools) {
        BuiltInToolDiscoveryService svc = mock(BuiltInToolDiscoveryService.class);
        when(svc.getDiscoveredTools()).thenReturn(List.of(tools));
        return svc;
    }

    private static DiscoveredTool discovered(String name, String description,
                                             String beanName, String beanClass,
                                             String methodName) {
        DiscoveredTool t = new DiscoveredTool();
        t.setName(name);
        t.setDescription(description);
        t.setBeanName(beanName);
        t.setBeanClass(beanClass);
        t.setMethodName(methodName);
        return t;
    }

    @Test
    void searchToolsReturnsMatchesFromDiscoveryFallback() {
        var meta = new DynamicToolsetsMetaTool(
                new StaticApplicationContext(),
                new ObjectMapper(),
                mockDiscovery(discovered("echo_tool", "Echoes message back", null, null, "echo")),
                null);

        Map<String, Object> response = meta.searchTools(new DynamicToolsetsMetaTool.SearchToolsInput("echo", null));
        assertEquals("echo", response.get("query"));
        assertEquals(1, response.get("count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) response.get("matches");
        assertEquals("echo_tool", matches.get(0).get("name"));
    }

    @Test
    void searchToolsEmptyQueryReturnsAll() {
        var meta = new DynamicToolsetsMetaTool(
                new StaticApplicationContext(),
                new ObjectMapper(),
                mockDiscovery(
                        discovered("a", "desc-a", null, null, "a"),
                        discovered("b", "desc-b", null, null, "b")),
                null);
        Map<String, Object> response = meta.searchTools(new DynamicToolsetsMetaTool.SearchToolsInput("", null));
        assertEquals(2, response.get("count"));
    }

    @Test
    void executeToolInvokesNamedTool() {
        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.getBeanFactory().registerSingleton("sample", new Sample());

        var discovery = mockDiscovery(discovered("echo_tool", "desc", "sample",
                Sample.class.getName(), "echo"));

        var meta = new DynamicToolsetsMetaTool(ctx, new ObjectMapper(), discovery, null);

        Object response = meta.executeTool(new DynamicToolsetsMetaTool.ExecuteToolInput(
                "echo_tool", Map.of("message", "hi!")));
        assertInstanceOf(Map.class, response);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) response;
        assertEquals("echo_tool", map.get("tool"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map.get("result");
        assertEquals("hi!", result.get("echoed"));
    }

    @Test
    void executeToolUnknownNameReturnsErrorMap() {
        var meta = new DynamicToolsetsMetaTool(
                new StaticApplicationContext(), new ObjectMapper(),
                mockDiscovery(), null);
        Object response = meta.executeTool(
                new DynamicToolsetsMetaTool.ExecuteToolInput("nobody_home", Map.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) response;
        assertNotNull(map.get("error"));
    }

    @Test
    void executeToolNullInputErrors() {
        var meta = new DynamicToolsetsMetaTool(
                new StaticApplicationContext(), new ObjectMapper(),
                mockDiscovery(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) meta.executeTool(null);
        assertNotNull(map.get("error"));
    }

    @Test
    void executeToolMissingBeanReturnsError() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(anyString())).thenThrow(new NoSuchBeanDefinitionException("sample"));
        when(ctx.getBean(any(Class.class))).thenThrow(new NoSuchBeanDefinitionException("sample"));

        var discovery = mockDiscovery(discovered("echo_tool", "desc", "sample",
                Sample.class.getName(), "echo"));

        var meta = new DynamicToolsetsMetaTool(ctx, new ObjectMapper(), discovery, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) meta.executeTool(
                new DynamicToolsetsMetaTool.ExecuteToolInput("echo_tool", Map.of("message", "x")));
        assertTrue(Objects.toString(out.get("error")).contains("bean not available"));
    }

    @Test
    void describeToolsReportsMissing() {
        var meta = new DynamicToolsetsMetaTool(
                new StaticApplicationContext(), new ObjectMapper(),
                mockDiscovery(), null);
        Map<String, Object> response = meta.describeTools(
                new DynamicToolsetsMetaTool.DescribeToolsInput(List.of("unknown")));
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) response.get("missing");
        assertEquals(List.of("unknown"), missing);
    }
}
