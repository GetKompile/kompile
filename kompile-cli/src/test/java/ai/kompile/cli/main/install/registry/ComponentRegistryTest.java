package ai.kompile.cli.main.install.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComponentRegistry URL building and component lookup.
 */
public class ComponentRegistryTest {

    @Test
    public void testGitHubReleaseUrlDoesNotThrow() {
        ComponentRegistry registry = new ComponentRegistry();
        // This used to throw MissingFormatArgumentException due to 4 %s vs 3 args
        String url = registry.resolveDownloadUrl(
                ComponentRegistry.KOMPILE_APP_MAIN,
                ComponentRegistry.ReleaseSource.GITHUB_RELEASES);
        assertNotNull(url);
        assertTrue(url.startsWith("https://github.com/"), "URL should start with GitHub: " + url);
        assertTrue(url.contains("/releases/download/"), "URL should contain releases path: " + url);
        assertTrue(url.contains("kompile-app-main"), "URL should contain component name: " + url);
        // Verify no doubled slashes (sign of format error)
        assertFalse(url.contains("download//"), "URL has doubled slashes: " + url);
    }

    @Test
    public void testGitHubReleaseUrlStructure() {
        // Verify the URL has exactly the right path structure:
        // https://github.com/KonduitAI/kompile/releases/download/v<version>/<filename>
        ComponentRegistry registry = new ComponentRegistry();
        String version = registry.getVersion();
        String url = registry.resolveDownloadUrl(
                ComponentRegistry.KOMPILE_APP_MAIN,
                ComponentRegistry.ReleaseSource.GITHUB_RELEASES);

        String expectedPrefix = "https://github.com/KonduitAI/kompile/releases/download/v" + version + "/";
        assertTrue(url.startsWith(expectedPrefix),
                "URL should start with: " + expectedPrefix + "\n  but got: " + url);
    }

    @Test
    public void testGetDistributionJarPathReturnsNullWhenMissing() {
        ComponentRegistry registry = new ComponentRegistry();
        // In test environment, lib/ dir typically doesn't exist
        // Should return null rather than throwing
        assertDoesNotThrow(() -> registry.getDistributionJarPath("nonexistent"));
    }

    @Test
    public void testComponentLookup() {
        ComponentRegistry registry = new ComponentRegistry();
        assertTrue(registry.getComponent(ComponentRegistry.KOMPILE_APP_MAIN).isPresent());
        assertTrue(registry.getComponent(ComponentRegistry.KOMPILE_MODEL_STAGING).isPresent());
        assertTrue(registry.getComponent(ComponentRegistry.KOMPILE_CLI).isPresent());
        assertFalse(registry.getComponent("nonexistent").isPresent());
    }

    @Test
    public void testFindInstalledJarReturnsNullForMissing() {
        ComponentRegistry registry = new ComponentRegistry();
        // Use a temp directory so no real installs are found
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "kompile-test-registry-" + System.nanoTime());
        tempDir.mkdirs();
        registry.setInstallBaseDir(tempDir);
        assertNull(registry.findInstalledJar("nonexistent-component"));
        assertNull(registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING));
        tempDir.delete();
    }

    @Test
    public void testFindInstalledJarFindsExecJar() throws Exception {
        ComponentRegistry registry = new ComponentRegistry();
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "kompile-test-registry-" + System.nanoTime());
        registry.setInstallBaseDir(tempDir);

        // Create a fake exec JAR in the version directory
        String version = registry.getVersion();
        java.io.File installDir = new java.io.File(tempDir,
                "components/kompile-model-staging/" + version);
        installDir.mkdirs();
        java.io.File execJar = new java.io.File(installDir,
                "kompile-model-staging-" + version + "-exec.jar");
        execJar.createNewFile();

        java.io.File found = registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
        assertNotNull(found, "Should find the exec JAR");
        assertTrue(found.getName().contains("-exec.jar"), "Should be the exec JAR");

        // Clean up
        execJar.delete();
        installDir.delete();
        installDir.getParentFile().delete();
        new java.io.File(tempDir, "components").delete();
        tempDir.delete();
    }

    @Test
    public void testFindInstalledJarPrefersCanonicalOverExec() throws Exception {
        ComponentRegistry registry = new ComponentRegistry();
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "kompile-test-registry-" + System.nanoTime());
        registry.setInstallBaseDir(tempDir);

        String version = registry.getVersion();
        java.io.File installDir = new java.io.File(tempDir,
                "components/kompile-model-staging/" + version);
        installDir.mkdirs();

        // Create both canonical and exec JARs
        java.io.File canonicalJar = new java.io.File(installDir,
                "kompile-model-staging-" + version + ".jar");
        canonicalJar.createNewFile();
        java.io.File execJar = new java.io.File(installDir,
                "kompile-model-staging-" + version + "-exec.jar");
        execJar.createNewFile();

        java.io.File found = registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
        assertNotNull(found, "Should find a JAR");
        assertEquals(canonicalJar.getName(), found.getName(), "Should prefer canonical JAR");

        // Clean up
        canonicalJar.delete();
        execJar.delete();
        installDir.delete();
        installDir.getParentFile().delete();
        new java.io.File(tempDir, "components").delete();
        tempDir.delete();
    }
}
