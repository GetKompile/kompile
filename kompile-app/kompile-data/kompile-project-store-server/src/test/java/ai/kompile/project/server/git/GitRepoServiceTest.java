package ai.kompile.project.server.git;

import ai.kompile.project.server.ProjectStoreServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitRepoServiceTest {

    @TempDir
    Path temp;

    @Test
    void resolvedRepositoryPathStaysBelowConfiguredBase() throws Exception {
        ProjectStoreServerProperties props = new ProjectStoreServerProperties();
        props.setDataDir(temp.toString());
        GitRepoService service = new GitRepoService(props);
        Path expectedBase = temp.resolve("repos").toAbsolutePath().normalize();

        assertEquals(expectedBase.resolve("team/project.git"), service.repoDir("team", "project").toPath());
        assertThrows(IllegalArgumentException.class, () -> service.repoDir("..", "escape"));
        assertThrows(IllegalArgumentException.class, () -> service.repoDir("/tmp", "escape"));
        assertThrows(IllegalArgumentException.class, () -> service.repoDir("team", "../escape"));
    }

    @Test
    void canonicalizationRejectsSymlinkEscape() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path repos = Files.createDirectories(temp.resolve("repos"));
        try {
            Files.createSymbolicLink(repos.resolve("team"), outside);
        } catch (UnsupportedOperationException e) {
            return;
        }
        ProjectStoreServerProperties props = new ProjectStoreServerProperties();
        props.setDataDir(temp.toString());
        GitRepoService service = new GitRepoService(props);

        assertThrows(IllegalArgumentException.class, () -> service.repoDir("team", "project"));
    }
}
