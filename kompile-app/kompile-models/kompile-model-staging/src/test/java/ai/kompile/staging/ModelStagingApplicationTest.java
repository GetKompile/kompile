/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStagingApplicationTest {

    @AfterEach
    void clearCliProperties() {
        System.clearProperty("server.address");
        System.clearProperty("server.port");
        System.clearProperty("kompile.staging.mcp.enabled");
    }

    @Test
    void identifiesCliAndServerModes() {
        assertFalse(ModelStagingApplication.isCliMode(null));
        assertFalse(ModelStagingApplication.isCliMode(new String[0]));
        assertFalse(ModelStagingApplication.isCliMode(new String[]{"--server.port=0"}));
        assertTrue(ModelStagingApplication.isCliMode(new String[]{"bootstrap"}));
        assertTrue(ModelStagingApplication.isCliMode(new String[]{"--help"}));
    }

    @Test
    void cliUsesTheServletContextCompiledIntoTheNativeImage() {
        SpringApplication application = ModelStagingApplication.createCliApplication();

        assertEquals(WebApplicationType.SERVLET, application.getWebApplicationType());
        assertEquals("127.0.0.1", System.getProperty("server.address"));
        assertEquals("0", System.getProperty("server.port"));
        assertEquals("false", System.getProperty("kompile.staging.mcp.enabled"));
    }

    @Test
    void nativeResourcesKeepRuntimeManifestButExcludeSideLoadedBinaries() throws Exception {
        String resource = "META-INF/native-image/staging/resource-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("shared-runtime-manifest\\\\.txt"));
            assertFalse(metadata.contains("(linux|windows|macosx|android|ios)[^/]*/.*"));
            assertTrue(metadata.contains("so(\\\\..*)?"));
        }
    }

    @Test
    void nd4jReflectionMetadataCoversRegisteredSamplingOps() throws Exception {
        String resource = "META-INF/native-image/org.eclipse.deeplearning4j/nd4j-api/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "ND4J native reflection metadata must be packaged");
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("org.nd4j.linalg.api.ops.impl.transforms.custom.TypicalPFilter"));
            assertTrue(metadata.contains("org.nd4j.linalg.api.ops.impl.transforms.custom.XtcFilter"));
        }
    }
}
