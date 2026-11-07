package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalRelationExtractorTest {
    private static Map<String, Object> method(String name, String fqn, int start, int end) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityType", "METHOD");
        entity.put("name", name);
        entity.put("fullyQualifiedName", fqn);
        entity.put("signature", name + "()");
        entity.put("startLine", start);
        if (end > 0) entity.put("endLine", end);
        return entity;
    }

    private static List<Map<String, Object>> calls(String source, String language,
                                                  List<Map<String, Object>> entities) {
        return LocalRelationExtractor.extract("Example.java", "test", entities,
                source.split("\n", -1), language).stream()
                .filter(relation -> "CALLS".equals(relation.get("relationType"))).toList();
    }

    @Test
    void emptyAndOverloadedBodiesDoNotInventDeclarationCalls() {
        String source = "void caller() {}\nvoid caller(int value) { helper(); }";
        var calls = calls(source, "java", List.of(method("caller", "Example.caller", 1, 1),
                method("caller", "Example.caller", 2, 2)));
        assertEquals(List.of("helper"), calls.stream().map(r -> r.get("targetName")).toList());
        assertEquals(2, calls.get(0).get("line"));
    }

    @Test
    void qualifiedOccurrenceDoesNotSuppressDistinctBareOrSingleLetterCalls() {
        var calls = calls("void caller() { remote.helper(); helper(); x(); }", "java",
                List.of(method("caller", "Example.caller", 1, 1)));
        assertEquals(List.of("remote.helper", "helper", "x"),
                calls.stream().map(r -> r.get("targetHint")).toList());
    }

    @Test
    void rustGenericLifetimeDoesNotMaskBodyAndCharacterLiteralRemainsMasked() {
        var calls = calls("fn caller<'a>(value: &'a str) { let ch = 'x'; helper(); }", "rust",
                List.of(method("caller", "caller", 1, 1)));
        assertEquals(List.of("helper"), calls.stream().map(r -> r.get("targetName")).toList());
    }

    @Test
    void kotlinAndScalaInlineExpressionBodiesRetainCalls() {
        for (String language : List.of("kotlin", "scala")) {
            String keyword = "kotlin".equals(language) ? "fun" : "def";
            var calls = calls(keyword + " caller() = helper()\nval field = outside()", language,
                    List.of(method("caller", "Example.caller", 1, 1)));
            assertEquals(List.of("helper"), calls.stream().map(r -> r.get("targetName")).toList());
        }
    }

    @Test
    void inlineBodyIncludesRealCallsButNotDeclarationOrTrailingInitializer() {
        var calls = calls("void caller() { helper(); } Object field = outside();", "java",
                List.of(method("caller", "Example.caller", 1, 1)));
        assertEquals(List.of("helper"), calls.stream().map(r -> r.get("targetName")).toList());
        assertEquals(1, calls.get(0).get("line"));
        assertNull(calls.get(0).get("targetFqn"));
        assertEquals("helper", calls.get(0).get("targetHint"));
    }

    @Test
    void bodyBoundsIgnoreStringsAndMultilineComments() {
        String source = "void caller() {\n"
                + "  String fake = \"hidden(); }\"; // commentCall();\n"
                + "  /* commentOpen();\n"
                + "     commentClose(); */ real();\n"
                + "}\n"
                + "Object field = outside();\n";
        var calls = calls(source, "java", List.of(method("caller", "Example.caller", 1, 5)));
        assertEquals(List.of("real"), calls.stream().map(r -> r.get("targetName")).toList());
        assertEquals(4, calls.get(0).get("line"));
    }

    @Test
    void textBlocksAndEscapedQuotesDoNotCreateCallsOrCloseBody() {
        String source = "void caller() {\n"
                + "String text = \"\"\"\n"
                + "fake(); }\n"
                + "\"\"\";\n"
                + "String quoted = \"escaped \\\" stillFake();\";\n"
                + "real();\n}";
        var calls = calls(source, "java", List.of(method("caller", "Example.caller", 1, 7)));
        assertEquals(List.of("real"), calls.stream().map(r -> r.get("targetName")).toList());
        assertEquals(6, calls.get(0).get("line"));
    }

    @Test
    void missingEndUsesClassBoundaryAndDeclaredEndIsRespected() {
        String source = "void caller() {\n  inside();\nclass Next {\n  outside();\n}";
        Map<String, Object> next = Map.of("entityType", "CLASS", "name", "Next",
                "fullyQualifiedName", "Next", "startLine", 3);
        var bounded = calls(source, "java", List.of(method("caller", "Example.caller", 1, 0), next));
        assertEquals(List.of("inside"), bounded.stream().map(r -> r.get("targetName")).toList());
        var declared = calls("void caller() {\ninside();\noutside();\n}", "java",
                List.of(method("caller", "Example.caller", 1, 2)));
        assertEquals(List.of("inside"), declared.stream().map(r -> r.get("targetName")).toList());
    }

    @Test
    void abstractDeclarationDoesNotStealFollowingInitializer() {
        var calls = calls("abstract void caller();\nObject field = outside();", "java",
                List.of(method("caller", "Example.caller", 1, 0)));
        assertTrue(calls.isEmpty());
    }

    @Test
    void duplicateNamesRemainLookupHintsRatherThanLastEntityWins() {
        var caller = method("caller", "Example.caller", 1, 1);
        var first = method("helper", "First.helper", 2, 2);
        var second = method("helper", "Second.helper", 3, 3);
        String source = "void caller() { helper(); }\nvoid helper() {}\nvoid helper() {}";
        for (var entities : List.of(List.of(caller, first, second), List.of(second, caller, first))) {
            var calls = calls(source, "java", entities);
            assertEquals(1, calls.size());
            assertNull(calls.get(0).get("targetFqn"));
            assertEquals("helper", calls.get(0).get("targetHint"));
        }
    }

    @Test
    void unknownReceiverKeepsQualificationInsteadOfUsingUnrelatedUniqueMethod() {
        var calls = calls("void caller() { remote.helper(); }\nvoid helper() {}", "java",
                List.of(method("caller", "Example.caller", 1, 1),
                        method("helper", "Unrelated.helper", 2, 2)));
        assertEquals(1, calls.size());
        assertNull(calls.get(0).get("targetFqn"));
        assertEquals("remote.helper", calls.get(0).get("targetHint"));
    }

    @Test
    void pythonCallsDoNotUseOtherLanguagesKeywordLists() {
        var relations = calls("def caller():\n    inline()\n    close()\n    range(3)\n    service.inline()\n    if (ready):\n        work()", "python",
                List.of(method("caller", "caller", 1, 1)));
        assertEquals(List.of("inline", "close", "range", "inline", "work"),
                relations.stream().map(r -> r.get("targetName")).toList());
    }

    @Test
    void pythonDeclarationPlaceholderUsesIndentationAndRetainsInlineCalls() {
        var inline = calls("def caller(): inline()\noutside()", "python",
                List.of(method("caller", "caller", 1, 1)));
        assertEquals(List.of("inline"), inline.stream().map(r -> r.get("targetName")).toList());
        var block = calls("def caller():\n    nested()\noutside()", "python",
                List.of(method("caller", "caller", 1, 1)));
        assertEquals(List.of("nested"), block.stream().map(r -> r.get("targetName")).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmParserRetainsInlineMethodContainingNewExpression() throws Exception {
        String source = "class Example {\n  Object caller() { return new Result(helper()); }\n}";
        var parse = LocalCodeIndexer.class.getDeclaredMethod("parseEntities",
                String[].class, String.class, String.class, String.class);
        parse.setAccessible(true);
        var entities = (List<Map<String, Object>>) parse.invoke(new LocalCodeIndexer(),
                source.split("\n"), "Example.java", "test", "java");
        assertTrue(entities.stream().anyMatch(e -> "Example.caller".equals(e.get("fullyQualifiedName"))));
        var calls = calls(source, "java", entities);
        assertEquals(List.of("Result", "helper"), calls.stream().map(r -> r.get("targetName")).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmParserRecordsFullBodyForMultilineMethodsAndConstructors() throws Exception {
        String source = "class Example {\n"
                + "  Example() {\n    construct();\n  }\n"
                + "  void caller(\n      String argument\n  ) {\n    helper();\n  }\n"
                + "  Object field = outside();\n}";
        var parse = LocalCodeIndexer.class.getDeclaredMethod("parseEntities",
                String[].class, String.class, String.class, String.class);
        parse.setAccessible(true);
        var entities = (List<Map<String, Object>>) parse.invoke(new LocalCodeIndexer(),
                source.split("\n"), "Example.java", "test", "java");
        var constructor = entities.stream().filter(e -> "Example.Example".equals(e.get("fullyQualifiedName")))
                .findFirst().orElseThrow();
        var caller = entities.stream().filter(e -> "Example.caller".equals(e.get("fullyQualifiedName")))
                .findFirst().orElseThrow();
        assertEquals(4, constructor.get("endLine"));
        assertEquals(9, caller.get("endLine"));
        var calls = calls(source, "java", entities);
        assertEquals(List.of("construct", "helper"), calls.stream().map(r -> r.get("targetName")).toList());
        assertEquals(List.of(3, 8), calls.stream().map(r -> r.get("line")).toList());
    }
}
