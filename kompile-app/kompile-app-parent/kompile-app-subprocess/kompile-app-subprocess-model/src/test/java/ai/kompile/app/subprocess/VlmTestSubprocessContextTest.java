/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.subprocess;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.ocr.integration.OcrPipelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VlmTestSubprocessContextTest {

    @TempDir
    Path tempDir;

    @Test
    void localModelRuntimeDescriptorSelectsItsContainingPipelineDirectory() throws Exception {
        Path modelDirectory = tempDir.resolve("data/models/vlm-pipelines/custom-vlm");
        Files.createDirectories(modelDirectory);
        Path descriptor = modelDirectory.resolve("pipeline.json");
        Files.writeString(descriptor, "{}");
        VlmTestSubprocessArgs args = VlmTestSubprocessArgs.builder()
                .modelId("custom-vlm")
                .modelSourceType("LOCAL")
                .modelIdentifier(descriptor.toString())
                .build();

        assertEquals(modelDirectory.toAbsolutePath().normalize(),
                VlmTestSubprocessMain.localModelDirectory(args));
    }

    @Test
    void pageFailureSummaryIgnoresNullErrorsAndReportsAUsefulFallback() {
        Map<String, Object> nullError = new java.util.HashMap<>();
        nullError.put("success", false);
        nullError.put("error", null);

        assertEquals(
                "All pages failed without a reported page error",
                VlmTestSubprocessMain.firstPageError(List.of(
                        Map.of("success", false), nullError)));
        assertEquals(
                "PTX JIT compiler unavailable",
                VlmTestSubprocessMain.firstPageError(List.of(
                        Map.of("success", false, "error", "  "),
                        Map.of("success", false, "error", "PTX JIT compiler unavailable"))));
    }

    @Test
    void requestScopedContextUsesNativeSafeConfigurationSupplier() {
        String previous = System.getProperty("kompile.subprocess.vlmtest.mode");
        try (AnnotationConfigApplicationContext context = VlmTestSubprocessMain.createContext()) {
            String[] names = context.getBeanNamesForType(SubprocessVlmTestConfiguration.class);
            assertEquals(1, names.length);
            AbstractBeanDefinition definition = (AbstractBeanDefinition)
                    context.getBeanFactory().getBeanDefinition(names[0]);
            assertNotNull(definition.getInstanceSupplier(),
                    "Manual VLM context registration must not require reflective construction");

            String[] modelManagerNames = context.getBeanNamesForType(KompileModelManager.class);
            assertEquals(1, modelManagerNames.length);
            AbstractBeanDefinition modelManagerDefinition = (AbstractBeanDefinition)
                    context.getBeanFactory().getBeanDefinition(modelManagerNames[0]);
            assertNotNull(modelManagerDefinition.getInstanceSupplier(),
                    "Manual VLM context must not discover KompileModelManager through @Bean reflection");
            assertNotNull(context.getBean(OcrPipelineService.class));
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.subprocess.vlmtest.mode");
            } else {
                System.setProperty("kompile.subprocess.vlmtest.mode", previous);
            }
        }
    }
}
