/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CliExtensionDescriptorTest {
    @Test
    void conditionalCapabilitiesAreDeeplyImmutableAndValidated() {
        Set<String> dependencies = new HashSet<>(Set.of("graph.learning"));
        Map<String, Set<String>> conditional = new HashMap<>();
        conditional.put("index", dependencies);
        CliToolDescriptor tool = new CliToolDescriptor("code_index", Set.of(), Map.of(), conditional);
        dependencies.clear();
        conditional.clear();
        assertEquals(Map.of("index", Set.of("graph.learning")), tool.conditionalCapabilities());
        assertThrows(UnsupportedOperationException.class, () -> tool.conditionalCapabilities().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.conditionalCapabilities().get("index").clear());
        assertThrows(IllegalArgumentException.class, () -> new CliToolDescriptor("code_index", Set.of(),
                Map.of(), Map.of("bad action", Set.of())));
    }

    @Test
    void acceptsExistingUnderscoreToolIdsAndNonSemverVersions() {
        CliToolDescriptor tool = new CliToolDescriptor("ask_graph_verify", Set.of("graph.local"),
                Map.of("read_only", Set.of("host-capability")));
        CliExtensionDescriptor descriptor = descriptor("graph_tools", "dev-build", 1,
                Set.of("graph.local"), Set.of(), List.of(tool), Set.of("graph-tools"));

        assertEquals("graph_tools", descriptor.id());
        assertEquals("dev-build", descriptor.version());
        assertEquals(CliExtensionDescriptor.API_VERSION, descriptor.apiVersion());
        assertEquals("ask_graph_verify", descriptor.tools().get(0).id());
    }

    @Test
    void rejectsBlankAndInvalidIdentifiersEverywhere() {
        for (String invalid : Arrays.asList(null, "", " ", "with space", " bad", "bad ", "bad/id", "bad:part", "bad!")) {
            assertThrows(IllegalArgumentException.class,
                    () -> descriptor(invalid, "1", 1, Set.of(), Set.of(), List.of(), Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new CliToolDescriptor(invalid, Set.of(), Map.of()));
            Set<String> invalidSet = new HashSet<>();
            invalidSet.add(invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> descriptor("valid", "1", 1, invalidSet, Set.of(), List.of(), Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> descriptor("valid", "1", 1, Set.of(), invalidSet, List.of(), Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> descriptor("valid", "1", 1, Set.of(), Set.of(), List.of(), invalidSet));
            assertThrows(IllegalArgumentException.class,
                    () -> new CliToolDescriptor("tool", invalidSet, Map.of()));
            Map<String, Set<String>> invalidActions = new HashMap<>();
            invalidActions.put(invalid, Set.of());
            assertThrows(IllegalArgumentException.class,
                    () -> new CliToolDescriptor("tool", Set.of(), invalidActions));
            assertThrows(IllegalArgumentException.class,
                    () -> new CliToolDescriptor("tool", Set.of(), Map.of("read", invalidSet)));
        }
    }

    @Test
    void rejectsBlankVersionsAndNonPositiveApiVersions() {
        for (String version : Arrays.asList(null, "", " \t")) {
            assertThrows(IllegalArgumentException.class,
                    () -> descriptor("valid", version, 1, Set.of(), Set.of(), List.of(), Set.of()));
        }
        for (int version : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> descriptor("valid", "1", version, Set.of(), Set.of(), List.of(), Set.of()));
        }
        assertEquals(2, descriptor("valid", "1", 2, Set.of(), Set.of(), List.of(), Set.of()).apiVersion());
    }

    @Test
    void rejectsNullCollectionsAndElements() {
        assertThrows(NullPointerException.class,
                () -> descriptor("valid", "1", 1, null, Set.of(), List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> descriptor("valid", "1", 1, Set.of(), null, List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> descriptor("valid", "1", 1, Set.of(), Set.of(), null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> descriptor("valid", "1", 1, Set.of(), Set.of(), Arrays.asList((CliToolDescriptor) null), Set.of()));
        assertThrows(NullPointerException.class,
                () -> descriptor("valid", "1", 1, Set.of(), Set.of(), List.of(), null));
        assertThrows(NullPointerException.class, () -> new CliToolDescriptor("tool", null, Map.of()));
        assertThrows(NullPointerException.class, () -> new CliToolDescriptor("tool", Set.of(), null));
        Map<String, Set<String>> actions = new HashMap<>();
        actions.put("read", null);
        assertThrows(NullPointerException.class, () -> new CliToolDescriptor("tool", Set.of(), actions));
    }

    @Test
    void deeplySnapshotsMutableInputs() {
        Set<String> provides = new HashSet<>(Set.of("provided"));
        Set<String> requires = new HashSet<>(Set.of("required"));
        Set<String> commands = new HashSet<>(Set.of("command"));
        Set<String> toolRequires = new HashSet<>(Set.of("tool_capability"));
        Set<String> actionRequires = new HashSet<>(Set.of("action_capability"));
        Map<String, Set<String>> actions = new HashMap<>();
        actions.put("read", actionRequires);
        CliToolDescriptor tool = new CliToolDescriptor("tool", toolRequires, actions);
        List<CliToolDescriptor> tools = new ArrayList<>(List.of(tool));
        CliExtensionDescriptor descriptor = descriptor("valid", "1", 1, provides, requires, tools, commands);

        provides.clear();
        requires.clear();
        commands.clear();
        toolRequires.clear();
        actionRequires.clear();
        actions.clear();
        tools.clear();

        assertEquals(Set.of("provided"), descriptor.provides());
        assertEquals(Set.of("required"), descriptor.requires());
        assertEquals(Set.of("command"), descriptor.commands());
        assertEquals(List.of(tool), descriptor.tools());
        assertEquals(Set.of("tool_capability"), tool.requires());
        assertEquals(Map.of("read", Set.of("action_capability")), tool.actionRequirements());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.provides().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.requires().clear());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.commands().clear());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.tools().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.requires().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.actionRequirements().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.actionRequirements().get("read").clear());
        assertThrows(UnsupportedOperationException.class,
                () -> tool.actionRequirements().entrySet().iterator().next().setValue(Set.of()));
    }

    @Test
    void iteratesAllMetadataInIdentifierOrder() {
        CliToolDescriptor zTool = new CliToolDescriptor("z_tool", Set.of("z", "a"),
                Map.of("z_action", Set.of("z", "a"), "a_action", Set.of()));
        CliToolDescriptor aTool = new CliToolDescriptor("a_tool", Set.of(), Map.of());
        CliExtensionDescriptor descriptor = descriptor("valid", "1", 1,
                Set.of("z", "a"), Set.of("z", "a"), List.of(zTool, aTool), Set.of("z", "a"));

        assertEquals(List.of("a", "z"), List.copyOf(descriptor.provides()));
        assertEquals(List.of("a", "z"), List.copyOf(descriptor.requires()));
        assertEquals(List.of("a", "z"), List.copyOf(descriptor.commands()));
        assertEquals(List.of(aTool, zTool), descriptor.tools());
        assertEquals(List.of("a", "z"), List.copyOf(zTool.requires()));
        assertEquals(List.of("a_action", "z_action"), List.copyOf(zTool.actionRequirements().keySet()));
        assertEquals(List.of("a", "z"), List.copyOf(zTool.actionRequirements().get("z_action")));
    }

    private static CliExtensionDescriptor descriptor(String id, String version, int apiVersion,
                                                      Set<String> provides, Set<String> requires,
                                                      List<CliToolDescriptor> tools, Set<String> commands) {
        return new CliExtensionDescriptor(id, version, apiVersion, provides, requires, tools, commands);
    }
}
