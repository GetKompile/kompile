package ai.kompile.cli.main.chat.config;

import ai.kompile.core.llm.CliModelCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog-driven thinking discovery: the CLI's on-disk models.dev catalog
 * ({@code reasoning_options}) must supply thinking options for providers whose live
 * /models response carries no thinking metadata (zai and the other OpenAI-compatible
 * hosts), ranking between live provider metadata and the documented fallback resource.
 */
class CatalogThinkingCapabilitiesTest {

    private static final String PROP = "kompile.cli.modelCatalogPaths";

    @TempDir
    Path tempDir;

    private String previousCatalogPaths;

    @BeforeEach
    void isolateCatalog() throws Exception {
        previousCatalogPaths = System.getProperty(PROP);
        System.setProperty(PROP, tempDir.resolve("absent.json").toString());
        invalidateCatalogCache();
    }

    @AfterEach
    void restoreAndClear() throws Exception {
        if (previousCatalogPaths == null) System.clearProperty(PROP);
        else System.setProperty(PROP, previousCatalogPaths);
        invalidateCatalogCache();
        ProviderThinkingConfig.clearCacheForTests();
    }

    private static void invalidateCatalogCache() throws Exception {
        Method method = CliModelCatalog.class.getDeclaredMethod("invalidateCacheForTest");
        method.setAccessible(true);
        method.invoke(null);
    }

    private void useCatalog(String json) throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, json);
        System.setProperty(PROP, file.toString());
        invalidateCatalogCache();
    }

    @Test
    void catalogSuppliesThinkingOptionsForZaiToggleAndEffortModels() throws Exception {
        useCatalog("""
                {
                  "zai": {"models": {
                    "glm-4.6": {"limit": {"context": 204800, "output": 131072},
                                "reasoning": true,
                                "reasoning_options": [{"type": "toggle"}]},
                    "glm-5.3": {"limit": {"context": 1000000, "output": 131072},
                                "reasoning": true,
                                "reasoning_options": [{"type": "effort", "values": ["low", "high", "max"]}]}
                  }}
                }
                """);

        ThinkingCapabilityProvider.ThinkingCapabilities toggle =
                ChatProviderRegistry.find("zai").thinkingCapabilityProvider()
                        .resolve(new LiveModelDiscovery.Model("glm-4.6", List.of()));
        assertTrue(toggle.supported(), "catalog toggle must surface a thinking selector");
        assertEquals(List.of("enabled", "disabled"),
                toggle.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
        assertFalse(toggle.mandatory());

        ThinkingCapabilityProvider.ThinkingCapabilities effort =
                ChatProviderRegistry.find("zai").thinkingCapabilityProvider()
                        .resolve(new LiveModelDiscovery.Model("glm-5.3", List.of()));
        assertTrue(effort.supported());
        assertEquals(List.of("low", "high", "max"),
                effort.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
        assertTrue(effort.sourceIndicator().contains("reasoning_options"),
                "catalog provenance must be distinguishable from the documented fallback");
    }

    @Test
    void unknownCatalogOrModelYieldsNoThinkingSelector() throws Exception {
        useCatalog("""
                {
                  "zai": {"models": {
                    "glm-4.6": {"limit": {"context": 204800, "output": 131072},
                                "reasoning_options": [{"type": "toggle"}]}
                  }}
                }
                """);

        // Different provider, model not under this provider's scope.
        assertFalse(ChatProviderRegistry.find("openai").thinkingCapabilityProvider()
                .resolve(new LiveModelDiscovery.Model("glm-4.6", List.of())).supported());
        // Provider matches but the model is absent from the catalog.
        assertFalse(ChatProviderRegistry.find("zai").thinkingCapabilityProvider()
                .resolve(new LiveModelDiscovery.Model("glm-9.9", List.of())).supported());
    }

    @Test
    void liveProviderMetadataStillWinsOverCatalog() throws Exception {
        useCatalog("""
                {
                  "zai": {"models": {
                    "glm-live": {"limit": {"context": 204800, "output": 131072},
                                 "reasoning_options": [{"type": "toggle"}]}
                  }}
                }
                """);
        LiveModelDiscovery.Model live = new LiveModelDiscovery.Model(
                "glm-live",
                List.of("wire-low", "wire-max"),
                java.util.Map.of(),
                "wire-max",
                false,
                "live:zai");

        ThinkingCapabilityProvider.ThinkingCapabilities capabilities =
                ChatProviderRegistry.find("zai").thinkingCapabilityProvider().resolve(live);

        assertEquals(ThinkingCapabilityProvider.Source.LIVE_PROVIDER, capabilities.source());
        assertEquals(List.of("wire-low", "wire-max"),
                capabilities.options().stream()
                        .map(ThinkingCapabilityProvider.Option::value).toList());
    }

    @Test
    void documentedFallbackCoversZaiWhenNoCatalogExists() {
        // No catalog file at all (isolated in @BeforeEach) → zai.json must carry
        // the documented families so the wizard still offers thinking controls.
        ThinkingCapabilityProvider.ThinkingCapabilities flash =
                ChatProviderRegistry.find("zai").thinkingCapabilityProvider()
                        .resolve(new LiveModelDiscovery.Model("glm-5.3-flash", List.of()));
        assertTrue(flash.supported(), "glm-5.3(-flash) effort family from zai.json");
        assertEquals(List.of("low", "high", "max"),
                flash.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
        assertTrue(flash.mandatory(), "z.ai docs: GLM-5.3 thinking is always on");
        assertTrue(flash.documentedFallback());

        ThinkingCapabilityProvider.ThinkingCapabilities effort52 =
                ChatProviderRegistry.find("zai").thinkingCapabilityProvider()
                        .resolve(new LiveModelDiscovery.Model("glm-5.2", List.of()));
        assertTrue(effort52.supported());
        assertEquals(List.of("none", "low", "medium", "high", "max", "xhigh"),
                effort52.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());

        ThinkingCapabilityProvider.ThinkingCapabilities toggle =
                ChatProviderRegistry.find("zai").thinkingCapabilityProvider()
                        .resolve(new LiveModelDiscovery.Model("glm-4.6", List.of()));
        assertTrue(toggle.supported(), "glm-4.5/4.6/4.7/5/5.1 toggle family from zai.json");
        assertEquals(List.of("enabled", "disabled"),
                toggle.options().stream().map(ThinkingCapabilityProvider.Option::value).toList());
    }

    @Test
    void zaiDocumentedFallbackResourceParsesUnderTheStandardSchema() {
        ProviderThinkingConfig.Config config =
                ProviderThinkingConfig.load("zai").orElseThrow(
                        () -> new AssertionError("zai.json must exist and parse"));
        assertEquals("zai", config.provider());
        assertEquals(ProviderThinkingConfig.DOCUMENTED_FALLBACK, config.metadataSource());
        assertTrue(config.source().url().startsWith("https://docs.z.ai/"));
    }
}
