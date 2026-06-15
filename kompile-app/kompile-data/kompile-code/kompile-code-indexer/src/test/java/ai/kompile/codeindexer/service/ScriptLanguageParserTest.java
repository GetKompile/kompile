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
import ai.kompile.codeindexer.service.parsers.ScriptLanguageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScriptLanguageParserTest {

    private ScriptLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new ScriptLanguageParser();
    }

    @Test
    void supportedLanguages() {
        Set<String> langs = parser.supportedLanguages();
        assertTrue(langs.contains("python"));
        assertTrue(langs.contains("ruby"));
        assertTrue(langs.contains("php"));
    }

    // ── Python ──────────────────────────────────────────────────────────

    @Test
    void parsePythonClass() {
        String python = """
                import os
                from typing import List, Optional

                class UserService:
                    \"\"\"Manages user operations.\"\"\"

                    def __init__(self, db_url: str):
                        self.db_url = db_url

                    def get_user(self, user_id: int) -> Optional[dict]:
                        \"\"\"Fetch a user by ID.\"\"\"
                        pass

                    def list_users(self) -> List[dict]:
                        return []

                    @staticmethod
                    def validate(email: str) -> bool:
                        return "@" in email
                """;
        String[] lines = python.split("\n");

        ExtractionOutput output = parser.parse(lines, "user_service.py", "default", "python");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find class");
        assertEquals("UserService", classes.get(0).getName());

        List<CodeEntity> methods = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.METHOD
                        || e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertTrue(methods.size() >= 3, "Should find methods");

        List<CodeEntity> imports = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.IMPORT).toList();
        assertTrue(imports.size() >= 2, "Should find imports");
    }

    @Test
    void parsePythonInheritance() {
        String python = """
                class Animal:
                    def speak(self):
                        pass

                class Dog(Animal):
                    def speak(self):
                        return "Woof"

                class Cat(Animal):
                    def speak(self):
                        return "Meow"
                """;
        String[] lines = python.split("\n");

        ExtractionOutput output = parser.parse(lines, "animals.py", "default", "python");

        List<RelationTriple> extends_ = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.EXTENDS).toList();
        assertTrue(extends_.size() >= 2, "Should have EXTENDS for Dog and Cat");
    }

    @Test
    void parsePythonStandaloneFunction() {
        String python = """
                def calculate_distance(x1: float, y1: float, x2: float, y2: float) -> float:
                    \"\"\"Calculate Euclidean distance between two points.\"\"\"
                    return ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5

                async def fetch_data(url: str) -> dict:
                    pass
                """;
        String[] lines = python.split("\n");

        ExtractionOutput output = parser.parse(lines, "utils.py", "default", "python");

        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertTrue(functions.size() >= 2, "Should find standalone functions");
    }

    @Test
    void parsePythonDocstring() {
        String python = """
                class Config:
                    \"\"\"Application configuration manager.

                    Loads config from YAML files and environment variables.
                    \"\"\"

                    def load(self):
                        pass
                """;
        String[] lines = python.split("\n");

        ExtractionOutput output = parser.parse(lines, "config.py", "default", "python");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertFalse(classes.isEmpty());
        String doc = classes.get(0).getDocComment();
        assertNotNull(doc, "Class should have docstring");
        assertTrue(doc.contains("configuration"), "Docstring should contain description");
    }

    // ── Ruby ─────────────���────────────────────────���─────────────────────

    @Test
    void parseRubyClass() {
        String ruby = """
                require 'json'
                require_relative 'base_model'

                module App
                  class User < BaseModel
                    attr_accessor :name, :email

                    def initialize(name, email)
                      @name = name
                      @email = email
                    end

                    def to_json
                      { name: @name, email: @email }.to_json
                    end
                  end
                end
                """;
        String[] lines = ruby.split("\n");

        ExtractionOutput output = parser.parse(lines, "user.rb", "default", "ruby");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find class");

        List<CodeEntity> modules = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.MODULE).toList();
        assertTrue(modules.size() >= 1, "Should find module");

        List<RelationTriple> extends_ = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.EXTENDS).toList();
        assertTrue(extends_.size() >= 1, "Should have EXTENDS for User < BaseModel");
    }

    // ── PHP ─────────────────────────────────────────────────────────────

    @Test
    void parsePhpClass() {
        String php = """
                <?php
                namespace App\\Services;

                use App\\Models\\User;
                use App\\Contracts\\UserRepository;

                class UserService implements UserRepository {
                    private $db;

                    public function __construct($db) {
                        $this->db = $db;
                    }

                    public function findById(int $id): ?User {
                        return null;
                    }
                }
                """;
        String[] lines = php.split("\n");

        ExtractionOutput output = parser.parse(lines, "UserService.php", "default", "php");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find PHP class");

        List<RelationTriple> implements_ = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.IMPLEMENTS).toList();
        assertTrue(implements_.size() >= 1, "Should have IMPLEMENTS for UserRepository");
    }

    // ── Cross-cutting ───────────────────────────────────────────────────

    @Test
    void containsRelationsEmitted() {
        String python = """
                class Service:
                    def method_a(self):
                        pass
                    def method_b(self):
                        pass
                """;
        ExtractionOutput output = parser.parse(python.split("\n"), "service.py", "default", "python");

        List<RelationTriple> contains = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS).toList();
        assertFalse(contains.isEmpty(), "Should have CONTAINS relations");
    }
}
