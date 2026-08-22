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
package ai.kompile.cli.main;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.config.GpuProbe;
import ai.kompile.cli.common.config.HardwareAutoConfigurator;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.util.JsonUtils;
import java.util.stream.Collectors;
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * {@code kompile doctor} — prints a ✓/✗/! check table with one actionable
 * fix line per failure and exits with code 0 when all critical checks pass, 1
 * otherwise.
 *
 * <p>Every probe is wrapped so no individual failure can crash the command.
 * Unknown / unprobed states surface as "!" warnings.</p>
 */
@CommandLine.Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Run environment health checks and print a diagnostics report.")
public class DoctorCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Severity / result indicator for a single check. */
    public enum Status {
        /** Check passed — no action needed. */
        OK("✓"),
        /** Check failed — action required (counts toward exit code 1 only when critical). */
        FAIL("✗"),
        /** Unknown or non-critical warning. */
        WARN("!");

        private final String symbol;

        Status(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /**
     * Result of a single check.
     *
     * @param name     Short check name, e.g. "CLI version"
     * @param status   {@link Status} value
     * @param detail   One-line detail shown in the table, e.g. "1.2.3 (native)"
     * @param fix      Null when status is OK; actionable one-liner otherwise
     * @param critical True only for checks whose failure should set exit code 1
     */
    public record CheckResult(String name, Status status, String detail, String fix, boolean critical) {

        /** Convenience ctor for OK results (no fix needed, not critical failure). */
        public static CheckResult ok(String name, String detail) {
            return new CheckResult(name, Status.OK, detail, null, false);
        }

        /** Non-critical warning. */
        public static CheckResult warn(String name, String detail, String fix) {
            return new CheckResult(name, Status.WARN, detail, fix, false);
        }

        /** Critical failure — drives exit code 1. */
        public static CheckResult fail(String name, String detail, String fix) {
            return new CheckResult(name, Status.FAIL, detail, fix, true);
        }

        /** Warning with explicit criticality flag. */
        public static CheckResult of(String name, Status status, String detail, String fix, boolean critical) {
            return new CheckResult(name, status, detail, fix, critical);
        }
    }

    // ── CLI options ───────────────────────────────────────────────────────────

    @CommandLine.Option(
            names = {"--root"},
            description = "Project root to check (optional; defaults to cwd if it contains kompile.project.json).")
    private String root;

    @CommandLine.Option(
            names = {"--no-color"},
            description = "Disable ANSI colour in output.")
    private boolean noColor;

    @CommandLine.Option(
            names = {"--json"},
            description = "Emit machine-readable JSON instead of the text report.")
    private boolean json;

    // ── Entry point ───────────────────────────────────────────────────────────

    @Override
    public Integer call() {
        Path projectRoot = resolveProjectRoot();

        List<CheckResult> results = new ArrayList<>();

        // 1. CLI identity
        results.addAll(checkCli());

        // 2. Java runtime for jar tier
        results.addAll(checkJavaRuntime());

        // 3. Components
        results.addAll(checkComponents());

        // 4. Hardware
        results.addAll(checkHardware());

        // 5. Disk space
        results.addAll(checkDisk(projectRoot));

        // 6. Agent CLIs + Ollama
        results.addAll(checkAgents());

        // 7. Ports
        results.addAll(checkPorts());

        // 8. Global config
        results.addAll(checkGlobalConfig());

        // 9. Project-specific checks (only when a root is available)
        if (projectRoot != null) {
            results.addAll(checkProject(projectRoot));
        }

        // 10. Instance registry GC
        results.addAll(checkInstances());

        // ── Summary ───────────────────────────────────────────────────────────
        long passed = results.stream().filter(r -> r.status() == Status.OK).count();
        long warnings = results.stream().filter(r -> r.status() == Status.WARN).count();
        long failures = results.stream().filter(r -> r.status() == Status.FAIL).count();

        boolean anyCriticalFailure = results.stream()
                .anyMatch(r -> r.status() == Status.FAIL && r.critical());
        if (json) {
            printJsonReport(projectRoot, results, passed, warnings, failures, anyCriticalFailure);
        } else {
            printTable(results);
            System.out.printf("%n%d passed, %d warnings, %d failures%n", passed, warnings, failures);
        }
        return anyCriticalFailure ? 1 : 0;
    }

    // ── Check implementations ─────────────────────────────────────────────────

    /** Check 1: CLI version + execution mode (native vs jar). */
    List<CheckResult> checkCli() {
        List<CheckResult> out = new ArrayList<>();
        try {
            String version = Info.getVersion();
            boolean isNative = isNativeImage();
            String mode = isNative ? "native" : "jar";
            out.add(CheckResult.ok("CLI version", version + " (" + mode + ")"));
        } catch (Exception e) {
            out.add(CheckResult.warn("CLI version", "could not determine: " + e.getMessage(),
                    "Try reinstalling: install.sh"));
        }
        return out;
    }

    /** Check 2: Java runtime for jar-tier components. */
    List<CheckResult> checkJavaRuntime() {
        List<CheckResult> out = new ArrayList<>();
        try {
            String javaExe = JavaRuntimeLocator.javaExecutable();
            String source = resolveJavaSource(javaExe);

            // Run java -version with a 2-second timeout
            String versionLine = runWithTimeout(new String[]{javaExe, "-version"}, 2000);
            if (versionLine != null) {
                out.add(CheckResult.ok("Java runtime", source + " → " + versionLine.trim()));
            } else {
                // Found the binary but it didn't respond in time
                out.add(CheckResult.warn("Java runtime",
                        source + " (version probe timed out)",
                        "Check that " + javaExe + " is executable"));
            }
        } catch (Exception e) {
            // No java is only critical when a jar-tier component is installed
            ComponentRegistry reg = new ComponentRegistry();
            boolean jarTierInstalled =
                    reg.findInstalledJar(ComponentRegistry.KOMPILE_APP_MAIN) != null
                    || reg.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING) != null
                    || reg.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_SERVING) != null
                    || reg.findInstalledJar(ComponentRegistry.KOMPILE_PIPELINE_SERVING) != null;
            if (jarTierInstalled) {
                out.add(CheckResult.fail("Java runtime",
                        "not found: " + e.getMessage(),
                        "Install a JRE 21+ or set KOMPILE_JAVA to a java executable"));
            } else {
                out.add(CheckResult.warn("Java runtime",
                        "not found (OK if using native binaries only)",
                        "Set KOMPILE_JAVA or install a JRE 21+ if you plan to use jar-tier components"));
            }
        }
        return out;
    }

    /** Check 3: the executable closure required by the installed distribution variant. */
    List<CheckResult> checkComponents() {
        List<CheckResult> out = new ArrayList<>();
        ComponentRegistry reg = new ComponentRegistry();
        Path installHome = KompileHome.installDirectory().toPath().toAbsolutePath().normalize();
        DistributionComponentContract contract = distributionComponentContract(installHome);
        List<String> required = contract.required();
        List<String> optional = contract.optional();
        List<String> all = new ArrayList<>(required);
        all.addAll(optional);

        for (String id : all) {
            try {
                File artifact = reg.findInstalledJar(id);
                if (artifact == null) {
                    String fix = "Install with: kompile install " + id
                            + "  — or reinstall the " + contract.variant()
                            + " distribution (install.sh)";
                    out.add(required.contains(id)
                            ? CheckResult.fail(id, "not installed", fix)
                            : CheckResult.warn(id, "not installed — "
                                    + surfacesServedBy(id) + " will be unavailable", fix));
                } else {
                    boolean isNative = artifact.canExecute() && !artifact.getName().endsWith(".jar");
                    String mode = isNative ? "native" : "jar";
                    out.add(CheckResult.ok(id, mode + " at " + artifact.getAbsolutePath()));
                }
            } catch (Exception e) {
                out.add(CheckResult.warn(id,
                        "probe error: " + e.getMessage(),
                        "Run: kompile install " + id));
            }
        }
        return out;
    }

    record DistributionComponentContract(
            String variant, List<String> required, List<String> optional) {
    }

    /**
     * Resolve the component closure from the distribution manifest. The manifest
     * is authoritative: a component marked absent is intentionally omitted and
     * must not make doctor fail. Legacy installs without metadata retain the old
     * local/full fallback contract.
     */
    static DistributionComponentContract distributionComponentContract(Path installHome) {
        Path metadata = installHome.resolve(".dist-info.json");
        if (Files.isRegularFile(metadata)) {
            try {
                JsonNode root = JSON.readTree(metadata.toFile());
                String variant = root.path("variant").asText("distribution");
                JsonNode components = root.path("components");
                LinkedHashMap<String, String> componentIds = new LinkedHashMap<>();
                componentIds.put("server", ComponentRegistry.KOMPILE_APP_MAIN);
                componentIds.put("model-staging", ComponentRegistry.KOMPILE_MODEL_STAGING);
                componentIds.put("model-serving", ComponentRegistry.KOMPILE_MODEL_SERVING);
                componentIds.put("pipeline-serving", ComponentRegistry.KOMPILE_PIPELINE_SERVING);
                componentIds.put("chat", ComponentRegistry.KOMPILE_APP_CHAT);
                componentIds.put("crawl-manager", ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER);

                List<String> required = new ArrayList<>();
                componentIds.forEach((metadataId, registryId) -> {
                    if (components.path(metadataId).path("present").asBoolean(false)) {
                        required.add(registryId);
                    }
                });
                return new DistributionComponentContract(variant, List.copyOf(required), List.of());
            } catch (IOException ignored) {
                // Fall through to the legacy marker contract for older/corrupt installs.
            }
        }

        boolean localDistribution = isLocalDistribution(installHome);
        return localDistribution
                ? new DistributionComponentContract(
                        "local",
                        List.of(ComponentRegistry.KOMPILE_MODEL_STAGING,
                                ComponentRegistry.KOMPILE_MODEL_SERVING,
                                ComponentRegistry.KOMPILE_PIPELINE_SERVING),
                        List.of())
                : new DistributionComponentContract(
                        "full",
                        List.of(ComponentRegistry.KOMPILE_APP_MAIN,
                                ComponentRegistry.KOMPILE_MODEL_STAGING),
                        List.of(ComponentRegistry.KOMPILE_APP_CHAT,
                                ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER));
    }

    static boolean isLocalDistribution(Path kompileHome) {
        Path variant = kompileHome.resolve(".variant");
        try {
            return Files.isRegularFile(variant)
                    && "local".equalsIgnoreCase(Files.readString(variant).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** Human-readable description of what a persona app serves, for the "not installed" warning. */
    private static String surfacesServedBy(String componentId) {
        if (ComponentRegistry.KOMPILE_APP_CHAT.equals(componentId)) {
            return "chat, agents, and RAG";
        }
        if (ComponentRegistry.KOMPILE_APP_CRAWL_MANAGER.equals(componentId)) {
            return "crawls, ingest, and indexing";
        }
        return "some surfaces";
    }

    /** Check 4: RAM tier, CPU count, GPU presence. Informational only. */
    List<CheckResult> checkHardware() {
        List<CheckResult> out = new ArrayList<>();
        try {
            long ramBytes = HardwareAutoConfigurator.detectSystemRamBytes();
            HardwareAutoConfigurator.Tier tier = HardwareAutoConfigurator.resolveTier(ramBytes);
            int cpus = HardwareAutoConfigurator.detectCpuCount();
            long ramGb = ramBytes / (1024L * 1024L * 1024L);
            out.add(CheckResult.ok("Hardware",
                    ramGb + " GB RAM, " + cpus + " CPUs, tier=" + tier.name().toLowerCase()));
        } catch (Exception e) {
            out.add(CheckResult.warn("Hardware", "detection failed: " + e.getMessage(), null));
        }
        try {
            List<GpuProbe.GpuInfo> gpus = GpuProbe.probe();
            if (gpus.isEmpty()) {
                out.add(CheckResult.ok("GPU", "none detected (CPU-only mode)"));
            } else {
                StringBuilder sb = new StringBuilder();
                for (GpuProbe.GpuInfo g : gpus) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(g.name()).append(" (").append(g.vramMb()).append(" MiB)");
                }
                out.add(CheckResult.ok("GPU", sb.toString()));
            }
        } catch (Exception e) {
            out.add(CheckResult.warn("GPU", "probe failed: " + e.getMessage(), null));
        }
        return out;
    }

    /** Check 5: usable disk space at ~/.kompile and optionally at --root. */
    List<CheckResult> checkDisk(Path projectRoot) {
        List<CheckResult> out = new ArrayList<>();
        long warnThresholdGb = 10L;

        Path kompileHome = Info.homeDirectory().toPath();
        out.add(diskCheck("Disk (~/.kompile)", kompileHome, warnThresholdGb));

        if (projectRoot != null && !projectRoot.equals(kompileHome)) {
            out.add(diskCheck("Disk (project root)", projectRoot, warnThresholdGb));
        }
        return out;
    }

    private CheckResult diskCheck(String name, Path path, long warnGb) {
        try {
            Path probe = path;
            // Walk up to find the first existing ancestor
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) {
                return CheckResult.warn(name, "path not found", "Create " + path);
            }
            long usableBytes = probe.toFile().getUsableSpace();
            long usableGb = usableBytes / (1024L * 1024L * 1024L);
            String detail = usableGb + " GB usable at " + path;
            if (usableGb < warnGb) {
                return CheckResult.warn(name, detail,
                        "Free up disk space; kompile may fail when crawling or building models");
            }
            return CheckResult.ok(name, detail);
        } catch (Exception e) {
            return CheckResult.warn(name, "could not stat: " + e.getMessage(), null);
        }
    }

    /** Check 6: known agent CLIs on PATH + Ollama probe. */
    List<CheckResult> checkAgents() {
        List<CheckResult> out = new ArrayList<>();
        try {
            List<AgentProvider> agents = CliAgentRegistry.loadAll();
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (AgentProvider agent : agents) {
                if (isOnPath(agent.getCommand())) {
                    found.add(agent.getName());
                } else {
                    missing.add(agent.getName());
                }
            }
            if (agents.isEmpty()) {
                out.add(CheckResult.warn("Agent CLIs", "registry empty (cli-agents.json not on classpath)", null));
            } else if (found.isEmpty()) {
                out.add(CheckResult.warn("Agent CLIs",
                        "none found (checked: " + String.join(", ", missing) + ")",
                        "Install one of: claude, codex, gemini, opencode — or use Ollama as fallback"));
            } else {
                String foundStr = String.join(", ", found);
                String missingStr = missing.isEmpty() ? "" : "  [missing: " + String.join(", ", missing) + "]";
                out.add(CheckResult.ok("Agent CLIs", "found: " + foundStr + missingStr));
            }
        } catch (Exception e) {
            out.add(CheckResult.warn("Agent CLIs", "detection failed: " + e.getMessage(), null));
        }

        // Probe Ollama
        out.add(probeOllama());

        return out;
    }

    private CheckResult probeOllama() {
        try {
            URL url = URI.create("http://localhost:11434/api/tags").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                return CheckResult.ok("Ollama", "running at localhost:11434");
            } else {
                return CheckResult.warn("Ollama", "responded with HTTP " + code,
                        "Check Ollama logs or restart it");
            }
        } catch (Exception e) {
            return CheckResult.warn("Ollama",
                    "not reachable at localhost:11434 (OK if using a different agent)",
                    "Start Ollama with: ollama serve");
        }
    }

    /**
     * Check 7: the ports kompile wants to bind — warn only, cross-referenced against InstanceRegistry.
     *
     * <p>The three persona apps come from the routing config, so an overridden port is checked instead
     * of the built-in one; 8090/8091 are model staging and its sibling.</p>
     */
    List<CheckResult> checkPorts() {
        List<CheckResult> out = new ArrayList<>();
        LinkedHashSet<Integer> ports = new LinkedHashSet<>();
        for (KompileService service : KompileService.values()) {
            ports.add(KompileServiceEndpoints.resolve(service).port());
        }
        ports.add(8090);
        ports.add(8091);
        for (int port : ports) {
            out.add(checkPort(port));
        }
        return out;
    }

    CheckResult checkPort(int port) {
        try (ServerSocket s = new ServerSocket(port, 0, InetAddress.getLoopbackAddress())) {
            s.setReuseAddress(true);
            return CheckResult.ok("Port " + port, "free");
        } catch (IOException e) {
            // Port in use — try to cross-reference InstanceRegistry
            String inUseBy = "unknown process";
            try {
                InstanceInfo instance = InstanceRegistry.findByPort(port);
                if (instance != null) {
                    inUseBy = "kompile instance '" + instance.getName() + "' (pid=" + instance.getPid() + ")";
                }
            } catch (Exception ignored) {
                // Registry read failure is non-fatal
            }
            return CheckResult.warn("Port " + port,
                    "in use by " + inUseBy,
                    "Stop the process using port " + port + " if you need to start a fresh kompile service");
        } catch (Exception e) {
            return CheckResult.warn("Port " + port, "probe failed: " + e.getMessage(), null);
        }
    }

    /** Check 8: global ~/.kompile/config directory. */
    List<CheckResult> checkGlobalConfig() {
        List<CheckResult> out = new ArrayList<>();
        try {
            File configDir = new File(Info.homeDirectory(), "config");
            if (!configDir.isDirectory()) {
                out.add(CheckResult.warn("Global config",
                        "~/.kompile/config does not exist",
                        "Run: kompile configure init  (or kompile project init to create a project)"));
            } else {
                File[] jsons = configDir.listFiles((d, n) -> n.endsWith(".json"));
                int count = jsons == null ? 0 : jsons.length;
                out.add(CheckResult.ok("Global config", count + " JSON file(s) in ~/.kompile/config"));
            }
        } catch (Exception e) {
            out.add(CheckResult.warn("Global config", "probe failed: " + e.getMessage(), null));
        }
        return out;
    }

    /** Check 9: project-specific checks (only when a project root is found). */
    List<CheckResult> checkProject(Path projectRoot) {
        List<CheckResult> out = new ArrayList<>();

        // a. kompile.project.json present and loadable
        Path manifestPath = projectRoot.resolve("kompile.project.json");
        if (!Files.isRegularFile(manifestPath)) {
            out.add(CheckResult.warn("Project manifest",
                    "kompile.project.json not found at " + projectRoot,
                    "Run: kompile project init"));
            return out; // no point in further project checks
        }
        out.add(CheckResult.ok("Project manifest", manifestPath.toString()));

        // b. config/project-runtime.json
        Path runtimeConfig = projectRoot.resolve("config").resolve("project-runtime.json");
        if (Files.isRegularFile(runtimeConfig)) {
            out.add(CheckResult.ok("project-runtime.json", runtimeConfig.toString()));
        } else {
            out.add(CheckResult.warn("project-runtime.json",
                    "not found",
                    "Run: kompile configure init  to generate default runtime configuration"));
        }

        // c. .mcp.json
        Path mcpJson = projectRoot.resolve(".mcp.json");
        if (Files.isRegularFile(mcpJson)) {
            out.add(CheckResult.ok(".mcp.json", mcpJson.toString()));
        } else {
            out.add(CheckResult.warn(".mcp.json",
                    "not found",
                    "Run: kompile project init  to provision MCP config for agent tools"));
        }

        // d. Model entries vs registry
        out.addAll(checkProjectModels(projectRoot, manifestPath));

        return out;
    }

    private List<CheckResult> checkProjectModels(Path projectRoot, Path manifestPath) {
        List<CheckResult> out = new ArrayList<>();
        try {
            // Read manifest JSON minimally (no dependency on KompileProjectStore to keep this
            // in cli-main without pulling in kompile-project-store at test time)
            String content = Files.readString(manifestPath);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(content);
            com.fasterxml.jackson.databind.JsonNode models = root.path("models");
            if (models.isMissingNode() || models.isEmpty()) {
                out.add(CheckResult.ok("Project models", "no models configured"));
                return out;
            }

            // Load model registry if present
            Path modelRegistry = Paths.get(System.getProperty("user.home"), ".kompile", "models", "registry.json");
            com.fasterxml.jackson.databind.JsonNode registryRoot = null;
            if (Files.isRegularFile(modelRegistry)) {
                try {
                    registryRoot = mapper.readTree(modelRegistry.toFile());
                } catch (Exception ignored) { }
            }

            for (com.fasterxml.jackson.databind.JsonNode model : models) {
                String modelId = model.path("id").asText("(unknown)");
                boolean requiresDownload = model.path("staging").path("requiresDownload").asBoolean(false);
                boolean hasArtifact = hasLocalModelArtifact(registryRoot, modelId, model);

                if (!hasArtifact && requiresDownload) {
                    out.add(CheckResult.of("Model: " + modelId, Status.OK,
                            "will download on first serve", null, false));
                } else if (!hasArtifact) {
                    out.add(CheckResult.warn("Model: " + modelId,
                            "no local artifact found",
                            "Download with: kompile install model " + modelId));
                } else {
                    out.add(CheckResult.ok("Model: " + modelId, "artifact present"));
                }
            }
        } catch (Exception e) {
            out.add(CheckResult.warn("Project models", "could not parse manifest: " + e.getMessage(), null));
        }
        return out;
    }

    private boolean hasLocalModelArtifact(
            com.fasterxml.jackson.databind.JsonNode registryRoot,
            String modelId,
            com.fasterxml.jackson.databind.JsonNode modelNode) {
        // Check explicit localPath in the model node
        String localPath = modelNode.path("localPath").asText(null);
        if (localPath != null && !localPath.isBlank() && new File(localPath).isFile()) {
            return true;
        }
        // Check ~/.kompile/models/<id>/
        Path modelDir = Paths.get(System.getProperty("user.home"), ".kompile", "models", modelId);
        if (Files.isDirectory(modelDir)) {
            try {
                return Files.list(modelDir).anyMatch(p ->
                        p.getFileName().toString().endsWith(".gguf")
                        || p.getFileName().toString().endsWith(".bin")
                        || p.getFileName().toString().endsWith(".onnx"));
            } catch (IOException ignored) { }
        }
        // Check the global model registry
        if (registryRoot != null) {
            com.fasterxml.jackson.databind.JsonNode entry = registryRoot.path(modelId);
            if (!entry.isMissingNode()) {
                String regPath = entry.path("localPath").asText(null);
                if (regPath != null && !regPath.isBlank() && new File(regPath).isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check 10: GC stale instance-registry entries and report what was cleaned.
     * An entry is stale when the registered PID is no longer alive AND the port
     * is no longer bound — i.e. the process is genuinely gone, not just slow.
     */
    List<CheckResult> checkInstances() {
        List<CheckResult> out = new ArrayList<>();
        try {
            List<InstanceInfo> removed = InstanceRegistry.gcDeadInstances();
            if (!removed.isEmpty()) {
                String names = removed.stream().map(InstanceInfo::getName).collect(Collectors.joining(", "));
                out.add(CheckResult.ok("Instance registry",
                        "cleaned " + removed.size() + " stale entry(ies): " + names));
            }

            List<InstanceInfo> live = InstanceRegistry.listAll();
            if (live.isEmpty()) {
                out.add(CheckResult.ok("Instance registry", "no registered instances"));
                return out;
            }

            String names = live.stream().map(InstanceInfo::getName).collect(Collectors.joining(", "));
            out.add(CheckResult.ok("Instance registry", live.size() + " instance(s): " + names));
            for (InstanceInfo instance : live) {
                out.add(checkInstanceHealth(instance));
            }
        } catch (Exception e) {
            out.add(CheckResult.warn("Instance registry",
                    "GC failed: " + e.getMessage(), null));
        }
        return out;
    }

    private CheckResult checkInstanceHealth(InstanceInfo instance) {
        String name = instance.getName() == null || instance.getName().isBlank()
                ? "(unnamed)" : instance.getName();
        String checkName = "Instance: " + name;
        long pid = instance.getPid();
        boolean pidAlive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        boolean httpHealthy;
        try {
            httpHealthy = new KompileHttpClient(instance.getUrl()).isHealthy();
        } catch (Exception ignored) {
            httpHealthy = false;
        }

        String detail = String.format("type=%s pid=%d pidAlive=%s url=%s health=%s",
                nullToDash(instance.getType()), pid, pidAlive, instance.getUrl(), httpHealthy ? "ok" : "down");
        if (pidAlive && httpHealthy) {
            return CheckResult.ok(checkName, detail);
        }
        if (httpHealthy) {
            return CheckResult.warn(checkName, detail,
                    "Registry PID is stale; stop/start the instance or run project stop/start to refresh it");
        }
        if (pidAlive) {
            return CheckResult.warn(checkName, detail,
                    "Process is alive but health probes failed; check instance logs and /actuator/health");
        }
        return CheckResult.warn(checkName, detail,
                "Instance appears stopped; remove stale registry entry or restart the project");
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private void printJsonReport(Path projectRoot,
                                 List<CheckResult> results,
                                 long passed,
                                 long warnings,
                                 long failures,
                                 boolean anyCriticalFailure) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("projectRoot", projectRoot != null ? projectRoot.toString() : null);
        report.put("exitCode", anyCriticalFailure ? 1 : 0);
        report.put("summary", Map.of(
                "passed", passed,
                "warnings", warnings,
                "failures", failures,
                "criticalFailure", anyCriticalFailure));
        List<Map<String, Object>> checks = new ArrayList<>();
        for (CheckResult result : results) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("name", result.name());
            check.put("status", result.status().name());
            check.put("detail", result.detail());
            check.put("fix", result.fix());
            check.put("critical", result.critical());
            checks.add(check);
        }
        report.put("checks", checks);
        try {
            System.out.println(JSON.writeValueAsString(report));
        } catch (Exception e) {
            System.err.println("Failed to render JSON report: " + e.getMessage());
        }
    }

    /** Print an aligned check table followed by the fix lines. */
    void printTable(List<CheckResult> results) {
        System.out.println();
        System.out.println("kompile doctor — environment check");
        System.out.println("─".repeat(72));

        // Compute column widths
        int nameWidth = results.stream().mapToInt(r -> r.name().length()).max().orElse(20);
        nameWidth = Math.max(nameWidth, 20);

        String fmt = "  %s  %-" + nameWidth + "s  %s%n";

        for (CheckResult r : results) {
            String symbol = colorize(r.status());
            System.out.printf(fmt, symbol, r.name(), r.detail());
        }

        // Print fix lines
        boolean hasFixLines = results.stream().anyMatch(r -> r.fix() != null);
        if (hasFixLines) {
            System.out.println();
            System.out.println("Actions:");
            for (CheckResult r : results) {
                if (r.fix() != null) {
                    System.out.printf("  [%s] %s%n    -> %s%n", r.status().symbol(), r.name(), r.fix());
                }
            }
        }
    }

    private String colorize(Status status) {
        if (noColor || !supportsAnsi()) {
            return status.symbol();
        }
        return switch (status) {
            case OK -> "[32m" + status.symbol() + "[0m";  // green
            case FAIL -> "[31m" + status.symbol() + "[0m"; // red
            case WARN -> "[33m" + status.symbol() + "[0m"; // yellow
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path resolveProjectRoot() {
        if (root != null && !root.isBlank()) {
            return Paths.get(root).toAbsolutePath().normalize();
        }
        // Auto-detect: cwd contains kompile.project.json?
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("kompile.project.json"))) {
            return cwd;
        }
        return null;
    }

    /** True when running as a GraalVM native image. */
    static boolean isNativeImage() {
        try {
            Class.forName("org.graalvm.nativeimage.ImageInfo");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Describe which resolution path produced the given java executable.
     * Mirrors {@link JavaRuntimeLocator}'s resolution order for display.
     */
    private String resolveJavaSource(String javaExe) {
        String override = System.getenv("KOMPILE_JAVA");
        if (override != null && !override.isBlank() && override.equals(javaExe)) {
            return "$KOMPILE_JAVA";
        }
        File bundled = JavaRuntimeLocator.bundledRuntimeJava();
        if (bundled != null && bundled.getAbsolutePath().equals(javaExe)) {
            return "bundled runtime";
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()
                && javaExe.startsWith(Paths.get(javaHome).toAbsolutePath().toString())) {
            return "$JAVA_HOME";
        }
        if ("java".equals(javaExe)) {
            return "PATH";
        }
        return "current JVM";
    }

    /**
     * Run a command, capture the first line of stderr+stdout combined, with a
     * wall-clock timeout in milliseconds. Returns null on timeout or error.
     */
    String runWithTimeout(String[] cmd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String line = null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                line = br.readLine();
            }
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            proc.destroyForcibly();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True if {@code command} resolves on the system PATH. Uses the same approach
     * as {@link ai.kompile.cli.main.project.InitAgentProvisioner} / CliAgentRegistry.
     */
    private boolean isOnPath(String command) {
        if (command == null || command.isBlank()) return false;
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, command);
            if (f.isFile() && f.canExecute()) return true;
            // Windows .exe
            File fExe = new File(dir, command + ".exe");
            if (fExe.isFile() && fExe.canExecute()) return true;
        }
        return false;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private boolean supportsAnsi() {
        // Heuristic: terminal is a real TTY and the TERM env var is set
        return System.console() != null
                || (System.getenv("TERM") != null && !System.getenv("TERM").isBlank());
    }
}
