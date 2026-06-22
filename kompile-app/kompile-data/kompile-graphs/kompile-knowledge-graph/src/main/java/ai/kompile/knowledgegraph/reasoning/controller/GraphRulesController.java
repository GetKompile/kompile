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
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.knowledgegraph.grounding.FactSheetKbState;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.OntologyToPslRuleCompiler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read-only REST controller that exposes the PSL, ontology-derived, and file-level PSL rules
 * currently in effect for a given fact sheet.
 *
 * <p>Endpoint: {@code GET /api/graph/{factSheetId}/rules}</p>
 *
 * <p>The response is a list of {@link RuleDto} records, each tagged with a {@code kind}:
 * <ul>
 *   <li>{@code PSL} — soft propagation rules built from the observed FactStore atoms
 *       (the same rules {@link IncrementalReasoningOrchestrator#buildProgramFromFactStore}
 *       generates before every MAP solve).</li>
 *   <li>{@code ONTOLOGY} — PSL rules compiled from the fact sheet's bound ontology's
 *       DOMAIN/RANGE axioms (via {@link OntologyToPslRuleCompiler}); only present when an
 *       ontology is bound.</li>
 *   <li>{@code FILE_PSL} — rules loaded from {@code <dataDir>/rules/*.psl} project files;
 *       only present when a data directory is configured and rule files exist.</li>
 * </ul>
 *
 * <p>All three sources are resilient: when a source is unavailable (empty FactStore, no
 * bound ontology, no data directory) the section is simply omitted from the response.
 * The endpoint never returns an error for a missing source — only a smaller list.</p>
 *
 * <p>The controller is <em>read-only</em>: it builds the same program structure that the
 * orchestrator would use, but does NOT run MAP inference and does NOT write any state.</p>
 *
 * <h3>Injection model</h3>
 * <p>Follows the same pattern as {@link IncrementalReasoningOrchestrator}:
 * <ul>
 *   <li>{@link #kbGroundingService} — required, injected via the single-arg {@code @Autowired}
 *       constructor (the primary injection point).</li>
 *   <li>{@link #ontologyProvider} — optional, field-injected via
 *       {@code @Autowired(required = false)}; null in plain-Java tests that don't wire
 *       {@code GraphOntologyBindingService}.</li>
 *   <li>{@link #dataDir} — optional, field-injected via
 *       {@code @Value("${kompile.data.dir:#{null}}}")}; package-private so plain-Java tests
 *       can set it directly without reflection.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph/{factSheetId}/rules")
public class GraphRulesController {

    /** Default ontology rule weight — mirrors orchestrator's production default. */
    static final double DEFAULT_ONTOLOGY_RULE_WEIGHT = 0.8;

    private final KbGroundingService kbGroundingService;

    /**
     * Optional: ontology projection provider. When null (plain-Java tests, Spring contexts
     * without app-main), ontology-derived rules are omitted from the response.
     */
    @Nullable
    @Autowired(required = false)
    OntologyProjectionProvider ontologyProvider;

    /**
     * Optional project data directory. When set, {@code <dataDir>/rules/*.psl} files are
     * enumerated and their rules returned as FILE_PSL rules.
     *
     * <p>Package-private visibility so that same-package tests can inject a temp dir
     * without requiring Spring or reflection — matches the same idiom used by
     * {@link IncrementalReasoningOrchestrator#dataDir}.</p>
     */
    @Nullable
    @Value("${kompile.data.dir:#{null}}")
    String dataDir;

    @Autowired
    public GraphRulesController(KbGroundingService kbGroundingService) {
        this.kbGroundingService = kbGroundingService;
    }

    /**
     * Return all active rules for the given fact sheet, grouped by kind.
     *
     * <p>Rules are ordered: PSL rules first (from the FactStore), then ONTOLOGY rules,
     * then FILE_PSL rules from project-level .psl files.</p>
     *
     * @param factSheetId the fact sheet whose rule program to inspect
     * @return list of {@link RuleDto} records (never null), or empty list when no rules exist
     */
    @GetMapping
    public ResponseEntity<List<RuleDto>> getRules(@PathVariable Long factSheetId) {
        log.info("GET /api/graph/{}/rules", factSheetId);

        List<RuleDto> rules = new ArrayList<>();

        // ── SOURCE 1: PSL rules from the observed FactStore ──────────────────────────
        try {
            FactSheetKbState state = kbGroundingService.getState(factSheetId);
            FactStore factStore = state.factStore();

            if (!factStore.isEmpty()) {
                PslProgram program = IncrementalReasoningOrchestrator.buildProgramFromFactStore(factStore);
                for (PslRule rule : program.rules()) {
                    rules.add(RuleDto.fromPslRule(rule, "PSL"));
                }
                log.debug("GraphRulesController: factSheet={} — {} PSL rules from FactStore",
                        factSheetId, program.rules().size());
            } else {
                log.debug("GraphRulesController: factSheet={} — FactStore is empty, no PSL rules",
                        factSheetId);
            }
        } catch (Exception e) {
            log.warn("GraphRulesController: could not read FactStore for factSheet={} — {}",
                    factSheetId, e.getMessage());
        }

        // ── SOURCE 2: Ontology-derived PSL rules ──────────────────────────────────────
        if (ontologyProvider != null) {
            try {
                if (ontologyProvider.hasBoundOntology(factSheetId)) {
                    List<OntologyAxiom> axioms = ontologyProvider.ontologyAxioms(factSheetId);
                    if (axioms != null && !axioms.isEmpty()) {
                        OntologyToPslRuleCompiler compiler =
                                new OntologyToPslRuleCompiler(DEFAULT_ONTOLOGY_RULE_WEIGHT);
                        List<String> ruleStrings = compiler.compile(axioms);
                        for (String ruleStr : ruleStrings) {
                            try {
                                PslRule parsed = PslRule.parse(ruleStr);
                                rules.add(RuleDto.fromPslRule(parsed, "ONTOLOGY"));
                            } catch (Exception parseEx) {
                                log.warn("GraphRulesController: could not parse ontology rule '{}' — {}",
                                        ruleStr, parseEx.getMessage());
                            }
                        }
                        log.debug("GraphRulesController: factSheet={} — {} ONTOLOGY rules from axioms",
                                factSheetId, ruleStrings.size());
                    }
                }
            } catch (Exception e) {
                log.warn("GraphRulesController: could not load ontology rules for factSheet={} — {}",
                        factSheetId, e.getMessage());
            }
        }

        // ── SOURCE 3: Project .psl files ─────────────────────────────────────────────
        if (dataDir != null && !dataDir.isBlank()) {
            Path rulesDir = Path.of(dataDir, "rules");
            if (Files.isDirectory(rulesDir)) {
                try (Stream<Path> files = Files.list(rulesDir)) {
                    files.filter(p -> p.toString().endsWith(".psl"))
                         .sorted()
                         .forEach(ruleFile -> {
                             try {
                                 List<String> lines = Files.readAllLines(ruleFile, StandardCharsets.UTF_8);
                                 for (String line : lines) {
                                     String trimmed = line.trim();
                                     if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                                     try {
                                         PslRule parsed = PslRule.parse(trimmed);
                                         rules.add(RuleDto.fromPslRule(parsed, "FILE_PSL"));
                                     } catch (Exception parseEx) {
                                         log.warn("GraphRulesController: could not parse FILE_PSL rule '{}' — {}",
                                                 trimmed, parseEx.getMessage());
                                     }
                                 }
                             } catch (IOException e) {
                                 log.warn("GraphRulesController: could not read {} — {}",
                                         ruleFile, e.getMessage());
                             }
                         });
                } catch (IOException e) {
                    log.warn("GraphRulesController: could not list rules directory {} — {}",
                            rulesDir, e.getMessage());
                }
            }
        }

        return ResponseEntity.ok(rules);
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────────

    /**
     * Wire-format DTO for a single rule.
     *
     * @param kind      "PSL", "ONTOLOGY", or "FILE_PSL"
     * @param ruleText  the canonical PSL rule string (e.g. "0.8: pred(?X) -> derived_pred(?X) ^2")
     * @param weight    the rule weight (Double.POSITIVE_INFINITY for hard constraints)
     * @param hard      true if this is a hard constraint (infinite weight)
     * @param head      the head atom string(s) as rendered by {@link PslRule#toString()}
     * @param body      the body atom string(s) as rendered by {@link PslRule#toString()}
     */
    public record RuleDto(
            String kind,
            String ruleText,
            double weight,
            boolean hard,
            String head,
            String body
    ) {
        /**
         * Build a {@link RuleDto} from a {@link PslRule} and its source kind tag.
         *
         * <p>Head and body are rendered by joining the atom strings with " | " (head) and
         * " & " (body), consistent with the canonical PSL text format. For 0-atom sides an
         * empty string is returned.</p>
         */
        static RuleDto fromPslRule(PslRule rule, String kind) {
            String headStr = rule.head().stream()
                    .map(Object::toString)
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
            String bodyStr = rule.body().stream()
                    .map(Object::toString)
                    .reduce((a, b) -> a + " & " + b)
                    .orElse("");
            return new RuleDto(
                    kind,
                    rule.toString(),
                    rule.weight(),
                    rule.hard(),
                    headStr,
                    bodyStr
            );
        }
    }
}
