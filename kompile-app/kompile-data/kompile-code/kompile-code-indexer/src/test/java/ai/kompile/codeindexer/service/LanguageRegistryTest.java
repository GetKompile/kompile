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

package ai.kompile.codeindexer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LanguageRegistryTest {

    private LanguageRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new LanguageRegistry();
    }

    @Test
    void detectJavaByExtension() {
        String lang = registry.detectLanguage(Paths.get("src/main/java/MyClass.java"));
        assertNotNull(lang);
        assertEquals("java", lang);
    }

    @Test
    void detectPythonByExtension() {
        String lang = registry.detectLanguage(Paths.get("script.py"));
        assertNotNull(lang);
        assertEquals("python", lang);
    }

    @Test
    void detectRustByExtension() {
        String lang = registry.detectLanguage(Paths.get("lib.rs"));
        assertNotNull(lang);
        assertEquals("rust", lang);
    }

    @Test
    void detectGoByExtension() {
        String lang = registry.detectLanguage(Paths.get("main.go"));
        assertNotNull(lang);
        assertEquals("go", lang);
    }

    @Test
    void detectTypeScriptByExtension() {
        String lang = registry.detectLanguage(Paths.get("app.component.ts"));
        assertNotNull(lang);
        assertEquals("typescript", lang);
    }

    @Test
    void detectKotlinByExtension() {
        String lang = registry.detectLanguage(Paths.get("Main.kt"));
        assertNotNull(lang);
        assertEquals("kotlin", lang);
    }

    @Test
    void detectCppByExtension() {
        String lang = registry.detectLanguage(Paths.get("main.cpp"));
        assertNotNull(lang);
        assertEquals("cpp", lang);

        lang = registry.detectLanguage(Paths.get("main.cc"));
        assertNotNull(lang);
        assertEquals("cpp", lang);
    }

    @Test
    void detectSqlByExtension() {
        String lang = registry.detectLanguage(Paths.get("schema.sql"));
        assertNotNull(lang);
        assertEquals("sql", lang);
    }

    @Test
    void detectDockerfileByName() {
        String lang = registry.detectLanguage(Paths.get("Dockerfile"));
        assertNotNull(lang);
        assertEquals("dockerfile", lang);
    }

    @Test
    void detectMakefileByName() {
        String lang = registry.detectLanguage(Paths.get("Makefile"));
        assertNotNull(lang);
        assertEquals("make", lang);
    }

    @Test
    void detectSplanByExtension() {
        String lang = registry.detectLanguage(Paths.get("plan.splan"));
        assertNotNull(lang);
        assertEquals("splan", lang);
    }

    @Test
    void unknownExtensionReturnsNull() {
        String lang = registry.detectLanguage(Paths.get("file.xyz"));
        assertNull(lang);
    }

    @Test
    void fileOverrideTakesPrecedence() {
        Path file = Paths.get("config.jsx");
        registry.setFileLanguage(file.toString(), "typescript");

        String lang = registry.detectLanguage(file);
        assertNotNull(lang);
        assertEquals("typescript", lang);
    }

    @Test
    void patternOverrideTakesPrecedence() {
        registry.setPatternLanguage("*.jsx", "typescript");

        String lang = registry.detectLanguage(Paths.get("component.jsx"));
        assertNotNull(lang);
        assertEquals("typescript", lang);
    }

    @Test
    void fileOverrideOverridesPattern() {
        registry.setPatternLanguage("*.jsx", "typescript");
        Path file = Paths.get("special.jsx");
        registry.setFileLanguage(file.toString(), "javascript");

        String lang = registry.detectLanguage(file);
        assertNotNull(lang);
        assertEquals("javascript", lang);
    }

    @Test
    void getSupportedLanguagesNotEmpty() {
        Set<String> supported = registry.getSupportedLanguages();
        assertFalse(supported.isEmpty());
        assertTrue(supported.contains("java"));
        assertTrue(supported.contains("python"));
        assertTrue(supported.contains("rust"));
        assertTrue(supported.contains("go"));
    }
}
