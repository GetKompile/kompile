package ai.kompile.graph.reasoning.psl;

import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PslProgramArtifactCodecTest {
    @TempDir Path directory;

    private static PslProgram fixture() {
        Term x = Term.var("X");
        PslAtom p = PslAtom.of("Evidence", false, x);
        PslAtom q = PslAtom.of("Answer", false, x);
        return new PslProgram().declareClosed("Evidence", 1).declareOpen("Answer", 1)
                .declareOpen("Choice", 2).declareClosed("Unused", 0)
                .observe("Evidence", 0.7, "UPPER_CASE_CONSTANT").target("Answer", "UPPER_CASE_CONSTANT")
                .target("Choice", "UPPER_CASE_CONSTANT", "a").target("Choice", "UPPER_CASE_CONSTANT", "b")
                .addRule(PslRule.weighted(2, false, List.of(p), List.of(q)))
                .addRule(PslRule.weighted(3, true, List.of(q), List.of(p)))
                .addRule(PslRule.hard(List.of(PslAtom.ground("Evidence", "UPPER_CASE_CONSTANT")),
                        List.of(PslAtom.ground("Answer", "UPPER_CASE_CONSTANT"))))
                .addArithmeticRule(ArithmeticRule.hard(
                        List.of(at("Choice", 1, true, x, Term.var("C"))), List.of(scalar(1)), RelOp.EQ))
                .addArithmeticRule(ArithmeticRule.weighted(1, false,
                        List.of(at("Answer", 2, false, x)), List.of(scalar(1.6)), RelOp.LEQ))
                .addArithmeticRule(ArithmeticRule.weighted(1, true,
                        List.of(at("Answer", -1, false, x)), List.of(scalar(-0.7)), RelOp.GEQ));
    }

    private static ArithmeticRule.ArithmeticTerm at(String predicate, double coefficient, boolean sum, Term... args) {
        return new ArithmeticRule.ArithmeticTerm(predicate, List.of(args), coefficient, sum);
    }

    private static ArithmeticRule.ArithmeticTerm scalar(double value) {
        return at(null, value, false);
    }

    @Test void roundTripPreservesStructuredProgramAndPureJavaInference() {
        PslProgram original = fixture();
        String json = PslProgramArtifactCodec.encode(original);
        PslProgram copy = PslProgramArtifactCodec.decode(json);
        assertEquals(json, PslProgramArtifactCodec.encode(copy));
        assertEquals(original.atomsSnapshot(), copy.atomsSnapshot());
        assertEquals(original.closedPredicatesSnapshot(), copy.closedPredicatesSnapshot());
        assertEquals(original.openPredicatesSnapshot(), copy.openPredicatesSnapshot());
        assertEquals(original.valueSnapshot(), copy.valueSnapshot());
        assertEquals(original.observedKeys(), copy.observedKeys());
        assertEquals(original.targetKeys(), copy.targetKeys());
        assertEquals(original.rules(), copy.rules()); // This fixture has no array-valued distinct guards.
        assertEquals(original.arithmeticRules(), copy.arithmeticRules());
        assertFalse(copy.rules().get(2).head().get(0).args().get(0).variable());
        assertEquals(Double.POSITIVE_INFINITY, copy.rules().get(2).weight());
        assertEquals(Double.POSITIVE_INFINITY, copy.arithmeticRules().get(0).weight());
        assertEquals(0.0, map(rows(document(original), "logicalRules").get(2)).get("weight"));
        assertEquals(original.ground(), copy.ground());
        List<ArithmeticGroundRule> before = original.groundArithmetic(), after = copy.groundArithmetic();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(before.get(i).atomKeys(), after.get(i).atomKeys());
            assertArrayEquals(before.get(i).coefficients(), after.get(i).coefficients());
            assertEquals(before.get(i).rhs(), after.get(i).rhs());
            assertEquals(before.get(i).op(), after.get(i).op());
        }
        HlMrfMapInference.Result left = solve(original), right = solve(copy);
        // The mixed nonsmooth/hard fixture reaches the direct solver's iteration cap.
        // Round-tripping must preserve that outcome, not manufacture convergence.
        assertFalse(left.converged(), left.toString());
        assertEquals(left.converged(), right.converged());
        assertEquals(left.values(), right.values());
        assertEquals(left.objective(), right.objective(), 1e-10);
        assertTrue(right.values().get("Answer(UPPER_CASE_CONSTANT)") >= 0.7);
        assertEquals(1, right.values().get("Choice(UPPER_CASE_CONSTANT, a)")
                + right.values().get("Choice(UPPER_CASE_CONSTANT, b)"), 1e-4);
    }

    private static HlMrfMapInference.Result solve(PslProgram program) {
        return new AdmmHlMrfInference().solve(program, program.ground(), program.groundArithmetic(),
                4000, 1e-6, 1e6);
    }

    @Test void squaredProgramConvergesBeforeAndAfterRoundTrip() {
        var p = new PslProgram().observe("Given", 0.8, "a").target("Custom", "a")
                .addRule(PslRule.weighted(3, true, List.of(PslAtom.of("Given", false, Term.var("X"))),
                        List.of(PslAtom.of("Custom", false, Term.var("X")))));
        var expected = solve(p);
        var actual = solve(PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(p)));
        assertTrue(expected.converged(), expected.toString());
        assertTrue(actual.converged(), actual.toString());
        assertEquals(expected.values(), actual.values());
        assertEquals(0.8, actual.values().get("Custom(a)"), 1e-4);
    }

    @Test void additiveStorageReportsUnsupportedProgramsInsteadOfRetainingOldExecution() {
        assertEquals(PslProgramArtifactCodec.encode(fixture()), PslProgramArtifactCodec.encodeForStorage(fixture()));
        var oversized = new PslProgram();
        for (int i = 0; i <= PslProgramArtifactCodec.MAX_ATOMS; i++) oversized.target("P", "n" + i);
        String diagnostic = PslProgramArtifactCodec.encodeForStorage(oversized);
        assertTrue(diagnostic.contains("kompile-psl-program-unavailable"));
        var error = assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(diagnostic));
        assertTrue(error.getMessage().contains("producer could not export"));
        var callback = new PslProgram().registerFunction("Callback", args -> { fail("must not execute"); return 0; });
        assertThrows(IllegalArgumentException.class,
                () -> PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encodeForStorage(callback)));
    }

    @Test void textSummationsRejectLostPositionSemanticsAndRoundTripFinalPosition() {
        for (String source : List.of("P(+X,Y) = 1", "P(+X,+Y) = 1", "P(X,+lower) = 1")) {
            assertThrows(IllegalArgumentException.class, () -> ArithmeticRule.parse(source), source);
        }
        var rule = ArithmeticRule.parse("P(X,+Y) = 1");
        assertTrue(rule.toString().contains("P(X, +Y)"), rule.toString());
        assertEquals(rule, ArithmeticRule.parse(rule.toString()));
    }

    @Test void sameNamedConstantAndVariableRemainDistinctArithmeticJoinTemplates() {
        var p = new PslProgram().observe("P", 0.2, "X").observe("P", 0.8, "a")
                .addArithmeticRule(ArithmeticRule.hard(
                        List.of(at("P", 1, false, Term.con("X")), at("P", 1, false, Term.var("X"))),
                        List.of(scalar(1)), RelOp.LEQ));
        var copy = PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(p));
        var ground = copy.groundAll(new PslProgram.GroundingLimits(10, 10, 20, 1000));
        assertEquals(2, ground.arithmeticRules().size());
        assertTrue(ground.arithmeticRules().stream().anyMatch(r -> List.of(r.atomKeys()).contains("P(a)")));
    }

    @Test void independentSamePredicateSummationsFailClosedInBothDirections() {
        var supported = new PslProgram().observe("P", 0.2, "a", "c")
                .addArithmeticRule(ArithmeticRule.hard(List.of(
                        at("P", 1, true, Term.var("X"), Term.var("C")),
                        at("P", 1, true, Term.var("X"), Term.var("D"))),
                        List.of(scalar(1)), RelOp.LEQ));
        String json = PslProgramArtifactCodec.encode(supported);
        assertEquals(json, PslProgramArtifactCodec.encode(PslProgramArtifactCodec.decode(json)));
        rejected(supported, d -> {
            var second = map(rows(map(rows(d, "arithmeticRules").get(0)), "lhs").get(1));
            map(rows(second, "args").get(0)).put("name", "Y");
        });
        var independent = new PslProgram().addArithmeticRule(ArithmeticRule.hard(List.of(
                at("P", 1, true, Term.var("X"), Term.var("C")),
                at("P", 1, true, Term.var("Y"), Term.var("D"))), List.of(scalar(1)), RelOp.LEQ));
        var error = assertThrows(IllegalArgumentException.class,
                () -> PslProgramArtifactCodec.encode(independent));
        assertTrue(error.getMessage().contains("independent summations"));
    }

    @Test void portableV2SavedGraphArtifactReloadHasInferenceParity() throws Exception {
        PslProgram original = fixture();
        String json = PslProgramArtifactCodec.encode(original);
        UnifiedGraph graph = new UnifiedGraph().addEntity("custom", "Example", "Custom rule program")
                .putArtifactText(PslProgramArtifactCodec.ARTIFACT, json);
        Path saved = directory.resolve("program.kgraph");
        graph.save(saved, KGraphCompatibilityPolicy.portableDefault());
        UnifiedGraph loaded = UnifiedGraph.load(saved);
        assertEquals(json, loaded.artifactText(PslProgramArtifactCodec.ARTIFACT));
        PslProgram copy = PslProgramArtifactCodec.decode(loaded.artifactText(PslProgramArtifactCodec.ARTIFACT));
        assertEquals(solve(original).values(), solve(copy).values());
        assertEquals(json, PslProgramArtifactCodec.encode(copy));
    }

    @Test void infersUndeclaredProducerPredicatesButDecodeNeverInfers() {
        PslProgram producer = new PslProgram().observe("Given", 0.9, "ABC").target("Custom", "ABC")
                .addRule(PslRule.weighted(1, true, List.of(PslAtom.of("Given", false, Term.var("X"))),
                        List.of(PslAtom.of("Custom", false, Term.var("X")))))
                .addArithmeticRule(ArithmeticRule.weighted(2, true,
                        List.of(at("RuleOnly", 1, false, Term.var("Y"))), List.of(scalar(1)), RelOp.LEQ));
        PslProgram copy = PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(producer));
        assertEquals(Map.of("Given", 1, "Custom", 1, "RuleOnly", 1), copy.openPredicatesSnapshot());
        assertTrue(producer.openPredicatesSnapshot().isEmpty());
        rejected(producer, d -> rows(d, "declarations").clear());
        producer.declareClosed("Given", 1).declareOpen("Given", 1);
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(producer));
    }

    @Test void preservesNegationDistinctAndHeadOnlyVariablePriors() {
        PslAtom pair = PslAtom.of("Pair", false, Term.var("x"), Term.var("y"));
        PslProgram program = new PslProgram().observe("Pair", 1, "A", "B")
                .target("State", "A").target("State", "B")
                .addRule(new PslRule(1, false, false, List.of(pair),
                        List.of(PslAtom.of("State", true, Term.var("x"))), List.<String[]>of(new String[]{"x", "y"})))
                .addRule(PslRule.weighted(1, true, List.of(), List.of(PslAtom.of("State", true, Term.var("x")))));
        PslProgram copy = PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(program));
        assertTrue(copy.rules().get(0).head().get(0).negated());
        assertArrayEquals(new String[]{"x", "y"}, copy.rules().get(0).distinct().get(0));
        assertEquals(program.ground(), copy.ground());
        assertEquals(3, copy.ground().size());
        rejected(program, d -> map(rows(d, "logicalRules").get(0)).put("distinct", List.of(List.of("x", "z"))));
        rejected(program, d -> map(rows(d, "logicalRules").get(0)).put("distinct", List.of(List.of("x", "x"))));
        rejected(program, d -> map(rows(d, "logicalRules").get(0)).put("distinct",
                List.of(List.of("x", "y"), List.of("y", "x"))));
    }

    @Test void snapshotsAreImmutableAndDetached() {
        PslProgram program = fixture();
        List<PslAtom> atoms = program.atomsSnapshot();
        Map<String, Integer> open = program.openPredicatesSnapshot();
        var functions = program.functionNamesSnapshot();
        assertThrows(UnsupportedOperationException.class, atoms::clear);
        assertThrows(UnsupportedOperationException.class, open::clear);
        assertThrows(UnsupportedOperationException.class, () -> program.closedPredicatesSnapshot().clear());
        assertThrows(UnsupportedOperationException.class, functions::clear);
        program.target("Later", "x").declareOpen("Later", 1).registerFunction("Callback", args -> 1);
        assertEquals(4, atoms.size());
        assertFalse(open.containsKey("Later"));
        assertTrue(functions.isEmpty());
        assertEquals(java.util.Set.of("Callback"), program.functionNamesSnapshot());
    }

    @Test void rejectsCallbacksEvenWhenUnusedAndNonfiniteObservedValues() {
        PslProgram program = fixture().registerFunction("NeverUsed", args -> {
            fail("codec must never invoke callbacks");
            return 0;
        });
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(program));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(
                new PslProgram().observe("P", Double.NaN, "x")));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(
                new PslProgram().target(PslAtom.ground("P", "x").withNegated(true))));
    }

    @Test void rejectsUnsupportedArithmeticSemanticsInBothDirections() {
        ArithmeticRule.ArithmeticTerm term = at("P", 1, true, Term.var("X"), Term.var("C"));
        PslProgram filters = new PslProgram().addArithmeticRule(new ArithmeticRule(1, false, true,
                List.of(term), List.of(scalar(1)), RelOp.EQ,
                List.of(new ArithmeticRule.FilterClause("C", PslAtom.of("Allowed", false, Term.var("C"))))));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(filters));
        rejected(fixture(), d -> map(rows(d, "arithmeticRules").get(0)).put("filters", List.of(Map.of("variable", "C"))));
        for (ArithmeticRule.ArithmeticTerm bad : List.of(
                at("P", 1, true, Term.var("X"), Term.con("constant")),
                at("P", 1, true, Term.var("X"), Term.var("X")),
                at("P", 1, true, Term.var("+X"), Term.var("+C")),
                at(null, 1, true, Term.var("C")))) {
            PslProgram program = new PslProgram().addArithmeticRule(
                    ArithmeticRule.hard(List.of(bad), List.of(scalar(1)), RelOp.EQ));
            assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(program));
        }
        PslProgram constants = new PslProgram().addArithmeticRule(
                ArithmeticRule.hard(List.of(scalar(0)), List.of(scalar(1)), RelOp.EQ));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(constants));
        rejected(fixture(), d -> map(rows(arithmeticTerm(d), "args").get(1)).put("variable", false));
        rejected(fixture(), d -> map(rows(arithmeticTerm(d), "args").get(0)).put("name", "+X"));
        rejected(fixture(), d -> map(rows(arithmeticTerm(d), "args").get(1)).put("name", "+C"));
        rejected(fixture(), d -> arithmeticTerm(d).put("sumPositions", List.of(0, 1)));
        rejected(fixture(), d -> {
            Map<String, Object> rule = map(rows(d, "arithmeticRules").get(0));
            rule.put("lhs", rule.get("rhs"));
        });
        PslProgram reused = new PslProgram().addArithmeticRule(ArithmeticRule.weighted(1, true,
                List.of(at("P", 1, true, Term.var("X"), Term.var("C"))),
                List.of(at("Q", 1, false, Term.var("C"))), RelOp.EQ));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(reused));
    }

    @Test void rejectsUnexpectedMissingAndCoercedSchemaFields() {
        for (String field : List.of("format", "version", "semantics", "declarations", "atoms", "logicalRules", "arithmeticRules")) {
            rejected(fixture(), d -> d.remove(field));
        }
        rejected(fixture(), d -> d.put("functions", List.of()));
        rejected(fixture(), d -> d.put("format", "other"));
        rejected(fixture(), d -> d.put("semantics", "learned-weights"));
        for (Object version : List.of(2, "1", 1.0, true)) rejected(fixture(), d -> d.put("version", version));
        rejected(fixture(), d -> map(rows(d, "declarations").get(0)).put("arity", 1.0));
        rejected(fixture(), d -> map(rows(d, "declarations").get(0)).put("closed", "true"));
        rejected(fixture(), d -> map(rows(d, "atoms").get(0)).put("observed", 1));
        rejected(fixture(), d -> map(rows(d, "atoms").get(0)).put("negated", false));
        rejected(fixture(), d -> map(rows(d, "logicalRules").get(0)).put("body", "Evidence(X)"));
        rejected(fixture(), d -> map(rows(d, "logicalRules").get(0)).put("hard", "false"));
        rejected(fixture(), d -> map(rows(d, "arithmeticRules").get(0)).put("op", "="));
        rejected(fixture(), d -> map(rows(d, "arithmeticRules").get(0)).put("filters", null));
        rejected(fixture(), d -> firstArg(d).put("variable", "false"));
        rejected(fixture(), d -> firstArg(d).put("extra", 0));
        rejected(fixture(), d -> firstArg(d).remove("name"));
        String json = PslProgramArtifactCodec.encode(fixture());
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(
                json.replace("\"version\":1", "\"version\":1,\"version\":1")));
        for (String token : List.of("01", "+1", "NaN", "Infinity", "1e999")) {
            assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(
                    json.replace("\"version\":1", "\"version\":" + token)));
        }
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(json + " false"));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode("[".repeat(33) + "0" + "]".repeat(33)));
    }

    @Test void rejectsDuplicatesArityConflictsAndNongroundAtoms() {
        rejected(fixture(), d -> rows(d, "atoms").add(rows(d, "atoms").get(0)));
        rejected(fixture(), d -> rows(d, "declarations").add(rows(d, "declarations").get(0)));
        rejected(fixture(), d -> map(rows(d, "declarations").get(0)).put("arity", 2));
        rejected(fixture(), d -> firstArg(d).put("variable", true));
        rejected(fixture(), d -> map(rows(d, "atoms").get(0)).put("predicate", "Unknown"));
        rejected(fixture(), d -> {
            Map<String, Object> r = map(rows(d, "logicalRules").get(0));
            r.put("body", List.of()); r.put("head", List.of());
        });
        PslProgram conflict = new PslProgram().target("P", "x").target("P", "x", "y");
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(conflict));
    }

    @Test void rejectsNonfiniteOutOfRangeAndCoercedNumbers() {
        for (Object value : List.of(-0.1, 1.1, "0.7", "NaN", "Infinity", Double.NaN, Double.POSITIVE_INFINITY)) {
            rejected(fixture(), d -> map(rows(d, "atoms").get(0)).put("value", value));
        }
        rejected(fixture(), d -> map(rows(d, "atoms").get(1)).put("value", 0.2));
        for (Object value : List.of(-1, 1_000_001, "2", Double.POSITIVE_INFINITY)) {
            rejected(fixture(), d -> map(rows(d, "logicalRules").get(0)).put("weight", value));
        }
        rejected(fixture(), d -> map(rows(d, "logicalRules").get(2)).put("weight", 1));
        rejected(fixture(), d -> map(rows(d, "logicalRules").get(2)).put("squared", false));
        for (Object value : List.of(-1_000_001, 1_000_001, "1", Double.NaN)) {
            rejected(fixture(), d -> arithmeticTerm(d).put("coefficient", value));
        }
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(
                new PslProgram().addRule(PslRule.weighted(1_000_001, true, List.of(), List.of(PslAtom.ground("P", "x"))))));
    }

    @Test void rejectsAmbiguousArgumentsAndUnsafeIdentifiers() {
        for (String name : List.of("a,b", "a(b", "a)b", " x", "x ", "\u00A0x", "x\u00A0", "x\n", "x\u0000", "", "x".repeat(257), "\uD800")) {
            rejected(fixture(), d -> firstArg(d).put("name", name));
            assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(new PslProgram().target("P", name)));
        }
        for (String name : List.of("P(x)", "P,Q", "P Q", "1P", "__SUM_P_0", "P".repeat(257))) {
            rejected(fixture(), d -> map(rows(d, "declarations").get(0)).put("predicate", name));
        }
        PslProgram special = new PslProgram().observe("P", 1, "UPPER", "node-id:42", "inside space", "quoted\"text", "😀");
        assertEquals(special.atomsSnapshot(), PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(special)).atomsSnapshot());
    }

    @Test void enforcesAllStructuralCapsAndAcceptsBoundaries() {
        PslProgram atoms = new PslProgram();
        for (int i = 0; i < PslProgramArtifactCodec.MAX_ATOMS; i++) atoms.target("P", "a" + i);
        assertEquals(PslProgramArtifactCodec.MAX_ATOMS, PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(atoms)).atomCount());
        atoms.target("P", "overflow");
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(atoms));
        rejected(fixture(), d -> fill(rows(d, "atoms"), 2001));
        rejected(fixture(), d -> fill(rows(d, "declarations"), 513));
        rejected(fixture(), d -> { fill(rows(d, "logicalRules"), 129); fill(rows(d, "arithmeticRules"), 128); });
        rejected(fixture(), d -> map(rows(d, "declarations").get(0)).put("arity", 9));
        rejected(fixture(), d -> fill(rows(map(rows(d, "atoms").get(0)), "args"), 9));
        PslProgram declarations = new PslProgram();
        for (int i = 0; i < 512; i++) declarations.declareOpen("P" + i, 0);
        assertEquals(512, PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(declarations)).openPredicatesSnapshot().size());
        declarations.declareOpen("TooMany", 0);
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(declarations));
        PslAtom p = PslAtom.ground("P", "x");
        PslProgram rules = new PslProgram();
        for (int i = 0; i < 256; i++) rules.addRule(PslRule.weighted(1, true, List.of(), List.of(p)));
        assertEquals(256, PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(rules)).rules().size());
        rules.addRule(PslRule.weighted(1, true, List.of(), List.of(p)));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(rules));
        PslProgram literals = new PslProgram().addRule(PslRule.weighted(1, false, List.of(), java.util.Collections.nCopies(32, p)));
        assertEquals(32, PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(literals)).rules().get(0).head().size());
        literals.rules().set(0, PslRule.weighted(1, false, List.of(p), java.util.Collections.nCopies(32, p)));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.encode(literals));
        rejected(fixture(), d -> fill(rows(map(rows(d, "arithmeticRules").get(0)), "lhs"), 32)); // plus RHS = 33
        PslProgram arithmeticBoundary = new PslProgram().addArithmeticRule(ArithmeticRule.weighted(1e6, true,
                java.util.Collections.nCopies(31, at("P", -1e6, false, Term.var("X"))),
                List.of(scalar(1e6)), RelOp.LEQ));
        assertEquals(31, PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(arithmeticBoundary))
                .arithmeticRules().get(0).lhs().size());
        PslProgram argumentBoundary = new PslProgram().target("P", "x".repeat(256), "b", "c", "d", "e", "f", "g", "h")
                .observe("ZeroArity", 1);
        assertEquals(argumentBoundary.atomsSnapshot(), PslProgramArtifactCodec.decode(PslProgramArtifactCodec.encode(argumentBoundary)).atomsSnapshot());
        String empty = PslProgramArtifactCodec.encode(new PslProgram());
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(empty + " ".repeat(PslProgramArtifactCodec.MAX_BYTES)));
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(null));
    }

    @Test void utf8ByteCapIsNotOnlyACharacterCap() {
        String oversized = "\"" + "€".repeat(PslProgramArtifactCodec.MAX_BYTES / 3 + 1) + "\"";
        assertTrue(oversized.length() < PslProgramArtifactCodec.MAX_BYTES);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PslProgramArtifactCodec.decode(oversized));
        assertTrue(error.getMessage().contains("byte limit"));
        PslProgram program = new PslProgram();
        String[] args = new String[8];
        java.util.Arrays.fill(args, "€".repeat(128));
        for (int i = 0; i < 2000; i++) {
            args[7] = "€".repeat(124) + i;
            program.target("P", args);
        }
        IllegalArgumentException encodingError = assertThrows(IllegalArgumentException.class,
                () -> PslProgramArtifactCodec.encode(program));
        assertTrue(encodingError.getMessage().contains("byte limit"));
    }

    private static void fill(List<Object> list, int size) {
        Object first = list.get(0);
        while (list.size() < size) list.add(first);
    }

    private static void rejected(PslProgram program, Consumer<Map<String, Object>> mutation) {
        Map<String, Object> document = document(program);
        mutation.accept(document);
        assertThrows(IllegalArgumentException.class, () -> PslProgramArtifactCodec.decode(MiniJson.write(document)));
    }

    private static Map<String, Object> document(PslProgram program) {
        return map(MiniJson.parseStrict(PslProgramArtifactCodec.encode(program), 32));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object object) { return (Map<String, Object>) object; }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Map<String, Object> object, String name) { return (List<Object>) object.get(name); }

    private static Map<String, Object> firstArg(Map<String, Object> document) {
        return map(rows(map(rows(document, "atoms").get(0)), "args").get(0));
    }

    private static Map<String, Object> arithmeticTerm(Map<String, Object> document) {
        return map(rows(map(rows(document, "arithmeticRules").get(0)), "lhs").get(0));
    }
}
