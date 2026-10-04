package ai.kompile.graph.reasoning.psl;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, constructor-based portable inference-program codec. No executable callbacks or text rules.
 * A summation term means exactly its final argument is summed; the current runtime cannot execute
 * filters, multiple summation positions, or constant-only arithmetic constraints. The legacy text
 * parser rejects unsupported '+' placements before losing their provenance. Direct constructors
 * represent a single final-position sum; producers must obey that contract.
 * Unobserved atoms have the runtime's zero initial value (not a persisted inference result).
 * This bounds the input program, not its grounding expansion; consumers must cap runtime grounding.
 */
public final class PslProgramArtifactCodec {
    public static final String ARTIFACT = "reasoning/fol-psl-program.v1.json";
    public static final String FORMAT = "kompile-psl-program";
    public static final int VERSION = 1;
    public static final String SEMANTICS = "inference-program";
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_ATOMS = 2000;
    public static final int MAX_RULES = 256;
    public static final int MAX_RULE_TERMS = 32;
    public static final int MAX_ARITY = 8;
    public static final int MAX_DECLARATIONS = 512;
    public static final int MAX_NAME_CHARS = 256;
    private static final double MAX_MAGNITUDE = 1e6;

    private PslProgramArtifactCodec() { }

    public static String encode(PslProgram program) {
        if (program == null) throw invalid("null program");
        if (!program.functionNamesSnapshot().isEmpty()) throw invalid("external functions are not portable");
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        program.closedPredicatesSnapshot().forEach((p, a) -> declare(declarations, p, a, true));
        program.openPredicatesSnapshot().forEach((p, a) -> declare(declarations, p, a, false));
        checkCount(program.atomCount(), MAX_ATOMS, "atoms");
        checkCount((long) program.rules().size() + program.arithmeticRules().size(), MAX_RULES, "rules");
        List<Object> atoms = new ArrayList<>();
        for (PslAtom atom : program.atomsSnapshot()) {
            infer(declarations, atom.predicate(), atom.args().size());
            atoms.add(row("predicate", atom.predicate(), "args", terms(atom.args()),
                    "observed", program.isObserved(atom.key()), "value", program.value(atom.key())));
            if (atom.negated() || !atom.isGround()) throw invalid("registered atoms must be nonnegated and ground");
        }
        List<Object> logical = new ArrayList<>();
        for (PslRule rule : program.rules()) {
            if (rule == null) throw invalid("null logical rule");
            checkCount((long) rule.body().size() + rule.head().size(), MAX_RULE_TERMS, "logical literals");
            checkCount(rule.distinct().size(), MAX_RULE_TERMS, "distinct guards");
            List<Object> body = literals(rule.body(), declarations);
            List<Object> head = literals(rule.head(), declarations);
            List<Object> distinct = new ArrayList<>();
            for (String[] pair : rule.distinct()) {
                if (pair == null || pair.length != 2) throw invalid("distinct pair requires two variables");
                distinct.add(List.of(identifier(pair[0]), identifier(pair[1])));
            }
            logical.add(row("weight", portableWeight(rule.weight(), rule.hard()), "hard", rule.hard(),
                    "squared", rule.squared(), "body", body, "head", head, "distinct", distinct));
        }
        List<Object> arithmetic = new ArrayList<>();
        for (ArithmeticRule rule : program.arithmeticRules()) {
            if (rule == null) throw invalid("null arithmetic rule");
            checkCount((long) rule.lhs().size() + rule.rhs().size(), MAX_RULE_TERMS, "arithmetic terms");
            if (!rule.filters().isEmpty()) throw invalid("arithmetic filters are not executed by the runtime");
            if (rule.op() == null) throw invalid("missing arithmetic operator");
            arithmetic.add(row("weight", portableWeight(rule.weight(), rule.hard()), "hard", rule.hard(),
                    "squared", rule.squared(), "lhs", arithmeticTerms(rule.lhs(), declarations),
                    "rhs", arithmeticTerms(rule.rhs(), declarations), "op", rule.op().name(), "filters", List.of()));
        }
        List<Object> decls = new ArrayList<>();
        declarations.forEach((p, d) -> decls.add(row("predicate", p, "arity", d.arity(), "closed", d.closed())));
        Map<String, Object> document = row("format", FORMAT, "version", VERSION, "semantics", SEMANTICS,
                "declarations", decls, "atoms", atoms, "logicalRules", logical, "arithmeticRules", arithmetic);
        // Apply precisely the same schema and semantic validation on both sides of the boundary.
        decodeDocument(document);
        String json = MiniJson.write(document);
        requireSize(json);
        return json;
    }

    /** Additive producer path: unsupported programs do not abort otherwise valid legacy learning.
     * A diagnostic replaces any previous executable artifact, so a stale program cannot survive.
     */
    public static String encodeForStorage(PslProgram program) {
        try {
            return encode(program);
        } catch (IllegalArgumentException e) {
            String reason = String.valueOf(e.getMessage());
            if (reason.length() > 1024) reason = reason.substring(0, 1024);
            return MiniJson.write(row("format", "kompile-psl-program-unavailable", "version", VERSION,
                    "reason", reason));
        }
    }

    public static PslProgram decode(String json) {
        requireSize(json);
        Object parsed = MiniJson.parseStrict(json, 32);
        if (parsed instanceof Map<?, ?> root && "kompile-psl-program-unavailable".equals(root.get("format"))) {
            object(root, "format", "version", "reason");
            if (integer(root.get("version"), VERSION) != VERSION) throw invalid("unsupported diagnostic version");
            if (!(root.get("reason") instanceof String reason) || reason.isEmpty() || reason.length() > 1024) {
                throw invalid("invalid producer diagnostic");
            }
            throw invalid("producer could not export this program: " + reason);
        }
        return decodeDocument(parsed);
    }

    private record Declaration(int arity, boolean closed) { }

    private static PslProgram decodeDocument(Object document) {
        Map<?, ?> root = object(document, "format", "version", "semantics", "declarations", "atoms",
                "logicalRules", "arithmeticRules");
        if (!FORMAT.equals(text(root.get("format"))) || integer(root.get("version"), VERSION) != VERSION
                || !SEMANTICS.equals(text(root.get("semantics")))) throw invalid("unsupported program header");
        PslProgram program = new PslProgram();
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        for (Object item : array(root.get("declarations"), MAX_DECLARATIONS)) {
            Map<?, ?> d = object(item, "predicate", "arity", "closed");
            String p = identifier(d.get("predicate"));
            int arity = integer(d.get("arity"), MAX_ARITY);
            boolean closed = bool(d.get("closed"));
            declare(declarations, p, arity, closed);
            if (closed) program.declareClosed(p, arity); else program.declareOpen(p, arity);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : array(root.get("atoms"), MAX_ATOMS)) {
            Map<?, ?> a = object(item, "predicate", "args", "observed", "value");
            PslAtom atom = atom(a.get("predicate"), a.get("args"), false, declarations);
            if (!atom.isGround() || !keys.add(atom.key())) throw invalid("duplicate or nonground registered atom");
            boolean observed = bool(a.get("observed"));
            double value = number(a.get("value"), 0, 1);
            if (observed) program.observe(atom, value);
            else {
                // There is no public target-initial-value API; never silently discard a supplied value.
                if (value != 0) throw invalid("target initial value must be zero");
                program.target(atom);
            }
        }
        List<?> logical = array(root.get("logicalRules"), MAX_RULES);
        List<?> arithmetic = array(root.get("arithmeticRules"), MAX_RULES);
        checkCount((long) logical.size() + arithmetic.size(), MAX_RULES, "rules");
        for (Object item : logical) {
            Map<?, ?> r = object(item, "weight", "hard", "squared", "body", "head", "distinct");
            boolean hard = bool(r.get("hard")), squared = bool(r.get("squared"));
            double weight = weight(r.get("weight"), hard, squared);
            List<PslAtom> body = readLiterals(r.get("body"), declarations);
            List<PslAtom> head = readLiterals(r.get("head"), declarations);
            checkCount((long) body.size() + head.size(), MAX_RULE_TERMS, "logical literals");
            if (body.isEmpty() && head.isEmpty()) throw invalid("empty logical rule");
            // The grounder joins all literals, including head-only and negated priors. Every variable
            // in a literal is bound by that join. Distinct guards, unlike literals, never bind names.
            Set<String> bound = new LinkedHashSet<>();
            body.forEach(a -> bound.addAll(a.variables()));
            head.forEach(a -> bound.addAll(a.variables()));
            List<String[]> distinct = new ArrayList<>();
            Set<Set<String>> seenPairs = new LinkedHashSet<>();
            for (Object pair : array(r.get("distinct"), MAX_RULE_TERMS)) {
                List<?> endpoints = array(pair, 2);
                if (endpoints.size() != 2) throw invalid("distinct requires two variables");
                String a = identifier(endpoints.get(0)), b = identifier(endpoints.get(1));
                if (!bound.contains(a) || !bound.contains(b) || a.equals(b)) throw invalid("unsafe distinct pair");
                if (!seenPairs.add(Set.of(a, b))) throw invalid("duplicate distinct pair");
                distinct.add(new String[]{a, b});
            }
            program.addRule(new PslRule(weight, hard, squared, body, head, distinct));
        }
        for (Object item : arithmetic) {
            Map<?, ?> r = object(item, "weight", "hard", "squared", "lhs", "rhs", "op", "filters");
            boolean hard = bool(r.get("hard")), squared = bool(r.get("squared"));
            double weight = weight(r.get("weight"), hard, squared);
            if (!array(r.get("filters"), MAX_RULE_TERMS).isEmpty()) throw invalid("unsupported arithmetic filters");
            List<ArithmeticRule.ArithmeticTerm> lhs = readArithmeticTerms(r.get("lhs"), declarations);
            List<ArithmeticRule.ArithmeticTerm> rhs = readArithmeticTerms(r.get("rhs"), declarations);
            checkCount((long) lhs.size() + rhs.size(), MAX_RULE_TERMS, "arithmetic terms");
            List<ArithmeticRule.ArithmeticTerm> all = new ArrayList<>(lhs);
            all.addAll(rhs);
            if (all.stream().allMatch(ArithmeticRule.ArithmeticTerm::isConstant)) {
                throw invalid("constant-only arithmetic constraints are not executed by the runtime");
            }
            validateSummations(all);
            RelOp op;
            try { op = RelOp.valueOf(text(r.get("op"))); }
            catch (IllegalArgumentException e) { throw invalid("unsupported arithmetic operator"); }
            program.addArithmeticRule(new ArithmeticRule(weight, hard, squared, lhs, rhs, op, List.of()));
        }
        return program;
    }

    private static void validateSummations(List<ArithmeticRule.ArithmeticTerm> terms) {
        Set<String> outer = new LinkedHashSet<>();
        Set<String> summed = new LinkedHashSet<>();
        Map<String, List<Term>> sumPrefixes = new LinkedHashMap<>();
        for (ArithmeticRule.ArithmeticTerm term : terms) {
            if (term.summationVariable()) {
                // The current grounder names its wildcard by predicate and position, not term.
                // Different prefixes would accidentally join independent summation domains.
                List<Term> prefix = term.args().subList(0, term.args().size() - 1);
                List<Term> previous = sumPrefixes.putIfAbsent(term.predicate(), prefix);
                if (previous != null && !previous.equals(prefix)) {
                    throw invalid("independent summations of the same predicate are not supported");
                }
            }
            for (int i = 0; i < term.args().size(); i++) {
                Term arg = term.args().get(i);
                if (!arg.variable()) continue;
                if (term.summationVariable() && i == term.args().size() - 1) summed.add(arg.name());
                else outer.add(arg.name());
            }
        }
        for (String name : summed) {
            if (outer.contains(name)) throw invalid("summation variable also used in outer binding");
        }
    }

    private static List<ArithmeticRule.ArithmeticTerm> readArithmeticTerms(Object value,
                                                                          Map<String, Declaration> declarations) {
        List<ArithmeticRule.ArithmeticTerm> out = new ArrayList<>();
        for (Object item : array(value, MAX_RULE_TERMS)) {
            Map<?, ?> t = object(item, "coefficient", "predicate", "args", "summationVariable");
            double coefficient = number(t.get("coefficient"), -MAX_MAGNITUDE, MAX_MAGNITUDE);
            List<Term> args = readTerms(t.get("args"));
            boolean sum = bool(t.get("summationVariable"));
            String predicate = t.get("predicate") == null ? null : identifier(t.get("predicate"));
            if (predicate == null) {
                if (!args.isEmpty() || sum) throw invalid("constant arithmetic term has arguments or summation");
            } else {
                requireDeclared(declarations, predicate, args.size());
                if (sum && (args.isEmpty() || !args.get(args.size() - 1).variable())) {
                    throw invalid("summation must be a single final variable argument");
                }
                if (sum) {
                    String last = args.get(args.size() - 1).name();
                    for (int i = 0; i < args.size() - 1; i++) {
                        if (args.get(i).variable() && last.equals(args.get(i).name())) {
                            throw invalid("summation variable repeats in non-final position");
                        }
                    }
                }
            }
            out.add(new ArithmeticRule.ArithmeticTerm(predicate, args, coefficient, sum));
        }
        return out;
    }

    private static List<PslAtom> readLiterals(Object value, Map<String, Declaration> declarations) {
        List<PslAtom> out = new ArrayList<>();
        for (Object item : array(value, MAX_RULE_TERMS)) {
            Map<?, ?> a = object(item, "predicate", "args", "negated");
            out.add(atom(a.get("predicate"), a.get("args"), bool(a.get("negated")), declarations));
        }
        return out;
    }

    private static PslAtom atom(Object predicate, Object args, boolean negated,
                                Map<String, Declaration> declarations) {
        String p = identifier(predicate);
        List<Term> terms = readTerms(args);
        requireDeclared(declarations, p, terms.size());
        return new PslAtom(p, terms, negated);
    }

    private static List<Term> readTerms(Object value) {
        List<Term> out = new ArrayList<>();
        for (Object item : array(value, MAX_ARITY)) {
            Map<?, ?> t = object(item, "name", "variable");
            boolean variable = bool(t.get("variable"));
            String name = variable ? identifier(t.get("name")) : constant(t.get("name"));
            out.add(new Term(name, variable));
        }
        return out;
    }

    private static List<Object> terms(List<Term> terms) {
        checkCount(terms.size(), MAX_ARITY, "arity");
        List<Object> out = new ArrayList<>();
        for (Term t : terms) out.add(row("name", t.name(), "variable", t.variable()));
        return out;
    }

    private static List<Object> literals(List<PslAtom> atoms, Map<String, Declaration> declarations) {
        checkCount(atoms.size(), MAX_RULE_TERMS, "literals");
        List<Object> out = new ArrayList<>();
        for (PslAtom a : atoms) {
            infer(declarations, a.predicate(), a.args().size());
            out.add(row("predicate", a.predicate(), "args", terms(a.args()), "negated", a.negated()));
        }
        return out;
    }

    private static List<Object> arithmeticTerms(List<ArithmeticRule.ArithmeticTerm> terms,
                                               Map<String, Declaration> declarations) {
        checkCount(terms.size(), MAX_RULE_TERMS, "arithmetic terms");
        List<Object> out = new ArrayList<>();
        for (ArithmeticRule.ArithmeticTerm t : terms) {
            if (!t.isConstant()) infer(declarations, t.predicate(), t.args().size());
            out.add(row("coefficient", t.coefficient(), "predicate", t.predicate(),
                    "args", terms(t.args()), "summationVariable", t.summationVariable()));
        }
        return out;
    }

    private static void declare(Map<String, Declaration> declarations, String p, int arity, boolean closed) {
        identifier(p);
        if (arity < 0 || arity > MAX_ARITY) throw invalid("invalid declaration arity");
        if (declarations.putIfAbsent(p, new Declaration(arity, closed)) != null) throw invalid("duplicate declaration");
        checkCount(declarations.size(), MAX_DECLARATIONS, "declarations");
    }

    private static void infer(Map<String, Declaration> declarations, String p, int arity) {
        if (!declarations.containsKey(p)) declare(declarations, p, arity, false);
        else requireDeclared(declarations, p, arity);
    }

    private static void requireDeclared(Map<String, Declaration> declarations, String p, int arity) {
        Declaration d = declarations.get(p);
        if (d == null || d.arity() != arity) throw invalid("undeclared predicate or arity mismatch: " + p);
    }

    private static double portableWeight(double weight, boolean hard) {
        if (hard) {
            if (weight != Double.POSITIVE_INFINITY) throw invalid("hard rule must have infinite internal weight");
            return 0;
        }
        return number(weight, 0, MAX_MAGNITUDE);
    }

    private static double weight(Object value, boolean hard, boolean squared) {
        double weight = number(value, 0, MAX_MAGNITUDE);
        if (hard && (weight != 0 || !squared)) throw invalid("hard rule requires weight zero and squared true");
        return hard ? Double.POSITIVE_INFINITY : weight;
    }

    private static String identifier(Object value) {
        String s = text(value);
        if (!s.matches("[A-Za-z_][A-Za-z0-9_]*") || s.startsWith("__SUM_")) throw invalid("unsafe identifier");
        return s;
    }

    private static String constant(Object value) {
        String s = text(value);
        if (!s.equals(s.strip()) || Character.isSpaceChar(s.charAt(0))
                || Character.isSpaceChar(s.charAt(s.length() - 1))
                || s.indexOf(',') >= 0 || s.indexOf('(') >= 0 || s.indexOf(')') >= 0) {
            throw invalid("ambiguous atom-key constant");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c) &&
                    (!Character.isHighSurrogate(c) || i + 1 == s.length() ||
                            !Character.isLowSurrogate(s.charAt(++i)))) throw invalid("invalid constant character");
        }
        return s;
    }

    private static String text(Object value) {
        if (!(value instanceof String s) || s.isEmpty() || s.length() > MAX_NAME_CHARS) throw invalid("expected bounded string");
        return s;
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean b)) throw invalid("expected boolean");
        return b;
    }

    private static double number(Object value, double min, double max) {
        if (!(value instanceof Number n)) throw invalid("expected number");
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d < min || d > max) throw invalid("number out of bounds");
        return d;
    }

    private static int integer(Object value, int max) {
        if (!(value instanceof Integer || value instanceof Long)) throw invalid("expected integer");
        return (int) number(value, 0, max);
    }

    private static Map<?, ?> object(Object value, String... fields) {
        if (!(value instanceof Map<?, ?> m) || !m.keySet().equals(Set.of(fields))) throw invalid("unexpected object fields");
        return m;
    }

    private static List<?> array(Object value, int max) {
        if (!(value instanceof List<?> list)) throw invalid("expected array");
        checkCount(list.size(), max, "array");
        return list;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    private static void requireSize(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("program exceeds byte limit or is null");
        }
    }

    private static void checkCount(long count, int max, String field) {
        if (count > max) throw invalid(field + " exceeds limit " + max);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("PSL program artifact: " + message);
    }
}
