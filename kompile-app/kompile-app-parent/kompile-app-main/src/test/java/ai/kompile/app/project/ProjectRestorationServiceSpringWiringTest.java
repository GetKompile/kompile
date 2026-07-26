/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Guards the production component-scan path, where Spring must select the injectable constructor.
 */
class ProjectRestorationServiceSpringWiringTest {

    @Test
    void springCanSelectTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton(
                    "projectBackendService", mock(ProjectBackendService.class));
            context.getBeanFactory().registerSingleton(
                    "factSheetService", mock(FactSheetService.class));
            context.getBeanFactory().registerSingleton(
                    "noteSyncConnectionRepository", mock(NoteSyncConnectionRepository.class));
            context.register(
                    ProjectRestorationWiringConfiguration.class,
                    ProjectRestorationService.class);

            context.refresh();

            assertNotNull(context.getBean(ProjectRestorationService.class));
        }
    }
}
