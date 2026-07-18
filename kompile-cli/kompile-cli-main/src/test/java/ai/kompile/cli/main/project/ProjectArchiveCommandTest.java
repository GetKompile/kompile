package ai.kompile.cli.main.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectArchiveCommandTest {
    @TempDir Path temp;

    @Test
    void registersExportAndImport() {
        CommandLine command = new CommandLine(new ProjectCommand());
        assertInstanceOf(ProjectCommand.ExportArchive.class,
                command.getSubcommands().get("export").getCommand());
        assertInstanceOf(ProjectCommand.ImportArchive.class,
                command.getSubcommands().get("import").getCommand());
    }

    @Test
    void sanitizesDefaultArchiveName() {
        assertEquals("Project-Alpha.kproject",
                ProjectCommand.ExportArchive.defaultArchiveFileName("Project Alpha"));
        assertEquals("escape.kproject",
                ProjectCommand.ExportArchive.defaultArchiveFileName("../../escape\r\n"));
        assertEquals("kompile-project.kproject",
                ProjectCommand.ExportArchive.defaultArchiveFileName("日本語"));
    }

    @Test
    void exportsWithDefaultNameAndImportsPositionally() throws Exception {
        Path project = Files.createDirectory(temp.resolve("source"));
        Files.writeString(project.resolve("kompile.project.json"),
                "{\"schemaVersion\":1,\"projectId\":\"p1\",\"name\":\"portable\"}");
        Files.writeString(project.resolve("payload.txt"), "payload");
        Path archive = temp.resolve("portable.kproject");

        int exportExit = new CommandLine(new ProjectCommand.ExportArchive()).execute(
                "--root", project.toString(), "--output", archive.toString());
        assertEquals(0, exportExit);
        assertTrue(Files.isRegularFile(archive));

        Path target = temp.resolve("target");
        int importExit = new CommandLine(new ProjectCommand.ImportArchive()).execute(
                archive.toString(), "--target", target.toString());
        assertEquals(0, importExit);
        assertEquals("payload", Files.readString(target.resolve("payload.txt")));

        int existingExit = new CommandLine(new ProjectCommand.ImportArchive()).execute(
                "--archive", archive.toString(), "--target", target.toString());
        assertNotEquals(0, existingExit);
    }

    @Test
    void importRequiresExactlyOneArchive() {
        int exit = new CommandLine(new ProjectCommand.ImportArchive()).execute(
                "--target", temp.resolve("target").toString());
        assertNotEquals(0, exit);
    }

    @Test
    void exportRequiresKprojectExtension() throws Exception {
        Path project = Files.createDirectory(temp.resolve("extension-source"));
        Files.writeString(project.resolve("kompile.project.json"),
                "{\"schemaVersion\":1,\"projectId\":\"p2\",\"name\":\"extension\"}");
        Path invalid = temp.resolve("extension.zip");

        int exit = new CommandLine(new ProjectCommand.ExportArchive()).execute(
                "--root", project.toString(), "--output", invalid.toString());

        assertNotEquals(0, exit);
        assertFalse(Files.exists(invalid));
    }
}
