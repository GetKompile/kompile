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

package ai.kompile.codeindexer.splan;

import ai.kompile.codeindexer.splan.SplanPlanParser.Argument;
import ai.kompile.codeindexer.splan.SplanPlanParser.Operation;
import ai.kompile.codeindexer.splan.SplanPlanParser.Plan;
import ai.kompile.codeindexer.splan.SplanPlanParser.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SplanPlanParserTest {

    @Test
    void parseSimpleOperation() {
        Plan plan = SplanPlanParser.parse("write output.txt");

        assertEquals(1, plan.sections().size());
        Section section = plan.sections().get(0);
        assertEquals(1, section.operations().size());

        Operation op = section.operations().get(0);
        assertEquals("write", op.command());
        assertEquals(1, op.arguments().size());
        assertInstanceOf(Argument.Token.class, op.arguments().get(0));
        assertEquals("output.txt", ((Argument.Token) op.arguments().get(0)).value());
    }

    @Test
    void parseMultipleOperations() {
        String input = """
                create file.txt
                update config.yaml
                delete temp.log
                """;
        Plan plan = SplanPlanParser.parse(input);

        assertEquals(1, plan.sections().size());
        assertEquals(3, plan.sections().get(0).operations().size());
        assertEquals("create", plan.sections().get(0).operations().get(0).command());
        assertEquals("update", plan.sections().get(0).operations().get(1).command());
        assertEquals("delete", plan.sections().get(0).operations().get(2).command());
    }

    @Test
    void parseSections() {
        String input = """
                create file.txt
                ---
                update config.yaml
                ---
                delete temp.log
                """;
        Plan plan = SplanPlanParser.parse(input);

        assertEquals(3, plan.sections().size());
        assertEquals(0, plan.sections().get(0).index());
        assertEquals(1, plan.sections().get(1).index());
        assertEquals(2, plan.sections().get(2).index());
    }

    @Test
    void parseComments() {
        String input = """
                # This is a comment
                write output.txt
                # Another comment
                """;
        Plan plan = SplanPlanParser.parse(input);

        assertEquals(2, plan.comments().size());
        assertEquals("This is a comment", plan.comments().get(0));
        assertEquals(1, plan.sections().get(0).operations().size());
    }

    @Test
    void parseInlineContentBlock() {
        String input = "write :::hello world:::";
        Plan plan = SplanPlanParser.parse(input);

        Operation op = plan.sections().get(0).operations().get(0);
        assertEquals(1, op.arguments().size());
        assertInstanceOf(Argument.ContentBlock.class, op.arguments().get(0));
        Argument.ContentBlock block = (Argument.ContentBlock) op.arguments().get(0);
        assertEquals("hello world", block.content());
        assertEquals(":::", block.delimiter());
    }

    @Test
    void parseDeclaration() {
        String input = ":prompt:::You are a helpful AI.:::\nchat :prompt";
        Plan plan = SplanPlanParser.parse(input);

        Section section = plan.sections().get(0);
        assertTrue(section.declarations().containsKey("prompt"));
        assertEquals("You are a helpful AI.", section.declarations().get("prompt"));

        Operation op = section.operations().get(0);
        assertEquals("chat", op.command());
        assertEquals(1, op.arguments().size());
        assertInstanceOf(Argument.DeclRef.class, op.arguments().get(0));
        assertEquals("prompt", ((Argument.DeclRef) op.arguments().get(0)).name());
    }

    @Test
    void parseMultipleArgTypes() {
        String input = "write output.txt :template :::extra content:::";
        Plan plan = SplanPlanParser.parse(input);

        Operation op = plan.sections().get(0).operations().get(0);
        assertEquals(3, op.arguments().size());
        assertInstanceOf(Argument.Token.class, op.arguments().get(0));
        assertInstanceOf(Argument.DeclRef.class, op.arguments().get(1));
        assertInstanceOf(Argument.ContentBlock.class, op.arguments().get(2));
    }

    @Test
    void parseBlankLines() {
        String input = """

                write file1.txt

                write file2.txt

                """;
        Plan plan = SplanPlanParser.parse(input);
        assertEquals(2, plan.sections().get(0).operations().size());
    }

    @Test
    void parseEmpty() {
        Plan plan = SplanPlanParser.parse("");
        assertEquals(1, plan.sections().size());
        assertTrue(plan.sections().get(0).operations().isEmpty());
    }

    @Test
    void parseAllOperationsFlattens() {
        String input = """
                op1 a
                ---
                op2 b
                ---
                op3 c
                """;
        Plan plan = SplanPlanParser.parse(input);

        List<Operation> allOps = plan.allOperations();
        assertEquals(3, allOps.size());
        assertEquals("op1", allOps.get(0).command());
        assertEquals("op2", allOps.get(1).command());
        assertEquals("op3", allOps.get(2).command());
    }

    @Test
    void parseLineNumbers() {
        String input = """
                # comment
                write file.txt
                read file.txt
                """;
        Plan plan = SplanPlanParser.parse(input);

        List<Operation> ops = plan.sections().get(0).operations();
        assertEquals(2, ops.size());
        // Line numbers should be > 0 (1-based)
        assertTrue(ops.get(0).lineNumber() > 0);
        assertTrue(ops.get(1).lineNumber() > ops.get(0).lineNumber());
    }

    @Test
    void parseHashDelimiter() {
        String input = "write ###hash content###";
        Plan plan = SplanPlanParser.parse(input);

        Operation op = plan.sections().get(0).operations().get(0);
        List<Argument> args = op.arguments();
        // The ### delimiter should be parsed as a content block
        boolean hasContentBlock = args.stream()
                .anyMatch(a -> a instanceof Argument.ContentBlock);
        assertTrue(hasContentBlock, "Should parse ### as content block delimiter");
    }

    @Test
    void parseDollarDelimiter() {
        String input = "write $$$dollar content$$$";
        Plan plan = SplanPlanParser.parse(input);

        Operation op = plan.sections().get(0).operations().get(0);
        boolean hasContentBlock = op.arguments().stream()
                .anyMatch(a -> a instanceof Argument.ContentBlock);
        assertTrue(hasContentBlock, "Should parse $$$ as content block delimiter");
    }

    @Test
    void declarationScopedToSection() {
        String input = """
                :msg:::Hello:::
                send :msg
                ---
                :msg:::World:::
                send :msg
                """;
        Plan plan = SplanPlanParser.parse(input);

        assertEquals(2, plan.sections().size());
        assertEquals("Hello", plan.sections().get(0).declarations().get("msg"));
        assertEquals("World", plan.sections().get(1).declarations().get("msg"));
    }
}
