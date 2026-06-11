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

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationType;
import ai.kompile.codeindexer.service.CodeEntityExtractor.RelationTriple;
import ai.kompile.codeindexer.service.LanguageParser.ExtractionOutput;
import ai.kompile.codeindexer.service.parsers.SystemsLanguageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SystemsLanguageParserTest {

    private SystemsLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new SystemsLanguageParser();
    }

    @Test
    void supportedLanguages() {
        Set<String> langs = parser.supportedLanguages();
        assertTrue(langs.contains("rust"));
        assertTrue(langs.contains("go"));
        assertTrue(langs.contains("c"));
        assertTrue(langs.contains("cpp"));
        assertTrue(langs.contains("swift"));
    }

    // ── Rust ────────────────────────────────────────────────────────────

    @Test
    void parseRustStruct() {
        String rust = """
                use std::collections::HashMap;

                /// A user in the system.
                pub struct User {
                    pub name: String,
                    age: u32,
                }

                impl User {
                    pub fn new(name: String, age: u32) -> Self {
                        User { name, age }
                    }

                    pub fn greet(&self) -> String {
                        format!("Hello, {}", self.name)
                    }
                }
                """;
        String[] lines = rust.split("\n");

        ExtractionOutput output = parser.parse(lines, "user.rs", "default", "rust");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find struct as CLASS");

        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION
                        || e.getEntityType() == CodeEntityType.METHOD).toList();
        assertTrue(functions.size() >= 2, "Should find impl methods");

        List<CodeEntity> imports = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.IMPORT).toList();
        assertTrue(imports.size() >= 1, "Should find use import");
    }

    @Test
    void parseRustTraitImpl() {
        String rust = """
                pub trait Display {
                    fn fmt(&self) -> String;
                }

                pub struct Point {
                    x: f64,
                    y: f64,
                }

                impl Display for Point {
                    fn fmt(&self) -> String {
                        format!("({}, {})", self.x, self.y)
                    }
                }
                """;
        String[] lines = rust.split("\n");

        ExtractionOutput output = parser.parse(lines, "point.rs", "default", "rust");

        List<CodeEntity> interfaces = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.INTERFACE).toList();
        assertTrue(interfaces.size() >= 1, "Should find trait as INTERFACE");

        List<RelationTriple> implements_ = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.IMPLEMENTS).toList();
        assertTrue(implements_.size() >= 1, "Should have IMPLEMENTS for impl Trait for Type");
    }

    @Test
    void parseRustEnum() {
        String rust = """
                pub enum Color {
                    Red,
                    Green,
                    Blue,
                    Custom(u8, u8, u8),
                }
                """;
        String[] lines = rust.split("\n");

        ExtractionOutput output = parser.parse(lines, "color.rs", "default", "rust");

        List<CodeEntity> enums = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.ENUM).toList();
        assertTrue(enums.size() >= 1, "Should find enum");
        assertEquals("Color", enums.get(0).getName());
    }

    // ── Go ──────────────────────────────────────────────────────────────

    @Test
    void parseGoStruct() {
        String go = """
                package main

                import "fmt"

                // Server handles HTTP requests.
                type Server struct {
                    Host string
                    Port int
                }

                func NewServer(host string, port int) *Server {
                    return &Server{Host: host, Port: port}
                }

                func (s *Server) Start() error {
                    fmt.Printf("Starting on %s:%d\\n", s.Host, s.Port)
                    return nil
                }
                """;
        String[] lines = go.split("\n");

        ExtractionOutput output = parser.parse(lines, "server.go", "default", "go");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find struct as CLASS");

        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION
                        || e.getEntityType() == CodeEntityType.METHOD).toList();
        assertTrue(functions.size() >= 2, "Should find functions/methods");
    }

    @Test
    void parseGoInterface() {
        String go = """
                package io

                type Reader interface {
                    Read(p []byte) (n int, err error)
                }

                type Writer interface {
                    Write(p []byte) (n int, err error)
                }

                type ReadWriter interface {
                    Reader
                    Writer
                }
                """;
        String[] lines = go.split("\n");

        ExtractionOutput output = parser.parse(lines, "io.go", "default", "go");

        List<CodeEntity> interfaces = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.INTERFACE).toList();
        assertTrue(interfaces.size() >= 3, "Should find 3 interfaces");
    }

    // ── C++ ─────────────────────────────────────────────────────────────

    @Test
    void parseCppClass() {
        String cpp = """
                #include <string>
                #include <vector>

                namespace app {

                /**
                 * Manages application configuration.
                 */
                class Config {
                public:
                    Config(const std::string& path);
                    virtual ~Config();

                    std::string get(const std::string& key) const;
                    void set(const std::string& key, const std::string& value);

                private:
                    std::string m_path;
                    std::vector<std::string> m_keys;
                };

                } // namespace app
                """;
        String[] lines = cpp.split("\n");

        ExtractionOutput output = parser.parse(lines, "config.hpp", "default", "cpp");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find class");
        assertEquals("Config", classes.get(0).getName());

        List<CodeEntity> imports = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.IMPORT).toList();
        assertTrue(imports.size() >= 2, "Should find includes");
    }

    // ── Cross-cutting ───────────────────────────────────────────────────

    @Test
    void containsRelationsForAllLanguages() {
        // Test that CONTAINS relations are emitted for Go
        String go = """
                package main
                type App struct { name string }
                func (a *App) Run() {}
                """;
        ExtractionOutput goOutput = parser.parse(go.split("\n"), "app.go", "default", "go");
        List<RelationTriple> goContains = goOutput.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS).toList();
        assertFalse(goContains.isEmpty(), "Go should emit CONTAINS relations");

        // Test that CONTAINS relations are emitted for Rust
        String rust = """
                pub struct Thing {}
                impl Thing { pub fn do_it(&self) {} }
                """;
        ExtractionOutput rustOutput = parser.parse(rust.split("\n"), "thing.rs", "default", "rust");
        List<RelationTriple> rustContains = rustOutput.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS).toList();
        assertFalse(rustContains.isEmpty(), "Rust should emit CONTAINS relations");
    }

    @Test
    void languageFieldSet() {
        String rust = "pub fn main() {}\n";
        ExtractionOutput output = parser.parse(rust.split("\n"), "main.rs", "default", "rust");

        for (CodeEntity entity : output.entities()) {
            assertEquals("rust", entity.getLanguage(), "Entity language should be set");
        }
    }
}
