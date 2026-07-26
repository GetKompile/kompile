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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the MCP tool beans in an application context by annotation.
 *
 * <p>Both MCP registration paths — the stdio/SSE registry and Spring AI's
 * {@code ToolCallbackProvider} — used to hold one {@code @Autowired(required = false)} field per tool
 * class and a matching hand-written "add if not null" call. Those lists had two failure modes: a tool
 * could be built and never wired (the SSE list had drifted to roughly half the stdio list), and every
 * field was a compile-time edge from the registry to the tool class, which pinned the entire MCP
 * surface to one module. Splitting kompile-app-main into persona apps made the second one fatal:
 * a tool that follows its controllers into the chat or crawl web module would have broken the admin
 * build, and an app that legitimately does not ship a tool could not compile the registry.</p>
 *
 * <p>Discovery replaces both lists. A tool is exposed exactly when it is a bean on that app's
 * classpath — the same "absent means not registered" behaviour optional injection gave, without
 * naming the classes anywhere.</p>
 *
 * <p>The contract this relies on is asserted by {@code McpToolDiscoveryContractTest} in
 * kompile-app-main: {@code @Tool} classes must carry a Spring stereotype, and tool names must be
 * unique (Spring AI rejects duplicates at startup).</p>
 */
public final class McpToolBeanDiscovery {

    private static final Logger log = LoggerFactory.getLogger(McpToolBeanDiscovery.class);

    private McpToolBeanDiscovery() {
    }

    /**
     * Every bean in the context that declares at least one Spring AI {@code @Tool} method.
     *
     * <p>Types are resolved with {@code getType} first so discovery never forces a lazy bean to
     * instantiate; only beans that actually declare tool methods are pulled from the context.
     * Bean-definition names are sorted so registration order is stable across boots.</p>
     *
     * @param applicationContext the context to scan; {@code null} yields an empty list
     * @return the tool beans, never {@code null}
     */
    public static List<Object> discoverToolBeans(ApplicationContext applicationContext) {
        List<Object> toolBeans = new ArrayList<>();
        if (applicationContext == null) {
            log.warn("No ApplicationContext available — no MCP tools will be discovered");
            return toolBeans;
        }

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        Arrays.sort(beanNames);

        for (String beanName : beanNames) {
            Class<?> type;
            try {
                type = applicationContext.getType(beanName);
            } catch (Exception e) {
                log.debug("Could not resolve type of bean {} during tool discovery: {}", beanName, e.getMessage());
                continue;
            }
            if (type == null || !declaresToolMethods(type)) {
                continue;
            }
            try {
                toolBeans.add(applicationContext.getBean(beanName));
                log.debug("Found tool bean {} ({})", beanName, type.getSimpleName());
            } catch (Exception e) {
                log.warn("Tool bean {} declares @Tool methods but could not be obtained: {}",
                        beanName, e.getMessage());
            }
        }

        log.info("Discovered {} MCP tool beans", toolBeans.size());
        return toolBeans;
    }

    /**
     * True when the type's user class (unwrapping any CGLIB proxy, whose overrides do not carry the
     * annotation) declares a {@code @Tool} method.
     */
    public static boolean declaresToolMethods(Class<?> type) {
        for (Method method : ClassUtils.getUserClass(type).getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }
}
