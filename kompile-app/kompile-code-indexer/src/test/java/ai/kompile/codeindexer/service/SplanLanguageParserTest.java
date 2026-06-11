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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SplanLanguageParserTest {

    private SplanLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new SplanLanguageParser();
    }

    @Test
    void supportedLanguages() {
        assertEquals(Set.of("splan"), parser.supportedLanguages());
    }

    @Test
    void parseSimpleOperation() {
        String splan = "write output.txt :::hello world:::\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "test.splan", "default", "splan");

        assertFalse(output.entities().isEmpty());

        // Should have: 1 section MODULE, 1 operation FUNCTION, 1 content block FIELD
        List<CodeEntity> modules = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.MODULE).toList();
        assertEquals(1, modules.size(), "Should have 1 section");
        assertEquals("section-0", modules.get(0).getName());

        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertEquals(1, functions.size(), "Should have 1 operation");
        assertEquals("write", functions.get(0).getName());

        List<CodeEntity> fields = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertEquals(1, fields.size(), "Should have 1 content block");
        assertTrue(fields.get(0).getContentPreview().contains("hello world"));
    }

    @Test
    void parseSections() {
        String splan = """
                create file.txt
                ---
                update config.yaml
                ---
                delete temp.log
                """;
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "multi.splan", "default", "splan");

        List<CodeEntity> modules = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.MODULE).toList();
        assertEquals(3, modules.size(), "Should have 3 sections");
        assertEquals("section-0", modules.get(0).getName());
        assertEquals("section-1", modules.get(1).getName());
        assertEquals("section-2", modules.get(2).getName());
    }

    @Test
    void parseDeclarations() {
        String splan = ":prompt:::You are a helpful assistant.:::\nchat :prompt\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "decl.splan", "default", "splan");

        List<CodeEntity> constants = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CONSTANT).toList();
        assertEquals(1, constants.size(), "Should have 1 declaration");
        assertEquals("prompt", constants.get(0).getName());
        assertTrue(constants.get(0).getContentPreview().contains("helpful assistant"));
    }

    @Test
    void parseDeclarationRefCreatesDepends() {
        String splan = ":msg:::Hello:::\nchat :msg\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "ref.splan", "default", "splan");

        List<RelationTriple> depends = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON).toList();
        assertEquals(1, depends.size(), "Should have DEPENDS_ON from operation to declaration");
        assertTrue(depends.get(0).targetFqn().endsWith(":msg"));
    }

    @Test
    void parseContainmentHierarchy() {
        String splan = "write output.txt\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "hier.splan", "default", "splan");

        // FILE → CONTAINS → Section
        List<RelationTriple> fileToSection = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS
                        && r.sourceFqn().equals("hier.splan")
                        && r.targetFqn().contains("section-0"))
                .toList();
        assertEquals(1, fileToSection.size(), "Should have FILE→CONTAINS→Section");

        // Section → CONTAINS → Operation
        List<RelationTriple> sectionToOp = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS
                        && r.sourceFqn().contains("section-0")
                        && r.targetFqn().contains("write"))
                .toList();
        assertEquals(1, sectionToOp.size(), "Should have Section→CONTAINS→Operation");
    }

    @Test
    void parseMultipleDelimiterTypes() {
        String splan = "write :::colon content::: ###hash content### $$$dollar$$$\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "delim.splan", "default", "splan");

        List<CodeEntity> fields = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertEquals(3, fields.size(), "Should have 3 content blocks");
    }

    @Test
    void parseEmpty() {
        ExtractionOutput output = parser.parse(new String[0], "empty.splan", "default", "splan");
        assertTrue(output.entities().isEmpty() || output.entities().stream()
                .allMatch(e -> e.getEntityType() == CodeEntityType.MODULE));
    }

    @Test
    void parseComments() {
        String splan = "# This is a comment\nwrite output.txt\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "comment.splan", "default", "splan");

        // Comments should not produce entities
        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertEquals(1, functions.size(), "Only operation should be an entity, not comment");
    }

    @Test
    void parentFqnSetCorrectly() {
        String splan = ":data:::some data:::\nprocess :data\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "parent.splan", "default", "splan");

        // Section's parent should be the file
        List<CodeEntity> modules = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.MODULE).toList();
        for (CodeEntity m : modules) {
            assertEquals("parent.splan", m.getParentFqn(), "Section parent should be file");
        }

        // Operation's parent should be the section
        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        for (CodeEntity f : functions) {
            assertTrue(f.getParentFqn().contains("section-"), "Operation parent should be section");
        }

        // Declaration's parent should be the section
        List<CodeEntity> constants = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CONSTANT).toList();
        for (CodeEntity c : constants) {
            assertTrue(c.getParentFqn().contains("section-"), "Declaration parent should be section");
        }
    }

    @Test
    void operationSignature() {
        String splan = "write output.txt :::content:::\n";
        String[] lines = splan.split("\n");

        ExtractionOutput output = parser.parse(lines, "sig.splan", "default", "splan");

        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertFalse(functions.isEmpty());
        String sig = functions.get(0).getSignature();
        assertNotNull(sig);
        assertTrue(sig.startsWith("write"), "Signature should start with command name");
    }
}
