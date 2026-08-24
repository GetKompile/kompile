package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCrawlCommandTest {
    @Test
    void modelPipelineCompletesWithPersistedMarkdownAndChunks(@TempDir Path root) throws Exception {
        Path pdf = root.resolve("page.pdf");
        Files.write(pdf, new byte[]{1, 2, 3});
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("completed-vlm");
        profile.setName("completed-vlm");
        profile.setSources(List.of(pdf.toString()));
        profile.setLoader("pdf");
        profile.setChunker("recursive-character");
        profile.setCollection("completed-vlm");
        profile.setFactSheetName("completed-vlm");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode request = mapper.createObjectNode();
        request.put("defaultPipelineId", "test-vlm");
        ObjectNode pipeline = request.putArray("pipelines").addObject();
        pipeline.put("pipelineId", "test-vlm");
        pipeline.put("pipelineType", "VLM");
        pipeline.put("loaderName", "pdf");
        pipeline.put("chunkerName", "recursive-character");

        ProjectCrawlCommand.LocalCrawlExecution result = ProjectCrawlCommand.executeLocalCrawl(
                profile, root, false, request,
                (projectRoot, file, resolved, loadedText) ->
                        "<doctag><text>MYTHIC HEROES</text></doctag>");

        assertEquals("COMPLETED", result.status());
        assertEquals(1, result.documentCount());
        assertEquals(1, result.markdownCount());
        assertTrue(result.chunkCount() > 0);
        assertTrue(Files.exists(result.outputDirectory().resolve("crawl-result.json")));
        assertTrue(Files.readString(result.markdownDirectory().resolve("page.pdf.md"))
                .contains("MYTHIC HEROES"));
    }

    @Test
    void optionalCatalogSyncDoesNotInvalidatePersistedCrawlOnInitializerFailure() {
        assertFalse(ProjectCrawlCommand.bestEffortCatalogSync(() -> {
            throw new ExceptionInInitializerError(new IllegalStateException("optional catalog unavailable"));
        }));
    }

    @Test
    void optionalCatalogSyncReportsSuccessAndDoesNotSwallowFatalVmErrors() {
        assertTrue(ProjectCrawlCommand.bestEffortCatalogSync(() -> { }));
        assertThrows(OutOfMemoryError.class, () ->
                ProjectCrawlCommand.bestEffortCatalogSync(() -> {
                    throw new OutOfMemoryError("fatal");
                }));
    }
}
