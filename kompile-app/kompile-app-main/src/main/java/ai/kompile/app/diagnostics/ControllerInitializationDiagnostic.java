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
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic bean post processor that logs when REST controllers are being initialized.
 * Helps catch exceptions that might be swallowed during controller setup.
 */
@Component
public class ControllerInitializationDiagnostic implements BeanPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ControllerInitializationDiagnostic.class);

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean.getClass().isAnnotationPresent(RestController.class)) {
            logger.debug("PRE-INIT RestController: {} ({})", beanName, bean.getClass().getName());
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean.getClass().isAnnotationPresent(RestController.class)) {
            logger.info("INITIALIZED RestController: {} ({})", beanName, bean.getClass().getName());

            // Check for common issues
            try {
                // Check if the class has any request mapping methods
                long mappingCount = java.util.Arrays.stream(bean.getClass().getMethods())
                    .filter(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class) ||
                                 m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class) ||
                                 m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class) ||
                                 m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class) ||
                                 m.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class))
                    .count();

                if (mappingCount == 0) {
                    logger.warn("  WARNING: Controller {} has no mapped endpoints!", beanName);
                } else {
                    logger.debug("  Controller {} has {} endpoint methods", beanName, mappingCount);
                }
            } catch (Exception e) {
                logger.error("  ERROR inspecting controller {}: {}", beanName, e.getMessage(), e);
            }
        }
        return bean;
    }
}
