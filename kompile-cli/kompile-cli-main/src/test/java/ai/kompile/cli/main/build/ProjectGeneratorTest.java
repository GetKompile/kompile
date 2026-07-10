package ai.kompile.cli.main.build;

import ai.kompile.cli.main.build.config.BuildConfiguration;
import ai.kompile.cli.main.build.config.ModuleSelection;
import ai.kompile.cli.main.build.generators.ApplicationPropertiesGenerator;
import ai.kompile.modelmanager.KompileModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast, in-process unit tests that lock in the project-generator hardening:
 * <ul>
 *   <li>RagPomGenerator writes {@code <nd4j.version>} and {@code <relativePath>} to the POM.</li>
 *   <li>ApplicationPropertiesWriter emits a comment-only properties file (zero active lines).</li>
 *   <li>ApplicationPropertiesGenerator emits the same comment-only stub.</li>
 * </ul>
 *
 * None of these tests run a real Maven build; they exercise the generator units in-process.
 */
class ProjectGeneratorTest {

    // -------------------------------------------------------------------------
    // RagPomGenerator — POM content assertions
    // -------------------------------------------------------------------------

    /**
     * Invokes RagPomGenerator via picocli (the same path used in production) against a temp
     * directory and asserts that the resulting POM contains the standalone-pom fixes:
     * {@code <nd4j.version>} in the properties block and {@code <relativePath>} on the parent.
     * No model downloads occur because no --includeChunkerSentence / --includeAnserini flags
     * are passed.
     */
    @Test
    void ragPomGenerator_pomContainsNd4jVersionAndRelativePath(@TempDir Path tempDir) throws Exception {
        int exit = new CommandLine(new RagPomGenerator())
                .execute("--outputFile=" + tempDir.toAbsolutePath());

        assertEquals(0, exit, "RagPomGenerator.call() should return exit code 0");

        File generatedPom = tempDir.resolve("pom-rag-instance.xml").toFile();
        assertTrue(generatedPom.exists(), "pom-rag-instance.xml should be written to the temp dir");

        String pomText = Files.readString(generatedPom.toPath());
        assertTrue(pomText.contains("<nd4j.version>"),
                "POM must contain <nd4j.version> property; got:\n" + pomText);
        assertTrue(pomText.contains("<relativePath>"),
                "POM must contain <relativePath> element on the parent; got:\n" + pomText);
    }

    // -------------------------------------------------------------------------
    // ApplicationPropertiesWriter — comment-only output
    // -------------------------------------------------------------------------

    /**
     * Directly exercises {@link ApplicationPropertiesWriter#generateApplicationPropertiesFile}
     * and asserts that every non-blank line in the resulting file begins with {@code #} (i.e.
     * no active Spring Boot property keys are emitted).
     */
    @Test
    void applicationPropertiesWriter_emitsCommentOnlyFile(@TempDir Path tempDir) throws IOException {
        ApplicationPropertiesWriter writer = new ApplicationPropertiesWriter(
                "test-artifact",
                "ai.kompile.test",
                "Test App",
                "jdbc:postgresql://localhost:5432/test",
                "postgres",
                "postgres",
                false,            // enableSchemaInit
                false,            // includeVectorstorePgvector
                false,            // includeEmbeddingPostgresml
                false,            // includePgmlIndexer
                false,            // includeEmbeddingOpenai
                false,            // includeEmbeddingSentenceTransformer
                false,            // includeLlmOpenai
                false,            // includeLlmAnthropic
                false,            // includeLlmGemini
                false,            // includeVectorstoreChroma
                false,            // includeAnserini
                false,            // includeChunkerSentence
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                new KompileModelManager()
        );

        writer.generateApplicationPropertiesFile(tempDir.toFile(), new Properties());

        File propsFile = tempDir.resolve("src/main/resources/application.properties").toFile();
        assertTrue(propsFile.exists(), "application.properties should be created under src/main/resources/");
        assertZeroActiveLines(propsFile, "ApplicationPropertiesWriter");
    }

    // -------------------------------------------------------------------------
    // ApplicationPropertiesGenerator — comment-only output
    // -------------------------------------------------------------------------

    /**
     * Exercises {@link ApplicationPropertiesGenerator#generate} (the BuildAppCommand path) and
     * asserts that the resulting file is also comment-only, matching the EPP-aware stub.
     */
    @Test
    void applicationPropertiesGenerator_emitsCommentOnlyFile(@TempDir Path tempDir) throws IOException {
        BuildConfiguration config = BuildConfiguration.builder()
                .configName("test-config")
                .modules(ModuleSelection.empty().build())
                .build();

        ApplicationPropertiesGenerator gen =
                new ApplicationPropertiesGenerator(config, new KompileModelManager(), Map.of());

        gen.generate(tempDir.toFile());

        File propsFile = tempDir.resolve("src/main/resources/application.properties").toFile();
        assertTrue(propsFile.exists(), "application.properties should be created under src/main/resources/");
        assertZeroActiveLines(propsFile, "ApplicationPropertiesGenerator");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Fails the test if any line in the given properties file is non-blank and does not start
     * with {@code #} (i.e. if there is an active Spring Boot property key).
     */
    private static void assertZeroActiveLines(File propsFile, String generatorLabel) throws IOException {
        List<String> lines = Files.readAllLines(propsFile.toPath());
        List<String> activeLines = lines.stream()
                .filter(l -> !l.isBlank() && !l.stripLeading().startsWith("#"))
                .toList();

        assertTrue(activeLines.isEmpty(),
                generatorLabel + " must produce a comment-only application.properties " +
                        "(KompileBootstrapEnvironmentPostProcessor supplies all defaults). " +
                        "Found active lines: " + activeLines);
    }
}
