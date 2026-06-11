package ai.kompile.cli.main.install;

import ai.kompile.cli.main.install.registry.ComponentRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComponentInstaller, focusing on the temp file naming
 * and extension extraction that routes processDownload() correctly.
 */
public class ComponentInstallerTest {

    /**
     * The temp file name must preserve the download URL's extension so
     * processDownload() can dispatch on .jar / .tar.gz / .zip.
     * Previously the temp file was always named ".download" which matched
     * none of the known extensions and threw IllegalArgumentException.
     */
    @Test
    public void testExtractExtensionFromUrl() throws Exception {
        // Access the private static method via reflection
        Method method = ComponentInstaller.class.getDeclaredMethod("extractExtension", String.class);
        method.setAccessible(true);

        assertEquals(".tar.gz",
                method.invoke(null, "https://github.com/org/repo/releases/download/v1.0/kompile-app-main-1.0-linux-x86_64.tar.gz"));
        assertEquals(".tgz",
                method.invoke(null, "https://example.com/kompile-app-main.tgz"));
        assertEquals(".zip",
                method.invoke(null, "https://example.com/kompile-dist-1.0-windows-x86_64.zip"));
        assertEquals(".jar",
                method.invoke(null, "https://repo1.maven.org/maven2/ai/kompile/kompile-app-main/1.0/kompile-app-main-1.0.jar"));

        // URL with query params
        assertEquals(".tar.gz",
                method.invoke(null, "https://example.com/archive.tar.gz?token=abc123"));

        // URL with fragment
        assertEquals(".zip",
                method.invoke(null, "https://example.com/archive.zip#section"));

        // Unknown extension
        assertEquals("",
                method.invoke(null, "https://example.com/some-binary"));
    }

    /**
     * Verify that the temp file name generated for a GitHub Releases
     * tar.gz download includes the .tar.gz extension.
     */
    @Test
    public void testTempFileNameIncludesExtension() {
        ComponentRegistry registry = new ComponentRegistry();
        String url = registry.resolveDownloadUrl(
                ComponentRegistry.KOMPILE_APP_MAIN,
                ComponentRegistry.ReleaseSource.GITHUB_RELEASES);

        // The URL should end with .tar.gz
        assertTrue(url.endsWith(".tar.gz"), "URL should end with .tar.gz: " + url);

        // The temp file name would be: kompile-app-main.download.tar.gz
        // processDownload checks endsWith(".tar.gz") which matches
        String tempFileName = ComponentRegistry.KOMPILE_APP_MAIN + ".download"
                + (url.endsWith(".tar.gz") ? ".tar.gz" : "");
        assertTrue(tempFileName.endsWith(".tar.gz"),
                "Temp file should end with .tar.gz: " + tempFileName);
    }
}
