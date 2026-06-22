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
package ai.kompile.knowledgegraph.reasoning.controller;

import ai.kompile.core.graphrag.conformance.OntologyAxiom;
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.controller.GraphRulesController.RuleDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphRulesController}.
 *
 * <p>All tests are pure Java — no Spring context, no mocks library required. Uses the
 * no-arg {@link KbGroundingService()} constructor and sets the package-private fields
 * {@code ontologyProvider} and {@code dataDir} directly (same-package access), matching
 * the test idiom used by {@code IncrementalReasoningOrchestratorTest}.</p>
 */
class GraphRulesControllerTest {

    private static final long FACT_SHEET_ID = 42L;

    private KbGroundingService groundingService;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
    }

    /** Build a controller with no optional dependencies. */
    private GraphRulesController bare() {
        return new GraphRulesController(groundingService);
    }

    /** Build a controller with a specific ontology provider. */
    private GraphRulesController withOntology(OntologyProjectionProvider provider) {
        GraphRulesController c = new GraphRulesController(groundingService);
        c.ontologyProvider = provider;
        return c;
    }

    /** Build a controller pointing at a specific data directory. */
    private GraphRulesController withDataDir(String dir) {
        GraphRulesController c = new GraphRulesController(groundingService);
        c.dataDir = dir;
        return c;
    }

    /** Build a controller with all three sources wired. */
    private GraphRulesController withAll(OntologyProjectionProvider provider, String dir) {
        GraphRulesController c = new GraphRulesController(groundingService);
        c.ontologyProvider = provider;
        c.dataDir = dir;
        return c;
    }

    // ─── Empty FactStore ──────────────────────────────────────────────────────

    @Test
    @DisplayName("empty FactStore + no ontology → empty list, 200 OK")
    void emptyFactStore_noOntology_returnsEmptyList() {
        ResponseEntity<List<RuleDto>> response = bare().getRules(FACT_SHEET_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    // ─── PSL rules from FactStore ─────────────────────────────────────────────

    @Test
    @DisplayName("populated FactStore → PSL-tagged rules")
    void populatedFactStore_returnsPslRules() {
        groundingService.assertFact(FACT_SHEET_ID,
                Fact.observed("works_at(alice, acme)", "test"));
        groundingService.assertFact(FACT_SHEET_ID,
                Fact.soft("knows(alice, bob)", 0.6, "test"));

        List<RuleDto> rules = bare().getRules(FACT_SHEET_ID).getBody();

        assertThat(rules).isNotNull().isNotEmpty();
        assertThat(rules).allMatch(r -> "PSL".equals(r.kind()));
        assertThat(rules).anyMatch(r -> r.ruleText().contains("works_at"));
    }

    @Test
    @DisplayName("PSL rules have positive weight, non-blank text, non-null head/body")
    void pslRules_haveValidShape() {
        groundingService.assertFact(FACT_SHEET_ID,
                Fact.observed("has_type_person(e1)", "test"));

        List<RuleDto> rules = bare().getRules(FACT_SHEET_ID).getBody();

        assertThat(rules).isNotNull().isNotEmpty();
        for (RuleDto rule : rules) {
            assertThat(rule.ruleText()).isNotBlank();
            assertThat(rule.weight()).isGreaterThan(0.0);
            assertThat(rule.head()).isNotNull();
            assertThat(rule.body()).isNotNull();
        }
    }

    // ─── Ontology-derived rules ───────────────────────────────────────────────

    @Test
    @DisplayName("bound DOMAIN+RANGE axioms → two ONTOLOGY rules with correct head/body")
    void boundOntology_producesOntologyRules() {
        OntologyProjectionProvider provider = new OntologyProjectionProvider() {
            @Override public boolean hasBoundOntology(Long id) { return FACT_SHEET_ID == id; }
            @Override public List<String> allowedEntityTypes(Long id) { return List.of(); }
            @Override public List<String> allowedRelationshipTypes(Long id) { return List.of(); }
            @Override public List<OntologyAxiom> ontologyAxioms(Long id) {
                return List.of(
                        new OntologyAxiom(OntologyAxiom.Kind.DOMAIN, "works_at", "Person"),
                        new OntologyAxiom(OntologyAxiom.Kind.RANGE,  "works_at", "Organization"));
            }
        };

        List<RuleDto> ontRules = withOntology(provider).getRules(FACT_SHEET_ID).getBody()
                .stream().filter(r -> "ONTOLOGY".equals(r.kind())).toList();

        assertThat(ontRules).hasSize(2);

        // DOMAIN: subject (?X) should be Person
        assertThat(ontRules).anyMatch(r ->
                r.ruleText().contains("works_at(?X, ?Y)") &&
                r.ruleText().contains("has_type_person(?X)"));

        // RANGE: object (?Y) should be Organization
        assertThat(ontRules).anyMatch(r ->
                r.ruleText().contains("works_at(?X, ?Y)") &&
                r.ruleText().contains("has_type_organization(?Y)"));
    }

    @Test
    @DisplayName("null ontologyProvider → no ONTOLOGY rules, no crash")
    void nullOntologyProvider_noOntologyRules() {
        groundingService.assertFact(FACT_SHEET_ID,
                Fact.observed("pred(x)", "test"));

        List<RuleDto> rules = bare().getRules(FACT_SHEET_ID).getBody();

        assertThat(rules).isNotNull().isNotEmpty();
        assertThat(rules).noneMatch(r -> "ONTOLOGY".equals(r.kind()));
    }

    @Test
    @DisplayName("unbound ontology → no ONTOLOGY rules")
    void unboundOntology_noOntologyRules() {
        OntologyProjectionProvider provider = new OntologyProjectionProvider() {
            @Override public boolean hasBoundOntology(Long id) { return false; }
            @Override public List<String> allowedEntityTypes(Long id) { return List.of(); }
            @Override public List<String> allowedRelationshipTypes(Long id) { return List.of(); }
            @Override public List<OntologyAxiom> ontologyAxioms(Long id) { return List.of(); }
        };

        List<RuleDto> rules = withOntology(provider).getRules(FACT_SHEET_ID).getBody();

        assertThat(rules).isNotNull();
        assertThat(rules).noneMatch(r -> "ONTOLOGY".equals(r.kind()));
    }

    @Test
    @DisplayName("ontology DOMAIN rule has weight = DEFAULT_ONTOLOGY_RULE_WEIGHT")
    void ontologyRule_weight_matchesDefault() {
        OntologyProjectionProvider provider = new OntologyProjectionProvider() {
            @Override public boolean hasBoundOntology(Long id) { return true; }
            @Override public List<String> allowedEntityTypes(Long id) { return List.of(); }
            @Override public List<String> allowedRelationshipTypes(Long id) { return List.of(); }
            @Override public List<OntologyAxiom> ontologyAxioms(Long id) {
                return List.of(new OntologyAxiom(OntologyAxiom.Kind.DOMAIN, "rel", "Type"));
            }
        };

        List<RuleDto> ontRules = withOntology(provider).getRules(FACT_SHEET_ID).getBody()
                .stream().filter(r -> "ONTOLOGY".equals(r.kind())).toList();

        assertThat(ontRules).hasSize(1);
        assertThat(ontRules.get(0).weight())
                .isEqualTo(GraphRulesController.DEFAULT_ONTOLOGY_RULE_WEIGHT);
    }

    // ─── FILE_PSL from .psl files ─────────────────────────────────────────────

    @Test
    @DisplayName("psl rule file → FILE_PSL rules returned, comments/blanks skipped")
    void pslFileRules_returnedAsFilePsl(@TempDir Path tempDir) throws IOException {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("custom.psl"),
                "# comment\n" +
                "0.9: knows(?X, ?Y) -> derived_knows(?X, ?Y) ^2\n" +
                "\n" +
                "0.7: trusts(?X, ?Y) -> derived_trusts(?X, ?Y) ^2\n");

        List<RuleDto> fileRules = withDataDir(tempDir.toString())
                .getRules(FACT_SHEET_ID).getBody()
                .stream().filter(r -> "FILE_PSL".equals(r.kind())).toList();

        assertThat(fileRules).hasSize(2);
        assertThat(fileRules).anyMatch(r -> r.ruleText().contains("knows"));
        assertThat(fileRules).anyMatch(r -> r.ruleText().contains("trusts"));
    }

    @Test
    @DisplayName("no rules directory → no FILE_PSL rules, 200 OK")
    void noRulesDir_noFilePslRules(@TempDir Path tempDir) {
        ResponseEntity<List<RuleDto>> response = withDataDir(tempDir.toString()).getRules(FACT_SHEET_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    // ─── Combined sources ─────────────────────────────────────────────────────

    @Test
    @DisplayName("all three sources → rules from each kind present")
    void allSources_allKindsPresent(@TempDir Path tempDir) throws IOException {
        // PSL: seed a fact
        groundingService.assertFact(FACT_SHEET_ID,
                Fact.observed("pred(x)", "test"));

        // ONTOLOGY: one DOMAIN axiom
        OntologyProjectionProvider provider = new OntologyProjectionProvider() {
            @Override public boolean hasBoundOntology(Long id) { return true; }
            @Override public List<String> allowedEntityTypes(Long id) { return List.of(); }
            @Override public List<String> allowedRelationshipTypes(Long id) { return List.of(); }
            @Override public List<OntologyAxiom> ontologyAxioms(Long id) {
                return List.of(new OntologyAxiom(OntologyAxiom.Kind.DOMAIN, "owns", "Person"));
            }
        };

        // FILE_PSL: one rule file
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("extra.psl"),
                "0.5: file_pred(?X) -> derived_file_pred(?X) ^2\n");

        List<RuleDto> rules = withAll(provider, tempDir.toString()).getRules(FACT_SHEET_ID).getBody();

        assertThat(rules).isNotNull();
        assertThat(rules).anyMatch(r -> "PSL".equals(r.kind()));
        assertThat(rules).anyMatch(r -> "ONTOLOGY".equals(r.kind()));
        assertThat(rules).anyMatch(r -> "FILE_PSL".equals(r.kind()));
    }

    // ─── RuleDto shape ────────────────────────────────────────────────────────

    @Test
    @DisplayName("binary PSL rule has correct head/body split")
    void ruleDto_headBodySplit() {
        groundingService.assertFact(FACT_SHEET_ID,
                Fact.observed("works_at(alice, acme)", "test"));

        List<RuleDto> rules = bare().getRules(FACT_SHEET_ID).getBody();

        RuleDto binaryRule = rules.stream()
                .filter(r -> r.ruleText().contains("works_at(?X, ?Y)"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected binary works_at rule"));

        assertThat(binaryRule.body()).contains("works_at");
        assertThat(binaryRule.head()).contains("derived_works_at");
        assertThat(binaryRule.weight()).isGreaterThan(0.0);
        assertThat(binaryRule.hard()).isFalse();
    }
}
