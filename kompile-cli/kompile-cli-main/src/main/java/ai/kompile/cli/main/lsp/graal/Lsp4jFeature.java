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

package ai.kompile.cli.main.lsp.graal;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GraalVM native-image {@link Feature} that bulk-registers the Eclipse LSP4J
 * protocol POJOs (and their constructors, methods, and fields) for reflection.
 *
 * <p>LSP4J drives its JSON-RPC wire (de)serialization through gson, which reflects
 * over the protocol classes at run time. Under a closed-world native image those
 * reflective accesses fail unless the classes are registered at build time. Rather
 * than hand-maintain a {@code reflect-config.json} of ~400 classes, this feature
 * scans the classpath for everything under {@code org/eclipse/lsp4j/} and registers
 * it in bulk. Per-class failures are swallowed; a summary count is logged to
 * {@code System.err} (stdout belongs to the MCP protocol).</p>
 *
 * <p>Wired into the {@code -Pnative} profile via
 * {@code --features=ai.kompile.cli.main.lsp.graal.Lsp4jFeature}.</p>
 */
public class Lsp4jFeature implements Feature {

    /** Classpath prefix covering the LSP4J protocol POJOs, adapters, services and jsonrpc messages. */
    private static final String LSP4J_PREFIX = "org/eclipse/lsp4j/";

    @Override
    public String getDescription() {
        return "Registers Eclipse LSP4J protocol POJOs for reflective gson (de)serialization at run time.";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // Anchor onto known lsp4j classes to discover the application classloader and
        // the jar/dir that actually holds lsp4j on the *image* classpath. The builder's
        // own java.class.path does NOT include the application, so scanning it finds nothing.
        ClassLoader loader = null;
        Set<URL> locations = new LinkedHashSet<>();
        for (String anchor : new String[]{
                "org.eclipse.lsp4j.services.LanguageServer",
                "org.eclipse.lsp4j.jsonrpc.messages.Either"}) {
            Class<?> clazz = access.findClassByName(anchor);
            if (clazz != null) {
                if (loader == null) {
                    loader = clazz.getClassLoader();
                }
                URL location = codeSourceLocation(clazz);
                if (location != null) {
                    locations.add(location);
                }
            }
        }
        if (loader == null) {
            loader = Lsp4jFeature.class.getClassLoader();
        }

        List<String> classNames = new ArrayList<>();
        for (URL location : locations) {
            collectFromLocation(location, classNames);
        }
        if (classNames.isEmpty()) {
            // Fallback for exploded/dev builds where lsp4j sits on java.class.path.
            collectFromClassPath(classNames);
        }

        int registered = 0;
        int failed = 0;
        for (String name : classNames) {
            try {
                Class<?> clazz = Class.forName(name, false, loader);
                RuntimeReflection.register(clazz);
                RuntimeReflection.register(clazz.getDeclaredConstructors());
                RuntimeReflection.register(clazz.getDeclaredMethods());
                RuntimeReflection.register(clazz.getDeclaredFields());
                registered++;
            } catch (Throwable t) {
                // Some entries are non-loadable (package-info, split-package artifacts,
                // classes whose transitive deps are absent). Reflection over the POJOs
                // that gson actually touches is what matters — swallow the rest.
                failed++;
            }
        }

        // Our own classes that are invoked reflectively at runtime. Picocli instantiates
        // version providers through reflection even when only --version is requested.
        int applicationClassesRegistered = 0;
        for (String impl : new String[]{
                "ai.kompile.cli.main.lsp.LspServerConnection$KompileLanguageClient",
                "ai.kompile.cli.main.VersionProvider"}) {
            if (registerClassForReflection(access, loader, impl)) {
                applicationClassesRegistered++;
            }
        }

        System.err.println("[Lsp4jFeature] Registered " + registered + " lsp4j classes for reflection ("
                + failed + " skipped) and " + applicationClassesRegistered
                + " application classes from " + locations.size() + " location(s).");
    }

    private boolean registerClassForReflection(BeforeAnalysisAccess access, ClassLoader loader, String className) {
        try {
            Class<?> clazz = access.findClassByName(className);
            if (clazz == null) {
                clazz = Class.forName(className, false, loader);
            }
            RuntimeReflection.register(clazz);
            RuntimeReflection.register(clazz.getDeclaredConstructors());
            RuntimeReflection.register(clazz.getDeclaredMethods());
            RuntimeReflection.register(clazz.getDeclaredFields());
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static URL codeSourceLocation(Class<?> clazz) {
        try {
            ProtectionDomain domain = clazz.getProtectionDomain();
            CodeSource source = domain != null ? domain.getCodeSource() : null;
            return source != null ? source.getLocation() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Scan the jar or exploded directory that holds lsp4j (found via its code source). */
    private void collectFromLocation(URL location, List<String> names) {
        try {
            File file = new File(location.toURI());
            if (file.isFile()) {
                collectFromJar(file, names);
            } else if (file.isDirectory()) {
                collectFromDir(file.toPath(), file, names);
            }
        } catch (Exception e) {
            // Non-file URL (unlikely for a native image classpath) — nothing to scan.
        }
    }

    /**
     * Fallback: scan every {@code java.class.path} entry (jars and exploded directories)
     * for {@code .class} files under {@link #LSP4J_PREFIX}.
     */
    private void collectFromClassPath(List<String> names) {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            File file = new File(entry);
            if (file.isFile() && entry.endsWith(".jar")) {
                collectFromJar(file, names);
            } else if (file.isDirectory()) {
                collectFromDir(file.toPath(), file, names);
            }
        }
    }

    private void collectFromJar(File jar, List<String> names) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (isLsp4jClassEntry(entryName)) {
                    names.add(entryToClassName(entryName));
                }
            }
        } catch (Throwable t) {
            // Unreadable / non-zip classpath entry — skip.
        }
    }

    private void collectFromDir(Path root, File dir, List<String> names) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFromDir(root, child, names);
            } else {
                String relative = root.relativize(child.toPath()).toString().replace(File.separatorChar, '/');
                if (isLsp4jClassEntry(relative)) {
                    names.add(entryToClassName(relative));
                }
            }
        }
    }

    private boolean isLsp4jClassEntry(String entryName) {
        if (!entryName.endsWith(".class") || !entryName.startsWith(LSP4J_PREFIX)) {
            return false;
        }
        return !entryName.endsWith("module-info.class") && !entryName.endsWith("package-info.class");
    }

    private String entryToClassName(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
    }
}
