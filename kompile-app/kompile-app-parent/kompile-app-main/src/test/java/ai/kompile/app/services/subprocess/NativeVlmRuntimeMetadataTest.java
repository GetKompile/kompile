/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards native-only runtime requirements exercised by the dedicated VLM worker. */
class NativeVlmRuntimeMetadataTest {

    private static final String CONFIG =
            "META-INF/native-image/vlm-test-runtime/reflect-config.json";
    private static final String JNI_CONFIG =
            "META-INF/native-image/vlm-test-runtime/jni-config.json";
    private static final Set<String> REGISTRY_TYPES = Set.of(
            "ai.kompile.modelmanager.registry.ModelRegistry",
            "ai.kompile.modelmanager.registry.ModelRegistry$ArchiveInstallInfo",
            "ai.kompile.modelmanager.registry.ModelEntry",
            "ai.kompile.modelmanager.registry.ModelMetadata",
            "ai.kompile.modelmanager.registry.ModelMetadata$OptimizationStats",
            "ai.kompile.modelmanager.registry.ModelMetadata$OptimizationConfig",
            "ai.kompile.modelmanager.registry.ModelMetadata$BenchmarkResult",
            "ai.kompile.modelmanager.registry.TokenizerConfig",
            "ai.kompile.modelmanager.registry.ImagePreprocessorConfig",
            "ai.kompile.modelmanager.registry.AudioSynthesisConfig",
            "ai.kompile.modelmanager.registry.ModelType",
            "ai.kompile.modelmanager.registry.ModelStatus");

    @Test
    void modelRegistryJsonGraphHasInvokableNativeMetadata() throws Exception {
        Map<String, JsonNode> registrations = new LinkedHashMap<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG)) {
            assertNotNull(input, () -> CONFIG + " must be on the app-main test classpath");
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                registrations.put(entry.path("name").asText(), entry);
            }
        }

        assertEquals(REGISTRY_TYPES, registrations.keySet());
        for (String typeName : REGISTRY_TYPES) {
            JsonNode entry = registrations.get(typeName);
            assertTrue(entry.path("allDeclaredFields").asBoolean(),
                    () -> typeName + " needs field access for registry JSON");
            assertTrue(entry.path("allDeclaredMethods").asBoolean(),
                    () -> typeName + " needs accessor access for registry JSON");
            if (!Class.forName(typeName).isEnum()) {
                assertTrue(entry.path("allDeclaredConstructors").asBoolean(),
                        () -> typeName + " needs invokable constructors for registry JSON");
            }
        }
    }

    @Test
    void awtBootstrapMethodsInvokedFromJdkNativeCodeHaveJniMetadata() throws Exception {
        Map<String, JsonNode> registrations = new LinkedHashMap<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(JNI_CONFIG)) {
            assertNotNull(input, () -> JNI_CONFIG + " must be on the app-main test classpath");
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                registrations.put(entry.path("name").asText(), entry);
            }
        }

        Set<String> requiredRegistrations = Set.of(
                "com.sun.imageio.plugins.jpeg.JPEGImageReader",
                "java.lang.System",
                "java.awt.GraphicsEnvironment",
                "java.awt.color.ICC_Profile",
                "java.awt.image.BufferedImage",
                "java.awt.image.ColorModel",
                "java.awt.image.ComponentSampleModel",
                "java.awt.image.Raster",
                "java.awt.image.SampleModel",
                "java.awt.image.SinglePixelPackedSampleModel",
                "sun.awt.image.ByteComponentRaster",
                "sun.awt.image.IntegerComponentRaster",
                "sun.java2d.Disposer",
                "sun.java2d.InvalidPipeException",
                "sun.java2d.NullSurfaceData",
                "sun.java2d.SunGraphics2D",
                "sun.java2d.SurfaceData",
                "sun.java2d.loops.GraphicsPrimitiveMgr",
                "sun.java2d.loops.SurfaceType",
                "sun.java2d.pipe.Region");
        assertTrue(registrations.keySet().containsAll(requiredRegistrations),
                () -> "missing traced AWT JNI registrations: "
                        + requiredRegistrations.stream()
                        .filter(type -> !registrations.containsKey(type))
                        .toList());
        assertEquals(Set.of(
                        "setProperty(java.lang.String,java.lang.String)",
                        "load(java.lang.String)"),
                methodSignatures(registrations.get("java.lang.System")));
        assertEquals(Set.of("isHeadless()"),
                methodSignatures(registrations.get("java.awt.GraphicsEnvironment")));
        assertEquals(Set.of("pData", "valid"),
                fieldNames(registrations.get("sun.java2d.SurfaceData")));
        assertEquals(Set.of("register(sun.java2d.loops.GraphicsPrimitive[])"),
                methodSignatures(registrations.get("sun.java2d.loops.GraphicsPrimitiveMgr")));

        JsonNode integerRaster = registrations.get("sun.awt.image.IntegerComponentRaster");
        assertTrue(integerRaster.path("allDeclaredFields").asBoolean(),
                "libawt initIDs requires IntegerComponentRaster.data and layout fields");
        assertTrue(integerRaster.path("allDeclaredMethods").asBoolean());
        assertTrue(integerRaster.path("allDeclaredConstructors").asBoolean());
    }

    private static Set<String> fieldNames(JsonNode registration) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode field : registration.path("fields")) {
            names.add(field.path("name").asText());
        }
        return names;
    }

    private static Set<String> methodSignatures(JsonNode registration) {
        Set<String> signatures = new java.util.LinkedHashSet<>();
        for (JsonNode method : registration.path("methods")) {
            StringBuilder signature = new StringBuilder(method.path("name").asText()).append('(');
            boolean first = true;
            for (JsonNode parameterType : method.path("parameterTypes")) {
                if (!first) {
                    signature.append(',');
                }
                signature.append(parameterType.asText());
                first = false;
            }
            signatures.add(signature.append(')').toString());
        }
        return signatures;
    }

    @Test
    void vlmNativeProfileIncludesFocusedMetadataAndPdfCharsets() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("META-INF/native-image/vlm-test-runtime"),
                "native-vlm-test must include its focused registry and JNI metadata");
        assertTrue(pom.contains("<buildArg>-H:+AddAllCharsets</buildArg>"),
                "native-vlm-test must include PDFBox legacy encodings such as Windows-1252");
    }

    @Test
    void minimalDistributionShipsTheVlmWorkersJdkShims() throws Exception {
        String buildDist = Files.readString(Path.of("../../../build-dist.sh"));
        assertTrue(buildDist.contains("VLM_WORKER_SHIMS=0"),
                "the document-model target must own Graal JDK shim staging");
        assertTrue(buildDist.contains(
                        "GraalVM JDK shim libraries from kompile-vlm-test"),
                "the VLM worker's emitted shims must be copied into the distribution lib tree");
    }
}
