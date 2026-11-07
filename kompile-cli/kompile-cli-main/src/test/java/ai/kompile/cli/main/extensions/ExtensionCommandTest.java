package ai.kompile.cli.main.extensions;

import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionCommandTest {
    @Test
    void actualServiceCatalogExposesAllBuiltinFamiliesWithoutToolConstruction() throws Exception {
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new ExtensionCommand(CliExtensionDiscovery::discover)).setOut(new PrintWriter(output));
        assertEquals(0, command.execute("--json"));
        var json = new ObjectMapper().readTree(output.toString());
        Set<String> ids = new java.util.HashSet<>();
        json.path("extensions").forEach(extension -> ids.add(extension.path("id").asText()));
        assertEquals(Set.of("builtin.code", "builtin.knowledge", "builtin.graph", "builtin.crawl",
                "builtin.model", "builtin.pipeline"), ids);
        assertFalse(json.path("runtimeReadinessChecked").asBoolean(true));
    }

    @Test
    void jsonDescribesOwnershipAndActionRequirementsWithoutClaimingReadiness() throws Exception {
        StringWriter output = new StringWriter();
        CommandLine command = command(output, new StringWriter());
        assertEquals(0, command.execute("code", "--json"));
        var json = new ObjectMapper().readTree(output.toString());
        assertEquals("kompile-cli-extensions/v1", json.path("schemaVersion").asText());
        assertEquals("build-classpath", json.path("composition").asText());
        assertFalse(json.path("runtimeReadinessChecked").asBoolean(true));
        var extension = json.path("extensions").get(0);
        assertEquals("code", extension.path("id").asText());
        assertEquals("code_graph", extension.path("tools").get(0).path("id").asText());
        assertEquals("graph.learning", extension.path("tools").get(0)
                .path("actionRequirements").path("learn").get(0).asText());
    }

    @Test
    void bareCommandListsLoadedComposition() {
        StringWriter output = new StringWriter();
        assertEquals(0, command(output, new StringWriter()).execute());
        assertTrue(output.toString().contains("code 1.0"));
        assertTrue(output.toString().contains("not model/runtime readiness"));
    }

    @Test
    void unknownIdFailsWithoutPrintingAnUnrelatedCatalog() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        assertEquals(2, command(output, error).execute("missing", "--json"));
        assertTrue(output.toString().isEmpty());
        assertTrue(error.toString().contains("Unknown composed extension: missing"));
    }

    private static CommandLine command(StringWriter output, StringWriter error) {
        CliExtension extension = () -> new CliExtensionDescriptor("code", "1.0", 1,
                Set.of("code.structure"), Set.of(),
                List.of(new CliToolDescriptor("code_graph", Set.of("code.structure"),
                        Map.of("learn", Set.of("graph.learning")))), Set.of());
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(extension), Set.of());
        return new CommandLine(new ExtensionCommand(() -> catalog))
                .setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
    }
}
