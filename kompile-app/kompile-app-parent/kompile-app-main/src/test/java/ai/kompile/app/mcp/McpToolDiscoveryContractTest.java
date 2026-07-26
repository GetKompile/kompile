/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.app.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for MCP tool discovery.
 *
 * <p>This replaced {@code McpToolRegistryParityTest} and {@code AllToolBeansRegisteredSweepTest},
 * which between them asserted that {@link McpToolRegistry} declared one
 * {@code @Autowired(required = false)} field per tool class and that
 * {@link ai.kompile.app.config.McpSseServerConfiguration} mirrored it. Those hand-maintained lists
 * were the reason tools could be built and never registered — and they were also a compile-time edge
 * from the registry to every tool class, which pinned the entire MCP surface to kompile-app-main and
 * blocked moving a tool into a persona web module. Both registries now discover tool beans from the
 * {@code ApplicationContext} by annotation, so "built but not registered" is structurally impossible
 * and the lists are gone.</p>
 *
 * <p>The sweep test also carried a {@code KNOWN_EXCLUSIONS} map naming sixteen tool classes as
 * deliberately outside the MCP surface. Six of those were simply not compile dependencies of
 * kompile-app-main, which discovery handles by construction — absent from the classpath, absent from
 * the surface. The other ten were annotated {@code @Component} + {@code @Tool} and are now registered;
 * see docs/architecture/app-persona-boundary.md for the list and the reasoning.</p>
 *
 * <p>What can still break under discovery is different, so this test asserts the invariants that
 * discovery actually depends on:</p>
 * <ol>
 *   <li>A class declaring {@code @Tool} methods is only ever discovered if it is a Spring bean.
 *       A tool class without a stereotype is dark exactly like a missing field used to be.</li>
 *   <li>Tool names must be unique across the discovered surface. Spring AI's
 *       {@code MethodToolCallbackProvider} rejects duplicate tool names at startup, so a collision
 *       is a boot failure, not a silent shadow.</li>
 *   <li>Neither registry may reintroduce per-tool fields — that is what re-pins the surface to one
 *       module.</li>
 * </ol>
 *
 * <p>Class metadata is read with ASM ({@link MetadataReaderFactory}) rather than reflection so the
 * scan never loads or initialises the classes; optional integrations whose transitive dependencies
 * are absent would otherwise blow up the scan instead of being reported.</p>
 */
class McpToolDiscoveryContractTest {

    private static final String TOOL_ANNOTATION = "org.springframework.ai.tool.annotation.Tool";
    private static final String COMPONENT_ANNOTATION = "org.springframework.stereotype.Component";

    /** Every kompile class on the test classpath, jars included. */
    private static final String SCAN_PATTERN = "classpath*:ai/kompile/**/*.class";

    /**
     * Classes that declare {@code @Tool} methods but are deliberately not Spring beans, with the
     * reason. Keep this empty unless there is a real one — an entry here is a tool nobody can call.
     */
    private static final Set<String> NOT_EXPECTED_TO_BE_BEANS = Set.of();

    /** A concrete, independently-instantiable class that declares at least one {@code @Tool} method. */
    private record ToolClass(String className, AnnotationMetadata metadata, Set<MethodMetadata> toolMethods) {

        boolean isSpringBean() {
            return metadata.isAnnotated(COMPONENT_ANNOTATION);
        }

        String simpleName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }

    private static List<ToolClass> scanToolClasses() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resolver);

        List<ToolClass> found = new ArrayList<>();
        for (Resource resource : resolver.getResources(SCAN_PATTERN)) {
            if (!resource.isReadable()) {
                continue;
            }
            MetadataReader reader;
            try {
                reader = readerFactory.getMetadataReader(resource);
            } catch (Exception e) {
                // A class compiled for a newer bytecode level than the ASM in use, or a malformed
                // entry in a shaded jar. Not what this test is about.
                continue;
            }
            AnnotationMetadata metadata = reader.getAnnotationMetadata();
            Set<MethodMetadata> toolMethods = metadata.getAnnotatedMethods(TOOL_ANNOTATION);
            if (toolMethods.isEmpty()) {
                continue;
            }
            // Interfaces and abstract bases can carry @Tool for documentation; only a concrete,
            // independent class can be the bean that discovery hands to Spring AI.
            if (!metadata.isConcrete() || !metadata.isIndependent()) {
                continue;
            }
            // target/test-classes is on this scan's classpath, and test fixtures declare @Tool to
            // exercise the dispatcher. They are not production tool beans and must not be held to
            // the stereotype or name-uniqueness rules.
            if (isTestFixture(metadata.getClassName())) {
                continue;
            }
            found.add(new ToolClass(metadata.getClassName(), metadata, toolMethods));
        }
        return found;
    }

    /**
     * True for a test class or anything nested inside one. Checks the <em>outermost</em> class, so a
     * fixture like {@code SomethingTest$SampleToolBean} is caught by its enclosing class's name.
     */
    private static boolean isTestFixture(String className) {
        String outermost = className.split("\\$", 2)[0];
        String simpleName = outermost.substring(outermost.lastIndexOf('.') + 1);
        return simpleName.endsWith("Test") || simpleName.endsWith("Tests") || simpleName.endsWith("IT");
    }

    /** The {@code name} attribute, or the method name when it is left blank — Spring AI's own rule. */
    private static String toolName(MethodMetadata method) {
        Map<String, Object> attributes = method.getAnnotationAttributes(TOOL_ANNOTATION);
        Object name = attributes != null ? attributes.get("name") : null;
        String declared = name instanceof String s ? s : "";
        return declared.isBlank() ? method.getMethodName() : declared;
    }

    @Test
    void everyToolClassIsASpringBeanSoDiscoveryCanFindIt() throws Exception {
        List<ToolClass> toolClasses = scanToolClasses();

        assertFalse(toolClasses.isEmpty(),
                "Scanned " + SCAN_PATTERN + " and found no @Tool classes at all — the scan itself is broken, "
                        + "which would make the rest of this test vacuously pass.");

        List<String> notBeans = new ArrayList<>();
        for (ToolClass toolClass : toolClasses) {
            if (!toolClass.isSpringBean() && !NOT_EXPECTED_TO_BE_BEANS.contains(toolClass.simpleName())) {
                notBeans.add(toolClass.className());
            }
        }

        assertTrue(notBeans.isEmpty(),
                "These classes declare @Tool methods but carry no @Component/@Service stereotype, so they "
                        + "are never beans and MCP tool discovery cannot see them. Annotate them, or add them to "
                        + "NOT_EXPECTED_TO_BE_BEANS with a reason: " + notBeans);
    }

    @Test
    void toolNamesAreUniqueAcrossTheDiscoveredSurface() throws Exception {
        Map<String, Set<String>> nameToDeclarers = new TreeMap<>();
        for (ToolClass toolClass : scanToolClasses()) {
            if (!toolClass.isSpringBean()) {
                continue;
            }
            for (MethodMetadata method : toolClass.toolMethods()) {
                nameToDeclarers
                        .computeIfAbsent(toolName(method), k -> new TreeSet<>())
                        .add(toolClass.simpleName() + "#" + method.getMethodName());
            }
        }

        Map<String, Set<String>> collisions = new TreeMap<>();
        nameToDeclarers.forEach((name, declarers) -> {
            if (declarers.size() > 1) {
                collisions.put(name, declarers);
            }
        });

        assertTrue(collisions.isEmpty(),
                "Duplicate MCP tool names on the classpath. Spring AI's MethodToolCallbackProvider rejects "
                        + "duplicates, so this is a startup failure for every app that has both beans. "
                        + "Rename one side: " + collisions);
    }

    @Test
    void registriesDeclareNoPerToolFields() {
        List<String> offenders = new ArrayList<>();
        offenders.addAll(perToolFields(McpToolRegistry.class));
        offenders.addAll(perToolFields(ai.kompile.app.config.McpSseServerConfiguration.class));

        assertTrue(offenders.isEmpty(),
                "A per-tool @Autowired field is back in an MCP registry. That is a compile-time edge from the "
                        + "registry to a tool class: it pins the tool to this module, so it can no longer be moved "
                        + "into a persona web module, and it reinstates a hand-maintained list that silently goes "
                        + "stale. Let discovery find the bean instead. Offending fields: " + offenders);
    }

    /**
     * Field types whose simple name looks like a tool class. Deliberately name-shaped rather than
     * {@code @Tool}-aware: the point is to catch the field before anyone wires it up.
     */
    private static List<String> perToolFields(Class<?> registryClass) {
        List<String> offenders = new ArrayList<>();
        for (Field field : registryClass.getDeclaredFields()) {
            String typeName = field.getType().getSimpleName();
            if (typeName.endsWith("Tool") || typeName.endsWith("ToolImpl")) {
                offenders.add(registryClass.getSimpleName() + "." + field.getName() + " : " + typeName);
            }
        }
        return offenders;
    }
}
