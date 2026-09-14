package ai.kompile.cli.main.chat.tools;

import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeGraphSelectionTest {
    @TempDir Path root;

    private KompileCodingProject registration(String id, String graphPath) {
        KompileCodingProject project = new KompileCodingProject();
        project.setId(id);
        project.setCodeProjectId(id + "-code");
        project.setName(id);
        project.setRootPath(root.resolve(id).toString());
        project.setMetadata(Map.of("graphPath", graphPath));
        return project;
    }

    @Test void resolvesRequestedNestedProjectRatherThanFirstRegistration() throws Exception {
        Path first = root.resolve("data/crawls/first/graph.kgraph");
        Path second = root.resolve("data/crawls/second/graph.kgraph");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.createFile(first);
        Files.createFile(second);
        KompileProjectInitRequest init = new KompileProjectInitRequest();
        init.setName("selection-test");
        init.setIncludeStandardComponents(false);
        init.setCodingProjects(List.of(registration("first", root.relativize(first).toString()),
                registration("second", root.relativize(second).toString()),
                registration("outside", "../outside.kgraph")));
        new KompileProjectStore().init(root, init);
        assertEquals(first, CodeGraphTool.resolveGraphPath(root, "first-code"));
        assertEquals(second, CodeGraphTool.resolveGraphPath(root, "second-code"));
        assertEquals(second, CodeGraphTool.resolveGraphPath(root, "second"));
        assertNull(CodeGraphTool.resolveGraphPath(root, "missing"));
        assertNull(CodeGraphTool.resolveGraphPath(root, "outside"));
    }
}
