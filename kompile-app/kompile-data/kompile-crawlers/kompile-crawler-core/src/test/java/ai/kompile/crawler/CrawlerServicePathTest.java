package ai.kompile.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrawlerServicePathTest {

    @Test
    void configuredProjectRootOwnsCrawlState(@TempDir Path projectRoot) {
        assertEquals(projectRoot.resolve(".kompile/state/crawl-state").toAbsolutePath().normalize(),
                CrawlerService.resolveStateDirectory(projectRoot.toString()));
    }
}
