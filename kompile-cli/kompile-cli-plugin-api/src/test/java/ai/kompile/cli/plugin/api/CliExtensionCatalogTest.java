/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CliExtensionCatalogTest {
    @Test
    void conditionalLearningIsNotAnUnconditionalIndexRequirement() {
        CliToolDescriptor tool = new CliToolDescriptor("code_index", Set.of("code.structure"),
                Map.of("index", Set.of()), Map.of("index", Set.of("graph.learning")));
        CliExtension extension = () -> descriptor("code", Set.of("code.structure"), Set.of(), List.of(tool), Set.of());
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(extension), Set.of());
        assertTrue(catalog.missingCapabilities("code_index", "index").isEmpty());
        assertEquals(Set.of("graph.learning"), tool.conditionalCapabilities().get("index"));
    }

    @Test
    void exposesCapturedDescriptorsWithoutRereadingProvider() {
        TrackingExtension extension = extension("code", Set.of(), Set.of());
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(extension), Set.of());
        CliExtensionDescriptor original = catalog.descriptorOf(extension);
        extension.descriptor = descriptor("changed", Set.of(), Set.of(), List.of(), Set.of());
        assertSame(original, catalog.descriptorOf(extension));
        assertEquals(List.of(original), catalog.descriptors());
        assertEquals(1, extension.descriptorReads);
        assertThrows(IllegalArgumentException.class, () -> catalog.descriptorOf(() -> original));
    }

    @Test
    void supportsEmptyCatalogAndDefaultNoOpRegistration() {
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(), Set.of());
        assertTrue(catalog.extensions().isEmpty());
        assertTrue(catalog.capabilities().isEmpty());
        assertTrue(catalog.toolIds().isEmpty());
        assertTrue(catalog.ownerOfTool("unknown_tool").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.missingCapabilities("unknown_tool", null));
        CliExtension extension = () -> descriptor("empty", Set.of(), Set.of(), List.of(), Set.of());
        assertDoesNotThrow(() -> extension.registerCommands(new CommandLine(CommandLine.Model.CommandSpec.create())));
    }

    @Test
    void satisfiesExtensionRequirementsFromHostAndOtherExtensionsRegardlessOfInputOrder() {
        TrackingExtension consumer = extension("a_consumer", Set.of("consumer"), Set.of("host", "provider"));
        TrackingExtension provider = extension("z_provider", Set.of("provider"), Set.of());
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(provider, consumer), Set.of("host"));
        assertEquals(List.of(consumer, provider), catalog.extensions());
        assertEquals(List.of("consumer", "host", "provider"), List.copyOf(catalog.capabilities()));
        assertEquals(0, consumer.registrations + provider.registrations);
    }

    @Test
    void rejectsDuplicateExtensionIdsBeforeRegistration() {
        TrackingExtension first = extension("duplicate", Set.of(), Set.of());
        TrackingExtension second = extension("duplicate", Set.of(), Set.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CliExtensionCatalog(List.of(first, second), Set.of()));
        assertTrue(error.getMessage().contains("Duplicate extension id"));
        assertEquals(0, first.registrations + second.registrations);
    }

    @Test
    void rejectsDuplicateToolIdsAcrossAndWithinExtensions() {
        CliToolDescriptor tool = new CliToolDescriptor("shared_tool", Set.of(), Map.of());
        TrackingExtension first = new TrackingExtension(descriptor("first", Set.of(), Set.of(), List.of(tool), Set.of()));
        TrackingExtension second = new TrackingExtension(descriptor("second", Set.of(), Set.of(), List.of(tool), Set.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CliExtensionCatalog(List.of(first, second), Set.of()));
        assertTrue(error.getMessage().contains("shared_tool"));
        assertTrue(error.getMessage().contains("first"));
        assertTrue(error.getMessage().contains("second"));
        assertEquals(0, first.registrations + second.registrations);
        TrackingExtension internalDuplicate = new TrackingExtension(
                descriptor("duplicate", Set.of(), Set.of(), List.of(tool, tool), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CliExtensionCatalog(List.of(internalDuplicate), Set.of()));
    }

    @Test
    void rejectsCommandContributionCollisionsBeforeRegistration() {
        TrackingExtension first = new TrackingExtension(descriptor("first", Set.of(), Set.of(), List.of(), Set.of("graph")));
        TrackingExtension second = new TrackingExtension(descriptor("second", Set.of(), Set.of(), List.of(), Set.of("graph")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CliExtensionCatalog(List.of(first, second), Set.of()));
        assertTrue(error.getMessage().contains("Duplicate command name graph"));
        assertEquals(0, first.registrations + second.registrations);
    }

    @Test
    void rejectsMissingExtensionRequirementsBeforeAnyRegistration() {
        TrackingExtension valid = extension("a_valid", Set.of("installed"), Set.of());
        TrackingExtension invalid = extension("z_invalid", Set.of(), Set.of("not_installed"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CliExtensionCatalog(List.of(valid, invalid), Set.of()));
        assertTrue(error.getMessage().contains("z_invalid"));
        assertTrue(error.getMessage().contains("not_installed"));
        assertEquals(0, valid.registrations + invalid.registrations);
    }

    @Test
    void rejectsUnsupportedApiVersionBeforeRegistration() {
        TrackingExtension extension = new TrackingExtension(new CliExtensionDescriptor(
                "future", "1", CliExtensionDescriptor.API_VERSION + 1, Set.of(), Set.of(), List.of(), Set.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CliExtensionCatalog(List.of(extension), Set.of()));
        assertTrue(error.getMessage().contains("Unsupported extension API version"));
        assertEquals(0, extension.registrations);
    }

    @Test
    void capturesDescriptorExactlyOnceAndRetainsOriginalOwnership() {
        CliToolDescriptor tool = new CliToolDescriptor("original_tool", Set.of(), Map.of());
        CliExtensionDescriptor original = descriptor("original", Set.of("installed"), Set.of(), List.of(tool), Set.of("original"));
        TrackingExtension extension = new TrackingExtension(original);
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(extension), Set.of());
        extension.descriptor = descriptor("replacement", Set.of("changed"), Set.of(), List.of(), Set.of());

        assertSame(original, catalog.ownerOfTool("original_tool").orElseThrow());
        assertTrue(catalog.ownerOfTool("other_tool").isEmpty());
        assertEquals(Set.of("installed"), catalog.capabilities());
        assertEquals(Set.of("original_tool"), catalog.toolIds());
        assertEquals(List.of(extension), catalog.extensions());
        assertEquals(Set.of(), catalog.missingCapabilities("original_tool", null));
        assertEquals(1, extension.descriptorReads);
        assertEquals(0, extension.registrations);
    }

    @Test
    void reportsCombinedToolAndActionRequirementsWithoutBlockingCatalog() {
        CliToolDescriptor tool = new CliToolDescriptor("graph_tool", Set.of("shared", "z_tool"), Map.of(
                "read", Set.of("a_read", "shared"), "write", Set.of("b_write"), "installed", Set.of()));
        TrackingExtension extension = new TrackingExtension(
                descriptor("graph", Set.of("shared"), Set.of(), List.of(tool), Set.of()));
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(extension), Set.of("z_host"));

        assertEquals(Set.of("z_tool"), catalog.missingCapabilities("graph_tool", null));
        assertEquals(Set.of("z_tool"), catalog.missingCapabilities("graph_tool", " \t"));
        assertEquals(List.of("a_read", "z_tool"), List.copyOf(catalog.missingCapabilities("graph_tool", "read")));
        assertEquals(Set.of("b_write", "z_tool"), catalog.missingCapabilities("graph_tool", "write"));
        assertEquals(Set.of("z_tool"), catalog.missingCapabilities("graph_tool", "installed"));
        assertThrows(IllegalArgumentException.class, () -> catalog.missingCapabilities("graph_tool", "typo"));
        assertThrows(IllegalArgumentException.class, () -> catalog.missingCapabilities("graph_tool", "READ"));
        assertThrows(IllegalArgumentException.class, () -> catalog.missingCapabilities("unknown_tool", "read"));
        assertEquals(0, extension.registrations);
    }

    @Test
    void satisfiedRequirementsAndUnrestrictedToolsReturnEmptyMissingSet() {
        CliToolDescriptor tool = new CliToolDescriptor("read_tool", Set.of("host"), Map.of());
        TrackingExtension extension = new TrackingExtension(descriptor("read", Set.of(), Set.of(), List.of(tool), Set.of()));
        CliExtensionCatalog catalog = new CliExtensionCatalog(List.of(extension), Set.of("host"));
        assertTrue(catalog.missingCapabilities("read_tool", null).isEmpty());
        assertTrue(catalog.missingCapabilities("read_tool", "any_action").isEmpty());
    }

    @Test
    void snapshotsInputsAndExposesOnlyImmutableDeterministicallyOrderedCollections() {
        CliToolDescriptor tool = new CliToolDescriptor("z_tool", Set.of("missing"), Map.of());
        TrackingExtension z = new TrackingExtension(descriptor("z", Set.of("z"), Set.of(), List.of(tool), Set.of()));
        TrackingExtension a = new TrackingExtension(descriptor("a", Set.of("a"), Set.of(),
                List.of(new CliToolDescriptor("a_tool", Set.of(), Map.of())), Set.of()));
        List<CliExtension> input = new ArrayList<>(List.of(z, a));
        Set<String> host = new HashSet<>(Set.of("host"));
        CliExtensionCatalog catalog = new CliExtensionCatalog(input, host);
        input.clear();
        host.clear();

        assertEquals(List.of(a, z), catalog.extensions());
        assertEquals(List.of("a", "host", "z"), List.copyOf(catalog.capabilities()));
        assertEquals(List.of("a_tool", "z_tool"), List.copyOf(catalog.toolIds()));
        assertThrows(UnsupportedOperationException.class, () -> catalog.extensions().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.capabilities().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.toolIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.missingCapabilities("z_tool", null).clear());
    }

    @Test
    void rejectsNullInputsAndInvalidHostCapabilities() {
        assertThrows(NullPointerException.class, () -> new CliExtensionCatalog(null, Set.of()));
        assertThrows(NullPointerException.class, () -> new CliExtensionCatalog(List.of(), null));
        assertThrows(NullPointerException.class, () -> new CliExtensionCatalog(Arrays.asList((CliExtension) null), Set.of()));
        assertThrows(NullPointerException.class, () -> new CliExtensionCatalog(List.of(() -> null), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CliExtensionCatalog(List.of(), Set.of(" ")));
    }

    private static TrackingExtension extension(String id, Set<String> provides, Set<String> requires) {
        return new TrackingExtension(descriptor(id, provides, requires, List.of(), Set.of()));
    }

    private static CliExtensionDescriptor descriptor(String id, Set<String> provides, Set<String> requires,
                                                     List<CliToolDescriptor> tools, Set<String> commands) {
        return new CliExtensionDescriptor(id, "1", CliExtensionDescriptor.API_VERSION, provides, requires, tools, commands);
    }

    private static final class TrackingExtension implements CliExtension {
        private CliExtensionDescriptor descriptor;
        private int descriptorReads;
        private int registrations;

        private TrackingExtension(CliExtensionDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public CliExtensionDescriptor descriptor() {
            descriptorReads++;
            return descriptor;
        }

        @Override
        public void registerCommands(CommandLine root) {
            registrations++;
            throw new AssertionError("Catalog must not register commands");
        }
    }
}
