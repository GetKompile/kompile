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

// getkompile/kompile/kompile-ag_new_kompile_cli/kompile-cli/src/main/java/ai/kompile/cli/main/Info.java
package ai.kompile.cli.main;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.common.status.ConfigReader;
import ai.kompile.cli.common.status.ServiceProber;
import ai.kompile.cli.main.util.OSResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "info", mixinStandardHelpOptions = false, description = "Display information on current kompile installation.")
public class Info implements Callable<Integer> {

    private static final String KOMPILE_PROPERTIES_FILE = "kompile-cli-versions.properties";
    private static final Properties buildProperties = new Properties();

    @CommandLine.Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    @CommandLine.Option(names = {"--section", "-s"}, description = "Section to display: version, services, instances, config, all")
    private String section = "all";

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
        buildProperties.setProperty("kompile.cli.version", "0.1.0-SNAPSHOT");
        buildProperties.setProperty("project.version", "0.1.0-SNAPSHOT");
        buildProperties.setProperty("kompile.app.version", "0.0.1-SNAPSHOT");
        buildProperties.setProperty("spring.boot.version", "3.4.5");
        buildProperties.setProperty("spring.ai.version", "1.0.0");
        buildProperties.setProperty("native.image.plugin.version", "0.10.6");
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

    public static String getVersion() {
        return buildProperties.getProperty("kompile.cli.version", "0.1.0-SNAPSHOT");
    }

    public static String getKompilePipelinesVersion() {
        return buildProperties.getProperty("project.version", getVersion());
    }

    public static String getKompileAppVersion() {
        return buildProperties.getProperty("kompile.app.version", "0.0.1-SNAPSHOT");
    }

    public static String getSpringBootVersion() {
        return buildProperties.getProperty("spring.boot.version", "3.4.5");
    }

    public static String getSpringAiVersion() {
        return buildProperties.getProperty("spring.ai.version", "1.0.0");
    }

    public static String getNativeImagePluginVersion() {
        return buildProperties.getProperty("native.image.plugin.version", "0.10.6");
    }

    public static String getMavenCompilerPluginVersion() {
        return buildProperties.getProperty("maven.compiler.plugin.version", "3.13.0");
    }

    public static String getMavenResourcesPluginVersion() {
        return buildProperties.getProperty("maven.resources.plugin.version", "3.3.1");
    }

    public static String getMavenAssemblyPluginVersion() {
        return buildProperties.getProperty("maven.assembly.plugin.version", "3.7.1");
    }

    public static String getFrontendMavenPluginVersion() {
        return buildProperties.getProperty("frontend.maven.plugin.version", "1.15.0");
    }

    public static String getOsMavenPluginVersion() {
        return buildProperties.getProperty("os.maven.plugin.version", "1.7.1");
    }

    @Override
    public Integer call() throws Exception {
        boolean showAll = "all".equalsIgnoreCase(section);
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();

        if (showAll || "version".equalsIgnoreCase(section)) {
            output.put("version", buildVersionSection());
        }

        if (showAll || "services".equalsIgnoreCase(section)) {
            output.put("services", buildServicesSection());
        }

        if (showAll || "instances".equalsIgnoreCase(section)) {
            output.put("instances", buildInstancesSection());
        }

        if (showAll || "config".equalsIgnoreCase(section)) {
            output.put("config", buildConfigSection());
        }

        if (jsonOutput) {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(output));
        } else {
            renderText(output);
        }

        return 0;
    }

    private Map<String, Object> buildVersionSection() {
        LinkedHashMap<String, Object> version = new LinkedHashMap<>();
        version.put("cli", getVersion());
        version.put("pipelines", getKompilePipelinesVersion());
        version.put("app", getKompileAppVersion());
        version.put("springBoot", getSpringBootVersion());
        version.put("springAi", getSpringAiVersion());
        version.put("homeDirectory", homeDirectory().getAbsolutePath());
        version.put("homeExists", homeDirectory().exists());
        version.put("os", OSResolver.os());
        version.put("arch", OSResolver.arch());

        LinkedHashMap<String, Boolean> tools = new LinkedHashMap<>();
        tools.put("graalvm", graalvmDirectory().exists());
        tools.put("maven", mavenDirectory().exists());
        tools.put("python", pythonDirectory().exists());
        tools.put("cmake", cmakeDirectory().exists());
        version.put("installedTools", tools);

        return version;
    }

    private List<Map<String, Object>> buildServicesSection() {
        List<ServiceProber.ServiceStatus> statuses = ServiceProber.probeAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServiceProber.ServiceStatus s : statuses) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.getName());
            entry.put("port", s.getPort());
            entry.put("healthy", s.isHealthy());
            if (s.isHealthy()) {
                entry.put("responseTimeMs", s.getResponseTimeMs());
            }
            result.add(entry);
        }
        return result;
    }

    private List<Map<String, Object>> buildInstancesSection() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (InstanceInfo info : InstanceRegistry.listAll()) {
                LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", info.getName());
                entry.put("type", info.getType());
                entry.put("port", info.getPort());
                entry.put("pid", info.getPid());
                boolean alive = ProcessHandle.of(info.getPid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false);
                entry.put("alive", alive);
                if (info.getStartedAt() != null) {
                    entry.put("startedAt", info.getStartedAt().toString());
                }
                result.add(entry);
            }
        } catch (Exception e) {
            // Registry unavailable
        }
        return result;
    }

    private Map<String, Object> buildConfigSection() {
        return ConfigReader.readAll();
    }

    @SuppressWarnings("unchecked")
    private void renderText(LinkedHashMap<String, Object> output) {
        StringBuilder sb = new StringBuilder();

        // Version section
        if (output.containsKey("version")) {
            Map<String, Object> v = (Map<String, Object>) output.get("version");
            sb.append("=== Kompile Version ===\n");
            sb.append("  CLI: ").append(v.get("cli")).append("\n");
            sb.append("  Pipelines: ").append(v.get("pipelines")).append("\n");
            sb.append("  App: ").append(v.get("app")).append("\n");
            sb.append("  Spring Boot: ").append(v.get("springBoot")).append("\n");
            sb.append("  Spring AI: ").append(v.get("springAi")).append("\n");
            sb.append("  Home: ").append(v.get("homeDirectory"))
                    .append(" (exists: ").append(v.get("homeExists")).append(")\n");
            sb.append("  OS: ").append(v.get("os")).append("  Arch: ").append(v.get("arch")).append("\n");
            Map<String, Boolean> tools = (Map<String, Boolean>) v.get("installedTools");
            if (tools != null) {
                sb.append("  Installed Tools:");
                for (Map.Entry<String, Boolean> t : tools.entrySet()) {
                    sb.append(" ").append(t.getKey()).append("=").append(t.getValue() ? "yes" : "no");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Services section
        if (output.containsKey("services")) {
            List<Map<String, Object>> services = (List<Map<String, Object>>) output.get("services");
            sb.append("=== Running Services ===\n");
            if (services.isEmpty()) {
                sb.append("  (none detected)\n");
            } else {
                for (Map<String, Object> s : services) {
                    String label = String.format("  %s (port %s)", s.get("name"), s.get("port"));
                    boolean healthy = Boolean.TRUE.equals(s.get("healthy"));
                    if (healthy) {
                        sb.append(String.format("%-45s [RUNNING]  %sms%n", label, s.get("responseTimeMs")));
                    } else {
                        sb.append(String.format("%-45s [NOT RUNNING]%n", label));
                    }
                }
            }
            sb.append("\n");
        }

        // Instances section
        if (output.containsKey("instances")) {
            List<Map<String, Object>> instances = (List<Map<String, Object>>) output.get("instances");
            sb.append("=== Registered Instances ===\n");
            if (instances.isEmpty()) {
                sb.append("  (none registered)\n");
            } else {
                for (Map<String, Object> inst : instances) {
                    boolean alive = Boolean.TRUE.equals(inst.get("alive"));
                    sb.append(String.format("  %s [%s] port=%s pid=%s [%s]%n",
                            inst.get("name"), inst.get("type"), inst.get("port"), inst.get("pid"),
                            alive ? "ALIVE" : "DEAD - stale entry"));
                }
            }
            sb.append("\n");
        }

        // Config section
        if (output.containsKey("config")) {
            Map<String, Object> configs = (Map<String, Object>) output.get("config");
            sb.append("=== Configuration (").append(KompileHome.configDirectory().getAbsolutePath()).append(") ===\n");
            if (configs.isEmpty()) {
                sb.append("  (no config files found)\n");
            } else {
                try {
                    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                    for (Map.Entry<String, Object> entry : configs.entrySet()) {
                        sb.append("  ").append(entry.getKey()).append(":\n");
                        String json = mapper.writeValueAsString(entry.getValue());
                        for (String line : json.split("\n")) {
                            sb.append("    ").append(line).append("\n");
                        }
                    }
                } catch (Exception e) {
                    sb.append("  (error rendering configs)\n");
                }
            }
        }

        System.out.println(sb);
    }

    public static class ManifestVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            String implTitle = Info.class.getPackage().getImplementationTitle();
            if (implTitle == null) {
                implTitle = "Kompile CLI";
            }
            return new String[]{implTitle + " version " + Info.getVersion()};
        }
    }

    public static void main(String... args) throws Exception {
        new CommandLine(new Info()).execute(args);
    }
}
