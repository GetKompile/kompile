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
package ai.kompile.app.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

/**
 * Diagnostic component that logs all registered request mappings at startup.
 * Helps identify when controllers are created but not properly mapped.
 */
@Component
public class RequestMappingDiagnostic {

    private static final Logger logger = LoggerFactory.getLogger(RequestMappingDiagnostic.class);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @EventListener(ApplicationReadyEvent.class)
    public void logRegisteredMappings() {
        logger.info("=== REQUEST MAPPING DIAGNOSTIC ===");

        // Log all beans with @RestController annotation
        logger.info("--- RestController Beans ---");
        Map<String, Object> restControllers = applicationContext.getBeansWithAnnotation(RestController.class);
        for (Map.Entry<String, Object> entry : restControllers.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            Class<?> beanClass = bean.getClass();

            // Check for @RequestMapping on the class
            RequestMapping classMapping = beanClass.getAnnotation(RequestMapping.class);
            String basePath = classMapping != null ? String.join(", ", classMapping.value()) : "(no class-level mapping)";

            logger.info("  Bean: {} -> Class: {} -> BasePath: {}", beanName, beanClass.getName(), basePath);
        }

        // Try to get RequestMappingHandlerMapping from context if not autowired
        RequestMappingHandlerMapping mapping = requestMappingHandlerMapping;
        logger.info("Initial RequestMappingHandlerMapping autowired value: {}", mapping);

        if (mapping == null) {
            logger.warn("RequestMappingHandlerMapping not autowired, trying to get from context...");
            try {
                // Try by type
                Map<String, RequestMappingHandlerMapping> mappingBeans =
                    applicationContext.getBeansOfType(RequestMappingHandlerMapping.class);
                logger.info("Found {} RequestMappingHandlerMapping beans: {}",
                    mappingBeans.size(), mappingBeans.keySet());
                if (!mappingBeans.isEmpty()) {
                    mapping = mappingBeans.values().iterator().next();
                    logger.info("Got RequestMappingHandlerMapping from context: {}", mapping);
                }
            } catch (Exception e) {
                logger.error("Error getting RequestMappingHandlerMapping from context: {}", e.getMessage(), e);
            }
        }

        // Log all registered handler mappings
        if (mapping != null) {
            logger.info("--- Registered Handler Mappings ---");
            Map<RequestMappingInfo, HandlerMethod> handlerMethods = mapping.getHandlerMethods();

            int count = 0;
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                RequestMappingInfo mappingInfo = entry.getKey();
                HandlerMethod handlerMethod = entry.getValue();

                // Only log API endpoints to reduce noise
                String pattern = mappingInfo.toString();
                if (pattern.contains("/api/")) {
                    logger.info("  {} -> {}.{}",
                        pattern,
                        handlerMethod.getBeanType().getSimpleName(),
                        handlerMethod.getMethod().getName());
                    count++;
                }
            }
            logger.info("Total /api/* mappings: {}", count);

            // Check for specific endpoints that were failing
            boolean foundProcessingMemory = false;
            boolean foundIngestTasks = false;
            for (RequestMappingInfo info : handlerMethods.keySet()) {
                String pattern = info.toString();
                if (pattern.contains("/api/processing/memory")) {
                    foundProcessingMemory = true;
                }
                if (pattern.contains("/api/documents/ingest-tasks")) {
                    foundIngestTasks = true;
                }
            }

            if (!foundProcessingMemory) {
                logger.warn("MISSING ENDPOINT: /api/processing/memory - ProcessingSettingsController may not be properly registered");
            }
            if (!foundIngestTasks) {
                logger.warn("MISSING ENDPOINT: /api/documents/ingest-tasks - DocumentManagementController may not be properly registered");
            }
        } else {
            logger.error("RequestMappingHandlerMapping is NULL - Spring MVC may not be configured properly");

            // Try to diagnose further
            logger.info("--- Checking WebMvc beans ---");
            String[] webMvcBeans = applicationContext.getBeanNamesForType(
                org.springframework.web.servlet.HandlerMapping.class);
            logger.info("HandlerMapping beans: {}", java.util.Arrays.toString(webMvcBeans));

            // Check for DispatcherServlet
            try {
                String[] dispatcherBeans = applicationContext.getBeanNamesForType(
                    org.springframework.web.servlet.DispatcherServlet.class);
                logger.info("DispatcherServlet beans: {}", java.util.Arrays.toString(dispatcherBeans));
            } catch (Exception e) {
                logger.info("No DispatcherServlet beans found: {}", e.getMessage());
            }
        }

        logger.info("=== END DIAGNOSTIC ===");
    }
}
