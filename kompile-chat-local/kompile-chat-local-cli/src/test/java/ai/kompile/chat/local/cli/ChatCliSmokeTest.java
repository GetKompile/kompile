package ai.kompile.chat.local.cli;

import ai.kompile.chat.local.ChatConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChatCliSmokeTest {
    @TempDir
    Path temp;

    @Test
    void explicitProjectTakesPrecedenceOverConfiguredKgraph() throws Exception {
        Path configFile = temp.resolve("chat.properties");
        Files.writeString(configFile, "kgraph.path=/configured/graph.kgraph\n"
                + "project.path=/configured/project\n"
                + "fact.sheet.id=config-id\n");
        ChatConfig config = ChatConfig.fromFile(configFile);

        Path cliProject = Path.of("/cli/project");
        ChatCli.AssetSelection selection = ChatCli.resolveAssetSelection(
                cliProject, true, null, false, "cli-id", config);

        assertEquals(cliProject, selection.projectPath());
        assertNull(selection.kgraphPath());
        assertEquals("cli-id", selection.factSheetId());
    }

    @Test
    void configuredSourcesConflictWhenNoCliSourceOverridesThem() throws Exception {
        Path configFile = temp.resolve("conflict.properties");
        Files.writeString(configFile, "kgraph.path=/configured/graph.kgraph\n"
                + "project.path=/configured/project\n");
        ChatConfig config = ChatConfig.fromFile(configFile);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ChatCli.resolveAssetSelection(null, false, null, false, null, config));
        assertTrue(error.getMessage().contains("cannot both"));
    }

    @Test
    void commandLineProjectAndKgraphConflictBeforeAnyGraphOrModelLoad() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ChatCli.main(new String[]{
                        "--project", temp.resolve("project").toString(),
                        "--kgraph", temp.resolve("graph.kgraph").toString()
                }));
        assertTrue(error.getMessage().contains("--project and --kgraph"));
    }

    @Test
    void explicitMissingGraphFailsClosedBeforeAnyGraphOrModelLoad() {
        Path missing = temp.resolve("missing.kgraph");
        IOException error = assertThrows(IOException.class,
                () -> ChatCli.main(new String[]{"--kgraph", missing.toString()}));
        assertTrue(error.getMessage().contains("does not exist"));
        assertFalse(Files.exists(missing));
    }

    @Test
    void factSheetRequiresProject() {
        ChatConfig defaults = ChatConfig.defaults();
        assertThrows(IllegalArgumentException.class,
                () -> ChatCli.resolveAssetSelection(null, false, null, false, "7", defaults));
    }
}
