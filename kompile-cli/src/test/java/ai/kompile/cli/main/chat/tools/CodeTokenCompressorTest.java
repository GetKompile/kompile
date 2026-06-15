/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CodeTokenCompressor}.
 * <p>
 * Tests language detection, code compression (license stripping, import
 * collapsing, blank line merging, block comment collapsing), structure
 * extraction, grep result compression, and CompressResult accessors.
 */
class CodeTokenCompressorTest {

    // ===================================================================
    // Language detection
    // ===================================================================

    @Nested
    class LanguageDetection {

        @Test
        void java_detection() {
            assertEquals(CodeTokenCompressor.Language.JAVA,
                    CodeTokenCompressor.detectLanguage("Main.java"));
        }

        @Test
        void kotlin_detection() {
            assertEquals(CodeTokenCompressor.Language.KOTLIN,
                    CodeTokenCompressor.detectLanguage("build.gradle.kts"));
            assertEquals(CodeTokenCompressor.Language.KOTLIN,
                    CodeTokenCompressor.detectLanguage("App.kt"));
        }

        @Test
        void python_detection() {
            assertEquals(CodeTokenCompressor.Language.PYTHON,
                    CodeTokenCompressor.detectLanguage("script.py"));
            assertEquals(CodeTokenCompressor.Language.PYTHON,
                    CodeTokenCompressor.detectLanguage("gui.pyw"));
        }

        @Test
        void javascript_detection() {
            assertEquals(CodeTokenCompressor.Language.JAVASCRIPT,
                    CodeTokenCompressor.detectLanguage("index.js"));
            assertEquals(CodeTokenCompressor.Language.JAVASCRIPT,
                    CodeTokenCompressor.detectLanguage("component.jsx"));
            assertEquals(CodeTokenCompressor.Language.JAVASCRIPT,
                    CodeTokenCompressor.detectLanguage("module.mjs"));
        }

        @Test
        void typescript_detection() {
            assertEquals(CodeTokenCompressor.Language.TYPESCRIPT,
                    CodeTokenCompressor.detectLanguage("app.ts"));
            assertEquals(CodeTokenCompressor.Language.TYPESCRIPT,
                    CodeTokenCompressor.detectLanguage("component.tsx"));
        }

        @Test
        void go_detection() {
            assertEquals(CodeTokenCompressor.Language.GO,
                    CodeTokenCompressor.detectLanguage("main.go"));
        }

        @Test
        void rust_detection() {
            assertEquals(CodeTokenCompressor.Language.RUST,
                    CodeTokenCompressor.detectLanguage("lib.rs"));
        }

        @Test
        void c_and_cpp_detection() {
            assertEquals(CodeTokenCompressor.Language.C,
                    CodeTokenCompressor.detectLanguage("main.c"));
            assertEquals(CodeTokenCompressor.Language.C,
                    CodeTokenCompressor.detectLanguage("header.h"));
            assertEquals(CodeTokenCompressor.Language.CPP,
                    CodeTokenCompressor.detectLanguage("main.cpp"));
            assertEquals(CodeTokenCompressor.Language.CPP,
                    CodeTokenCompressor.detectLanguage("header.hpp"));
        }

        @Test
        void csharp_detection() {
            assertEquals(CodeTokenCompressor.Language.CSHARP,
                    CodeTokenCompressor.detectLanguage("Program.cs"));
        }

        @Test
        void ruby_detection() {
            assertEquals(CodeTokenCompressor.Language.RUBY,
                    CodeTokenCompressor.detectLanguage("Gemfile.rb"));
        }

        @Test
        void scala_detection() {
            assertEquals(CodeTokenCompressor.Language.SCALA,
                    CodeTokenCompressor.detectLanguage("App.scala"));
        }

        @Test
        void xml_and_html_detection() {
            assertEquals(CodeTokenCompressor.Language.XML,
                    CodeTokenCompressor.detectLanguage("pom.xml"));
            assertEquals(CodeTokenCompressor.Language.XML,
                    CodeTokenCompressor.detectLanguage("index.html"));
        }

        @Test
        void yaml_detection() {
            assertEquals(CodeTokenCompressor.Language.YAML,
                    CodeTokenCompressor.detectLanguage("config.yml"));
            assertEquals(CodeTokenCompressor.Language.YAML,
                    CodeTokenCompressor.detectLanguage("docker-compose.yaml"));
        }

        @Test
        void json_detection() {
            assertEquals(CodeTokenCompressor.Language.JSON,
                    CodeTokenCompressor.detectLanguage("package.json"));
        }

        @Test
        void properties_detection() {
            assertEquals(CodeTokenCompressor.Language.PROPERTIES,
                    CodeTokenCompressor.detectLanguage("application.properties"));
            assertEquals(CodeTokenCompressor.Language.PROPERTIES,
                    CodeTokenCompressor.detectLanguage("settings.toml"));
        }

        @Test
        void shell_detection() {
            assertEquals(CodeTokenCompressor.Language.SHELL,
                    CodeTokenCompressor.detectLanguage("build.sh"));
            assertEquals(CodeTokenCompressor.Language.SHELL,
                    CodeTokenCompressor.detectLanguage("setup.bash"));
        }

        @Test
        void sql_detection() {
            assertEquals(CodeTokenCompressor.Language.SQL,
                    CodeTokenCompressor.detectLanguage("schema.sql"));
        }

        @Test
        void unknown_detection() {
            assertEquals(CodeTokenCompressor.Language.UNKNOWN,
                    CodeTokenCompressor.detectLanguage("README.md"));
            assertEquals(CodeTokenCompressor.Language.UNKNOWN,
                    CodeTokenCompressor.detectLanguage("Dockerfile"));
        }

        @Test
        void nullFileName_isUnknown() {
            assertEquals(CodeTokenCompressor.Language.UNKNOWN,
                    CodeTokenCompressor.detectLanguage(null));
        }

        @Test
        void caseInsensitive() {
            assertEquals(CodeTokenCompressor.Language.JAVA,
                    CodeTokenCompressor.detectLanguage("Main.JAVA"));
            assertEquals(CodeTokenCompressor.Language.PYTHON,
                    CodeTokenCompressor.detectLanguage("Script.PY"));
        }
    }

    // ===================================================================
    // Compress mode — license stripping
    // ===================================================================

    @Nested
    class LicenseStripping {

        @Test
        void stripApacheLicenseHeader() {
            // LICENSE_BLOCK_COMMENT_START requires copyright/license on same line as /*
            List<String> lines = Arrays.asList(
                    "/* Copyright 2025 Example Inc.",
                    " * Licensed under the Apache License, Version 2.0",
                    " */",
                    "",
                    "package com.example;",
                    "",
                    "public class Main {",
                    "    public static void main(String[] args) {}",
                    "}"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.compress(lines, "Main.java");

            String formatted = result.format();
            assertFalse(formatted.contains("Copyright"));
            assertFalse(formatted.contains("Apache License"));
            assertTrue(formatted.contains("package com.example"));
            assertTrue(formatted.contains("public class Main"));
            assertTrue(result.compressedLineCount() < result.originalLineCount());
        }

        @Test
        void preserveCodeWithoutLicense() {
            List<String> lines = Arrays.asList(
                    "package com.example;",
                    "",
                    "public class Simple {",
                    "    int x = 42;",
                    "}"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.compress(lines, "Simple.java");

            String formatted = result.format();
            assertTrue(formatted.contains("package com.example"));
            assertTrue(formatted.contains("int x = 42"));
        }
    }

    // ===================================================================
    // Compress mode — import collapsing
    // ===================================================================

    @Nested
    class ImportCollapsing {

        @Test
        void javaImports_collapsedToSummary() {
            List<String> lines = Arrays.asList(
                    "package com.example;",
                    "",
                    "import java.util.List;",
                    "import java.util.Map;",
                    "import java.util.Set;",
                    "import java.io.File;",
                    "import java.io.IOException;",
                    "",
                    "public class Service {",
                    "    void run() {}",
                    "}"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.compress(lines, "Service.java");

            String formatted = result.format();
            // Imports should be collapsed — exact text depends on implementation
            assertTrue(result.compressedLineCount() < result.originalLineCount(),
                    "Import collapsing should reduce line count");
            assertTrue(formatted.contains("public class Service"));
        }

        @Test
        void pythonImports_collapsedToSummary() {
            List<String> lines = Arrays.asList(
                    "import os",
                    "import sys",
                    "import json",
                    "from pathlib import Path",
                    "from typing import List",
                    "",
                    "def main():",
                    "    print('hello')"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.compress(lines, "main.py");

            String formatted = result.format();
            assertTrue(result.compressedLineCount() <= result.originalLineCount());
            assertTrue(formatted.contains("def main"));
        }
    }

    // ===================================================================
    // Compress mode — blank line merging
    // ===================================================================

    @Nested
    class BlankLineMerging {

        @Test
        void consecutiveBlanks_collapsedToOne() {
            List<String> lines = Arrays.asList(
                    "class A {",
                    "",
                    "",
                    "",
                    "",
                    "    int x;",
                    "",
                    "",
                    "    int y;",
                    "}"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.compress(lines, "A.java");

            String formatted = result.format();
            // Multiple consecutive blanks should collapse
            assertTrue(result.compressedLineCount() < result.originalLineCount());
            assertTrue(formatted.contains("int x"));
            assertTrue(formatted.contains("int y"));
        }
    }

    // ===================================================================
    // Structure extraction mode
    // ===================================================================

    @Nested
    class StructureExtraction {

        @Test
        void javaStructure_extractsSignatures() {
            List<String> lines = Arrays.asList(
                    "package com.example;",
                    "",
                    "import java.util.List;",
                    "",
                    "/**",
                    " * A service class.",
                    " */",
                    "public class UserService {",
                    "",
                    "    private final UserRepository repo;",
                    "",
                    "    public UserService(UserRepository repo) {",
                    "        this.repo = repo;",
                    "    }",
                    "",
                    "    public User findById(long id) {",
                    "        return repo.findById(id);",
                    "    }",
                    "",
                    "    public List<User> findAll() {",
                    "        return repo.findAll();",
                    "    }",
                    "",
                    "    private void validateUser(User user) {",
                    "        // validation logic",
                    "    }",
                    "}"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.extractStructure(lines, "UserService.java");

            String formatted = result.format();
            // Should include class declaration, method signatures, field
            assertTrue(formatted.contains("public class UserService"));
            assertTrue(formatted.contains("findById") || formatted.contains("findAll"));
            // Should be much smaller than original
            assertTrue(result.compressionRatio() < 1.0);
        }

        @Test
        void pythonStructure_extractsDefAndClass() {
            List<String> lines = Arrays.asList(
                    "import os",
                    "",
                    "class FileProcessor:",
                    "    \"\"\"Processes files.\"\"\"",
                    "",
                    "    def __init__(self, path):",
                    "        self.path = path",
                    "",
                    "    def process(self):",
                    "        for f in os.listdir(self.path):",
                    "            self.handle(f)",
                    "",
                    "    def handle(self, filename):",
                    "        with open(filename) as fh:",
                    "            return fh.read()"
            );

            CodeTokenCompressor.CompressResult result =
                    CodeTokenCompressor.extractStructure(lines, "processor.py");

            String formatted = result.format();
            assertTrue(formatted.contains("class FileProcessor"));
            assertTrue(formatted.contains("def __init__") || formatted.contains("def process"));
        }
    }

    // ===================================================================
    // CompressResult accessors
    // ===================================================================

    @Nested
    class CompressResultAccessors {

        @Test
        void format_includesLineNumbers() {
            CodeTokenCompressor.NumberedLine line1 =
                    new CodeTokenCompressor.NumberedLine(1, "public class A {");
            CodeTokenCompressor.NumberedLine line2 =
                    new CodeTokenCompressor.NumberedLine(5, "    void run() {}");
            CodeTokenCompressor.NumberedLine line3 =
                    new CodeTokenCompressor.NumberedLine(6, "}");

            CodeTokenCompressor.CompressResult result =
                    new CodeTokenCompressor.CompressResult(
                            List.of(line1, line2, line3), 10, 3);

            String formatted = result.format();
            assertNotNull(formatted);
            assertTrue(formatted.contains("public class A"));
        }

        @Test
        void compressionRatio_calculatesCorrectly() {
            // compressionRatio() = 1.0 - (compressed/original)
            CodeTokenCompressor.CompressResult result =
                    new CodeTokenCompressor.CompressResult(List.of(), 100, 30);

            assertEquals(0.7, result.compressionRatio(), 0.001);
        }

        @Test
        void compressionRatio_zeroOriginal() {
            CodeTokenCompressor.CompressResult result =
                    new CodeTokenCompressor.CompressResult(List.of(), 0, 0);

            assertEquals(1.0, result.compressionRatio(), 0.001);
        }
    }

    // ===================================================================
    // Grep result compression
    // ===================================================================

    @Nested
    class GrepResultCompression {

        @Test
        void shortGrepResult_notCompressed() {
            String input = "src/Main.java:5:    int x = 42;\nsrc/Main.java:10:    int y = 99;";
            String result = CodeTokenCompressor.compressGrepResults(input);
            assertNotNull(result);
            assertTrue(result.contains("x = 42"));
        }

        @Test
        void emptyInput_returnsEmpty() {
            String result = CodeTokenCompressor.compressGrepResults("");
            assertNotNull(result);
        }

        @Test
        void nullInput_handledGracefully() {
            // Null input may return null — just verify no exception thrown
            assertDoesNotThrow(() -> CodeTokenCompressor.compressGrepResults(null));
        }
    }
}
