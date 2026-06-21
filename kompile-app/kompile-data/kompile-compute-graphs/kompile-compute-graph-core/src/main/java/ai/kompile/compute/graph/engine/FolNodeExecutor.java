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
package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.graph.reasoning.fol.FolInferenceService;
import ai.kompile.graph.reasoning.fol.FolRule;
import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.psl.Term;
import ai.kompile.graph.reasoning.table.ColumnRole;
import ai.kompile.graph.reasoning.table.ColumnType;
import ai.kompile.graph.reasoning.table.Table;
import ai.kompile.graph.reasoning.table.TableColumn;
import ai.kompile.graph.reasoning.table.TableDecisionCompiler;
import ai.kompile.graph.reasoning.table.TableKind;
import ai.kompile.graph.reasoning.table.TableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native reasoning {@link NodeExecutor} that replaces the Drools node executor
 * for the three rule-execution types.
 *
 * <table>
 *   <caption>Type mapping</caption>
 *   <tr><th>NodeExecutionType</th><th>Replaces</th><th>Backend</th></tr>
 *   <tr><td>FOL_RULE</td><td>DROOLS_RULE</td><td>FolInferenceService → HlMrfMapInference (single-shot)</td></tr>
 *   <tr><td>PSL_RULE</td><td>DROOLS_INFERENCE</td><td>HlMrfMapInference (full program, forward-chaining via semi-naive fixpoint)</td></tr>
 *   <tr><td>TABULAR_RULE</td><td>DROOLS_DECISION_TABLE</td><td>TableDecisionCompiler → PslProgram → HlMrfMapInference</td></tr>
 * </table>
 *
 * <h3>Input convention</h3>
 * <p>The node {@code script} field carries the rule definition:</p>
 * <ul>
 *   <li><b>FOL_RULE / PSL_RULE</b>: newline-delimited simple rule text in the form
 *       {@code weight: predicate(X,Y) -> predicate(X,Z) ^2} (raw PSL syntax).
 *       Rules are parsed by {@link #parsePslRuleText(String)}. Input map entries are
 *       observed as PSL atoms at truth-value 1.0.</li>
 *   <li><b>TABULAR_RULE</b>: CSV text.  First line = header (column names ending with
 *       {@code :CONDITION} or {@code :CONCLUSION} to declare roles).  Subsequent lines =
 *       data rows.  Parameters: {@code hitPolicy} (FIRST|ALL|PRIORITY), {@code weight}.</li>
 * </ul>
 *
 * <h3>Output convention</h3>
 * <p>Outputs are placed in the {@link ExecutionResult#getOutputs()} map:</p>
 * <ul>
 *   <li>{@code _inferredFacts} — {@code List<String>} of predicate(args…) strings</li>
 *   <li>{@code _rulesFired} — number of PSL rules grounded</li>
 *   <li>{@code _converged} — boolean (from HlMrfMapInference.Result#converged)</li>
 *   <li>{@code _iterations} — int (ADMM iterations)</li>
 *   <li>One entry per open-world atom key, value = soft-truth [0,1]</li>
 * </ul>
 *
 * <p>This class has NO Spring dependency — it is wired by the auto-configuration or
 * constructed directly in tests.</p>
 */
public class FolNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(FolNodeExecutor.class);

    private final FolInferenceService folService;
    private final TableDecisionCompiler tableCompiler;

    /** Constructor for use when FolInferenceService is already instantiated (DI or test). */
    public FolNodeExecutor(FolInferenceService folService) {
        this.folService = folService;
        this.tableCompiler = new TableDecisionCompiler();
    }

    /** No-arg factory — creates a standalone executor without Spring. */
    public FolNodeExecutor() {
        this(new FolInferenceService());
    }

    // ─── NodeExecutor SPI ─────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("deprecation")
    public Set<NodeExecutionType> supportedTypes() {
        // Also handle the deprecated DROOLS_* types as a transparent migration bridge:
        // DROOLS_RULE → FOL_RULE, DROOLS_INFERENCE → PSL_RULE, DROOLS_DECISION_TABLE → TABULAR_RULE
        // This ensures any persisted nodes with old types continue to work without a DB migration.
        return Set.of(
                NodeExecutionType.FOL_RULE,
                NodeExecutionType.PSL_RULE,
                NodeExecutionType.TABULAR_RULE,
                NodeExecutionType.DROOLS_RULE,
                NodeExecutionType.DROOLS_INFERENCE,
                NodeExecutionType.DROOLS_DECISION_TABLE);
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();
        try {
            @SuppressWarnings("deprecation")
            Map<String, Object> outputs = switch (node.getExecutionType()) {
                case FOL_RULE, DROOLS_RULE      -> executeFolRule(node, inputs);
                case PSL_RULE, DROOLS_INFERENCE -> executePslRule(node, inputs);
                case TABULAR_RULE, DROOLS_DECISION_TABLE -> executeTabularRule(node, inputs);
                default -> throw new IllegalArgumentException(
                        "FolNodeExecutor does not handle type " + node.getExecutionType());
            };
            Instant completedAt = Instant.now();
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(ExecutionStatus.COMPLETED)
                    .outputs(outputs)
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();
        } catch (Exception e) {
            log.error("FolNodeExecutor failed on node '{}'", node.getId(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "script is empty — provide PSL rule text (FOL_RULE/PSL_RULE) or CSV decision table (TABULAR_RULE)";
        }
        @SuppressWarnings("deprecation")
        boolean isTabular = node.getExecutionType() == NodeExecutionType.TABULAR_RULE
                || node.getExecutionType() == NodeExecutionType.DROOLS_DECISION_TABLE;
        if (isTabular) {
            try {
                buildTable(node);
                return null;
            } catch (Exception e) {
                return "TABULAR_RULE: table parse error — " + e.getMessage();
            }
        }
        try {
            parsePslRuleText(node.getScript());
            return null;
        } catch (Exception e) {
            return "PSL rule parse error — " + e.getMessage();
        }
    }

    // ─── Execution strategies ─────────────────────────────────────────────────

    /**
     * FOL_RULE: parse script as PSL rules, observe inputs as ground atoms, solve MAP.
     * Mirrors DROOLS_RULE single-shot targeted semantics: the FolInferenceService handles
     * the program building internally; here we bypass it and call HlMrfMapInference directly
     * so we can inject the caller's input map as observed atoms.
     */
    private Map<String, Object> executeFolRule(ComputeNode node, Map<String, Object> inputs) {
        PslProgram program = buildProgramFromScript(node, inputs);
        return solveAndCollect(program);
    }

    /**
     * PSL_RULE: same as FOL_RULE but additionally runs the crisp Datalog fixpoint
     * (RecursiveQueryEngine) before PSL to derive recursive facts.
     * Mirrors DROOLS_INFERENCE full-chaining semantics.
     */
    private Map<String, Object> executePslRule(ComputeNode node, Map<String, Object> inputs) {
        PslProgram program = buildProgramFromScript(node, inputs);

        // Tier-1: crisp Datalog fixpoint for any explicitly declared recursive rules
        // (rules whose head predicate appears in the body).  We translate the PSL rules
        // that are purely crisp (weight >= 1e5) into DatalogRules for the fixpoint engine.
        List<RecursiveQueryEngine.DatalogRule> datalogRules = toDatalogRules(program);
        if (!datalogRules.isEmpty()) {
            RecursiveQueryEngine.EdbProvider edb = buildEdb(inputs);
            RecursiveQueryEngine.FixpointResult fixpoint = RecursiveQueryEngine.evaluate(datalogRules, edb);
            // Inject derived facts back into the PSL program as observations.
            // derivedFacts() returns Map<predicate, Set<List<args>>>
            for (Map.Entry<String, java.util.Set<List<String>>> entry : fixpoint.derivedFacts().entrySet()) {
                String predicate = entry.getKey();
                for (List<String> args : entry.getValue()) {
                    program.observe(predicate, 1.0, args.toArray(String[]::new));
                }
            }
        }

        return solveAndCollect(program);
    }

    /**
     * TABULAR_RULE: parse script as CSV decision table, compile to PSL, solve MAP.
     * Mirrors DROOLS_DECISION_TABLE semantics.
     */
    private Map<String, Object> executeTabularRule(ComputeNode node, Map<String, Object> inputs) {
        Table table = buildTable(node);
        PslProgram program = tableCompiler.compile(table);

        // Observe inputs as ground atoms
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            double truth = toTruth(e.getValue());
            program.observe(e.getKey(), truth);
        }

        return solveAndCollect(program);
    }

    // ─── Helpers: program building ────────────────────────────────────────────

    private PslProgram buildProgramFromScript(ComputeNode node, Map<String, Object> inputs) {
        PslProgram program = parsePslRuleText(node.getScript());

        // Observe all inputs as ground atoms (truth = 1.0 for truthy, 0.0 for falsy)
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            double truth = toTruth(e.getValue());
            program.observe(e.getKey(), truth);
        }

        // Respect node parameters for max iterations etc.
        // (HlMrfMapInference takes them via the static solve signature — no action needed here;
        //  the node params are available in context for logging/debugging.)
        return program;
    }

    /**
     * Parse simple PSL rule text into a PslProgram.
     *
     * Supported syntax (one rule per non-blank line, # comments ignored):
     * <pre>
     *   # optional comment
     *   weight: Body(X) -> Head(X) ^2
     *   1e6: Body(X,Y) -> Head(X,Y) ^2   # hard rule (weight >= 1e5 treated as hard)
     * </pre>
     *
     * Each token before the first colon is the weight. Everything after is the implication.
     * The body is the part before {@code ->}; the head is after it.  Atom syntax:
     * {@code predicate(arg1, arg2, ...)}. Negation: {@code !predicate(...)} or
     * {@code ~predicate(...)}.  Squaring: trailing {@code ^2} (currently stored but PSL
     * always uses L2 potential; it is preserved for documentation).
     */
    static PslProgram parsePslRuleText(String script) {
        PslProgram program = new PslProgram();
        for (String rawLine : script.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) {
                log.warn("FolNodeExecutor: skipping malformed rule (no weight:) — {}", line);
                continue;
            }
            double weight;
            try {
                weight = Double.parseDouble(line.substring(0, colonIdx).trim());
            } catch (NumberFormatException ex) {
                log.warn("FolNodeExecutor: bad weight in rule — {}", line);
                continue;
            }
            boolean squared = line.endsWith("^2");
            String body = line.substring(colonIdx + 1, squared ? line.length() - 2 : line.length()).trim();

            String[] sides = body.split("->");
            if (sides.length != 2) {
                log.warn("FolNodeExecutor: rule missing -> separator — {}", line);
                continue;
            }
            List<PslAtom> bodyAtoms = parseAtoms(sides[0].trim());
            List<PslAtom> headAtoms = parseAtoms(sides[1].trim());
            boolean hard = weight >= 1e5;
            PslRule rule = new PslRule(weight, hard, squared, bodyAtoms, headAtoms, List.of());
            program.addRule(rule);
        }
        return program;
    }

    /** Parse a comma-separated list of atoms from one side of an implication. */
    private static List<PslAtom> parseAtoms(String side) {
        List<PslAtom> atoms = new ArrayList<>();
        // split on '&' or ',' as conjunct separator (not inside parens)
        for (String token : splitAtoms(side)) {
            String t = token.trim();
            if (t.isBlank()) continue;
            boolean negated = t.startsWith("!") || t.startsWith("~");
            if (negated) t = t.substring(1).trim();
            int paren = t.indexOf('(');
            String predicate;
            List<Term> args;
            if (paren < 0) {
                predicate = t;
                args = List.of();
            } else {
                predicate = t.substring(0, paren).trim();
                String argStr = t.substring(paren + 1, t.lastIndexOf(')')).trim();
                args = new ArrayList<>();
                for (String arg : argStr.split(",")) {
                    String a = arg.trim();
                    if (!a.isBlank()) args.add(Term.var(a));
                }
            }
            atoms.add(new PslAtom(predicate, args, negated));
        }
        return atoms;
    }

    /** Split an atom list on '&' or ',' that are not inside parentheses. */
    private static List<String> splitAtoms(String s) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if ((c == ',' || c == '&') && depth == 0) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    /**
     * Build a CSV-based Table from the node script.
     *
     * Format:
     * <pre>
     *   col1:CONDITION,col2:CONDITION,col3:CONCLUSION
     *   val1,val2,val3
     *   ...
     * </pre>
     *
     * If no role suffix is given, first columns default to CONDITION, last to CONCLUSION
     * (role is determined by column position: columns up to but excluding the last are
     * CONDITION; the last column is CONCLUSION).
     */
    private Table buildTable(ComputeNode node) {
        String csv = node.getScript().trim();
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 1) throw new IllegalArgumentException("TABULAR_RULE script must have at least a header row");

        String[] headers = lines[0].split(",");
        List<TableColumn> columns = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            ColumnRole role;
            String colName;
            if (h.contains(":")) {
                String[] parts = h.split(":", 2);
                colName = parts[0].trim();
                role = ColumnRole.valueOf(parts[1].trim().toUpperCase());
            } else {
                colName = h;
                role = (i == headers.length - 1) ? ColumnRole.CONCLUSION : ColumnRole.CONDITION;
            }
            columns.add(new TableColumn(colName, role, ColumnType.STRING, ""));
        }

        String hitPolicy = node.getParameters() != null
                ? (String) node.getParameters().getOrDefault("hitPolicy", "FIRST")
                : "FIRST";
        String weightStr = node.getParameters() != null
                ? String.valueOf(node.getParameters().getOrDefault("weight", "2.0"))
                : "2.0";

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("hitPolicy", hitPolicy);
        meta.put("weight", weightStr);

        List<TableRow> rows = new ArrayList<>();
        for (int r = 1; r < lines.length; r++) {
            String line = lines[r].trim();
            if (line.isBlank()) continue;
            String[] cells = line.split(",", -1);
            Map<String, Object> cellMap = new LinkedHashMap<>();
            for (int c = 0; c < columns.size() && c < cells.length; c++) {
                cellMap.put(columns.get(c).name(), cells[c].trim());
            }
            rows.add(new TableRow(UUID.randomUUID().toString(), cellMap));
        }

        return new Table(null, node.getName(), TableKind.DECISION, columns, rows, meta, null);
    }

    // ─── Helpers: MAP inference ───────────────────────────────────────────────

    private Map<String, Object> solveAndCollect(PslProgram program) {
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("_rulesFired", result.groundRules().size());
        outputs.put("_converged", result.converged());
        outputs.put("_iterations", result.iterations());

        // Expose all atom values
        outputs.putAll(result.values());

        // Convenience list of facts above threshold 0.5
        List<String> inferredFacts = new ArrayList<>();
        for (Map.Entry<String, Double> e : result.values().entrySet()) {
            if (e.getValue() >= 0.5) inferredFacts.add(e.getKey());
        }
        outputs.put("_inferredFacts", inferredFacts);

        return outputs;
    }

    // ─── Helpers: Datalog fixpoint (PSL_RULE forward-chaining) ───────────────

    /**
     * Convert hard PSL rules (weight >= 1e5) to Datalog rules for the crisp fixpoint.
     * Only rules with a single head atom and purely positive body atoms are converted;
     * soft rules and arithmetic rules are left for PSL MAP inference.
     */
    static List<RecursiveQueryEngine.DatalogRule> toDatalogRules(PslProgram program) {
        List<RecursiveQueryEngine.DatalogRule> result = new ArrayList<>();
        for (PslRule rule : program.rules()) {
            if (!rule.hard()) continue; // only crisp rules → Datalog
            if (rule.head().size() != 1) continue; // exactly one head atom
            boolean allPositiveBody = rule.body().stream().noneMatch(PslAtom::negated);
            if (!allPositiveBody) continue; // stratification is the RecursiveQueryEngine's concern

            PslAtom head = rule.head().get(0);
            List<RecursiveQueryEngine.RuleAtom> body = new ArrayList<>();
            for (PslAtom ba : rule.body()) {
                List<String> argNames = ba.args().stream().map(Term::name).toList();
                body.add(ba.negated()
                        ? RecursiveQueryEngine.RuleAtom.neg(ba.predicate(), argNames.toArray(String[]::new))
                        : RecursiveQueryEngine.RuleAtom.pos(ba.predicate(), argNames.toArray(String[]::new)));
            }
            List<String> headArgs = head.args().stream().map(Term::name).toList();
            result.add(new RecursiveQueryEngine.DatalogRule(head.predicate(), headArgs, body));
        }
        return result;
    }

    /** Build a trivial EdbProvider from the input map (each key → arity-0 predicate). */
    private static RecursiveQueryEngine.EdbProvider buildEdb(Map<String, Object> inputs) {
        Map<String, Set<List<String>>> edb = new HashMap<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            double truth = toTruth(e.getValue());
            if (truth >= 0.5) {
                edb.put(e.getKey(), Set.of(List.of()));
            }
        }
        return predicate -> {
            Set<List<String>> tuples = edb.get(predicate);
            if (tuples == null) return List.of();
            return List.copyOf(tuples);
        };
    }

    // ─── Helpers: truth coercion ──────────────────────────────────────────────

    /** Convert an arbitrary input value to a PSL soft-truth in [0,1]. */
    static double toTruth(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d >= 0.0 && d <= 1.0) return d;   // already a soft-truth
            return d != 0.0 ? 1.0 : 0.0;            // treat as boolean
        }
        String s = value.toString().trim().toLowerCase();
        if (s.isEmpty() || s.equals("false") || s.equals("0") || s.equals("no")) return 0.0;
        try { return Math.min(1.0, Math.max(0.0, Double.parseDouble(s))); }
        catch (NumberFormatException ignored) {}
        return 1.0; // non-empty string = true
    }
}
