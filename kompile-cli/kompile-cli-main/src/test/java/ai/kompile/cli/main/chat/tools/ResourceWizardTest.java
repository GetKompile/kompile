package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.reader.UserInterruptException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResourceWizardTest {
    @TempDir Path root;

    private String run(String args, String... answers) {
        var queue = new ArrayDeque<>(List.of(answers));
        String result = ResourceWizard.run(args, (lines, question) -> queue.removeFirst(),
                command -> ResourcePolicy.command(root, command, root.resolve("user.json")));
        assertTrue(queue.isEmpty());
        return result;
    }

    @Test void addWizardSavesOnlyAfterConfirmation() throws Exception {
        assertTrue(run("add", "project", "version", "java", "-version", "low", "yes").contains("saved"));
        assertTrue(ResourcePolicy.command(root, "check java -version", root.resolve("user.json")).contains("resourceClass=low"));
        assertTrue(ResourcePolicy.command(root, "check java -jar app.jar", root.resolve("user.json")).contains("resourceClass=high"));
    }

    @Test void defaultsAndDecliningNeverWrite() {
        assertTrue(run("add", "", "version", "java", "", "", "").contains("cancelled"));
        assertFalse(Files.exists(root.resolve(".kompile/resource-policy.json")));
    }

    @Test void cancelAtEveryStepNeverWrites() {
        String[] complete = {"project", "version", "java", "-version", "low", "yes"};
        for (int i = 0; i < complete.length; i++) {
            String[] answers = java.util.Arrays.copyOf(complete, i + 1);
            answers[i] = "cancel";
            assertTrue(run("add", answers).contains("cancelled"));
            assertFalse(Files.exists(root.resolve(".kompile/resource-policy.json")));
        }
    }

    @Test void interruptedReaderDoesNotSave() {
        assertTrue(ResourceWizard.run("add", (lines, question) -> { throw new UserInterruptException(""); },
                command -> { fail("must not save"); return ""; }).contains("cancelled"));
    }

    @Test void numberedSetupChoicesSupportGlobalDefaults() throws Exception {
        assertTrue(run("setup", "2", "2", "2", "1").contains("saved"));
        assertTrue(Files.exists(root.resolve("user.json")));
        assertFalse(Files.exists(root.resolve(".kompile/resource-policy.json")));
    }

    @Test void invalidChoicesRepromptAndQuotedPrefixesStayLiteral() {
        assertTrue(run("global add", "bad name", "quoted", "my-tool", "a && b", "'hello world'", "oops", "low", "yes").contains("saved"));
        assertTrue(ResourcePolicy.command(root, "global check my-tool 'hello world'", root.resolve("user.json")).contains("resourceClass=low"));
    }

    @Test void wizardRoutesAndNoninteractiveAdd() {
        for (String args : List.of("add", "setup", "wizard", "global add", "global setup")) assertTrue(ResourceWizard.handles(args));
        assertFalse(ResourceWizard.handles("add version low java -version"));
        assertTrue(ResourcePolicy.command(root, "add version low java -version", root.resolve("user.json")).contains("saved"));
        assertTrue(ResourceWizard.run(root, "add", null).contains("interactive"));
    }
}
