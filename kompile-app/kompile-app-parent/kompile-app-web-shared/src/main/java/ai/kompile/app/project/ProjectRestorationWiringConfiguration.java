/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;

/**
 * Makes the runtime constructor explicit for the restoration component.
 *
 * <p>The service also exposes a package-private constructor for deterministic store injection in
 * tests. Without an explicit candidate, Spring treats the component as if it needed a no-argument
 * constructor and application startup fails.</p>
 *
 * <p>It has to sit in the same module as {@link ProjectRestorationService} — kompile-app-web-shared —
 * because it is only useful where that service is scanned, and every persona process scans it. Left
 * behind in app-main it took the chat process down with
 * {@code NoSuchMethodException: ProjectRestorationService.<init>()}.</p>
 */
@Configuration(proxyBeanMethods = false)
class ProjectRestorationWiringConfiguration {

    @Bean
    static SmartInstantiationAwareBeanPostProcessor projectRestorationConstructorSelector() {
        final Constructor<ProjectRestorationService> runtimeConstructor;
        try {
            runtimeConstructor = ProjectRestorationService.class.getConstructor(
                    ProjectBackendService.class,
                    FactSheetService.class,
                    NoteSyncConnectionRepository.class);
        } catch (NoSuchMethodException missingRuntimeConstructor) {
            throw new IllegalStateException(
                    "ProjectRestorationService runtime constructor is unavailable",
                    missingRuntimeConstructor);
        }

        return new SmartInstantiationAwareBeanPostProcessor() {
            @Override
            public Constructor<?>[] determineCandidateConstructors(
                    Class<?> beanClass, String beanName) {
                if (beanClass != ProjectRestorationService.class) {
                    return null;
                }
                return new Constructor<?>[]{runtimeConstructor};
            }
        };
    }
}
