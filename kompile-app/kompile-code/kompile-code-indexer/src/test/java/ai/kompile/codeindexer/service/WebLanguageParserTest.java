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
import ai.kompile.codeindexer.service.parsers.WebLanguageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebLanguageParserTest {

    private WebLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new WebLanguageParser();
    }

    @Test
    void supportedLanguages() {
        Set<String> langs = parser.supportedLanguages();
        assertTrue(langs.contains("javascript"));
        assertTrue(langs.contains("typescript"));
        assertTrue(langs.contains("html"));
        assertTrue(langs.contains("css"));
    }

    // ── JS/TS: Basic structure ─────────────────────────────────────────

    @Test
    void parseTypescriptClass() {
        String ts = """
                import { Injectable } from '@angular/core';

                export class UserService {
                    private name: string;

                    constructor(name: string) {
                        this.name = name;
                    }

                    getUsers(): string[] {
                        return ['alice', 'bob'];
                    }
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "user.service.ts", "default", "typescript");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertEquals(1, classes.size(), "Should find 1 class");
        assertEquals("UserService", classes.get(0).getName());

        List<CodeEntity> imports = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.IMPORT).toList();
        assertTrue(imports.size() >= 1, "Should find imports");

        List<CodeEntity> methods = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.METHOD).toList();
        assertTrue(methods.size() >= 1, "Should find methods");
    }

    @Test
    void parseTypescriptInterface() {
        String ts = """
                export interface User {
                    name: string;
                    age: number;
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "user.ts", "default", "typescript");

        List<CodeEntity> interfaces = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.INTERFACE).toList();
        assertEquals(1, interfaces.size());
        assertEquals("User", interfaces.get(0).getName());
    }

    @Test
    void parseTypescriptEnum() {
        String ts = """
                export enum Status {
                    Active = 'ACTIVE',
                    Inactive = 'INACTIVE',
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "status.ts", "default", "typescript");

        List<CodeEntity> enums = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.ENUM).toList();
        assertEquals(1, enums.size());
        assertEquals("Status", enums.get(0).getName());
    }

    @Test
    void parseJavascriptFunctions() {
        String js = """
                function greet(name) {
                    return `Hello, ${name}!`;
                }

                const add = (a, b) => a + b;

                async function fetchData(url) {
                    const res = await fetch(url);
                    return res.json();
                }
                """;
        String[] lines = js.split("\n");

        ExtractionOutput output = parser.parse(lines, "utils.js", "default", "javascript");

        List<CodeEntity> funcs = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertTrue(funcs.size() >= 2, "Should find functions (greet, fetchData, and possibly add arrow fn)");
    }

    @Test
    void parseTypeAlias() {
        String ts = """
                export type UserId = string;
                type Callback = (data: any) => void;
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "types.ts", "default", "typescript");

        List<CodeEntity> aliases = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.TYPE_ALIAS).toList();
        assertTrue(aliases.size() >= 1, "Should find type aliases");
    }

    // ── JSDoc extraction ───────────────────────────────────────────────

    @Test
    void jsDocOnFunction() {
        String ts = """
                /**
                 * Calculates the sum of two numbers.
                 * @param a First number
                 * @param b Second number
                 * @returns The sum
                 */
                function add(a: number, b: number): number {
                    return a + b;
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "math.ts", "default", "typescript");

        List<CodeEntity> funcs = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertFalse(funcs.isEmpty());
        String doc = funcs.get(0).getDocComment();
        assertNotNull(doc, "Function should have JSDoc");
        assertTrue(doc.contains("Calculates the sum"), "Doc should contain description");
    }

    @Test
    void jsDocOnClass() {
        String ts = """
                /**
                 * Manages user authentication and sessions.
                 */
                export class AuthService {
                    login() {}
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "auth.ts", "default", "typescript");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertFalse(classes.isEmpty());
        String doc = classes.get(0).getDocComment();
        assertNotNull(doc, "Class should have JSDoc");
        assertTrue(doc.contains("authentication"), "Doc should contain description");
    }

    @Test
    void jsDocOnInterface() {
        String ts = """
                /**
                 * Defines the shape of a configuration object.
                 */
                export interface Config {
                    host: string;
                    port: number;
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "config.ts", "default", "typescript");

        List<CodeEntity> interfaces = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.INTERFACE).toList();
        assertFalse(interfaces.isEmpty());
        assertNotNull(interfaces.get(0).getDocComment(), "Interface should have JSDoc");
    }

    @Test
    void jsDocOnEnum() {
        String ts = """
                /**
                 * Possible log levels.
                 */
                enum LogLevel {
                    DEBUG,
                    INFO,
                    ERROR
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "log.ts", "default", "typescript");

        List<CodeEntity> enums = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.ENUM).toList();
        assertFalse(enums.isEmpty());
        assertNotNull(enums.get(0).getDocComment(), "Enum should have JSDoc");
    }

    @Test
    void jsDocNotLeakedToNextEntity() {
        String ts = """
                /**
                 * First function doc.
                 */
                function first() {}

                function second() {}
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "leak.ts", "default", "typescript");

        List<CodeEntity> funcs = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertTrue(funcs.size() >= 2);
        CodeEntity firstFn = funcs.stream().filter(f -> f.getName().equals("first")).findFirst().orElse(null);
        CodeEntity secondFn = funcs.stream().filter(f -> f.getName().equals("second")).findFirst().orElse(null);
        assertNotNull(firstFn);
        assertNotNull(secondFn);
        assertNotNull(firstFn.getDocComment(), "first() should have doc");
        assertNull(secondFn.getDocComment(), "second() should NOT have doc (no JSDoc above it)");
    }

    @Test
    void containsRelationsEmitted() {
        String ts = """
                export class Container {
                    doWork() {}
                }
                """;
        String[] lines = ts.split("\n");

        ExtractionOutput output = parser.parse(lines, "container.ts", "default", "typescript");

        List<RelationTriple> contains = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS).toList();
        assertTrue(contains.size() >= 1, "Should have CONTAINS relations");
    }
}
