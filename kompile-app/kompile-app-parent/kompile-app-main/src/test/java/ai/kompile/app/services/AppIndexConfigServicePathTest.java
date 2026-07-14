package ai.kompile.app.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AppIndexConfigServicePathTest {

    @Test
    void relativeIndexPathsAreScopedToEachProject() {
        String relative = "anserini/indexes/vector_index";
        Path projectA = Path.of("/tmp/kompile-project-a");
        Path projectB = Path.of("/tmp/kompile-project-b");

        String resolvedA = AppIndexConfigService.resolveProjectPath(projectA.toString(), relative);
        String resolvedB = AppIndexConfigService.resolveProjectPath(projectB.toString(), relative);

        assertEquals(projectA.resolve(relative).toAbsolutePath().normalize().toString(), resolvedA);
        assertEquals(projectB.resolve(relative).toAbsolutePath().normalize().toString(), resolvedB);
        assertNotEquals(resolvedA, resolvedB);
    }

    @Test
    void absoluteExternalIndexPathIsPreserved() {
        Path external = Path.of("/var/lib/kompile/indexes/project-a").toAbsolutePath().normalize();

        assertEquals(external.toString(),
                AppIndexConfigService.resolveProjectPath("/tmp/kompile-project", external.toString()));
    }

    @Test
    void blankAndNullPathsRemainUnset() {
        assertEquals("", AppIndexConfigService.resolveProjectPath("/tmp/project", ""));
        assertEquals(null, AppIndexConfigService.resolveProjectPath("/tmp/project", null));
    }
}
