package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExtensionLoaderTest {
    private final ToolExtensionContext context = new ToolExtensionContext(new ObjectMapper(), null, null, null,
            Path.of("."), ToolExtensionContext.Surface.CHAT);

    @Test
    void validatesEntireCatalogBeforeAnyFactoryAndConstructsOnlyRequestedFamilies() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        var a = extension("a", "first", Set.of(), 1, first, "first");
        var b = extension("b", "second", Set.of(), 1, second, "second");
        var loader = new ToolExtensionLoader(List.of(a, b), Set.of(), context);
        assertEquals(0, first.get());
        assertEquals(0, second.get());
        var tool = loader.contribution("first");
        assertSame(tool, loader.contribution("first"));
        assertEquals(1, first.get());
        assertEquals(0, second.get());
        assertEquals("a", loader.catalog().ownerOfTool("first").orElseThrow().id());
    }

    @Test
    void reusesValidatedMetadataSnapshotWithoutReadingProviderAgain() {
        AtomicInteger reads = new AtomicInteger();
        var extension = new CliToolExtension() {
            @Override public CliExtensionDescriptor descriptor() {
                if (reads.incrementAndGet() != 1) throw new AssertionError("Metadata must be captured once");
                return new CliExtensionDescriptor("snapshot", "1", 1, Set.of(), Set.of(),
                        List.of(new CliToolDescriptor("captured", Set.of(), Map.of())), Set.of());
            }
            @Override public List<ToolContribution> createTools(ToolExtensionContext context) {
                return List.of(new ToolContribution(stub("captured"), false));
            }
        };
        var catalog = new CliExtensionCatalog(List.of(extension), Set.of());
        var loader = new ToolExtensionLoader(catalog, context);
        assertSame(catalog, loader.catalog());
        assertEquals("captured", loader.contribution("captured").tool().id());
        assertEquals(1, reads.get());
    }

    @Test
    void duplicateIdsMissingRequirementsAndUnsupportedApiNeverCallFactories() {
        AtomicInteger calls = new AtomicInteger();
        var a = extension("a", "shared", Set.of(), 1, calls, "shared");
        var duplicate = extension("b", "shared", Set.of(), 1, calls, "shared");
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExtensionLoader(List.of(a, duplicate), Set.of(), context));
        var missing = extension("missing", "missing_tool", Set.of("not.installed"), 1, calls, "missing_tool");
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExtensionLoader(List.of(a, missing), Set.of(), context));
        var future = extension("future", "future_tool", Set.of(), 2, calls, "future_tool");
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExtensionLoader(List.of(a, future), Set.of(), context));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsFactoryIdsThatAreUndeclaredDuplicatedOrOwnedElsewhere() {
        AtomicInteger calls = new AtomicInteger();
        var undeclared = extension("a", "declared", Set.of(), 1, calls, "surprise");
        assertThrows(IllegalArgumentException.class, () ->
                new ToolExtensionLoader(List.of(undeclared), Set.of(), context).contribution("declared"));
        var duplicate = extension("a", "declared", Set.of(), 1, calls, "declared", "declared");
        assertThrows(IllegalArgumentException.class, () ->
                new ToolExtensionLoader(List.of(duplicate), Set.of(), context).contribution("declared"));
        var stealing = extension("a", "declared", Set.of(), 1, calls, "other");
        var owner = extension("b", "other", Set.of(), 1, calls, "other");
        assertThrows(IllegalArgumentException.class, () ->
                new ToolExtensionLoader(List.of(stealing, owner), Set.of(), context).contribution("declared"));
    }

    @Test
    void metadataOnlyToolDeclarationsAndMissingToolCapabilitiesFailBeforeFactories() {
        AtomicInteger calls = new AtomicInteger();
        var valid = extension("valid", "valid_tool", Set.of(), 1, calls, "valid_tool");
        CliExtension metadataOnly = () -> new CliExtensionDescriptor("metadata", "1", 1, Set.of(), Set.of(),
                List.of(new CliToolDescriptor("metadata_tool", Set.of(), Map.of())), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExtensionLoader(List.of(valid, metadataOnly), Set.of(), context));
        CliToolExtension missing = new CliToolExtension() {
            @Override public CliExtensionDescriptor descriptor() {
                return new CliExtensionDescriptor("missing", "1", 1, Set.of(), Set.of(),
                        List.of(new CliToolDescriptor("missing_tool", Set.of("not.installed"), Map.of())), Set.of());
            }
            @Override public List<ToolContribution> createTools(ToolExtensionContext ignored) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExtensionLoader(List.of(valid, missing), Set.of(), context));
        assertEquals(0, calls.get());
    }

    @Test
    void factoryCannotSilentlyDropDeclaredTools() {
        AtomicInteger calls = new AtomicInteger();
        var missing = extension("missing", "declared", Set.of(), 1, calls);
        var loader = new ToolExtensionLoader(List.of(missing), Set.of(), context);
        assertThrows(IllegalArgumentException.class, () -> loader.additionalContributions(Set.of()));
        assertEquals(1, calls.get());
    }

    @Test
    void additionalComposedToolsAreDeliveredOnceAndCannotShadowHostTools() {
        AtomicInteger calls = new AtomicInteger();
        var extension = extension("extra", "extra_tool", Set.of(), 1, calls, "extra_tool");
        var loader = new ToolExtensionLoader(List.of(extension), Set.of(), context);
        assertEquals(List.of("extra_tool"), loader.additionalContributions(Set.of("read")).stream()
                .map(contribution -> contribution.tool().id()).toList());
        assertTrue(loader.additionalContributions(Set.of("read", "extra_tool")).isEmpty());
        assertEquals(1, calls.get());
        AtomicInteger conflictingCalls = new AtomicInteger();
        var conflict = extension("conflict", "read", Set.of(), 1, conflictingCalls, "read");
        assertThrows(IllegalArgumentException.class, () ->
                new ToolExtensionLoader(List.of(conflict), Set.of(), context).additionalContributions(Set.of("read")));
        assertEquals(0, conflictingCalls.get());
    }

    private static CliToolExtension extension(String id, String toolId, Set<String> requires, int api,
                                               AtomicInteger calls, String... constructedIds) {
        return new CliToolExtension() {
            @Override public CliExtensionDescriptor descriptor() {
                return new CliExtensionDescriptor(id, "1", api, Set.of(), requires,
                        List.of(new CliToolDescriptor(toolId, Set.of(), Map.of())), Set.of());
            }
            @Override public List<ToolContribution> createTools(ToolExtensionContext context) {
                calls.incrementAndGet();
                return java.util.Arrays.stream(constructedIds)
                        .map(name -> new ToolContribution(stub(name), false)).toList();
            }
        };
    }

    private static CliTool stub(String id) {
        return new CliTool() {
            @Override public String id() { return id; }
            @Override public String description() { return id; }
            @Override public JsonNode parameterSchema() { return new ObjectMapper().createObjectNode(); }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                throw new AssertionError("Construction tests must not execute tools");
            }
        };
    }
}
