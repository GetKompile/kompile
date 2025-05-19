/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// getkompile/kompile/kompile-ag_new_kompile_cli/kompile-cli/src/main/java/ai/kompile/cli/main/Info.java
package ai.kompile.cli.main;

import ai.kompile.cli.main.util.OSResolver;
import picocli.CommandLine;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "info",mixinStandardHelpOptions = false,description = "Display information on current kompile installation.")
public class Info implements Callable<Integer> {

    private static final String KOMPILE_PROPERTIES_FILE = "kompile-cli-versions.properties";
    private static final Properties buildProperties = new Properties();

    static {
        try (InputStream is = Info.class.getClassLoader().getResourceAsStream(KOMPILE_PROPERTIES_FILE)) {
            if (is != null) {
                buildProperties.load(is);
            } else {
                System.err.println("WARNING: " + KOMPILE_PROPERTIES_FILE + " not found on classpath. Using default versions.");
                loadDefaultVersions();
            }
        } catch (Exception e) {
            System.err.println("Error loading " + KOMPILE_PROPERTIES_FILE + ": " + e.getMessage() + ". Using default versions.");
            loadDefaultVersions();
        }
    }

    private static void loadDefaultVersions() {
        // These should match the current project versions if properties file fails
        buildProperties.setProperty("kompile.cli.version", "0.1.0-SNAPSHOT"); // Version of the CLI itself (kompile parent)
        buildProperties.setProperty("project.version", "0.1.0-SNAPSHOT"); // Version for kompile-pipelines-* modules
        buildProperties.setProperty("kompile.app.version", "0.0.1-SNAPSHOT"); // Version for kompile-app-* modules (RAG parent)
        buildProperties.setProperty("spring.boot.version", "3.2.5");
        buildProperties.setProperty("spring.ai.version", "1.0.0-M8");
        buildProperties.setProperty("native.image.plugin.version", "0.10.6");

        // Default versions for common Maven plugins
        buildProperties.setProperty("maven.compiler.plugin.version", "3.13.0");
        buildProperties.setProperty("maven.resources.plugin.version", "3.3.1");
        buildProperties.setProperty("maven.assembly.plugin.version", "3.7.1");
        buildProperties.setProperty("frontend.maven.plugin.version", "1.15.0");
        buildProperties.setProperty("os.maven.plugin.version", "1.7.1");
    }

    public Info() {
    }

    public static File homeDirectory() {
        return new File(System.getProperty("user.home"), ".kompile");
    }

    public static File mavenDirectory() {
        return new File(homeDirectory(), "mvn");
    }

    public static File graalvmDirectory() {
        return new File(homeDirectory(), "graalvm");
    }

    public static File pythonDirectory() {
        return new File(homeDirectory(), "python");
    }

    public static File cmakeDirectory() {
        return new File(homeDirectory(), "cmake");
    }

    /**
     * Gets the overall Kompile project version (typically from the parent POM of kompile-cli).
     * @return The version string.
     */
    public static String getVersion() {
        return buildProperties.getProperty("kompile.cli.version", "0.1.0-SNAPSHOT");
    }

    /**
     * Gets the version for Kompile Pipelines Framework modules.
     * @return The version string.
     */
    public static String getKompilePipelinesVersion() {
        return buildProperties.getProperty("project.version", getVersion());
    }

    /**
     * Gets the version for Kompile App modules (e.g., rag-mcp-assistant-parent and its children).
     * @return The version string.
     */
    public static String getKompileAppVersion() {
        return buildProperties.getProperty("kompile.app.version", "0.0.1-SNAPSHOT");
    }

    /**
     * Gets the Spring Boot version used by kompile-app modules.
     * @return The version string.
     */
    public static String getSpringBootVersion() {
        return buildProperties.getProperty("spring.boot.version", "3.2.5");
    }

    /**
     * Gets the Spring AI version used by kompile-app modules.
     * @return The version string.
     */
    public static String getSpringAiVersion() {
        return buildProperties.getProperty("spring.ai.version", "1.0.0-M8");
    }

    /**
     * Gets the GraalVM Native Image Maven Plugin version.
     * @return The version string.
     */
    public static String getNativeImagePluginVersion() {
        return buildProperties.getProperty("native.image.plugin.version", "0.10.6");
    }

    /**
     * Gets the Maven Compiler Plugin version.
     * @return The version string.
     */
    public static String getMavenCompilerPluginVersion() {
        return buildProperties.getProperty("maven.compiler.plugin.version", "3.13.0");
    }

    /**
     * Gets the Maven Resources Plugin version.
     * @return The version string.
     */
    public static String getMavenResourcesPluginVersion() {
        return buildProperties.getProperty("maven.resources.plugin.version", "3.3.1");
    }

    /**
     * Gets the Maven Assembly Plugin version.
     * @return The version string.
     */
    public static String getMavenAssemblyPluginVersion() {
        return buildProperties.getProperty("maven.assembly.plugin.version", "3.7.1");
    }

    /**
     * Gets the Frontend Maven Plugin version.
     * @return The version string.
     */
    public static String getFrontendMavenPluginVersion() {
        return buildProperties.getProperty("frontend.maven.plugin.version", "1.15.0");
    }

    /**
     * Gets the OS Maven Plugin version.
     * @return The version string.
     */
    public static String getOsMavenPluginVersion() {
        return buildProperties.getProperty("os.maven.plugin.version", "1.7.1");
    }


    @Override
    public Integer call() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Kompile CLI Version: ").append(getVersion()).append("\n");
        stringBuilder.append("Kompile Pipelines Modules Version: ").append(getKompilePipelinesVersion()).append("\n");
        stringBuilder.append("Kompile App Modules (RAG Parent) Version: ").append(getKompileAppVersion()).append("\n");
        stringBuilder.append("  Spring Boot Version (for apps): ").append(getSpringBootVersion()).append("\n");
        stringBuilder.append("  Spring AI Version (for apps): ").append(getSpringAiVersion()).append("\n");
        stringBuilder.append("  Native Image Plugin Version (for apps): ").append(getNativeImagePluginVersion()).append("\n");
        stringBuilder.append("Common Maven Plugin Versions:\n");
        stringBuilder.append("  Maven Compiler Plugin: ").append(getMavenCompilerPluginVersion()).append("\n");
        stringBuilder.append("  Maven Resources Plugin: ").append(getMavenResourcesPluginVersion()).append("\n");
        stringBuilder.append("  Maven Assembly Plugin: ").append(getMavenAssemblyPluginVersion()).append("\n");
        stringBuilder.append("  Frontend Maven Plugin: ").append(getFrontendMavenPluginVersion()).append("\n");
        stringBuilder.append("  OS Maven Plugin: ").append(getOsMavenPluginVersion()).append("\n");
        stringBuilder.append("Kompile Home Directory: ").append(homeDirectory().getAbsolutePath()).append(" (exists: ").append(homeDirectory().exists()).append(")\n");
        stringBuilder.append("  GraalVM Installed: ").append(graalvmDirectory().exists()).append(" (at ").append(graalvmDirectory().getAbsolutePath()).append(")\n");
        stringBuilder.append("  Maven Installed: ").append(mavenDirectory().exists()).append(" (at ").append(mavenDirectory().getAbsolutePath()).append(")\n");
        stringBuilder.append("  Python Installed: ").append(pythonDirectory().exists()).append(" (at ").append(pythonDirectory().getAbsolutePath()).append(")\n");
        stringBuilder.append("  CMake Installed: ").append(cmakeDirectory().exists()).append(" (at ").append(cmakeDirectory().getAbsolutePath()).append(")\n");
        stringBuilder.append("Resolved OS for Install Commands: ").append(OSResolver.os()).append("\n");
        stringBuilder.append("Resolved Architecture: ").append(OSResolver.arch()).append("\n");
        stringBuilder.append("Shared Library Extension: ").append(OSResolver.sharedLibraryExtension()).append("\n");
        System.out.println(stringBuilder);
        return 0;
    }

    public static class ManifestVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            String implTitle = Info.class.getPackage().getImplementationTitle();
            if (implTitle == null) {
                implTitle = "Kompile CLI"; // Fallback if not in manifest
            }
            return new String[]{ implTitle + " version " + Info.getVersion() };
        }
    }

    public static void main(String...args) throws Exception {
        new CommandLine(new Info()).execute(args);
    }
}