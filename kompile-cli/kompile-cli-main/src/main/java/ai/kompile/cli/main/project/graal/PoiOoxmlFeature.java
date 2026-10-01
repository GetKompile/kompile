/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.project.graal;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GraalVM native-image {@link Feature} that registers the XMLBeans schema types behind Apache
 * POI's OOXML (xlsx/xlsm) support for the reflection XMLBeans performs at run time.
 *
 * <p>XMLBeans resolves every schema type by name: it loads the generated interface with
 * {@code Class.forName}, instantiates its {@code *Impl} through the {@code (SchemaType)} /
 * {@code (SchemaType, boolean)} constructors, reads enumeration tables from the
 * {@code $Enum.table} field, maps an interface to its schema type through its {@code type} field,
 * reaches its built-in type systems through {@code TypeSystemHolder.typeSystem}, and types
 * {@code selectPath} results with {@code Array.newInstance(interface, n)}. The closed-world
 * analysis sees none of those targets, so without registration the first {@code XSSFWorkbook}
 * fails in a static initializer and the workbook never loads. poi-ooxml-lite carries only the
 * schema classes POI's own code uses, and they change with every POI upgrade, so the jar is
 * registered in bulk: class, declared constructors, declared fields and, for interfaces, the
 * array class. Methods are never registered - XMLBeans does not reflect over them, and
 * registering them would make every generated accessor reachable.</p>
 *
 * <p>The {@code .xsb} schema metadata and XMLBeans' message bundles are resources listed in this
 * module's {@code resource-config.json}; POI's {@code StringUtil} needs the {@code cp1252}
 * charset from {@code -H:+AddAllCharsets}. Wired into the {@code -Pnative} profile via
 * {@code --features=ai.kompile.cli.main.project.graal.PoiOoxmlFeature}.</p>
 */
public class PoiOoxmlFeature implements Feature {

    /** Every class in the jar holding this one (poi-ooxml-lite) is an XMLBeans schema type. */
    static final String OOXML_SCHEMA_ANCHOR = "org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder";

    /** Anchors the xmlbeans jar, whose built-in type systems and schema types are resolved the same way. */
    static final String XMLBEANS_ANCHOR = "org.apache.xmlbeans.XmlObject";

    private static final String XMLBEANS_PACKAGE = "org/apache/xmlbeans/";

    @Override
    public String getDescription() {
        return "Registers Apache POI's OOXML XMLBeans schema types for reflective instantiation at run time.";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Class<?> ooxmlAnchor = access.findClassByName(OOXML_SCHEMA_ANCHOR);
        Class<?> xmlbeansAnchor = access.findClassByName(XMLBEANS_ANCHOR);
        if (ooxmlAnchor == null || xmlbeansAnchor == null) {
            System.err.println("[PoiOoxmlFeature] POI OOXML schemas are not on the image classpath; nothing registered.");
            return;
        }
        List<String> classNames;
        try {
            classNames = schemaClassNames(ooxmlAnchor, xmlbeansAnchor);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Unable to scan the POI OOXML schema jars", e);
        }

        ClassLoader loader = ooxmlAnchor.getClassLoader();
        int registered = 0;
        int failed = 0;
        for (String name : classNames) {
            try {
                Class<?> clazz = Class.forName(name, false, loader);
                RuntimeReflection.register(clazz);
                RuntimeReflection.register(clazz.getDeclaredConstructors());
                RuntimeReflection.register(clazz.getDeclaredFields());
                if (clazz.isInterface()) {
                    RuntimeReflection.register(Array.newInstance(clazz, 0).getClass());
                }
                registered++;
            } catch (Throwable t) {
                // A schema class whose optional dependencies are absent cannot be loaded; XMLBeans
                // can never resolve it at run time either.
                failed++;
            }
        }
        System.err.println("[PoiOoxmlFeature] Registered " + registered + " XMLBeans schema classes for reflection ("
                + failed + " skipped).");
    }

    /**
     * The classes XMLBeans reaches reflectively: every class in the jar holding
     * {@code ooxmlAnchor}, plus, from the jar holding {@code xmlbeansAnchor}, the built-in
     * type-system holders ({@code metadata/}), XMLBeans' own generated schema types
     * ({@code impl/xb/}) and the top-level {@code Xml*} built-in type interfaces.
     */
    static List<String> schemaClassNames(Class<?> ooxmlAnchor, Class<?> xmlbeansAnchor)
            throws IOException, URISyntaxException {
        List<String> names = new ArrayList<>();
        collectClassNames(jarOf(ooxmlAnchor), entry -> true, names);
        collectClassNames(jarOf(xmlbeansAnchor), entry -> entry.startsWith(XMLBEANS_PACKAGE + "metadata/")
                || entry.startsWith(XMLBEANS_PACKAGE + "impl/xb/")
                || (entry.startsWith(XMLBEANS_PACKAGE + "Xml")
                        && entry.indexOf('/', XMLBEANS_PACKAGE.length()) < 0 && entry.indexOf('$') < 0), names);
        return names;
    }

    private static File jarOf(Class<?> clazz) throws IOException, URISyntaxException {
        CodeSource source = clazz.getProtectionDomain() == null ? null : clazz.getProtectionDomain().getCodeSource();
        File jar = source == null || source.getLocation() == null ? null : new File(source.getLocation().toURI());
        if (jar == null || !jar.isFile()) {
            throw new IOException("No jar holds " + clazz.getName() + " (code source: " + jar + ")");
        }
        return jar;
    }

    private static void collectClassNames(File jar, Predicate<String> include, List<String> names) throws IOException {
        Set<String> classes = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (entry.endsWith(".class") && !entry.startsWith("META-INF/")
                        && !entry.endsWith("module-info.class") && !entry.endsWith("package-info.class")) {
                    classes.add(entry.substring(0, entry.length() - ".class".length()));
                }
            }
        }
        for (String path : classes) {
            if (include.test(path + ".class") && enclosingClassesPresent(path, classes)) {
                names.add(path.replace('/', '.'));
            }
        }
    }

    /**
     * poi-ooxml-lite keeps some {@code $Enum} types whose enclosing schema type it strips. Such a
     * class can never be resolved, and registering it only yields incomplete-metadata warnings.
     */
    private static boolean enclosingClassesPresent(String path, Set<String> classes) {
        for (int nested = path.indexOf('$'); nested > 0; nested = path.indexOf('$', nested + 1)) {
            if (!classes.contains(path.substring(0, nested))) {
                return false;
            }
        }
        return true;
    }
}
