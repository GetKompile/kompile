/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.web;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.main.GlobalBootstrap;
import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.install.ComponentInstaller;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager;
import ai.kompile.cli.main.manage.ServiceManager.ComponentStatus;
import ai.kompile.cli.main.manage.ServiceManager.ProcessResult;
import ai.kompile.cli.common.util.EnvironmentUtils;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launches the kompile web application (kompile-app-main).
 *
 * <p>Operates in two modes:</p>
 * <ul>
 *   <li><b>Project mode</b> — when run from a kompile project directory (or with
 *       {@code --project-dir}), starts the project's built JAR along with a
 *       model staging server.</li>
 *   <li><b>Global mode</b> — when run from any other directory, starts the
 *       globally installed {@code kompile-app-main} from
 *       {@code ~/.kompile/components/}. Auto-bootstraps {@code ~/.kompile/} and
 *       auto-installs the component if needed.</li>
 * </ul>
 *
 * <p>Delegates process lifecycle management to {@link ServiceManager} so that
 * instances started via {@code kompile web} are fully visible to
 * {@code kompile manage list/status/stop} and {@code kompile info}.</p>
 *
 * Usage:
 *   kompile web                                  # auto-detect: project or global
 *   kompile web --project-dir=/path/to/project   # force project mode
 *   kompile web --port=9090                      # custom app port
 *   kompile web --build                          # rebuild project before starting
 *   kompile web --no-staging                     # skip staging server
 *   kompile web --no-open                        # don't auto-open browser
 *   kompile web stop                             # stop running instances
 *   kompile web status                           # check what's running
 */
@Command(name = "web",
        description = "Launch the kompile web application.%n%n" +
                "When run from a kompile project directory, starts that project's JAR.%n" +
                "When run from any other directory, starts the globally installed%n" +
                "kompile-app-main (auto-installs if needed).%n%n" +
                "Examples:%n" +
                "  kompile web                              # start (auto-detect mode)%n" +
                "  kompile web --project-dir=./my-rag-app   # start a specific project%n" +
                "  kompile web --port=9090                  # custom port%n" +
                "  kompile web --build                      # rebuild project first%n" +
                "  kompile web --no-staging                 # skip staging server%n" +
                "  kompile web --no-open                    # don't open browser%n" +
                "  kompile web stop                         # stop running instances%n" +
                "  kompile web status                       # check status%n",
        subcommands = {
                WebCommand.StopCommand.class,
                WebCommand.StatusCommand.class
        },
        mixinStandardHelpOptions = true)
public class WebCommand implements Callable<Integer> {

    private static final int STAGING_STARTUP_TIMEOUT_SECONDS = 60;
    private static final String TYPE_APP = "kompile-app-main";
    private static final String TYPE_STAGING = "kompile-model-staging";
    static final String GLOBAL_INSTANCE_NAME = "kompile-web-global";

    private final ServiceManager serviceManager = new ServiceManager();

    @Option(names = {"--project-dir", "-d"},
            description = "Path to the kompile project directory. Forces project mode.")
    private File projectDir;

    @Option(names = {"--port", "-p"},
            description = "Port for the main web application. Default: 8080",
            defaultValue = "8080")
    private int appPort;

    @Option(names = {"--staging-port"},
            description = "Port for the model staging server. Default: 8090",
            defaultValue = "8090")
    private int stagingPort;

    @Option(names = {"--no-staging"},
            description = "Skip starting the model staging server",
            defaultValue = "false")
    private boolean noStaging;

    @Option(names = {"--build", "-b"},
            description = "Rebuild the project before starting (project mode only)",
            defaultValue = "false")
    private boolean rebuild;

    @Option(names = {"--no-open"},
            description = "Do not automatically open the browser",
            defaultValue = "false")
    private boolean noOpenBrowser;

    @Option(names = {"--jvm-args"},
            description = "Additional JVM arguments for the main application (comma-separated)",
            split = ",")
    private List<String> jvmArgs;

    @Option(names = {"--javacppPlatform"},
            description = "JavaCPP platform (e.g., linux-x86_64)",
            defaultValue = "linux-x86_64")
    private String javacppPlatform;

    @Option(names = {"--mavenHome"},
            description = "Path to Maven installation")
    private File mavenHome;

    @Override
    public Integer call() throws Exception {
        if (isProjectMode()) {
            return runProjectMode();
        } else {
            return runGlobalMode();
        }
    }

    // ─── Mode detection ────────────────────────────────────────────────────────

    /**
     * Determine whether we're in a kompile project directory.
     * Returns true if --project-dir is set, or cwd has a pom.xml referencing ai.kompile.
     */
    private boolean isProjectMode() {
        if (projectDir != null) return true;
        File cwd = new File(System.getProperty("user.dir"));
        File pom = new File(cwd, "pom.xml");
        if (!pom.exists()) return false;
        try {
            return Files.readString(pom.toPath()).contains("ai.kompile");
        } catch (IOException e) {
            return false;
        }
    }

    // ─── Global mode ───────────────────────────────────────────────────────────

    /**
     * Start kompile-app-main from the global install at ~/.kompile/components/.
     * Auto-bootstraps ~/.kompile/ and auto-installs the component if needed.
     */
    private Integer runGlobalMode() throws Exception {
        System.out.println("Kompile Web");
        System.out.println("===========");

        // 1. Ensure ~/.kompile/ directory layout exists
        System.out.println("  Checking kompile home directory...");
        GlobalBootstrap.ensureHomeDirectory();

        // 2. Bootstrap config files if not already present
        GlobalBootstrap.ensureConfigs();

        // 3. Check if already running on the target port
        if (serviceManager.checkHealth(appPort)) {
            String url = "http://localhost:" + appPort;
            System.out.println("\n  kompile-app-main is already running at " + url);
            if (!noOpenBrowser) {
                openBrowser(url);
            }
            return 0;
        }

        // 4. Find or auto-install kompile-app-main JAR
        File appJar = findGlobalAppJar();
        if (appJar == null) {
            System.out.println("\n  kompile-app-main not installed. Installing...");
            appJar = autoInstallAppMain();
            if (appJar == null) {
                System.err.println("\nFailed to install kompile-app-main.");
                System.err.println("Install manually with: kompile install kompile-app");
                System.err.println("Or create a project with: kompile init-project");
                return 1;
            }
        }

        File homeDir = Info.homeDirectory();
        System.out.println("  Mode:    global (~/.kompile)");
        System.out.println("  App JAR: " + appJar.getAbsolutePath());
        System.out.println("  Port:    " + appPort);

        // 5. Build app args with absolute paths for global mode
        List<String> appArgs = buildGlobalAppArgs(homeDir);

        // 6. Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            try {
                InstanceRegistry.unregister(GLOBAL_INSTANCE_NAME);
            } catch (Exception ignored) {}
        }));

        // 7. Start the app (foreground, blocks until Ctrl+C)
        System.out.println("\nStarting kompile-app-main on port " + appPort + "...");
        System.out.println("  Press Ctrl+C to stop.\n");

        // Open browser after a delay so the app has time to start
        if (!noOpenBrowser) {
            scheduleOpenBrowser(appPort);
        }

        Process appProcess = serviceManager.startProjectComponent(
                GLOBAL_INSTANCE_NAME, TYPE_APP, appJar, appPort,
                homeDir, jvmArgs, appArgs, null, true);

        System.out.println("  PID: " + appProcess.pid());

        // Block until the app process exits
        int exitCode = appProcess.waitFor();

        InstanceRegistry.unregister(GLOBAL_INSTANCE_NAME);
        return exitCode;
    }

    /**
     * Find the kompile-app-main JAR or binary from any known install location.
     * Search order:
     * <ol>
     *   <li>Distribution install: {@code ~/.kompile/lib/kompile-app-main*.jar}</li>
     *   <li>Component install (exact version): {@code ~/.kompile/components/kompile-app-main/<version>/}</li>
     *   <li>Component install (any version): scan versioned directories</li>
     *   <li>Native executable at component root</li>
     * </ol>
     */
    private File findGlobalAppJar() {
        ComponentRegistry registry = new ComponentRegistry();

        // 1. Check distribution install (lib/ directory)
        File distJar = registry.getDistributionJarPath(ComponentRegistry.KOMPILE_APP_MAIN);
        if (distJar != null && distJar.isFile()) return distJar;

        // 2. Try exact version match from component install
        File componentJar = registry.getJarPath(ComponentRegistry.KOMPILE_APP_MAIN);
        if (componentJar.isFile()) return componentJar;

        // 3. Scan versioned directories for any matching JAR (handles version mismatch)
        File globalDir = new File(Info.homeDirectory(),
                "components/" + ComponentRegistry.KOMPILE_APP_MAIN);
        if (globalDir.isDirectory()) {
            File[] versionDirs = globalDir.listFiles(File::isDirectory);
            if (versionDirs != null && versionDirs.length > 0) {
                Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File versionDir : versionDirs) {
                    File[] jars = versionDir.listFiles(
                            (dir, name) -> name.startsWith("kompile-app-main") && name.endsWith(".jar"));
                    if (jars != null && jars.length > 0) return jars[0];
                }
            }

            // 4. Check for native executable at the component root
            File nativeExe = new File(globalDir, "kompile-app-main");
            if (nativeExe.isFile() && nativeExe.canExecute()) return nativeExe;
        }

        return null;
    }

    /**
     * Download and install kompile-app-main from GitHub Releases.
     */
    private File autoInstallAppMain() {
        try {
            ComponentRegistry registry = new ComponentRegistry();
            ComponentInstaller installer = new ComponentInstaller(registry);
            installer.setVerbose(true);
            return installer.installComponent(
                    ComponentRegistry.KOMPILE_APP_MAIN,
                    ComponentRegistry.ReleaseSource.GITHUB_RELEASES);
        } catch (Exception e) {
            System.err.println("  Auto-install failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build application arguments for global mode with absolute paths.
     */
    private List<String> buildGlobalAppArgs(File homeDir) {
        List<String> args = new ArrayList<>();

        // Point at global data directories with absolute paths
        File modelsDir = new File(homeDir, "data/models");
        args.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());

        // Staging control
        if (noStaging) {
            args.add("--kompile.staging.auto-start=false");
        }

        return args;
    }

    /**
     * Open the browser to the given URL.
     */
    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            Process p = pb.redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception ignored) {
            // Browser open is best-effort
        }
    }

    /**
     * Schedule browser open after a delay to give the app time to start.
     */
    private void scheduleOpenBrowser(int port) {
        String url = "http://localhost:" + port;
        Thread t = new Thread(() -> {
            // Wait for the app to become healthy before opening browser
            boolean healthy = serviceManager.waitForHealth(port, 60);
            if (healthy) {
                System.out.println("  Opening browser: " + url);
                openBrowser(url);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ─── Project mode (existing behavior) ──────────────────────────────────────

    /**
     * Start from a kompile project directory. This is the original behavior
     * of {@code kompile web} — finds the built JAR in target/, starts staging,
     * and launches the app.
     */
    private Integer runProjectMode() throws Exception {
        // 1. Resolve project directory
        File projDir = resolveProjectDir();
        if (projDir == null) {
            return 1;
        }

        System.out.println("Kompile Web");
        System.out.println("===========");
        System.out.println("  Mode:    project");
        System.out.println("  Project: " + projDir.getAbsolutePath());

        // 2. Parse POM to get artifact coordinates
        PomInfo pomInfo = parsePom(projDir);
        if (pomInfo == null) {
            return 1;
        }

        // 3. Rebuild if requested
        if (rebuild) {
            System.out.println("\nRebuilding project...");
            int buildResult = invokeMavenBuild(projDir);
            if (buildResult != 0) {
                System.err.println("Build failed.");
                return 1;
            }
        }

        // 4. Find the built JAR
        File appJar = findAppJar(projDir, pomInfo);
        if (appJar == null) {
            System.err.println("\nNo built JAR found in " + new File(projDir, "target").getAbsolutePath());
            System.err.println("Build the project first:");
            System.err.println("  cd " + projDir.getAbsolutePath());
            System.err.println("  mvn clean package -DskipTests");
            System.err.println("\nOr use --build to rebuild automatically:");
            System.err.println("  kompile web --build");
            return 1;
        }

        System.out.println("  App JAR: " + appJar.getName());
        System.out.println("  App port: " + appPort);

        String webInstanceName = instanceName(projDir, "web");
        String stagingInstanceName = instanceName(projDir, "staging");
        File logDir = new File(projDir, "data/logs");

        // 5. Start staging server (unless --no-staging)
        Process stagingProcess = null;
        if (!noStaging) {
            stagingProcess = startStagingServer(projDir, stagingInstanceName, logDir);
        } else {
            System.out.println("  Staging: skipped (--no-staging)");
        }

        // 6. Build app args
        List<String> appArgs = new ArrayList<>();
        File dataDir = new File(projDir, "data");
        if (dataDir.isDirectory()) {
            File modelsDir = new File(dataDir, "models");
            if (modelsDir.isDirectory()) {
                appArgs.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());
            }
        }
        if (stagingProcess != null) {
            appArgs.add("--kompile.staging.url=http://localhost:" + stagingPort);
            appArgs.add("--kompile.staging.port=" + stagingPort);
        }

        // 7. Start main application (foreground, blocks until Ctrl+C)
        System.out.println("\nStarting kompile-app-main on port " + appPort + "...");
        System.out.println("  Press Ctrl+C to stop.\n");

        // Register shutdown hook to clean up staging server and registry entries
        final Process stagingRef = stagingProcess;
        final String webName = webInstanceName;
        final String stagName = stagingInstanceName;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            if (stagingRef != null && stagingRef.isAlive()) {
                stagingRef.destroy();
                try {
                    stagingRef.waitFor();
                } catch (InterruptedException ignored) {}
                System.out.println("  Staging server stopped.");
            }
            try {
                InstanceRegistry.unregister(webName);
                InstanceRegistry.unregister(stagName);
            } catch (Exception ignored) {}
        }));

        // Open browser if requested
        if (!noOpenBrowser) {
            scheduleOpenBrowser(appPort);
        }

        // Start main app via ServiceManager (foreground with inherited IO)
        Process appProcess = serviceManager.startProjectComponent(
                webInstanceName, TYPE_APP, appJar, appPort, projDir,
                jvmArgs, appArgs, null, true);

        System.out.println("  PID: " + appProcess.pid());

        // Block until the app process exits
        int exitCode = appProcess.waitFor();

        // Clean up registry and staging
        InstanceRegistry.unregister(webInstanceName);
        if (stagingProcess != null) {
            InstanceRegistry.unregister(stagingInstanceName);
            if (stagingProcess.isAlive()) {
                stagingProcess.destroy();
            }
        }

        return exitCode;
    }

    /**
     * Derive a registry instance name from the project directory.
     * Format: projectDirName-suffix (e.g., "my-rag-app-web", "my-rag-app-staging")
     */
    static String instanceName(File projDir, String suffix) {
        return projDir.getName() + "-" + suffix;
    }

    /**
     * Resolve the project directory from --project-dir or the current directory.
     * Validates that it looks like a kompile project.
     */
    private File resolveProjectDir() {
        File dir = projectDir != null ? projectDir : new File(System.getProperty("user.dir"));

        if (!dir.isDirectory()) {
            System.err.println("Not a directory: " + dir.getAbsolutePath());
            return null;
        }

        File pomFile = new File(dir, "pom.xml");
        if (!pomFile.exists()) {
            System.err.println("No pom.xml found in: " + dir.getAbsolutePath());
            System.err.println("Run this command from a kompile project directory,");
            System.err.println("or use --project-dir to specify one.");
            System.err.println("\nCreate a new project with: kompile init-project");
            return null;
        }

        // Verify it's a kompile project by checking for kompile dependencies
        try {
            String pomContent = Files.readString(pomFile.toPath());
            if (!pomContent.contains("ai.kompile")) {
                System.err.println("Directory does not appear to be a kompile project: " + dir.getAbsolutePath());
                System.err.println("The pom.xml does not reference ai.kompile modules.");
                return null;
            }
        } catch (IOException e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return null;
        }

        return dir;
    }

    /**
     * Parse the pom.xml to extract artifactId and version for JAR discovery.
     */
    private PomInfo parsePom(File projDir) {
        File pomFile = new File(projDir, "pom.xml");
        try {
            String content = Files.readString(pomFile.toPath());

            String artifactId = extractPomElement(content, "artifactId");
            String version = extractPomElement(content, "version");

            if (artifactId == null) {
                System.err.println("Could not parse artifactId from pom.xml");
                return null;
            }
            if (version == null) {
                version = "0.1.0-SNAPSHOT"; // safe default
            }

            return new PomInfo(artifactId, version);
        } catch (IOException e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract a top-level element from pom.xml content.
     * Skips elements inside &lt;parent&gt; blocks.
     */
    private String extractPomElement(String content, String element) {
        // Remove parent block to avoid matching parent's artifactId/version
        String withoutParent = content.replaceAll("(?s)<parent>.*?</parent>", "");
        Pattern pattern = Pattern.compile("<" + element + ">([^<]+)</" + element + ">");
        Matcher matcher = pattern.matcher(withoutParent);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Find the built application JAR in the target/ directory.
     * Looks for &lt;artifactId&gt;-&lt;version&gt;.jar first, then falls back to
     * scanning for any Spring Boot fat JAR.
     */
    private File findAppJar(File projDir, PomInfo pomInfo) {
        File targetDir = new File(projDir, "target");
        if (!targetDir.isDirectory()) {
            return null;
        }

        // Try exact match first
        File exactJar = new File(targetDir, pomInfo.artifactId + "-" + pomInfo.version + ".jar");
        if (exactJar.isFile()) {
            return exactJar;
        }

        // Fallback: scan for any JAR that isn't .original (Spring Boot repackaged)
        File[] jars = targetDir.listFiles((dir, name) ->
                name.endsWith(".jar") && !name.endsWith(".original") && !name.endsWith("-sources.jar"));
        if (jars != null && jars.length > 0) {
            // Sort by modification time, most recent first
            Arrays.sort(jars, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return jars[0];
        }

        return null;
    }

    /**
     * Start the model staging server from the project's staging/ directory
     * or from the global component install, using ServiceManager.
     */
    private Process startStagingServer(File projDir, String stagingInstanceName, File logDir) {
        // Check if already running on the target port
        if (serviceManager.checkHealth(stagingPort)) {
            System.out.println("  Staging: already running on port " + stagingPort);
            return null;
        }

        // Find the staging server JAR/executable
        File stagingJar = findStagingJar(projDir);
        if (stagingJar == null) {
            System.out.println("  Staging: not found (install with: kompile install kompile-model-staging)");
            return null;
        }

        // Build staging-specific app args
        List<String> stagingAppArgs = new ArrayList<>();
        stagingAppArgs.add("--server");
        File modelsDir = new File(projDir, "data/models");
        if (modelsDir.isDirectory()) {
            stagingAppArgs.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());
            stagingAppArgs.add("--kompile.staging.staging-dir=" +
                    new File(modelsDir, ".staging").getAbsolutePath());
        }

        System.out.println("  Starting staging server on port " + stagingPort + "...");

        try {
            Process process = serviceManager.startProjectComponent(
                    stagingInstanceName, TYPE_STAGING, stagingJar, stagingPort,
                    projDir, null, stagingAppArgs, logDir, false);

            // Wait for health
            boolean healthy = false;
            long deadline = System.currentTimeMillis() + (STAGING_STARTUP_TIMEOUT_SECONDS * 1000L);
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    System.err.println("  Staging server exited unexpectedly. Check data/logs/" +
                            stagingInstanceName + ".err.log");
                    InstanceRegistry.unregister(stagingInstanceName);
                    return null;
                }
                if (serviceManager.checkHealth(stagingPort)) {
                    healthy = true;
                    break;
                }
                Thread.sleep(2000);
            }

            if (healthy) {
                System.out.println("  Staging: running on port " + stagingPort + " (PID: " + process.pid() + ")");
            } else {
                System.out.println("  Staging: started but health check timed out (PID: " + process.pid() + ")");
                System.out.println("           It may still be initializing. Check data/logs/" +
                        stagingInstanceName + ".out.log");
            }

            return process;
        } catch (Exception e) {
            System.err.println("  Failed to start staging server: " + e.getMessage());
            return null;
        }
    }

    /**
     * Find the staging server JAR — checks project-local staging/ directory first,
     * then falls back to the global component install at ~/.kompile/components/.
     */
    private File findStagingJar(File projDir) {
        File stagingDir = new File(projDir, "staging");

        // 1. Check project-local native executable
        File nativeExe = new File(stagingDir, "kompile-model-staging");
        if (nativeExe.isFile() && nativeExe.canExecute()) {
            return nativeExe;
        }

        // 2. Check project-local JARs
        if (stagingDir.isDirectory()) {
            File[] localJars = stagingDir.listFiles(
                    (dir, name) -> name.startsWith("kompile-model-staging") && name.endsWith(".jar"));
            if (localJars != null && localJars.length > 0) {
                try {
                    return localJars[0].toPath().toRealPath().toFile();
                } catch (IOException e) {
                    return localJars[0];
                }
            }
        }

        // 3. Fallback: global component install
        return findGlobalStagingJar();
    }

    /**
     * Find the staging server JAR from the global component install at
     * ~/.kompile/components/kompile-model-staging/
     */
    private File findGlobalStagingJar() {
        String home = System.getProperty("user.home");
        File globalDir = new File(home, ".kompile/components/kompile-model-staging");
        if (!globalDir.isDirectory()) {
            return null;
        }

        // Check for native image first
        File nativeExe = new File(globalDir, "kompile-model-staging");
        if (nativeExe.isFile() && nativeExe.canExecute()) {
            return nativeExe;
        }

        // Search versioned directories for JAR
        File[] versionDirs = globalDir.listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) {
            return null;
        }

        Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));

        for (File versionDir : versionDirs) {
            File[] jars = versionDir.listFiles(
                    (dir, name) -> name.startsWith("kompile-model-staging") && name.endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                return jars[0];
            }
        }

        return null;
    }

    /**
     * Invoke Maven to rebuild the project.
     */
    private int invokeMavenBuild(File projDir) throws MavenInvocationException {
        File pomFile = new File(projDir, "pom.xml");
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            goals.add("-Dorg.eclipse.python4j.numpyimport=false");
        }
        goals.add("clean");
        goals.add("package");
        request.setGoals(goals);

        Properties sysProps = new Properties();
        sysProps.setProperty("skipTests", "true");
        request.setProperties(sysProps);

        Invoker invoker = new DefaultInvoker();
        File effectiveMaven = resolveMavenHome();
        if (effectiveMaven == null) {
            System.err.println("Maven not found. Use --mavenHome to specify.");
            return 1;
        }

        request.setMavenOpts("-Dfile.encoding=UTF-8");
        invoker.setMavenHome(effectiveMaven);
        invoker.setWorkingDirectory(projDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() != null) {
                result.getExecutionException().printStackTrace(System.err);
            }
            return 1;
        }

        System.out.println("Build successful.\n");
        return 0;
    }

    private File resolveMavenHome() {
        if (mavenHome != null && mavenHome.exists()) return mavenHome;
        return EnvironmentUtils.defaultMavenHome();
    }

    // ─── Subcommands ────────────────────────────────────────────────────────────

    /**
     * Stop running kompile web instances for a project or the global instance.
     */
    @Command(name = "stop", description = "Stop running kompile web instances",
            mixinStandardHelpOptions = true)
    public static class StopCommand implements Callable<Integer> {

        @Option(names = {"--project-dir", "-d"},
                description = "Path to the kompile project directory. Default: current directory")
        private File projectDir;

        @Option(names = {"--all"},
                description = "Stop all kompile web instances across all projects",
                defaultValue = "false")
        private boolean stopAll;

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();

            if (stopAll) {
                return stopAllWebInstances(manager);
            }

            File dir = projectDir != null ? projectDir : new File(System.getProperty("user.dir"));

            // Check if we're in a project directory
            File pomFile = new File(dir, "pom.xml");
            boolean inProjectDir = pomFile.exists();
            if (inProjectDir) {
                try {
                    inProjectDir = Files.readString(pomFile.toPath()).contains("ai.kompile");
                } catch (IOException e) {
                    inProjectDir = false;
                }
            }

            if (inProjectDir) {
                // Project mode: stop by project dir or name
                String projPath = dir.getAbsolutePath();
                List<InstanceInfo> projectInstances = InstanceRegistry.findByProjectDir(projPath);

                if (!projectInstances.isEmpty()) {
                    for (InstanceInfo info : projectInstances) {
                        ProcessResult result = manager.stopByName(info.getName());
                        if (result.hasError()) {
                            System.err.println("  " + result.getMessage());
                        }
                    }
                    return 0;
                }

                // Fallback: try name-based lookup
                boolean stoppedAny = false;
                stoppedAny |= tryStopByName(manager, instanceName(dir, "web"), "Web application");
                stoppedAny |= tryStopByName(manager, instanceName(dir, "staging"), "Staging server");

                if (!stoppedAny) {
                    System.out.println("No running kompile web instances found for project: " + dir.getAbsolutePath());
                    System.out.println("Check all instances with: kompile manage list");
                    System.out.println("Or stop all web instances with: kompile web stop --all");
                }
            } else {
                // Global mode: stop the global instance
                boolean stopped = tryStopByName(manager, GLOBAL_INSTANCE_NAME, "Global web application");
                if (!stopped) {
                    System.out.println("No running kompile web instance found.");
                    System.out.println("Check all instances with: kompile web status --all");
                }
            }

            return 0;
        }

        private int stopAllWebInstances(ServiceManager manager) throws Exception {
            List<InstanceInfo> allInstances = InstanceRegistry.listAll();
            boolean stoppedAny = false;

            for (InstanceInfo info : allInstances) {
                if (TYPE_APP.equals(info.getType()) || TYPE_STAGING.equals(info.getType())) {
                    ProcessResult result = manager.stopByName(info.getName());
                    if (!result.hasError()) {
                        stoppedAny = true;
                    }
                }
            }

            if (!stoppedAny) {
                System.out.println("No running kompile web instances found.");
            }
            return 0;
        }

        private boolean tryStopByName(ServiceManager manager, String name, String label) {
            try {
                ProcessResult result = manager.stopByName(name);
                return !result.hasError();
            } catch (Exception e) {
                System.err.println("Failed to stop " + label + ": " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Show status of kompile web instances for a project or the global instance.
     */
    @Command(name = "status", description = "Show status of kompile web instances",
            mixinStandardHelpOptions = true)
    public static class StatusCommand implements Callable<Integer> {

        @Option(names = {"--project-dir", "-d"},
                description = "Path to the kompile project directory. Default: current directory")
        private File projectDir;

        @Option(names = {"--all"},
                description = "Show all kompile web instances across all projects",
                defaultValue = "false")
        private boolean showAll;

        @Option(names = {"--json"},
                description = "Output as JSON",
                defaultValue = "false")
        private boolean jsonOutput;

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();

            if (showAll) {
                return showAllWebInstances(manager);
            }

            File dir = projectDir != null ? projectDir : new File(System.getProperty("user.dir"));

            // Check if we're in a project directory
            File pomFile = new File(dir, "pom.xml");
            boolean inProjectDir = pomFile.exists();
            if (inProjectDir) {
                try {
                    inProjectDir = Files.readString(pomFile.toPath()).contains("ai.kompile");
                } catch (IOException e) {
                    inProjectDir = false;
                }
            }

            System.out.println("Kompile Web Status");
            System.out.println("==================");

            if (inProjectDir) {
                System.out.println("  Mode:    project");
                System.out.println("  Project: " + dir.getAbsolutePath());
                System.out.println();

                List<InstanceInfo> projectInstances = InstanceRegistry.findByProjectDir(dir.getAbsolutePath());

                if (!projectInstances.isEmpty()) {
                    for (InstanceInfo info : projectInstances) {
                        printStatus(manager, info.getName(), info.getType());
                    }
                } else {
                    printStatus(manager, instanceName(dir, "web"), "Web Application");
                    printStatus(manager, instanceName(dir, "staging"), "Staging Server");
                }
            } else {
                System.out.println("  Mode:    global (~/.kompile)");
                System.out.println();
                printStatus(manager, GLOBAL_INSTANCE_NAME, "Web Application");
            }

            return 0;
        }

        private int showAllWebInstances(ServiceManager manager) throws Exception {
            System.out.println("Kompile Web Instances (all projects)");
            System.out.println("====================================");
            System.out.println();

            List<InstanceInfo> allInstances = InstanceRegistry.listAll();
            boolean found = false;

            System.out.printf("%-25s %-22s %-10s %-8s %-6s %-30s%n",
                    "INSTANCE", "TYPE", "STATUS", "PID", "PORT", "PROJECT");
            System.out.println("-".repeat(105));

            for (InstanceInfo info : allInstances) {
                if (TYPE_APP.equals(info.getType()) || TYPE_STAGING.equals(info.getType())) {
                    found = true;
                    ComponentStatus status = manager.getInstanceStatus(info.getName());
                    String projectLabel = info.getProjectDir() != null ?
                            info.getProjectDir() : "(global)";
                    System.out.printf("%-25s %-22s %s %-8s %-8s %-6s %-30s%n",
                            info.getName(),
                            info.getType(),
                            status.getStatusIcon(),
                            status.getStatus(),
                            status.getPid().map(Object::toString).orElse("-"),
                            status.getPort().map(Object::toString).orElse("-"),
                            projectLabel);
                }
            }

            if (!found) {
                System.out.println("  (no web instances running)");
            }
            System.out.println();

            return 0;
        }

        private void printStatus(ServiceManager manager, String instanceName, String label) {
            ComponentStatus status = manager.getInstanceStatus(instanceName);
            if ("not_running".equals(status.getStatus())) {
                System.out.printf("  %-20s  not running%n", label + ":");
            } else {
                System.out.printf("  %-20s  %s %s  port=%s  pid=%s  %s%n",
                        label + ":",
                        status.getStatusIcon(),
                        status.getStatus(),
                        status.getPort().map(Object::toString).orElse("-"),
                        status.getPid().map(Object::toString).orElse("-"),
                        status.getUrl().orElse(""));
            }
        }
    }

    // ─── Internal types ─────────────────────────────────────────────────────────

    private static class PomInfo {
        final String artifactId;
        final String version;

        PomInfo(String artifactId, String version) {
            this.artifactId = artifactId;
            this.version = version;
        }
    }
}
