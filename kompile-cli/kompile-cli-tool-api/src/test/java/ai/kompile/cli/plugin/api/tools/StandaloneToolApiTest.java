package ai.kompile.cli.plugin.api.tools;

import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.tools.fixture.StandaloneToolProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneToolApiTest {
    @Test
    void loadsAndExecutesProviderWithoutHostCommonOrModels() throws Exception {
        Set<URL> locations = new LinkedHashSet<>();
        for (Class<?> type : List.of(ExtensionTool.class, CliExtension.class, ObjectMapper.class,
                JsonFactory.class, JsonProperty.class, CommandLine.class, StandaloneToolProvider.class)) {
            locations.add(type.getProtectionDomain().getCodeSource().getLocation());
        }
        // Only the API, its declared runtime dependencies and the source fixture are visible.
        // The platform parent cannot accidentally supply host classes from the test runner.
        try (var loader = new URLClassLoader(locations.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (String absent : List.of("ai.kompile.cli.main.MainCommand",
                    "ai.kompile.cli.main.chat.tools.CliTool", "ai.kompile.cli.main.chat.tools.ToolContext",
                    "ai.kompile.cli.common.util.JsonUtils", "ai.kompile.core.llm.StructuredChatLanguageModel",
                    "org.eclipse.deeplearning4j.nd4j.linalg.factory.Nd4j")) {
                assertThrows(ClassNotFoundException.class, () -> Class.forName(absent, false, loader), absent);
            }
            for (Class<?> type : List.of(ExtensionTool.class, ToolInvocationContext.class, ToolProviderContext.class,
                    ToolProviderContext.Surface.class, ProvidedTool.class, CliToolProvider.class,
                    ToolResult.class, ToolExecutionException.class, McpToolAnnotations.class)) {
                Class<?> isolated = Class.forName(type.getName(), true, loader);
                assertSame(loader, isolated.getClassLoader());
                assertNotSame(type, isolated);
                assertDoesNotThrow(() -> {
                    isolated.getDeclaredMethods();
                    isolated.getDeclaredConstructors();
                    isolated.getDeclaredFields();
                });
            }

            Class<?> mapperType = loader.loadClass(ObjectMapper.class.getName());
            Object mapper = mapperType.getConstructor().newInstance();
            Class<?> contextType = loader.loadClass(ToolProviderContext.class.getName());
            Class<?> surfaceType = loader.loadClass(ToolProviderContext.Surface.class.getName());
            Class<?> invocationType = loader.loadClass(ToolInvocationContext.class.getName());
            Class<?> extensionType = loader.loadClass(ExtensionTool.class.getName());
            Class<?> providerType = loader.loadClass(CliToolProvider.class.getName());
            Class<?> fixtureType = loader.loadClass(StandaloneToolProvider.class.getName());
            Object provider = fixtureType.getConstructor().newInstance();
            Path directory = Path.of("standalone-workspace");

            providerType.getMethod("descriptor").invoke(provider);
            assertEquals(0, fixtureType.getMethod("factoryCalls").invoke(provider));
            for (String surface : List.of("CHAT", "MCP")) {
                Object context = contextType.getConstructor(mapperType, String.class, String.class,
                                String.class, Path.class, surfaceType)
                        .newInstance(mapper, null, null, null, directory, surfaceType.getField(surface).get(null));
                assertEquals(Set.of(), providerType.getMethod("omittedTools", contextType).invoke(provider, context));
                List<?> tools = (List<?>) providerType.getMethod("createTools", contextType).invoke(provider, context);
                Object contribution = tools.get(0);
                Class<?> providedType = loader.loadClass(ProvidedTool.class.getName());
                assertEquals(false, providedType.getMethod("chatHighMemoryGuard").invoke(contribution));
                Object tool = providedType.getMethod("tool").invoke(contribution);
                assertEquals("echo", extensionType.getMethod("id").invoke(tool));
                assertEquals("read", extensionType.getMethod("permissionKey").invoke(tool));
                assertNull(extensionType.getMethod("compactHint").invoke(tool));
                assertEquals(false, extensionType.getMethod("isBackgroundable").invoke(tool));
                Object annotations = extensionType.getMethod("mcpAnnotations").invoke(tool);
                assertEquals("{\"readOnlyHint\":false,\"destructiveHint\":false,\"idempotentHint\":false,\"openWorldHint\":false}",
                        annotations.getClass().getMethod("toJsonNode").invoke(annotations).toString());

                Object params = mapperType.getMethod("readTree", String.class).invoke(mapper, "\"portable\"");
                Object invocation = fixtureType.getMethod("invocationContext", Path.class).invoke(null, directory);
                Object result = extensionType.getMethod("execute", loader.loadClass(JsonNode.class.getName()),
                        invocationType).invoke(tool, params, invocation);
                Class<?> resultType = loader.loadClass(ToolResult.class.getName());
                assertEquals("standalone-session", resultType.getMethod("getTitle").invoke(result));
                assertEquals("portable", resultType.getMethod("getOutput").invoke(result));
                assertEquals(false, resultType.getMethod("isError").invoke(result));
                Map<?, ?> metadata = (Map<?, ?>) resultType.getMethod("getMetadata").invoke(result);
                assertEquals(directory.resolve("echo.txt").toString(), metadata.get("path"));
            }
            assertEquals(2, fixtureType.getMethod("factoryCalls").invoke(provider));
        }
    }
}
