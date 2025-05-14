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

package ai.kompile.app.web.controllers;

import ai.kompile.tool.filesystem.FilesystemToolImpl;
import ai.kompile.tool.rag.RagToolImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mcp/tools")
public class McpToolController {

    private static final Logger logger = LoggerFactory.getLogger(McpToolController.class);
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    private final RagToolImpl ragToolImpl;
    private final FilesystemToolImpl filesystemToolImpl;

    @Autowired
    public McpToolController(ApplicationContext applicationContext,
                             ObjectMapper objectMapper,
                             RagToolImpl ragToolImpl,
                             FilesystemToolImpl filesystemToolImpl) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.ragToolImpl = ragToolImpl;
        this.filesystemToolImpl = filesystemToolImpl;
        logger.info("McpToolController initialized with RagToolImpl: {} and FilesystemToolImpl: {}",
                ragToolImpl.getClass().getSimpleName(), filesystemToolImpl.getClass().getSimpleName());
    }

    public record FrontendToolCallRequest(String toolName, Map<String, Object> arguments) {}

    @GetMapping("/list")
    public ResponseEntity<?> listAvailableTools() {
        List<Map<String, String>> toolInfos = new ArrayList<>();

        // Explicitly add known tool beans to the list to scan
        List<Object> beansToScanForTools = new ArrayList<>();
        if (ragToolImpl != null) beansToScanForTools.add(ragToolImpl);
        if (filesystemToolImpl != null) beansToScanForTools.add(filesystemToolImpl);

        // Optionally, scan for other @Component or @Service beans if tools can be defined elsewhere
        // String[] componentBeanNames = applicationContext.getBeanNamesForAnnotation(Component.class);
        // for (String beanName : componentBeanNames) {
        //     Object bean = applicationContext.getBean(beanName);
        //     if (!beansToScanForTools.contains(bean)) { // Avoid double processing
        //         beansToScanForTools.add(bean);
        //     }
        // }
        // String[] serviceBeanNames = applicationContext.getBeanNamesForAnnotation(Service.class);
        //  for (String beanName : serviceBeanNames) {
        //     Object bean = applicationContext.getBean(beanName);
        //     if (!beansToScanForTools.contains(bean)) { // Avoid double processing
        //         beansToScanForTools.add(bean);
        //     }
        // }


        for (Object bean : beansToScanForTools) {
            if (bean == null) continue;
            try {
                for (Method method : bean.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        Tool toolAnnotation = method.getAnnotation(Tool.class);
                        // Ensure no duplicate tool names if multiple beans processed this way
                        if (toolInfos.stream().noneMatch(info -> info.get("name").equals(toolAnnotation.name()))) {
                            toolInfos.add(Map.of(
                                    "name", toolAnnotation.name(),
                                    "description", toolAnnotation.description(),
                                    "note", "Full input schema available via MCP tools/list protocol."
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error introspecting bean {} for tools: {}", bean.getClass().getName(), e.getMessage(), e);
            }
        }

        if (toolInfos.isEmpty()) {
            logger.warn("No @Tool annotated methods found via introspection for /api/mcp/tools/list endpoint. " +
                    "Ensure tool implementation beans (e.g., RagToolImpl, FilesystemToolImpl) are correctly defined and scanned.");
        }

        return ResponseEntity.ok(toolInfos);
    }

    @PostMapping("/invoke-direct")
    public ResponseEntity<?> invokeToolDirectly(@RequestBody FrontendToolCallRequest request) {
        logger.info("McpToolController received direct tool invocation request: {}", request);
        if (request.toolName() == null || request.toolName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "toolName cannot be empty."));
        }

        try {
            Object result = null;
            switch (request.toolName()) {
                case "rag_query":
                    if (request.arguments() == null) { /* ... error ... */ }
                    RagToolImpl.RagQueryInput ragInput = objectMapper.convertValue(request.arguments(), RagToolImpl.RagQueryInput.class);
                    result = ragToolImpl.executeRagQuery(ragInput);
                    break;
                case "list_files":
                    if (request.arguments() == null) { /* ... error ... */ }
                    FilesystemToolImpl.ListFilesInput listInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.ListFilesInput.class);
                    result = filesystemToolImpl.listFiles(listInput);
                    break;
                case "read_file":
                    if (request.arguments() == null) { /* ... error ... */ }
                    FilesystemToolImpl.ReadFileInput readInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.ReadFileInput.class);
                    result = filesystemToolImpl.readFile(readInput);
                    break;
                default:
                    logger.warn("Tool not found or direct invocation not supported for: {}", request.toolName());
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "Tool not found or direct invocation not supported: " + request.toolName()));
            }
            return ResponseEntity.ok(Map.of("toolName", request.toolName(), "result", result));
        } catch (Exception e) {
            logger.error("Error invoking tool '{}' directly: {}", request.toolName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to invoke tool " + request.toolName() + ": " + e.getMessage()));
        }
    }
}