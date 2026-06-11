package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for splan parsing in the CLI-side LocalCodeIndexer.
 * Verifies that .splan files produce the correct entity hierarchy:
 * Section→MODULE, Declaration→CONSTANT, Operation→FUNCTION, ContentBlock→FIELD.
 */
class SplanParsingTest {

    @Test
    void testSplanPlanParserBasic() {
        String input = """
                # A comment
                :config:::some value:::
                write output.txt :config
                read input.txt
                ---
                transform data
                """;

        SplanPlanParser.Plan plan = SplanPlanParser.parse(input);

        assertEquals(2, plan.sections().size());
        assertEquals(1, plan.comments().size());
        assertEquals("A comment", plan.comments().get(0));

        // Section 0
        SplanPlanParser.Section s0 = plan.sections().get(0);
        assertEquals(0, s0.index());
        assertEquals(1, s0.declarations().size());
        assertEquals("some value", s0.declarations().get("config"));
        assertEquals(2, s0.operations().size());

        SplanPlanParser.Operation writeOp = s0.operations().get(0);
        assertEquals("write", writeOp.command());
        assertEquals(2, writeOp.arguments().size()); // output.txt + :config
        assertInstanceOf(SplanPlanParser.Argument.Token.class, writeOp.arguments().get(0));
        assertInstanceOf(SplanPlanParser.Argument.DeclRef.class, writeOp.arguments().get(1));
        assertEquals("config", ((SplanPlanParser.Argument.DeclRef) writeOp.arguments().get(1)).name());

        SplanPlanParser.Operation readOp = s0.operations().get(1);
        assertEquals("read", readOp.command());

        // Section 1
        SplanPlanParser.Section s1 = plan.sections().get(1);
        assertEquals(1, s1.index());
        assertEquals(1, s1.operations().size());
        assertEquals("transform", s1.operations().get(0).command());
    }

    @Test
    void testSplanPlanParserContentBlock() {
        // Delimiters must be whitespace-separated tokens for the tokenizer to recognize them
        String input = "write output.txt ::: hello world :::";

        SplanPlanParser.Plan plan = SplanPlanParser.parse(input);
        SplanPlanParser.Operation op = plan.sections().get(0).operations().get(0);

        assertEquals("write", op.command());
        assertEquals(2, op.arguments().size());
        assertInstanceOf(SplanPlanParser.Argument.Token.class, op.arguments().get(0));
        assertInstanceOf(SplanPlanParser.Argument.ContentBlock.class, op.arguments().get(1));

        SplanPlanParser.Argument.ContentBlock block = (SplanPlanParser.Argument.ContentBlock) op.arguments().get(1);
        assertEquals("hello world", block.content());
        assertEquals(":::", block.delimiter());
    }

    @Test
    void testSplanPlanParserMultiLineDeclaration() {
        String input = """
                :template:::
                line one
                line two
                :::
                process :template
                """;

        SplanPlanParser.Plan plan = SplanPlanParser.parse(input);
        SplanPlanParser.Section s0 = plan.sections().get(0);

        assertEquals(1, s0.declarations().size());
        String content = s0.declarations().get("template");
        assertTrue(content.contains("line one"));
        assertTrue(content.contains("line two"));
        assertEquals(1, s0.operations().size());
        assertEquals("process", s0.operations().get(0).command());
    }

    @Test
    void testSplanPlanParserAllDelimiters() {
        // Delimiters must be whitespace-separated for the tokenizer
        String input = """
                write ::: colon content :::
                write ### hash content ###
                write $$$ dollar content $$$
                write @@@ at content @@@
                write %%% percent content %%%
                """;

        SplanPlanParser.Plan plan = SplanPlanParser.parse(input);
        List<SplanPlanParser.Operation> ops = plan.sections().get(0).operations();
        assertEquals(5, ops.size());

        String[] expectedDelimiters = {":::", "###", "$$$", "@@@", "%%%"};
        String[] expectedContents = {"colon content", "hash content", "dollar content",
                "at content", "percent content"};

        for (int i = 0; i < ops.size(); i++) {
            SplanPlanParser.Argument.ContentBlock block =
                    (SplanPlanParser.Argument.ContentBlock) ops.get(i).arguments().get(0);
            assertEquals(expectedDelimiters[i], block.delimiter(), "Delimiter mismatch at index " + i);
            assertEquals(expectedContents[i], block.content(), "Content mismatch at index " + i);
        }
    }

    @Test
    void testSplanEntityExtraction() throws Exception {
        // Create a temp splan file and index it
        Path tempDir = Files.createTempDirectory("splan-test");
        Path splanFile = tempDir.resolve("test.splan");
        Files.writeString(splanFile, """
                # Build pipeline
                :config:::build settings:::
                compile src :config
                run tests
                ---
                :output:::result file:::
                deploy :output ::: target server :::
                """);

        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos);

            LocalCodeIndexer.IndexResult result = indexer.index(tempDir, "splan-test-project",
                    "*.splan", null, true, out);

            assertTrue(result.entitiesFound() > 0, "Should find entities in splan file");
            assertTrue(result.languageCounts().containsKey("splan"), "Should detect splan language");

            // Search for entities
            List<Map<String, Object>> modules = indexer.search("splan-test-project", "section", "MODULE", 10);
            assertFalse(modules.isEmpty(), "Should find MODULE entities for sections");

            List<Map<String, Object>> constants = indexer.search("splan-test-project", "config", "CONSTANT", 10);
            assertFalse(constants.isEmpty(), "Should find CONSTANT entity for :config declaration");

            List<Map<String, Object>> functions = indexer.search("splan-test-project", "compile", "FUNCTION", 10);
            assertFalse(functions.isEmpty(), "Should find FUNCTION entity for compile operation");

            // Verify signature format
            Map<String, Object> compileFunc = functions.get(0);
            String sig = (String) compileFunc.get("signature");
            assertNotNull(sig, "Operation should have a signature");
            assertTrue(sig.contains("compile"), "Signature should contain command name");
            assertTrue(sig.contains("declRef:config"), "Signature should show declRef for :config");

            // Check for FIELD entities (content blocks)
            List<Map<String, Object>> fields = indexer.search("splan-test-project", "deploy-block", "FIELD", 10);
            assertFalse(fields.isEmpty(), "Should find FIELD entity for content block in deploy operation");
        } finally {
            // Cleanup
            Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            // Clean up index
            Path indexDir = LocalCodeIndexer.getIndexDir("splan-test-project");
            if (Files.exists(indexDir)) {
                Files.walk(indexDir).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void testSplanEmptyInput() {
        SplanPlanParser.Plan plan = SplanPlanParser.parse("");
        assertEquals(1, plan.sections().size()); // always at least one section
        assertTrue(plan.sections().get(0).operations().isEmpty());
        assertTrue(plan.sections().get(0).declarations().isEmpty());
    }

    @Test
    void testSplanSignatureFormat() {
        String input = "write output.txt :config ::: content :::";
        SplanPlanParser.Plan plan = SplanPlanParser.parse(input);
        SplanPlanParser.Operation op = plan.sections().get(0).operations().get(0);

        assertEquals("write", op.command());
        assertEquals(3, op.arguments().size());

        // Verify we can build the signature the same way as the indexer
        assertInstanceOf(SplanPlanParser.Argument.Token.class, op.arguments().get(0));
        assertInstanceOf(SplanPlanParser.Argument.DeclRef.class, op.arguments().get(1));
        assertInstanceOf(SplanPlanParser.Argument.ContentBlock.class, op.arguments().get(2));
    }
}
