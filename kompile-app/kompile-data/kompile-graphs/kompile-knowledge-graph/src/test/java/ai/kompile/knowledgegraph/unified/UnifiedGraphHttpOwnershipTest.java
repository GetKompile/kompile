/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class UnifiedGraphHttpOwnershipTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(UnifiedGraphBridge.class, () -> mock(UnifiedGraphBridge.class))
            .withBean(GraphReasoningQueryService.class, () -> mock(GraphReasoningQueryService.class))
            .withUserConfiguration(HttpControllerConfiguration.class);

    @Test
    void graphHttpControllersAreDisabledUnlessAHostExplicitlyClaimsOwnership() {
        contextRunner.run(context -> {
            assertTrue(context.getBeansOfType(UnifiedGraphIOController.class).isEmpty());
            assertTrue(context.getBeansOfType(GraphReasoningQueryController.class).isEmpty());
        });
    }

    @Test
    void graphServiceCanExplicitlyClaimBothHttpContracts() {
        contextRunner
                .withPropertyValues("kompile.graph.http.enabled=true")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(UnifiedGraphIOController.class).size());
                    assertEquals(1, context.getBeansOfType(GraphReasoningQueryController.class).size());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({UnifiedGraphIOController.class, GraphReasoningQueryController.class})
    static class HttpControllerConfiguration {
    }
}
